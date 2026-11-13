# Strikers for Android

A native Android port of *Super Mario Strikers* for phones, tablets, and handhelds. Play with touch controls or connect controllers for local multiplayer. You’ll need game data from your own copy of *Super Mario Strikers*.

## Get the Android build

Use an ARM64 device running Android 9 or newer with Vulkan support. Open a successful **Android APK** run for `main` and download **strikers.apk** under **Artifacts**. GitHub requires sign-in to download CI artifacts. The [Android guide](smstrikers-port/README-android.md) covers installation, disc setup, building from source, and device testing.

[Android APK builds](https://github.com/keeganwhitacre/strikers-droid/actions/workflows/android.yml?query=branch%3Amain)

## Features

- **Android launcher and settings.** Pick a disc image with the Android file picker. The launcher remembers your selection.
- **Touch controls and gamepads.** Move and resize touch buttons, remap GameCube controls, and tune stick deadzones and rumble. The touch overlay hides when a physical controller connects.
- **Local multiplayer.** Identify connected controllers and swap player ports on the Choose Sides screen.
- **Graphics and performance.** Adjust resolution, frame rate, and anti-aliasing, or use a performance preset. Import Dolphin-format texture packs and use a custom Vulkan driver on supported devices.
- **Online multiplayer.** Host or join rollback matches, discover rooms on your local network, or connect by address. Cross-platform matches require a matching upstream game version and compatible disc region.

## Where it came from

This fork brings [new-coke’s Strikers port](https://github.com/new-coke/strikers) to Android using [Luis Santos’s Android port](https://github.com/luisxl15/Strikers-Android-Port) as its starting point. The game is based on the community decompilation led by [Yannick Suter](https://github.com/yannicksuter) and uses [Aurora](https://github.com/encounter/aurora) for platform support.

Report Android issues in the [fork’s issue tracker](https://github.com/keeganwhitacre/strikers-droid/issues). Include your device, Android version, APK build, and steps to reproduce.

<details>
<summary>Licensing and third-party notices</summary>

This project contains material with different licences and rights statuses.

The upstream author’s original porting code, tools and documentation are offered under CC0 1.0, except where otherwise noted, and only to the extent that I own the relevant rights. This does not relicense third-party material or grant rights to the original game.

The main distinctions are:

- Reconstructed game code: this is an unofficial source reconstruction, not an official source release. Reconstructed material may remain subject to third-party rights; this project claims no ownership of those rights and grants no permission on behalf of their holders.
- MusyX audio middleware: the upstream decompilation carries an MIT licence, which is preserved here. That notice does not, by itself, establish that its licensors hold all rights in the reconstructed middleware.
- ODE physics: upstream ODE portions use the historical BSD-style licence preserved in the repository. That licence does not, by itself, establish the licensing status of independently copyrightable game-specific modifications.
- Button prompt artwork: [Input Prompts](https://kenney.nl/assets/input-prompts) by Kenney, under CC0 1.0. 
- Controller pictures: [Gamepad Asset Pack](https://github.com/AL2009man/Gamepad-Asset-Pack) by Al. Lopez (AL2009man), under the MIT licence, recoloured and outlined.
- Discord Rich Presence: [borealis](https://github.com/encounter/borealis) by Luke Street and [nlohmann/json](https://github.com/nlohmann/json) by Niels Lohmann, both MIT.
- Online play's encryption: [Monocypher](https://monocypher.org) by Loup Vaillant, Michael Savage and Fabio Scotoni, under CC0 1.0 or the 2-clause BSD licence.
- Router port mapping: [miniupnpc](https://miniupnp.tuxfamily.org) by Thomas Bernard, under the 3-clause BSD licence.
- Online panel font: Roboto Medium by Google, under the Apache 2.0 licence.
- Switch build: NVK from [mesa-switch](https://github.com/danfromtico/mesa-switch) (MIT, see 'NOTICE-NVK.md'); [libnx](https://github.com/switchbrew/libnx) (ISC); devkitPro's SDL2 (zlib), Mesa EGL/OpenGL ES and libdrm_nouveau (MIT); [sdl3on2](https://github.com/new-coke/sdl3on2) (zlib); [dawn-switch](https://github.com/new-coke/dawn-switch) (BSD 3-Clause).
- Settings app: [Qt 6](https://www.qt.io), under the LGPL 3.0.
- FreeType: portions of this software are copyright The FreeType Project (www.freetype.org). All rights reserved.
- Other third-party material and dependencies: these retain their applicable licences, and each archive includes their notices.

The project distributes source code and compiled releases. These do not include game assets; you must supply game data from your own copy. Distribution does not grant permission to reuse or redistribute third-party material beyond its applicable licences and rights.

This is an unofficial project, unaffiliated with and not endorsed by Nintendo or Next Level Games.

</details>
