# Landscape Mode Report

## Summary

Replaced the earlier per-view "Portrait preview" experiment with an Android-level
**Landscape mode** toggle. When enabled, the activity locks to landscape
orientation, the control strip moves to the right side of the screen, and the
camera preview scales so its vertical dimension fills the landscape display
height. The feature is exposed as **Settings → Landscape mode** and defaults to
off.

## Why the change

The first implementation rotated only the `SurfaceView` and overlay 90° inside a
portrait window. That produced a portrait-shaped video inside a portrait UI,
but it did not give the requested two-pane landscape layout and required manual
touch-coordinate and clipping work-arounds. The user asked for the Android
system to rotate the entire screen, with the camera on the left and the
normally-bottom control strip on the right.

## Implementation

### Android-level orientation lock

- `DualCameraActivity.onCreate()` now reads the `landscape_mode` preference
  **before** `setContentView()` and calls `applyLandscapeMode()`.
- `applyLandscapeMode()` sets `requestedOrientation` to
  `SCREEN_ORIENTATION_LANDSCAPE` when enabled and
  `SCREEN_ORIENTATION_PORTRAIT` when disabled.
- Toggling the setting from the popup menu persists the value, applies the new
  orientation, and lets Android recreate the activity so the correct layout is
  loaded.

### Layout resource

- Added `app/src/main/res/layout-land/activity_dual_camera.xml`.
- Root `LinearLayout` is horizontal.
- `camera_container` is on the left with `layout_weight="1"` and
  `layout_height="match_parent"`.
- `thumbnail_container` is on the right as a vertical panel containing the
  thumbnail, settings/record buttons, and audio meters.
- `AspectRatioSurfaceView` and `AprilTagOverlayView` use
  `layout_width="match_parent"`, `layout_height="match_parent"`,
  `layout_gravity="center"`. The surface view preserves the 4:3 camera aspect
  ratio and scales until its height matches the display height, leaving
  letter-boxing on the left/right if the container is wider than the video.

### Manifest

- `DualCameraActivity.android:configChanges` was reduced from
  `orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden` to
  just `keyboardHidden`. This lets Android recreate the activity on orientation
  changes so the `layout-land` resource is used.

### Stability during recreation

- Orientation changes destroy and recreate the activity, which tears down the
  USB camera client. Added guards so that late disconnect callbacks on the
  destroyed instance do not crash:
  - `recoverCamera()` returns early if `isDestroyed` is true.
  - `onDestroy()` nulls out `cameraClient`, `currentCamera`,
    `currentDevice`, and `currentCtrlBlock` after destroying the client.

## Files changed

- `app/src/main/java/com/example/remotesupportheadset/DualCameraActivity.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/menu/menu_settings.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/layout-land/activity_dual_camera.xml` (new)
- `AGENTS.md`
- `reports/landscape_mode_report.md` (new)

## Verification

- `./gradlew installDebug` built and installed the debug APK on a connected
  Pixel 10a.
- `./gradlew test` passed.
- With `landscape_mode=true` in `RemoteSupportHeadsetPrefs.xml`:
  - Logcat shows `Landscape mode pref loaded: enabled=true`.
  - The activity recreates once as the orientation switches to landscape.
  - Camera opens successfully and streams at ~14–15 FPS.
  - `adb shell dumpsys window displays` reports display rotation `1`
    (landscape).
- With `landscape_mode=false`, the app returns to portrait and runs normally.
- No `AndroidRuntime` crashes or `already destroyed` errors were observed after
  the stability guards were added.

## Default behavior

Landscape mode is **off by default**. The app launches in portrait orientation
with the original vertical layout until the user enables the setting.
