// Optional stadium haze. See include/port/haze.h.
//
// strikers.ini:
//   stadium_haze = off | subtle | strong   (or 0, 1, 2)
//   haze_color   = r, g, b                 (0-255; overrides the stadium's default)
//   haze_start   = distance
//   haze_end     = distance
//   haze_sky     = 0 | 1
//   haze_all     = 1                       (also replace the retail fog in the three stadiums
//                                           that have one)
// The overrides apply to whichever stadium is loaded, which is what tuning one stadium needs.

#include "port/haze.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct HazeDefault
{
    const char* name;
    unsigned char r, g, b;
    int retailFog;  // The retail game already fogs this stadium (BasicStadium's constructor).
} HazeDefault;

// Starting points to tune by eye, not measurements. The three retail fog colours come from
// glx_SetFog; the rest are guesses at each stadium's ambient light.
static const HazeDefault kDefaults[] = {
    { "mario_stadium", 0xAE, 0x73, 0x55, 1 },  // Pipeline Central
    { "the_palace", 0x2C, 0xB9, 0xFF, 1 },     // The Palace
    { "dk_daisy", 0xFA, 0xE6, 0xB9, 1 },       // Konga Coliseum
    { "wario_stadium", 96, 101, 146, 0 },      // The Underground
    { "yoshi_stadium", 196, 172, 140, 0 },     // Crater Field
    { "super_stadium", 150, 165, 190, 0 },     // Super Stadium
    { "forbidden_dome", 112, 64, 54, 0 },      // Bowser Stadium
};

// Where the haze begins, and how far beyond that it would reach full strength, per level. The
// haze never reaches full strength on the pitch; these put roughly a third (subtle) or half
// (strong) of the haze colour on the far stands, if those are 150 to 200 units away.
static const float kStart = 40.0f;
static const float kSpanSubtle = 320.0f;
static const float kSpanStrong = 190.0f;

static PortHaze s_haze;

static const char* env(const char* name)
{
    const char* v = getenv(name);
    return (v != NULL && v[0] != '\0') ? v : NULL;
}

static int level(void)
{
    const char* v = env("STRIKERS_STADIUM_HAZE");
    if (v == NULL || strcmp(v, "0") == 0 || strcmp(v, "off") == 0)
        return 0;
    if (strcmp(v, "2") == 0 || strcmp(v, "strong") == 0)
        return 2;
    return 1;  // 1, subtle, or anything else that asks for it
}

static void read_float(const char* name, float* out)
{
    const char* v = env(name);
    char* end;
    float f;
    if (v == NULL)
        return;
    f = strtof(v, &end);
    if (end != v && f >= 0.0f)
        *out = f;
    else
        fprintf(stderr, "[port] haze: %s = %s is not a distance\n", name, v);
}

void PortHazeSelectStadium(const char* baseName)
{
    const HazeDefault* d = NULL;
    const int lvl = level();
    size_t i;
    int r, g, b;
    const char* color;

    memset(&s_haze, 0, sizeof s_haze);
    if (lvl == 0 || baseName == NULL)
        return;

    for (i = 0; i < sizeof kDefaults / sizeof kDefaults[0]; i++)
    {
        if (strcmp(baseName, kDefaults[i].name) == 0)
        {
            d = &kDefaults[i];
            break;
        }
    }
    if (d == NULL)
        return;
    if (d->retailFog && env("STRIKERS_HAZE_ALL") == NULL)
        return;

    s_haze.active = 1;
    s_haze.color[0] = d->r / 255.0f;
    s_haze.color[1] = d->g / 255.0f;
    s_haze.color[2] = d->b / 255.0f;
    s_haze.start = kStart;
    s_haze.end = kStart + (lvl == 2 ? kSpanStrong : kSpanSubtle);
    s_haze.sky = 0;

    color = env("STRIKERS_HAZE_COLOR");
    if (color != NULL)
    {
        if (sscanf(color, "%d , %d , %d", &r, &g, &b) == 3 && r >= 0 && r <= 255 && g >= 0
            && g <= 255 && b >= 0 && b <= 255)
        {
            s_haze.color[0] = r / 255.0f;
            s_haze.color[1] = g / 255.0f;
            s_haze.color[2] = b / 255.0f;
        }
        else
        {
            fprintf(stderr, "[port] haze: haze_color = %s is not r, g, b from 0 to 255\n", color);
        }
    }
    read_float("STRIKERS_HAZE_START", &s_haze.start);
    read_float("STRIKERS_HAZE_END", &s_haze.end);
    if (env("STRIKERS_HAZE_SKY") != NULL)
        s_haze.sky = strcmp(env("STRIKERS_HAZE_SKY"), "0") != 0;
    if (s_haze.end <= s_haze.start)
        s_haze.end = s_haze.start + 1.0f;

    fprintf(stderr, "[port] haze: %s, colour %d,%d,%d, %.0f to %.0f%s\n", baseName,
            (int)(s_haze.color[0] * 255.0f + 0.5f), (int)(s_haze.color[1] * 255.0f + 0.5f),
            (int)(s_haze.color[2] * 255.0f + 0.5f), (double)s_haze.start, (double)s_haze.end,
            s_haze.sky ? ", sky too" : "");
}

PortHaze* PortHazeState(void)
{
    return &s_haze;
}

int PortHazePlanes(const float m[4][4], float* nearPlane, float* farPlane)
{
    // C_MTXPerspective: m[2][2] = -n / (f - n), m[2][3] = -f n / (f - n), m[3][2] = -1.
    const float a = m[2][2];
    const float b = m[2][3];
    float n, f;
    if (m[3][2] != -1.0f || a == 0.0f || a == 1.0f)
        return 0;
    f = b / a;
    n = b / (a - 1.0f);
    if (!(n > 0.0f) || !(f > n))
        return 0;
    *nearPlane = n;
    *farPlane = f;
    return 1;
}
