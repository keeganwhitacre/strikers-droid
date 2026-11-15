// Optional film look: a colour grade, film grain and a vignette over the finished 3D picture. See
// include/port/haze.h for the settings, which are chosen per match with the haze.
//
// It is done the way the game does its own screen effects, in GX: copy the frame into a texture and
// draw it back over the screen through a chain of TEV stages. Aurora is left untouched, and the
// only game file that knows about it is glPlat.cpp, which calls PortPostFxApply between views.
//
// The engine caches GX state, so the pass is careful about what it touches:
// - State the engine caches (z mode, blend, cull, colour and alpha update, stage and texgen counts,
//   TEV alpha inputs) goes through its own gx* wrappers, and is put back where they return it.
// - Konst colours are not used: glxSend caches which levels it last loaded. Nor is any texture
//   matrix: it loads the crowd's once a frame. Nor TEV register 2, which goes with a translucency
//   flag it keeps.
// - Everything else (projection, TEV colour setup, vertex format, textures, fog, the position
//   matrix) the next view sends again for its first packet, because each view starts with empty
//   packet caches. The viewport is the exception: the game sets it once, so it is restored here.

#include "port/haze.h"
#include "port/aspect.h"

#include "NL/glx/glxGX.h"

#include <dolphin/gx.h>
#include <dolphin/mtx.h>

#include <cmath>
#include <cstdlib>
#include <cstring>

extern GXTevAlphaArg gx_alphaArg[2][32];   // glxGX.cpp's cache of the TEV alpha inputs

namespace
{

// The engine's frame is 448 lines at every aspect; only its width follows the display.
const u16 kFrameHeight = 448;
const int kStages = 8;

const int kNoiseSize = 128;     // texels; one per logical pixel, which is 2-3 on a phone screen
const int kVignetteSize = 64;   // a smooth falloff, so bilinear filtering does the rest

void* s_frame;   // RGBA8 copy of the frame
void* s_luma;    // I8 copy of the frame: Aurora converts it to luma, which saturation works from
u16 s_copyWidth;
u16 s_noise[kNoiseSize * kNoiseSize];            // IA8: grain in I, grain amount in A
u16 s_vignette[kVignetteSize * kVignetteSize];   // IA8: vignette in I, contrast factor in A
GXTexObj s_noiseObj;
GXTexObj s_vignetteObj;
int s_builtGeneration = -1;
u16 s_builtWidth;
u32 s_random = 0x9E3779B9u;

u32 next_random()
{
    // xorshift32: the grain only has to look random, frame to frame.
    s_random ^= s_random << 13;
    s_random ^= s_random >> 17;
    s_random ^= s_random << 5;
    return s_random;
}

u8 to_byte(float v)
{
    v = v < 0.0f ? 0.0f : v > 1.0f ? 1.0f : v;
    return (u8)(v * 255.0f + 0.5f);
}

// Aurora reads an IA8 texel as a native 16-bit value, intensity in the high byte.
u16 ia8(u8 intensity, u8 alpha) { return (u16)((intensity << 8) | alpha); }

// GX textures are stored in tiles; IA8 tiles are 4 by 4 texels.
int ia8_index(int x, int y, int width)
{
    return ((y / 4) * (width / 4) + x / 4) * 16 + (y % 4) * 4 + x % 4;
}

float smoothstep(float lo, float hi, float x)
{
    float t = (x - lo) / (hi - lo);
    t = t < 0.0f ? 0.0f : t > 1.0f ? 1.0f : t;
    return t * t * (3.0f - 2.0f * t);
}

void build_textures(const PortPostFx& fx, u16 width)
{
    // Grain: the mean of two uniform values, a triangular distribution, which reads as film grain
    // where uniform noise reads as static. Its amount rides in the alpha, for the grain stage to
    // use as its weight.
    const u8 grain = to_byte(fx.grain);
    for (int i = 0; i < kNoiseSize * kNoiseSize; i++)
    {
        const u32 r = next_random();
        s_noise[i] = ia8((u8)(((r & 0xFF) + ((r >> 8) & 0xFF)) / 2), grain);
    }
    GXInitTexObj(&s_noiseObj, s_noise, kNoiseSize, kNoiseSize, GX_TF_IA8, GX_REPEAT, GX_REPEAT,
                 GX_FALSE);
    GXInitTexObjLOD(&s_noiseObj, GX_LINEAR, GX_LINEAR, 0.0f, 0.0f, 0.0f, GX_FALSE, GX_FALSE,
                    GX_ANISO_1);

    // Vignette: round on the screen, so measured with the frame's aspect, darkening from just
    // inside half way out to the corners. The contrast stage's weight (half the contrast) rides in
    // the alpha.
    const float aspect = (float)width / (float)kFrameHeight;
    const float corner = std::sqrt(0.25f * aspect * aspect + 0.25f);
    const u8 contrastWeight = to_byte(fx.contrast * 0.5f);
    for (int y = 0; y < kVignetteSize; y++)
    {
        for (int x = 0; x < kVignetteSize; x++)
        {
            const float dx = ((x + 0.5f) / kVignetteSize - 0.5f) * aspect;
            const float dy = (y + 0.5f) / kVignetteSize - 0.5f;
            const float r = std::sqrt(dx * dx + dy * dy) / corner;
            const float v = 1.0f - fx.vignette * smoothstep(0.45f, 1.0f, r);
            s_vignette[ia8_index(x, y, kVignetteSize)] = ia8(to_byte(v), contrastWeight);
        }
    }
    GXInitTexObj(&s_vignetteObj, s_vignette, kVignetteSize, kVignetteSize, GX_TF_IA8, GX_CLAMP,
                 GX_CLAMP, GX_FALSE);
    GXInitTexObjLOD(&s_vignetteObj, GX_LINEAR, GX_LINEAR, 0.0f, 0.0f, 0.0f, GX_FALSE, GX_FALSE,
                    GX_ANISO_1);
}

bool ensure_copy_buffers(u16 width)
{
    if (s_frame != nullptr && s_copyWidth == width)
        return true;
    std::free(s_frame);
    std::free(s_luma);
    // Aurora keys the copies by these addresses and keeps the pixels on the GPU; the memory only
    // has to be real and distinct.
    s_frame = std::malloc(GXGetTexBufferSize(width, kFrameHeight, GX_TF_RGBA8, GX_FALSE, 0));
    s_luma = std::malloc(GXGetTexBufferSize(width, kFrameHeight, GX_TF_I8, GX_FALSE, 0));
    s_copyWidth = width;
    return s_frame != nullptr && s_luma != nullptr;
}

void stage_colour(int stage, GXTevColorArg a, GXTevColorArg b, GXTevColorArg c, GXTevColorArg d,
                  GXTevOp op = GX_TEV_ADD, GXTevBias bias = GX_TB_ZERO,
                  GXTevScale scale = GX_CS_SCALE_1, bool clamp = true, GXTevRegID out = GX_TEVPREV)
{
    GXSetTevColorIn((GXTevStageID)stage, a, b, c, d);
    gxSetTevColourOp(stage, op, bias, scale, clamp, out);
    gxSetTevAlphaIn(stage, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO);
    gxSetTevAlphaOp(stage, GX_TEV_ADD, GX_TB_ZERO, GX_CS_SCALE_1, true, GX_TEVPREV);
    GXSetTevDirect((GXTevStageID)stage);
    GXSetTevSwapMode((GXTevStageID)stage, GX_TEV_SWAP0, GX_TEV_SWAP0);
}

void vertex(float x, float y, float u, float v, float nu, float nv)
{
    GXPosition3f32(x, y, -0.5f);
    GXTexCoord2f32(u, v);
    GXTexCoord2f32(nu, nv);
}

}   // namespace

void PortPostFxApply(void)
{
    const PortPostFx* fx = PortPostFxState();
    if (!fx->active)
        return;
    const u16 width = (u16)PortLogicalFrameWidth();
    if (width == 0 || !ensure_copy_buffers(width))
        return;
    if (s_builtGeneration != fx->generation || s_builtWidth != width)
    {
        build_textures(*fx, width);
        s_builtGeneration = fx->generation;
        s_builtWidth = width;
    }

    // The picture so far, twice: in colour, and as luma for the saturation stages.
    GXSetTexCopySrc(0, 0, width, kFrameHeight);
    GXSetTexCopyDst(width, kFrameHeight, GX_TF_RGBA8, GX_FALSE);
    GXCopyTex(s_frame, GX_FALSE);
    GXSetTexCopyDst(width, kFrameHeight, GX_TF_I8, GX_FALSE);
    GXCopyTex(s_luma, GX_FALSE);
    GXPixModeSync();

    GXTexObj frameObj, lumaObj;
    GXInitTexObj(&frameObj, s_frame, width, kFrameHeight, GX_TF_RGBA8, GX_CLAMP, GX_CLAMP,
                 GX_FALSE);
    GXInitTexObjLOD(&frameObj, GX_NEAR, GX_NEAR, 0.0f, 0.0f, 0.0f, GX_FALSE, GX_FALSE, GX_ANISO_1);
    GXInitTexObj(&lumaObj, s_luma, width, kFrameHeight, GX_TF_I8, GX_CLAMP, GX_CLAMP, GX_FALSE);
    GXInitTexObjLOD(&lumaObj, GX_NEAR, GX_NEAR, 0.0f, 0.0f, 0.0f, GX_FALSE, GX_FALSE, GX_ANISO_1);
    GXLoadTexObj(&frameObj, GX_TEXMAP0);
    GXLoadTexObj(&lumaObj, GX_TEXMAP1);
    GXLoadTexObj(&s_noiseObj, GX_TEXMAP2);
    GXLoadTexObj(&s_vignetteObj, GX_TEXMAP3);

    // Saved for afterwards: what the wrappers cache, and the viewport, which the game sets only
    // once.
    f32 viewport[6];
    f32 projection[7];
    GXGetViewportv(viewport);
    GXGetProjectionv(projection);
    GXTevAlphaArg alphaArgs[kStages * 4];
    std::memcpy(alphaArgs, gx_alphaArg[0], sizeof alphaArgs);
    gxSaveZMode();
    const bool colourUpdate = gxSetColourUpdate(true);
    const bool alphaUpdate = gxSetAlphaUpdate(false);   // later effects may read the frame's alpha
    const bool zCompLoc = gxSetZCompLoc(true);
    const bool coplanar = gxSetCoPlanar(false);
    const _GXCullMode cull = gxSetCullMode(GX_CULL_NONE);
    const u32 numChans = gxSetNumChans(0);
    const u32 numGens = gxSetNumTexGens(2);
    const u32 numStages = gxSetNumTevStages(kStages);
    gxSetZMode(false, GX_ALWAYS, false);
    gxSetBlendMode(false, GX_BL_ONE, GX_BL_ZERO, false);
    gxSetAlphaCompare(GX_ALWAYS, 0);
    const GXColor black = { 0, 0, 0, 0 };
    GXSetFog(GX_FOG_NONE, 0.0f, 0.0f, 0.0f, 0.0f, black);

    GXSetViewport(0.0f, 0.0f, (f32)width, (f32)kFrameHeight, 0.0f, 1.0f);
    Mtx44 ortho;
    C_MTXOrtho(ortho, 0.0f, (f32)kFrameHeight, 0.0f, (f32)width, 0.0f, 1.0f);
    GXSetProjection(ortho, GX_ORTHOGRAPHIC);
    Mtx identity;
    C_MTXIdentity(identity);
    GXLoadPosMtxImm(identity, GX_PNMTX0);
    GXSetCurrentMtx(GX_PNMTX0);

    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX0, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX1, GX_DIRECT);
    // Format 7: the engine uses only format 0.
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_POS, GX_POS_XYZ, GX_F32, 0);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_TEX0, GX_TEX_ST, GX_F32, 0);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_TEX1, GX_TEX_ST, GX_F32, 0);
    gxSetTexCoordGen(GX_TEXCOORD0, GX_TG_MTX2x4, GX_TG_TEX0, GX_IDENTITY);
    gxSetTexCoordGen(GX_TEXCOORD1, GX_TG_MTX2x4, GX_TG_TEX1, GX_IDENTITY);

    // Register 0: the lift colour, with the lift amount in its alpha. Register 1: the saturation
    // amount in its alpha; its colour is scratch for the stages.
    GXColor lift = { to_byte(fx->liftColor[0]), to_byte(fx->liftColor[1]),
                     to_byte(fx->liftColor[2]), to_byte(fx->lift) };
    GXColor saturation = { 0, 0, 0, to_byte(std::fabs(fx->saturation)) };
    GXSetTevColor(GX_TEVREG0, lift);
    GXSetTevColor(GX_TEVREG1, saturation);

    // 0: luma.
    GXSetTevOrder(GX_TEVSTAGE0, GX_TEXCOORD0, GX_TEXMAP1, GX_COLOR_NULL);
    stage_colour(0, GX_CC_ZERO, GX_CC_ZERO, GX_CC_ZERO, GX_CC_TEXC);
    // 1: colour minus luma, how far each channel is from grey, into register 1. Aurora's luma is
    //    studio range (16-235), which adds a small neutral term here; at these amounts that is a
    //    couple of levels of contrast, not a colour shift.
    GXSetTevOrder(GX_TEVSTAGE1, GX_TEXCOORD0, GX_TEXMAP0, GX_COLOR_NULL);
    stage_colour(1, GX_CC_CPREV, GX_CC_ZERO, GX_CC_ZERO, GX_CC_TEXC, GX_TEV_SUB, GX_TB_ZERO,
                 GX_CS_SCALE_1, false, GX_TEVREG1);
    // 2: saturation: colour plus (or minus) that distance, weighted.
    GXSetTevOrder(GX_TEVSTAGE2, GX_TEXCOORD0, GX_TEXMAP0, GX_COLOR_NULL);
    stage_colour(2, GX_CC_ZERO, GX_CC_C1, GX_CC_A1, GX_CC_TEXC,
                 fx->saturation < 0.0f ? GX_TEV_SUB : GX_TEV_ADD);
    // 3, 4: contrast about mid grey. 2 * (lerp(0.5, x, k / 2) - 0.5) is k * (x - 0.5), then + 0.5.
    GXSetTevOrder(GX_TEVSTAGE3, GX_TEXCOORD0, GX_TEXMAP3, GX_COLOR_NULL);
    stage_colour(3, GX_CC_HALF, GX_CC_CPREV, GX_CC_TEXA, GX_CC_ZERO, GX_TEV_ADD, GX_TB_SUBHALF,
                 GX_CS_SCALE_2, false);
    GXSetTevOrder(GX_TEVSTAGE4, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR_NULL);
    stage_colour(4, GX_CC_ZERO, GX_CC_ZERO, GX_CC_ZERO, GX_CC_CPREV, GX_TEV_ADD, GX_TB_ADDHALF);
    // 5: lift toward the stadium's haze colour.
    GXSetTevOrder(GX_TEVSTAGE5, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR_NULL);
    stage_colour(5, GX_CC_CPREV, GX_CC_C0, GX_CC_A0, GX_CC_ZERO);
    // 6: grain, centred on zero: lerp(0.5, noise, amount) - 0.5.
    GXSetTevOrder(GX_TEVSTAGE6, GX_TEXCOORD1, GX_TEXMAP2, GX_COLOR_NULL);
    stage_colour(6, GX_CC_HALF, GX_CC_TEXC, GX_CC_TEXA, GX_CC_CPREV, GX_TEV_ADD, GX_TB_SUBHALF);
    // 7: vignette.
    GXSetTevOrder(GX_TEVSTAGE7, GX_TEXCOORD0, GX_TEXMAP3, GX_COLOR_NULL);
    stage_colour(7, GX_CC_ZERO, GX_CC_CPREV, GX_CC_TEXC, GX_CC_ZERO);

    // The grain moves every frame, or it would read as dirt on the screen.
    const float nu = (next_random() & 0xFFFF) / 65536.0f;
    const float nv = (next_random() & 0xFFFF) / 65536.0f;
    const float nw = (float)width / kNoiseSize;
    const float nh = (float)kFrameHeight / kNoiseSize;
    GXBegin(GX_QUADS, GX_VTXFMT7, 4);
    vertex(0.0f, 0.0f, 0.0f, 0.0f, nu, nv);
    vertex((f32)width, 0.0f, 1.0f, 0.0f, nu + nw, nv);
    vertex((f32)width, (f32)kFrameHeight, 1.0f, 1.0f, nu + nw, nv + nh);
    vertex(0.0f, (f32)kFrameHeight, 0.0f, 1.0f, nu, nv + nh);
    GXEnd();

    for (int stage = 0; stage < kStages; stage++)
        gxSetTevAlphaIn(stage, alphaArgs[stage * 4], alphaArgs[stage * 4 + 1],
                        alphaArgs[stage * 4 + 2], alphaArgs[stage * 4 + 3]);
    gxSetNumTevStages(numStages);
    gxSetNumTexGens(numGens);
    gxSetNumChans(numChans);
    gxSetCullMode(cull);
    gxSetCoPlanar(coplanar);
    gxSetZCompLoc(zCompLoc);
    gxSetAlphaUpdate(alphaUpdate);
    gxSetColourUpdate(colourUpdate);
    gxRestoreZMode();
    GXSetProjectionv(projection);
    GXSetViewport(viewport[0], viewport[1], viewport[2], viewport[3], viewport[4], viewport[5]);
}
