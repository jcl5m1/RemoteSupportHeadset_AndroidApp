# RemoteSupportHeadset — Agent Guide

This document captures the project structure, build process, runtime capabilities, and development conventions for the **RemoteSupportHeadset AndroidApp**. It is written for AI coding agents that need to modify or extend the project.

## Project overview

RemoteSupportHeadset is an Android application that runs on a physical phone with USB OTG host support. It is the companion viewer / flasher for the ESP32-P4 wearable webcam firmware in the sibling `esp32-wearable` project.

Key user-facing behavior:

- Launches directly into a full-screen, landscape dual-camera view (`DualCameraActivity`).
- Auto-detects and requests permission for attached UVC cameras.
- Assigns the first camera to the left slot and the second camera to the right slot.
- If only one camera is connected, the view automatically zooms to that camera after a short delay.
- Double-tap a camera feed to toggle zoom; single-tap anywhere to temporarily show labels and controls.
- Labels, sliders, and the settings button auto-hide after 5 seconds.
- Displays live MIC and SPK level meters at the bottom.
- Provides a single **Record** button that toggles video recording on/off.
- Provides a **Settings** popup with **Update firmware** (download from a URL and reflash ESP32-P4) and **Show diagnostics** options.
- Runs AprilTag detection on the live preview, overlays detected tags, and can compute a colour-correction matrix from a Macbeth chart.
- Saves debug preview frames when a complete Macbeth chart is detected.

## Technology stack

- **Platform**: Android (minimum API 21, target SDK 34, compile SDK 34).
- **Language**: Kotlin 1.9.22.
- **Build system**: Gradle 8.13 with Android Gradle Plugin 8.13.2.
- **UI framework**: AndroidX AppCompat, ConstraintLayout, Material Components.
- **Architecture**: Single-Activity-style flow; the launcher `DualCameraActivity` contains all runtime logic. There is no MVVM framework, no dependency injection, and no navigation component.
- **Primary third-party library**: `com.github.jiangdongguo.AndroidUSBCamera` (libausbc + libuvc, version 3.2.7) for UVC camera enumeration, permission handling, and preview.
- **Computer vision**: AprilTag3 (16h5 family) through a custom JNI wrapper (`libapriltag_jni`).
- **Firmware flashing**: `esp-serial-flasher` through a custom JNI wrapper (`libesp32flasher`).
- **Runtime CDC commands**: `usb-serial-for-android` is used to open the ESP32-P4's CDC-ACM interface (when not already exclusively owned by the UVC stack) so the app can send exposure commands for the anti-banding servo.
- **Audio APIs**:
  - `AudioRecord` for the microphone meter.
  - `Visualizer(0)` for the global speaker output meter.
  - `AudioManager` + `ToneGenerator` for the speaker-volume slider feedback.

## Project structure

```
RemoteSupportHeadset_AndroidApp/
├── build.gradle                  # Root build script (Kotlin/AGP versions, clean task)
├── settings.gradle               # Project name and repositories (Google, MavenCentral, JitPack)
├── gradle.properties             # AndroidX, Jetifier, JVM args, JitPack proxy workaround
├── gradle/wrapper/              # Gradle wrapper config (8.13)
├── scripts/                     # Host helper scripts (e.g. ELF patching)
├── app/
│   ├── build.gradle              # App module config, dependencies, ABI filters, NDK build
│   ├── test.gradle               # Empty applied script (placeholder for future test config)
│   ├── proguard-rules.pro        # Keep rules for AndroidUSBCamera classes
│   ├── classes.txt               # Manually captured dependency JAR/AAR list
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── cpp/              # CMake NDK build: AprilTag3 + esp-serial-flasher JNI
│       │   │   ├── CMakeLists.txt
│       │   │   ├── apriltag/     # AprilTag3 submodule / source
│       │   │   ├── esp-serial-flasher/  # espressif serial flasher submodule / source
│       │   │   ├── apriltag_jni.cpp
│       │   │   ├── esp32_flasher_jni.cpp
│       │   │   └── android_usb_cdc_port.c
│       │   ├── java/com/example/remotesupportheadset/
│       │   │   ├── DualCameraActivity.kt       # Main launcher activity
│       │   │   ├── MainActivity.kt             # Legacy entry point (not used)
│       │   │   ├── AprilTagDetector.kt         # JNI AprilTag 16h5 detector
│       │   │   ├── AprilTagOverlayView.kt      # Overlay canvas for tag corners/IDs
│       │   │   ├── AprilTagTracker.kt          # Temporal stability filter
│       │   │   ├── MacbethColorCorrector.kt    # Chart layout + CCM solver/applier
│   │   │   ├── Esp32Flasher.kt             # JNI wrapper for esp-serial-flasher
│   │   │   ├── BandingAnalyzer.kt          # Vertical-slice banding metric from preview frames
│   │   │   ├── AntiBandingTool.kt          # Stand-alone exposure-servo analysis tool (not auto-run)
│   │   │   ├── CdcCommandHelper.kt         # USB CDC-ACM sender for runtime ESP32 commands
│   │   │   ├── PinchZoomPanImageView.kt    # Zoom/pan image view for zoomed chart
│   │   │   └── ReceiverExportWorkaroundContext.kt  # Android 14 receiver export workaround
│       │   └── res/
│       │       ├── layout/
│       │       │   ├── activity_dual_camera.xml
│       │       │   ├── activity_main.xml
│       │       │   └── dialog_flash_progress.xml
│       │       ├── values/
│       │       │   ├── strings.xml
│       │       │   └── themes.xml
│       │       ├── drawable/
│       │       └── xml/device_filter.xml       # USB UVC device filter
│       └── test/java/
│           └── TestClass.kt      # Minimal reflection-based unit test
├── README.md
├── AGENTS.md
└── LICENSE (Apache 2.0)
```

## Code organization

The application runtime is contained mostly in `DualCameraActivity.kt`. The main sections are:

1. **Lifecycle and permissions** (`onCreate`, `checkAndRequestPermissions`, `onRequestPermissionsResult`).
2. **USB camera client** (`cameraClient`, `IDeviceConnectCallBack` callbacks) — attaches, detaches, permission queues, and camera opening.
3. **UI state and gestures** — overlay visibility, zoom toggling, key-event logging, flash-progress dialog, settings popup menu.
4. **Audio metering** — `startMicMeter`, `startSpeakerMeter`, and their cleanup counterparts.
5. **AprilTag detection** — periodic capture of the preview bitmap, `AprilTagDetector.detect()`, `AprilTagTracker` filtering, and overlay rendering.
6. **Macbeth colour correction** — when a supported chart is detected, `MacbethColorCorrector.correctFromAprilTags()` solves a 3×4 affine CCM and applies it automatically to the preview and saved debug frames.
7. **Firmware flashing** — `startFirmwareFlashFlow()` finds `/Firmware/{bootloader.bin,partition-table.bin,usb_webcam.bin}` under `getExternalFilesDir(null)` and flashes them with `Esp32Flasher`.
8. **Debug preview save** — `saveDebugPreview()` writes `win_raw.jpg`, `win_annotated.jpg`, and optionally `win_corrected.jpg` to `Pictures/DebugPreview`.
9. **Anti-banding tool** — `AntiBandingTool` is a stand-alone analysis utility that grabs a vertical slice of the live preview, computes a banding metric with `BandingAnalyzer`, sweeps CSI exposure time via `CdcCommandHelper` to minimize intensity variation, captures the ESP32's own anti-banding exposure and detected flicker frequency, and emits a structured comparison line to `adb logcat` under the `AntiBandResult` tag.  Frames with mean intensity above ~92 % are rejected so the tool does not converge on an overexposed, clipped image.  It is no longer wired to the main UI or intent auto-start; instantiate and call `start()` from a debug/test path when needed.
10. **Utility helpers** — `nextFreeSurface`, `shouldRotateDevice`, `processNextPermission`, etc.

`MainActivity.kt` is currently dead code from an earlier iteration; the launcher intent in `AndroidManifest.xml` points directly to `DualCameraActivity`.

### Native libraries and ABI support

- Only `armeabi-v7a` and `arm64-v8a` ABIs are packaged.
- Native `.so` conflicts are resolved via `packagingOptions.pickFirst` for `libc++_shared.so` and `libUVCCamera.so`.
- Two JNI shared libraries are built by CMake:
  - `libapriltag_jni.so` — exposes `AprilTagDetector.nativeDetect(Bitmap)`.
  - `libesp32flasher.so` — exposes `Esp32Flasher.nativeFlash(...)` for USB-CDC flashing.

## Build commands

From the project root:

```bash
# Build a debug APK (also builds the native libraries)
./gradlew assembleDebug

# Build and install on a connected device
./gradlew installDebug

# Clean build outputs
./gradlew clean
```

**Note:** The Gradle wrapper script (`gradlew` / `gradlew.bat`) is not checked into this repository. To use the command-line commands above, either open the project in Android Studio (which will generate the wrapper) or generate it locally with a system Gradle installation (`gradle wrapper --gradle-version 8.13`).

The debug APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

You can also open the project in Android Studio and let it sync Gradle automatically.

### Build configuration notes

- `minifyEnabled false` for release builds; ProGuard rules still keep AndroidUSBCamera classes for future use.
- `sourceCompatibility` / `targetCompatibility` are set to Java 8; Kotlin `jvmTarget` is `1.8`.
- JitPack is configured in `settings.gradle`. Some transitive dependencies originally hosted on JitPack are pulled directly from MavenCentral in `app/build.gradle` (`immersionbar`, `webpdecoder`) to avoid 401 errors.
- The NDK CMake build needs:
  - `app/src/main/cpp/apriltag/` — vendored AprilTag3 source (included in the repo).
  - `app/src/main/cpp/esp-serial-flasher/` — registered as a Git submodule pointing to `https://github.com/espressif/esp-serial-flasher.git`.
  Clone with `--recursive` or run `git submodule update --init --recursive` after cloning to populate the submodule before the first build.

## AprilTag detection

- Tag family: **16h5**.
- Detected on every preview-frame callback in a background thread.
- Detections are passed through `AprilTagTracker`, which requires a tag to be present in a small spatial window for several consecutive frames before it is reported as stable. This removes most single-frame false positives.
- Stable detections are drawn on `AprilTagOverlayView` and used by the Macbeth chart decoder.
- Detection runs directly on the upright preview bitmap. Do not re-introduce orientation permutations in the Android app; orientation is corrected in the ESP32 firmware.

## Macbeth colour correction

- Supports the same AprilTag-coded Macbeth chart layouts as `esp32-wearable/tools/chart_configs.py` (`3×3`, `3×4`, `4×4`, `4×6`).
- When all four corner tags of a known chart are stable, the app samples the swatch interiors and solves a 3×4 affine colour-correction matrix in linear light.
- The fit is hard-constrained so the observed black and white patches map to `(0,0,0)` and `(255,255,255)`, matching the Python reference decoder.
- Correction is applied automatically once a CCM has been computed; the raw UVC stream is shown before a chart is detected.

## ESP32-P4 firmware flashing

The app can reflash the ESP32-P4 over the high-speed USB-OTG CDC download port:

1. Push the three firmware binaries to `/sdcard/Android/data/com.example.remotesupportheadset/files/Firmware/`:
   - `bootloader.bin`
   - `partition-table.bin`
   - `usb_webcam.bin`
2. Open **Settings → Update firmware** in the app, or launch the activity with `--ez flash_now true` to skip the confirmation dialog:
   ```bash
   adb shell am start -S -n com.example.remotesupportheadset/.DualCameraActivity --ez flash_now true
   ```
3. The app reboots the ESP32 into ROM download mode, opens the download-mode USB device, and flashes each image to its offset via `libesp32flasher.so`.
4. After a successful flash the ESP32 resets and re-enumerates as a UVC+CDC device.

> The `AntiBandingTool` analysis utility is no longer wired to the main UI or to the `anti_band_now` intent.  Instantiate it from a debug/test path when you need to rerun the exposure sweep; results are still emitted to `adb logcat` under the `AntiBandResult` tag.

## Debug preview capture

Whenever all four corner tags of a Macbeth chart are stable, the app saves a debug frame to:

```
/sdcard/Android/data/com.example.remotesupportheadset/files/Pictures/DebugPreview/
```

Files:

- `win_raw.jpg` — uncorrected preview bitmap.
- `win_annotated.jpg` — preview with tag outlines/IDs drawn.
- `win_corrected.jpg` — colour-corrected bitmap (only after a CCM has been computed).

Captures are rate-limited to once per 5 seconds to avoid filling storage.

## Testing

The project has a single unit test at `app/src/test/java/TestClass.kt`. It uses Java reflection to print the methods of `com.jiangdg.ausbc.callback.IDeviceConnectCallBack`. It is not an automated assertion test; it was used to inspect the callback interface of the UVC library.

Run tests with:

```bash
./gradlew test
```

There are currently no instrumented/Android tests (`androidTest` source set does not exist).

## Development conventions

- **Package**: `com.example.remotesupportheadset`.
- **Code style**: Kotlin official code style is enabled in `gradle.properties` (`kotlin.code.style=official`).
- **String resources**: UI strings live in `res/values/strings.xml`. Hard-coded preview labels and status strings still exist in the layout and activity code; prefer extracting new user-facing text to `strings.xml`.
- **IDs**: Layout IDs use snake_case (`texture_camera_left`, `meter_mic`).
- **Logging**: Use Android `Log` with the class-level `TAG` constant.
- **Threading**: Camera callbacks run on background threads; UI updates are wrapped with `runOnUiThread`. Audio meter threads are managed manually.
- **Permissions**: Runtime permissions are requested together (`CAMERA`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`).

## Deployment process

There is no CI/CD pipeline, signing config, or app store deployment script in this repository. To produce a release APK:

1. Add a signing config to `app/build.gradle` under `buildTypes.release`.
2. Run `./gradlew assembleRelease`.
3. Distribute `app/build/outputs/apk/release/app-release.apk` manually.

## Security considerations

- The app requests `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS` so it can meter audio and control media volume. It does not transmit or store audio.
- `WRITE_EXTERNAL_STORAGE` is declared with `android:maxSdkVersion="28"` for older device compatibility but is not used in current code.
- The speaker meter uses `Visualizer(0)`, which attaches to the global audio output mix. Some OEM skins restrict this API or require `RECORD_AUDIO` permission; it may fail silently on those devices.
- USB device access relies on the user granting the Android USB permission dialog at runtime.
- Native libraries from `AndroidUSBCamera` are bundled as AAR dependencies. Keep those dependencies up to date and review ProGuard keep rules if minification is ever enabled.

## Known limitations and gotchas

- The app only runs on ARM devices with USB OTG host support. Emulators will not exercise camera or USB functionality meaningfully.
- UVC camera orientation is corrected in the ESP32-P4 firmware by configuring the CSI OV5647 sensor's vertical-flip register (`sensor_set_flip(..., h_mirror=false, v_flip=true)`). No software flipping is performed on the ESP32 or the Android phone; the live UVC stream arrives upright.
- The ISP Bayer order for the CSI live stream is `GRBG`. The OV5647 sensor's native CFA is `BGGR`; the sensor-level vertical flip maps that to `GRBG` for the ISP. The debayering stays in the ESP32-P4 ISP; the Android phone only receives YUV422/MJPEG.
- The firmware auto-detects the local mains frequency (50 Hz or 60 Hz) and snaps CSI exposure to integer multiples of the corresponding flicker half-period (10000 µs for 50 Hz mains, 8333 µs for 60 Hz mains) to reduce rolling banding.
- A diagonal ISP colour-correction matrix (CCM) is applied in the ESP32-P4 ISP to neutralise the warm cast from the test lighting. The current gains are derived from the Macbeth chart white patch (R scaled to match G, B scaled to match G). The scene-mode AE target is also raised so the chart is properly exposed.
- AprilTag detection runs directly on the upright preview bitmap. Do not re-introduce orientation permutations in the Android app.
- `MainActivity` is not the launcher; do not add it to the `MAIN/LAUNCHER` intent filter unless you intentionally change the entry point.
- `test.gradle` is applied but empty. It is safe to add future test-only dependencies or configuration there.
- `app/classes.txt` is a manually maintained inventory of resolved AAR/JAR dependencies; it is not used by the build. Update it if you want to keep it accurate.
- CDC still-capture reliability depends on small (4 KiB) bulk reads and correct handling of the `STILL_END` trailer. Large single-request bulk transfers can leave the final bytes behind on this host/device pair.
