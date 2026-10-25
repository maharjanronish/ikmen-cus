# Ikemen GO — Mobile Minimal Controller Edition

**APK:** `IkemenGO-Mobile-Minimal.apk` (arm64-v8a, Android 5.0+, debug-signed, sideload-ready)
Built from Ikemen GO engine main branch (commit e9bfe112, 2026-10-24) + patched ikemen-droid wrapper.

## The New Touch Controller

The stock Android port showed 12 buttons + 2 analog sticks (a full Xbox controller
simulation). This edition replaces it with a clean gamepad-style layout:

```
                                          [Pause] [Start]
   ( D-PAD )                                    (Y)
    8-way                                     (X) (A)
  bottom-left                                   (B)
                                          [HV] (amber)
                                        bottom-right
```

| Button | Function |
|---|---|
| D-pad | Move / jump / crouch (8-way, with diagonals) |
| X (left) | Light punch |
| Y (top) | Medium punch |
| A (right) | Light kick |
| B (bottom) | Medium kick |
| **HV (amber, hold)** | **Heavy modifier** |
| Start | Start button |
| Pause | Pause menu (game "menu" key) |

### The HV Modifier — how to throw heavies
Hold **HV** and any attack becomes its heavy version:

- HV + X or Y → **Z (heavy punch)**
- HV + A or B → **C (heavy kick)**

HV can be held before tapping the attack, or pressed while an attack is already
held — inputs are retranslated live. Every MUGEN 6-button attack is reachable
with 4 face buttons + 1 modifier.

### Removed (not needed for play)
- Analog sticks (MUGEN input is digital; d-pad covers movement)
- D / W extra buttons (only used by some custom screenpacks)
- C / Z buttons (replaced by HV modifier)

## Fullscreen on modern phones
- Engine renders fullscreen with aspect preserved: the 4:3 game fills the screen
  height and controls sit in the side letterbox bars on 20:9 displays.
- Fixed a wrapper bug where a stale `maxSdkVersion=21` blocked installation on
  Android 6.0+.
- Landscape orientation, immersive, cutout-aware, keep-screen-on.

## Installing
1. Copy the APK to your phone and open it (allow "install from unknown sources").
2. First launch extracts the bundled default content (Elecbyte screenpack,
   Kung Fu Man + 3 variants, all default stages).
3. To add your own MUGEN chars/stages, drop them into the game's data folder on
   the device (Android/data/org.ikemen_engine.ikemen_go/...).

## Physical gamepads
Bluetooth/USB controllers still work (the virtual gamepad auto-assigns an ID
after any physical pads).

## Rebuilding from source
```
git clone https://github.com/ikemen-engine/Ikemen-GO
cd Ikemen-GO
ANDROID_APK_REPO=/path/to/ikemen-droid-patched ./build/build.sh Android
```
(Or use the official Docker route: `./build/build_android.sh` with
`ANDROID_APK_REPO` pointing at the patched wrapper.)

## Changed files (vs stock ikemen-droid)
- `app/src/main/res/layout/virtual_controller.xml` — new minimal layout
- `app/src/main/java/org/libsdl/app/ControllerOverlay.java` — HV translation layer, removed stick handling
- `app/src/main/res/drawable-v24/round_button_heavy.xml` — amber HV button style
- `app/src/main/AndroidManifest.xml` — removed stale uses-sdk maxSdkVersion=21
- `app/build.gradle` — versionName 0.103-mobile-minimal
