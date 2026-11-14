#ifndef PORT_HAZE_H
#define PORT_HAZE_H

// Optional distance haze for stadiums. Rendering only: the simulation never reads it, so online
// peers may differ in this setting.
//
// The retail fog passes GXSetFog a far plane of 130 while the gameplay camera's projection runs to
// 1024, so its distances come out compressed and the fog stays faint. The haze reads the real planes
// from the drawing view's projection, so its start and end are true distances.

#ifdef __cplusplus
extern "C" {
#endif

// How the haze thickens between start and end, as GX's own fog types: with t the fraction of the
// way from start to end, linear is t, exp is 1 - 2^(-8t) and exp2 is 1 - 2^(-8t^2). All three are
// total at end; exp reaches half strength an eighth of the way there.
enum
{
    PORT_HAZE_LINEAR = 0,
    PORT_HAZE_EXP = 1,
    PORT_HAZE_EXP2 = 2
};

typedef struct PortHaze
{
    int active;      // The haze replaces the stadium's fog for this match.
    float color[3];  // 0..1.
    float start;     // Distance from the camera where the haze begins.
    float end;       // Distance where the haze is total (in practice, for exp and exp2).
    int curve;       // PORT_HAZE_*.
    int sky;         // Also haze the skybox.
    int diagnostic;  // stadium_haze = diagnostic: unmistakable magenta, even on no-fog materials.
    int logged;      // The renderer has reported its first hazed view this match.
} PortHaze;

// Called as each stadium is built, with the lower-case base name (wario_stadium, ...).
void PortHazeSelectStadium(const char* baseName);

// The current haze, live. The debug overlay edits it in place.
PortHaze* PortHazeState(void);

// "linear", "exp" or "exp2", as haze_curve spells them.
const char* PortHazeCurveName(int curve);

// Near and far planes recovered from a C_MTXPerspective matrix ([row][column]). Returns 0, leaving
// the outputs alone, for anything that is not a usable perspective projection.
int PortHazePlanes(const float projection[4][4], float* nearPlane, float* farPlane);

#ifdef __cplusplus
}
#endif

#endif
