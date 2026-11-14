# Android

This fork runs Strikers natively on ARM64 Android. The Android code builds on
[Luis Santos's port](https://github.com/luisxl15/Strikers-Android-Port). The game
and rollback code come from [Strikers upstream](https://github.com/new-coke/strikers).

## Install

- Use an ARM64 device running Android 9 or later with Vulkan support.
- Use data from your own US, European, or Japanese copy of Super Mario Strikers.
  The APK contains no game assets.
- Download the APK from [Releases](https://github.com/keeganwhitacre/strikers-droid/releases),
  or build it below. To try unreleased changes, see [Test builds](#test-builds).

The app appears as **Strikers** with the package ID `io.github.keeganwhitacre.strikers`.
Android requires the same signing key for an in-place update, so a build from
[Releases](https://github.com/keeganwhitacre/strikers-droid/releases) updates only
another release build. CI builds use a debug key and are intended for testing.

Builds before the package ID change installed as `io.github.newcoke.strikers.settingspreview`
and appear as a separate app. Copy `strikers.ini`, texture packs and any extracted `files`
directory from that app's folder under `Android/data/` into the new one, then pick your disc
image again. Memory card saves live in app-private storage and cannot be copied over; uninstall
the old app once you no longer need it.

## Test builds

Every `main` commit and every pull request also builds `strikers-test.apk` under its
[Android APK workflow run](https://github.com/keeganwhitacre/strikers-droid/actions/workflows/android.yml)
(sign in, open the run, download it under **Artifacts**). It installs as a separate app,
**Strikers Test** (`io.github.keeganwhitacre.strikers.test`), beside the released Strikers, with
its own saves, settings and disc selection. Test builds share a key, so a newer one installs over
an older one. Installing an older run over a newer one fails; uninstall Strikers Test first.
Local builds are test builds too, unless Gradle gets `-Pstrikers.channel=release`.

## Build from source

Install JDK 21, CMake, Ninja, Python 3, and the Android SDK command-line tools.
Set `ANDROID_HOME` to the SDK directory. Use NDK 29.0.14206865 to match Aurora's
prebuilt Dawn library.

From `smstrikers-port/`:

```sh
sdkmanager "ndk;29.0.14206865" "platforms;android-35" "build-tools;35.0.1"
./tools/build-android.sh
./android/gradlew -p android assembleRelease
```

The native script builds the game, generates platform stubs, and stages ARM64
libraries in `build-android/jniLibs/arm64-v8a/`. Gradle packages them at
`android/app/build/outputs/apk/release/app-release.apk`. Generated stubs stay in the native build directory.

The game reports version `2.0.0` by default to match the upstream multiplayer
handshake. When updating the fork, check the target upstream version and network
compatibility before changing the native version or Android package metadata.

## Game files

Choose **Disc Image…** and select a local disc image in the Android file picker. The launcher
remembers the selection. Press **Play** to read the image in place. If a storage provider can
only stream the image, download it locally before choosing it.

For an extracted disc, place its `files` directory under
`Android/data/io.github.keeganwhitacre.strikers/files/` with USB or ADB. The launcher
also finds supported images in that app-specific directory when no image is selected.

## Settings and controls

The Android launcher provides video, control and game settings. It writes the corresponding keys to
`strikers.ini` while preserving existing comments and unrelated keys. See `strikers.ini.example`
for the native configuration keys. The frame limit follows the display by default and can be capped
from Settings.

The on-screen pad and USB or Bluetooth controllers use the same SDL gamepad path. The on-screen pad
hides when a physical controller connects.

Open **Controller bindings** in Settings to map GameCube buttons to physical
button positions. The choices use the same `pad_*` keys as the Switch port.
They apply to all controllers, including the virtual touch pad, on the next
game start. Stick layout, deadzone, and rumble also use shared controller settings.
**Game default** clears the override for that option. During play,
press **Guide** to open Settings when Android delivers that button to the app.

**Touch layout** offers the existing Standard layout, smaller Compact phone
controls, and Tablet reach, which brings the side controls inward. Size,
opacity, deadzone, and optional controls can still be adjusted separately.

**Performance presets** apply resolution, frame limit, and MSAA together.
Battery uses the original render scale and a 30 FPS cap. Balanced uses a
720-line render scale and a 60 FPS cap. High refresh uses the same render
scale and restores the engine’s display-based limiter. All three turn MSAA
off and leave VSync and anisotropic filtering available as manual settings.

In **Move touch controls**, select a control and use **Smaller** or **Larger**
to resize it individually. The editor saves size and position per layout.
Enable **Match action labels** to show Shoot, Pass, and the Lob modifier during active play.
In **Controller bindings**, the Settings shortcut can also use L3 or R3.
That stick click is reserved for Settings while the shortcut is enabled.

With two or more controllers connected, local **Choose Sides** shows the
assigned player ports and highlights button presses. Hold **Back / Select**
and press the bottom, right, left, or top face button to claim P1, P2, P3, or
P4 respectively. An occupied port swaps controllers. After a swap, choose
teams and ready up again.

## Vulkan drivers

The system Vulkan driver is the default. On compatible Adreno devices, download
a suitable ZIP from [AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers/releases)
and choose **Settings → Video → Video driver → Import ZIP**. Imported drivers run
native code inside the game. Installed drivers and the selection persist across
updates signed with the same key.

## Texture packs

Open **Settings → Video → Texture packs** and import a Dolphin-format ZIP with PNG or DDS
replacements. Choose an individual pack, **All packs**, or **Off**. Imported packs are
stored under `mods/textures` in the app's game folder. You can also copy an extracted
pack folder there over USB or ADB. Pack changes apply the next time you start the game.
Android defaults to a 512 MiB texture cache. You can override it with `texture_cache_mb`
in `strikers.ini`.

## Online play

On the in-game main menu, press the on-screen **RB** button (GameCube **Z**) or the controller's
displayed Online button. Use the stick and face buttons to navigate the online panel. The same pad
edits addresses in the character wheel. Use **Nearby rooms** on the same network or **Join by
address** for a direct connection. On Android, **Paste** or the mapped GameCube
Y button fills the address field. Choose **Join** to connect. **Controller check**
in the Online menu shows Strikers’ player ports and highlights held buttons.

Peers need the same upstream version and a compatible disc region. US and European copies can play
together. Japanese copies match other Japanese copies.

## What is missing

- **Discord Rich Presence and Discord invites.** The upstream integration uses desktop IPC.
- **The desktop settings app.** Android has its own launcher settings.

## Distribution signing

CI artifacts use debug signing. To build with a maintainer-owned key, set
`STRIKERS_ANDROID_KEYSTORE` to the absolute keystore path and provide
`STRIKERS_ANDROID_STORE_PASSWORD`, `STRIKERS_ANDROID_KEY_ALIAS`, and
`STRIKERS_ANDROID_KEY_PASSWORD` in the environment before running
`assembleRelease`. Keep the key outside the repository and back it up: every
later release must be signed with the same key, or players cannot update in place.

Create the key once:

```sh
keytool -genkeypair -v -keystore strikers-release.keystore -alias strikers \
    -keyalg RSA -keysize 4096 -validity 10000
```

For CI, add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Output of `base64 -w0 strikers-release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `strikers`, or the alias you chose |
| `ANDROID_KEY_PASSWORD` | Key password |

## Releasing

Run the device checklist below. Then open **Actions → Android release → Run workflow**, leave the
branch on `main`, and enter the version, such as `2.0.0-android.1`. Use the upstream version the
build plays online with, then `-android.` and a number that goes up with each release.

The workflow builds the APK with the steps CI runs on every push, signs it with the
distribution key, refuses a debug-signed result, creates the `android-v<version>` tag, and
drafts a GitHub release with the APK and its checksum. Review the draft under **Releases** and
publish it. Pushing an `android-v<version>` tag yourself does the same.

The version sets the APK's version name only. The native version stays at the upstream
version in `tools/configure-android.sh`, because online play compares that string exactly.
Upstream's `release.yml` and its `v*` tags are left untouched for the same reason.

## Device testing

Run this checklist on an ARM64 Android device after syncing with upstream and
before distributing a new APK. Record the commit, device model, Android version,
GPU and driver, disc region, and results in the release notes or test issue.

| Area | Checks |
| --- | --- |
| Install and launch | Install the APK. Pick a local disc image, launch a match, and relaunch the app to check that the selection persists. Try upgrading from a build signed with the same key and verify that settings and saves survive. |
| Native settings | Change resolution, frame limit, and touch settings. Restart the game to check that settings applied. Open Settings from the touch overlay and return to the match. |
| Input and lifecycle | Play with touch, connect and disconnect a physical controller, then background and resume the game. Verify that the overlay returns when the controller disconnects and that inputs release on focus loss. |
| Local multiplayer | Connect multiple controllers, identify each player port on Choose Sides, swap ports, reselect teams, and finish a local Friendly. |
| Graphics and textures | Play with the system Vulkan driver. If supported, test a custom driver and return to the system driver. Import a known texture pack, confirm a visible replacement, disable the pack, and confirm the original texture returns. |
| Online | Play Android-to-Android and Android-to-desktop using builds of the same upstream version. Test discovery and direct connection, then complete a match and rematch. |

For crashes or input problems, capture Android's log and the native game log:

```sh
adb logcat -s strikers SDL DEBUG AndroidRuntime
adb pull /sdcard/Android/data/io.github.keeganwhitacre.strikers/files/strikers-log.txt
```

Use the [fork's issue tracker](https://github.com/keeganwhitacre/strikers-droid/issues)
for Android reports. Include the APK build, device details, and steps to reproduce.
