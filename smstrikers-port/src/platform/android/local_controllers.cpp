#if defined(__ANDROID__) && defined(PORT_USE_AURORA)

#include "port/couch_scorebook.h"

#include <cstdio>
#include <SDL3/SDL_gamepad.h>
#include <dolphin/pad.h>
#include <imgui.h>

// Return true while the assignment chord owns input on Choose Sides.
bool PortAndroidLocalControllers(bool& swapped)
{
    if (ImGui::GetCurrentContext() == nullptr || PADCount() < 2)
        return false;

    static bool chordWasHeld = false;
    constexpr SDL_GamepadButton targets[] = {
        SDL_GAMEPAD_BUTTON_SOUTH, SDL_GAMEPAD_BUTTON_EAST,
        SDL_GAMEPAD_BUTTON_WEST, SDL_GAMEPAD_BUTTON_NORTH,
    };
    bool assigning = false;
    bool chordHeld = false;
    bool faceHeld = false;
    s32 sourceIndex = -1;
    u32 sourcePort = 0, targetPort = 0;
    ImGui::SetNextWindowPos(ImVec2(12, 12), ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(0.85f);
    ImGui::Begin("Local controllers", nullptr, ImGuiWindowFlags_AlwaysAutoResize |
        ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoSavedSettings);
    ImGui::TextUnformatted("Hold Back / Select + bottom, right, left, or top for P1–P4");
    for (u32 port = 0; port < PAD_CHANMAX; ++port)
    {
        const s32 index = PADGetIndexForPort(port);
        SDL_Gamepad* pad = index < 0 ? nullptr : PADGetSDLGamepadForIndex((u32)index);
        if (pad == nullptr)
        {
            ImGui::TextDisabled("P%u  —", port + 1);
            continue;
        }

        bool pressed = false;
        for (int button = 0; button < SDL_GAMEPAD_BUTTON_COUNT; ++button)
            pressed |= SDL_GetGamepadButton(pad, (SDL_GamepadButton)button);
        const char* name = SDL_GetGamepadName(pad);
        ImGui::TextColored(pressed ? ImVec4(1.f, .75f, .25f, 1.f) : ImGui::GetStyleColorVec4(ImGuiCol_Text),
                           "P%u  %s", port + 1, name != nullptr ? name : "Controller");

        for (auto button : targets)
            faceHeld |= SDL_GetGamepadButton(pad, button);

        if (!SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_BACK))
            continue;
        assigning = true;
        for (u32 target = 0; target < PAD_CHANMAX; ++target)
        {
            if (!SDL_GetGamepadButton(pad, targets[target]))
                continue;
            chordHeld = true;
            if (!chordWasHeld && sourceIndex < 0 && target != port)
            {
                sourceIndex = index;
                sourcePort = port;
                targetPort = target;
            }
            break;
        }
    }
    const bool swallowRelease = chordWasHeld && faceHeld;
    chordWasHeld = chordHeld || swallowRelease;
    if (sourceIndex >= 0)
    {
        const s32 displaced = PADGetIndexForPort(targetPort);
        PADSetPortForIndex((u32)sourceIndex, targetPort);
        if (displaced >= 0)
            PADSetPortForIndex((u32)displaced, sourcePort);
        PortCouchRoster roster;
        PortCouchLoadRoster(roster);
        if (roster.ports[sourcePort] != roster.ports[targetPort])
        {
            const int player = roster.ports[sourcePort];
            roster.ports[sourcePort] = roster.ports[targetPort];
            roster.ports[targetPort] = player;
            if (!PortCouchSaveRoster(roster))
                std::fprintf(stderr, "[scorebook] Could not move nickname assignments\n");
        }
        swapped = true;
    }
    ImGui::End();
    return assigning || swallowRelease;
}

#endif
