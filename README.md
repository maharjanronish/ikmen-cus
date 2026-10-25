# Ikemen GO Mobile — Minimal Controller Edition

[Ikemen GO](https://github.com/ikemen-engine/Ikemen-GO) (the MUGEN-compatible 2D
fighting engine, written in Go) built for Android with a clean, gamepad-style
touch overlay — replacing the stock port's cluttered 12-button + 2-analog-stick
layout with **5 buttons that can do everything**.

**→ Test APK: [`apk/IkemenGO-Mobile-Minimal.apk`](apk/IkemenGO-Mobile-Minimal.apk)**
(arm64-v8a · Android 5.0+ · debug-signed · ~58 MB · bundles the Elecbyte
screenpack, Kung Fu Man + variants and all default stages)

## Touch controller

```
                                     [Pause] [Start]
  ( D-PAD 8-way )                            (Y)
  bottom-left                              (X)   (A)
                                               (B)
                                      [HV]  (amber, hold)
                                      bottom-right
```

| Input | Action |
|---|---|
| D-pad | move / jump / crouch (8-way, diagonals supported) |
| X / Y | light / medium punch |
| A / B | light / medium kick |
| **HV (hold)** | heavy modifier — HV + X or Y = **Z (heavy punch)**, HV + A or B = **C (heavy kick)** |
| Start | start button |
| Pause | pause menu (game "menu" key) |

One modifier covers all six MUGEN attack buttons, so nothing gameplay-relevant
was lost. Full details in [docs/MOBILE-MINIMAL-CONTROLS.md](docs/MOBILE-MINIMAL-CONTROLS.md);
the exact code changes are in [PATCHES.md](PATCHES.md).

Other notes:
- Fullscreen on modern 19.5:9–20:9 phones — the 4:3 game fills the screen
  height and the controls dock in the side letterbox bars (no overlap with play).
- Physical Bluetooth/USB gamepads still work; the virtual pad auto-assigns
  after them.
- Fixed a stock-wrapper bug (stale `maxSdkVersion=21`) that blocked installing
  on Android 6.0+.

## Repository layout

| Path | Contents |
|---|---|
| `Ikemen-GO/` | Engine source (Go) — upstream main @ `e9bfe112`, unmodified |
| `ikemen-droid/` | Android SDL2 wrapper (Java/Gradle) **with the minimal-control patch applied** |
| `apk/` | Ready-to-install APK built from this exact tree |
| `docs/` | Control scheme, install and rebuild notes |
| `scripts/` | Helper build runner (from the build sandbox) |
| `PATCHES.md` | Complete diff of the wrapper patch vs stock ikemen-droid |

## Installing on a phone

1. Download `apk/IkemenGO-Mobile-Minimal.apk` (or via git: `git clone` this repo).
2. Open it on the phone and allow "install from unknown sources".
3. First launch extracts the bundled content (screenpack, Kung Fu Man + 3
   variants, default stages).
4. Add your own MUGEN chars/stages into the game's data folder on the device
   (`Android/data/org.ikemen_engine.ikemen_go/...`).

## Rebuilding the APK from source

Toolchain (mirrors `build/docker/android`): Go ≥ 1.27, Android NDK r27d,
SDK platform 34 + build-tools 34.0.0, JDK 17.

```bash
cd Ikemen-GO
ANDROID_APK_REPO=/path/to/this/repo/ikemen-droid ./build/build.sh Android
```

- `build.sh` auto-fetches everything heavy on first run — the Elecbyte
  screenpack (187 MB, into `Ikemen-GO/build/screenpack/`), SDL2, FFmpeg, etc.
  Nothing fetchable is committed to this repo.
- Output lands in `ikemen-droid/app/build/outputs/apk/debug/`.
- Docker route: `./build/build_android.sh` with the same `ANDROID_APK_REPO`.

## Credits & licences

- Engine: [ikemen-engine/Ikemen-GO](https://github.com/ikemen-engine/Ikemen-GO) — see `Ikemen-GO/LICENCE.txt`
- Android wrapper: [Jesuszilla/ikemen-droid](https://github.com/Jesuszilla/ikemen-droid)
- Default game content: Elecbyte MUGEN screenpack distribution
