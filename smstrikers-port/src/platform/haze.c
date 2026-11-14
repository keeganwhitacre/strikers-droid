// Optional stadium haze. See include/port/haze.h.
//
// strikers.ini, read again as each stadium loads, so a change made while the game runs (the Android
// settings screen) applies from the next match:
//   stadium_haze = off | subtle | strong | diagnostic   (or 0, 1, 2)
//   haze_color   = r, g, b                 (0-255; overrides the stadium's default)
//   haze_start   = distance
//   haze_end     = distance
//   haze_sky     = 0 | 1
// The three stadiums with retail fog get the haze in their retail fog's colour instead.
// The overrides apply to whichever stadium is loaded, which is what tuning one stadium needs.

#include "port/haze.h"
#include "port/config.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

typedef struct HazeDefault
{
    const char* name;
    unsigned char r, g, b;
} HazeDefault;

// Starting points to tune by eye, not measurements. The three retail fog colours come from
// glx_SetFog (The Palace's at its 0.5 intensity); the rest are guesses at each stadium's ambient
// light.
static const HazeDefault kDefaults[] = {
    { "mario_stadium", 0xAE, 0x73, 0x55 },  // Pipeline Central
    { "the_palace", 0x16, 0x5C, 0x80 },     // The Palace
    { "dk_daisy", 0xFA, 0xE6, 0xB9 },       // Konga Coliseum
    { "wario_stadium", 96, 101, 146 },      // The Underground
    { "yoshi_stadium", 196, 172, 140 },     // Crater Field
    { "super_stadium", 150, 165, 190 },     // Super Stadium
    { "forbidden_dome", 112, 64, 54 },      // Bowser Stadium
};

// Where the haze begins, and how far beyond that it would reach full strength, per level, as
// tuned on a device: subtle is what first shipped as strong, and strong goes a step further.
static const float kStart = 40.0f;
static const float kSpanSubtle = 190.0f;
static const float kSpanStrong = 140.0f;

static PortHaze s_haze;

// The haze keys as strikers.ini has them now. The config loader exported the file to the
// environment once, at startup; this re-reads just these keys so later edits count.
#define HAZE_KEYS 7
static const char* const kKeys[HAZE_KEYS] = { "stadium_haze", "haze_color", "haze_start", "haze_end",
                                             "haze_sky", NULL, NULL };
static char s_values[HAZE_KEYS][64];
static int s_found[HAZE_KEYS];

static char* trim(char* p)
{
    char* end;
    while (isspace((unsigned char)*p))
        p++;
    end = p + strlen(p);
    while (end > p && isspace((unsigned char)end[-1]))
        *--end = '\0';
    return p;
}

static void read_ini(void)
{
    const char* path = PortConfigPath();
    char line[512];
    FILE* f;
    memset(s_found, 0, sizeof s_found);
    if (path == NULL || (f = fopen(path, "r")) == NULL)
        return;
    while (fgets(line, (int)sizeof line, f) != NULL)
    {
        char* p = trim(line);
        char* eq = strchr(p, '=');
        char* key;
        char* val;
        size_t i, n;
        if (*p == '#' || *p == ';' || *p == '[' || eq == NULL)
            continue;
        *eq = '\0';
        key = trim(p);
        val = trim(eq + 1);
        for (i = 0; key[i] != '\0'; i++)
            key[i] = (char)tolower((unsigned char)key[i]);
        if (strncmp(key, "strikers_", 9) == 0)
            key += 9;
        n = strlen(val);
        if (n >= 2 && (val[0] == '"' || val[0] == '\'') && val[n - 1] == val[0])
        {
            val[n - 1] = '\0';
            val++;
        }
        for (i = 0; kKeys[i] != NULL; i++)
        {
            // The first assignment wins, as it does for the config loader.
            if (!s_found[i] && strcmp(key, kKeys[i]) == 0)
            {
                snprintf(s_values[i], sizeof s_values[i], "%s", val);
                s_found[i] = 1;
            }
        }
    }
    fclose(f);
}

// strikers.ini's value, else the environment's (STRIKERS_<KEY>), else NULL.
static const char* setting(const char* key)
{
    char name[64];
    const char* v;
    size_t i;
    for (i = 0; kKeys[i] != NULL; i++)
    {
        if (strcmp(key, kKeys[i]) == 0 && s_found[i])
            return s_values[i][0] != '\0' ? s_values[i] : NULL;
    }
    snprintf(name, sizeof name, "STRIKERS_%s", key);
    for (i = 9; name[i] != '\0'; i++)
        name[i] = (char)toupper((unsigned char)name[i]);
    v = getenv(name);
    return (v != NULL && v[0] != '\0') ? v : NULL;
}

// 0 off, 1 subtle, 2 strong, 3 diagnostic.
static int level(void)
{
    const char* v = setting("stadium_haze");
    if (v == NULL || strcmp(v, "0") == 0 || strcmp(v, "off") == 0)
        return 0;
    if (strcmp(v, "2") == 0 || strcmp(v, "strong") == 0)
        return 2;
    if (strcmp(v, "diagnostic") == 0)
        return 3;
    return 1;  // 1, subtle, or anything else that asks for it
}

static void read_float(const char* name, float* out)
{
    const char* v = setting(name);
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
    int lvl;
    size_t i;
    int r, g, b;
    const char* color;

    memset(&s_haze, 0, sizeof s_haze);
    read_ini();
    lvl = level();
    if (lvl == 0 || baseName == NULL)
    {
        fprintf(stderr, "[port] haze: off for %s\n", baseName != NULL ? baseName : "?");
        return;
    }

    for (i = 0; i < sizeof kDefaults / sizeof kDefaults[0]; i++)
    {
        if (strcmp(baseName, kDefaults[i].name) == 0)
        {
            d = &kDefaults[i];
            break;
        }
    }
    if (d == NULL)
    {
        fprintf(stderr, "[port] haze: no entry for stadium %s\n", baseName);
        return;
    }
    if (lvl == 3)
    {
        // Proof that the haze reaches the screen, before any tuning: everything past a few units
        // turns magenta, sky and no-fog materials included, in every stadium.
        s_haze.active = 1;
        s_haze.diagnostic = 1;
        s_haze.color[0] = 1.0f;
        s_haze.color[1] = 0.0f;
        s_haze.color[2] = 1.0f;
        s_haze.start = 0.0f;
        s_haze.end = 120.0f;
        s_haze.sky = 1;
        fprintf(stderr, "[port] haze: diagnostic magenta for %s\n", baseName);
        return;
    }

    s_haze.active = 1;
    s_haze.color[0] = d->r / 255.0f;
    s_haze.color[1] = d->g / 255.0f;
    s_haze.color[2] = d->b / 255.0f;
    s_haze.start = kStart;
    s_haze.end = kStart + (lvl == 2 ? kSpanStrong : kSpanSubtle);
    s_haze.sky = 0;

    color = setting("haze_color");
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
    read_float("haze_start", &s_haze.start);
    read_float("haze_end", &s_haze.end);
    if (setting("haze_sky") != NULL)
        s_haze.sky = strcmp(setting("haze_sky"), "0") != 0;
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
