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
- Provides a single **Record** button that toggles video recording on/off; finished videos and still JPEGs are published to public MediaStore albums (`Pictures/RemoteSupportHeadset` and `Movies/RemoteSupportHeadset`) that Google Photos syncs automatically.
- The bottom thumbnail shows the most recent photo or video from the synced album; tapping it opens Google Photos (or the system's viewer) to that item.
- Provides a **Settings** popup with **Update firmware** (download from a URL and reflash ESP32-P4), **Show diagnostics**, **AprilTag detection**, and **Person detection** options.
- A **rotation button** in the bottom-right corner toggles between portrait and landscape orientation. Landscape mode locks the activity to landscape, uses a dedicated landscape layout (camera preview on the left filling the display height, control strip on the right), and scales the camera preview so its vertical dimension matches the landscape display height.
- Runs AprilTag detection on the live preview only when enabled in Settings; it defaults to off. When enabled, detected tags are overlaid and a colour-correction matrix can be computed from a Macbeth chart.
- Runs YOLOv8n person detection on the live preview only when enabled in Settings; it defaults to off. When enabled, person bounding boxes are overlaid on the live stream and burned into saved still JPEGs.
- Runs YOLOv8n person detection on the live preview only when enabled in Settings; it defaults to off. When enabled, person bounding boxes are overlaid on the live stream and burned into saved still JPEGs.
- Saves debug preview frames when a complete Macbeth chart is detected.

## Technology stack

- **Platform**: Android (minimum API 21, target SDK 34, compile SDK 34).
- **Language**: Kotlin 1.9.22.
- **Build system**: Gradle 8.13 with Android Gradle Plugin 8.13.2.
- **UI framework**: AndroidX AppCompat, ConstraintLayout, Material Components.
- **Architecture**: Single-Activity-style flow; the launcher `DualCameraActivity` contains all runtime logic. There is no MVVM framework, no dependency injection, and no navigation component.
- **Primary third-party library**: `com.github.jiangdongguo.AndroidUSBCamera` (libausbc + libuvc, version 3.2.7) for UVC camera enumeration, permission handling, and preview.
- **Computer vision**: AprilTag3 (16h5 family) through a custom JNI wrapper (`libapriltag_jni`); YOLOv8n person detection via ONNX Runtime Android.
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
│   │   │   ├── YoloPersonDetector.kt       # ONNX Runtime YOLOv8n person detector
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
3. **UI state and gestures** — overlay visibility, zoom toggling, key-event logging, flash-progress dialog, settings popup menu, and the album thumbnail that refreshes from the public MediaStore album and opens Google Photos on tap.
4. **Audio metering** — `startMicMeter`, `startSpeakerMeter`, and their cleanup counterparts.
5. **AprilTag detection** — periodic capture of the preview bitmap, `AprilTagDetector.detect()`, `AprilTagTracker` filtering, and overlay rendering.
6. **YOLO person detection** — optional, settings-toggled. Runs YOLOv8n inference via ONNX Runtime on the NV21 preview frame in a dedicated background thread, maps detections to overlay coordinates, and annotates saved still JPEGs.
6. **Macbeth colour correction** — when a supported chart is detected, `MacbethColorCorrector.correctFromAprilTags()` solves a 3×4 affine CCM and applies it automatically to the preview and saved debug frames.
7. **Firmware flashing** — `startFirmwareFlashFlow()` finds `/Firmware/{bootloader.bin,partition-table.bin,usb_webcam.bin}` under `getExternalFilesDir(null)` and flashes them with `Esp32Flasher`.
8. **Media publishing** — finished still captures (`saveJpeg()`) are copied into the public `Pictures/RemoteSupportHeadset` album via MediaStore and the app-private copy is deleted; videos (`finishRecordingFlow()`) are copied into `Movies/RemoteSupportHeadset` the same way. **Debug preview save** — `saveDebugPreview()` still writes `win_raw.jpg`, `win_annotated.jpg`, and optionally `win_corrected.jpg` to the app-private `Pictures/DebugPreview` folder.
9. **Anti-banding tool** — `AntiBandingTool` is a stand-alone analysis utility that grabs a vertical slice of the live preview, computes a banding metric with `BandingAnalyzer`, sweeps CSI exposure time via `CdcCommandHelper` to minimize intensity variation, captures the ESP32's own anti-banding exposure and detected flicker frequency, and emits a structured comparison line to `adb logcat` under the `AntiBandResult` tag.  Frames with mean intensity above ~92 % are rejected so the tool does not converge on an overexposed, clipped image.  It is no longer wired to the main UI or intent auto-start; instantiate and call `start()` from a debug/test path when needed.
10. **Utility helpers** — `nextFreeSurface`, `shouldRotateDevice`, `processNextPermission`, etc.

`MainActivity.kt` is currently dead code from an earlier iteration; the launcher intent in `AndroidManifest.xml` points directly to `DualCameraActivity`.

### Native libraries and ABI support

- Only `armeabi-v7a` and `arm64-v8a` ABIs are packaged.
- Native `.so` conflicts are resolved via `packagingOptions.pickFirst` for `libc++_shared.so` and `libUVCCamera.so`.
- Two JNI shared libraries are built by CMake:
  - `libapriltag_jni.so` — exposes `AprilTagDetector.nativeDetect(Bitmap)`.
  - `libesp32flasher.so` — exposes `Esp32Flasher.nativeFlash(...)` for USB-CDC flashing.
- All native libraries are forced to 16 KB ELF alignment for Android 15+:
  - CMake links our shared libraries with `-Wl,-z,max-page-size=16384`.
  - A Gradle hook runs `scripts/patch_elf_16kb.py` on the merged native libs directory after `mergeDebugNativeLibs` / `mergeReleaseNativeLibs`, upgrading any 4 KB aligned prebuilt `.so` files from AAR dependencies.

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

The Gradle wrapper (`gradlew` / `gradlew.bat`) is checked into the repository, so the above commands work out of the box.

The debug APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

You can also open the project in Android Studio and let it sync Gradle automatically.

### Agent build/deploy environment

> **All build and deploy tools are present.** This workspace has a working JDK, Android SDK, NDK, Gradle wrapper, and ADB. Do not claim that the JDK or Android SDK is missing — use the paths below if a command fails for an unrelated reason.

The agent environment in this workspace is set up to build and deploy the Android app directly. All required tools (JDK, Android SDK, ADB) are installed and available, so agents can and should build, install, and restart the app without claiming any are missing.

- **JDK**: OpenJDK 17 (LTS) is used for the Gradle build. OpenJDK 26 is installed on the host, but Gradle 8.13 / AGP 8.13 fail to evaluate the build scripts under JDK 26 (Groovy's bundled ASM rejects Java 26 class files, major version 70), so `org.gradle.java.home` in `gradle.properties` is pinned to `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. The app itself is still compiled to Java 8 bytecode (`sourceCompatibility` / `targetCompatibility` `VERSION_1_8`, Kotlin `jvmTarget = '1.8'`).
- **Android SDK**: Command-line tools are installed at `/opt/homebrew/share/android-commandlinetools` and referenced by `local.properties` (`sdk.dir=...`).
- **NDK**: The Android NDK required by the CMake build is bundled with the command-line tools / SDK and referenced via `ndkVersion` in `app/build.gradle`.
- **ADB**: `adb` is on `PATH` at `/opt/homebrew/bin/adb`; one or more physical devices are normally attached over ADB.

So the full build-and-install flow can be run by the agent without opening Android Studio and without manually exporting `JAVA_HOME`:

```bash
./gradlew installDebug
```

The same environment can also build the sibling ESP32-P4 firmware in `../esp32-wearable/esp32-p4-wearable/` and deploy it through the Android app.

### Build configuration notes

- `minifyEnabled false` for release builds; ProGuard rules still keep AndroidUSBCamera classes for future use.
- `sourceCompatibility` / `targetCompatibility` are set to Java 8; Kotlin `jvmTarget` is `1.8`.
- JitPack is configured in `settings.gradle`. Some transitive dependencies originally hosted on JitPack are pulled directly from MavenCentral in `app/build.gradle` (`immersionbar`, `webpdecoder`) to avoid 401 errors.
- The NDK CMake build needs:
  - `app/src/main/cpp/apriltag/` — vendored AprilTag3 source (included in the repo).
  - `app/src/main/cpp/esp-serial-flasher/` — registered as a Git submodule pointing to `https://github.com/espressif/esp-serial-flasher.git`.
  Clone with `--recursive` or run `git submodule update --init --recursive` after cloning to populate the submodule before the first build.

## Preview rendering

The camera preview is rendered with an `AspectRatioSurfaceView` instead of a `TextureView`. The UVC camera stream is decoded by the AndroidUSBCamera library and sent directly to the SurfaceView's Surface, so the GPU composites it to the screen without an intermediate CPU bitmap readback. This is the most memory-efficient path for live preview.

- The camera stream is rendered at the camera's chosen preview size (e.g. 1280×960).
- The SurfaceView uses `setZOrderMediaOverlay(true)` so the normal `AprilTagOverlayView` and other UI widgets can sit on top of it in the same window.
- `TextureView.getBitmap()` is no longer used, so there is no GPU→CPU readback on the preview path.

## AprilTag detection

- Tag family: **16h5**.
- Detection runs on the NV21 preview data delivered by `IPreviewDataCallBack` in a background thread.
- Only the Y plane is used for detection; it is subsampled to 640×480 before being passed to the native detector. This keeps the detector's internal `quad_decimate=2` pipeline running at 320×240 for speed and temporal stability.
- A full NV21→ARGB conversion is performed only when a complete Macbeth chart is stable, and only for colour-correction matrix computation and debug frame saves.
- Detections are passed through `AprilTagTracker`, which requires a tag to be present in a small spatial window for several consecutive frames before it is reported as stable. The tracker's `maxPositionJumpPx` is set relative to the detection resolution.
- Stable detections are drawn on `AprilTagOverlayView` (a normal View on top of the SurfaceView) and used by the Macbeth chart decoder.
- Detection runs directly on the upright preview frame. Do not re-introduce orientation permutations in the Android app; orientation is corrected in the ESP32 firmware.

## YOLO person detection

- Model: **Ultralytics YOLOv8n** exported to ONNX at 320×320, bundled as `app/src/main/assets/yolov8n-person-320.onnx`.
- Runtime: **ONNX Runtime Android** (`com.microsoft.onnxruntime:onnxruntime-android`).
- Detection defaults to **off** and is toggled from **Settings → Person detection**.
- The NV21 preview frame is converted to an ARGB `Bitmap` and run through `YoloPersonDetector` on a dedicated `HandlerThread("YoloLive")`.
- Only COCO class **0 (person)** is reported; all other classes are discarded.
- Detections are mapped to overlay view coordinates and drawn as red boxes with confidence labels on `AprilTagOverlayView`.
- Saved still JPEGs are annotated with the same person boxes before being published to the Google Photos album.
- Inference time and person count are shown in the diagnostics panel when the feature is enabled.

## Macbeth colour correction

- Supports the same AprilTag-coded Macbeth chart layouts as `esp32-wearable/tools/chart_configs.py` (`3×3`, `3×4`, `4×4`, `4×6`).
- When all four corner tags of a known chart are stable, the app samples the swatch interiors and solves a 3×4 affine colour-correction matrix in linear light.
- The fit is hard-constrained so the observed black and white patches map to `(0,0,0)` and `(255,255,255)`, matching the Python reference decoder.
- Correction is applied automatically once a CCM has been computed; the raw UVC stream is shown before a chart is detected.
- Full-resolution still JPEGs are only rotated/AWB-corrected when a stable Macbeth chart has been seen in the live preview within the last 30 seconds. This avoids a ~550 ms decode/rotate/re-encode pass on captures that contain no chart.

## ESP32-P4 firmware flashing

The app can reflash the ESP32-P4 over the high-speed USB-OTG CDC download port. The preferred deploy path is the one-command helper in the sibling `esp32-wearable/esp32-p4-wearable/` project:

```bash
python3 deploy_to_phone.py
```

This builds the firmware, serves the timestamped zip locally, sends the ADB intent to the Android app, and tails `adb logcat` until the flash finishes or fails. Options include `--device`, `--port`, `--no-build`, and `--no-logcat`.

### Manual path

If you need to run the steps separately:

1. Build the firmware and package it:
   ```bash
   idf.py build
   python3 serve_firmware.py --port 8765
   ```
   This writes `build/firmware_YYYYMMDD_HHMMSS.zip` containing `bootloader.bin`, `partition-table.bin`, and `usb_webcam.bin`, updates `build/firmware_latest.txt` with the latest zip name, and prints a reachable URL such as `http://192.168.1.123:8765/firmware_20260817_123045.zip`.
2. Open **Settings → Update firmware** in the app and paste the `.zip` URL, or launch the activity with the URL and `--ez flash_now true` to skip the confirmation dialog:
   ```bash
   adb shell am start -S -n com.example.remotesupportheadset/.DualCameraActivity \
       --ez flash_now true \
       --es firmware_url http://<host-ip>:8765/firmware_YYYYMMDD_HHMMSS.zip
   ```
3. The app downloads the zip to a temporary cache file, extracts the three binaries into `/sdcard/Android/data/com.example.remotesupportheadset/files/Firmware/`, reboots the ESP32 into ROM download mode, and flashes each image to its offset via `libesp32flasher.so`.
4. After a successful flash the ESP32 resets and re-enumerates as a UVC+CDC device.

The current firmware build version is queried over the high-speed CDC port and shown in the bottom UI as **FW: yyyymmdd_hhmmss**. **Settings → Check for latest firmware** reads `firmware_latest.txt` from the directory URL, compares versions, and offers to download and flash a newer zip.

If the binaries are already on the phone, `--ez flash_now true` alone starts flashing from the existing files.

> The `AntiBandingTool` analysis utility is no longer wired to the main UI or to the `anti_band_now` intent.  Instantiate it from a debug/test path when you need to rerun the exposure sweep; results are still emitted to `adb logcat` under the `AntiBandResult` tag.

## Photo and video storage

Captured stills and recordings are published to public MediaStore albums so they are picked up and synced by Google Photos:

- Still JPEGs: `Pictures/RemoteSupportHeadset/IMG_YYYYMMDD_HHMMSS_nnn.jpg`
- Videos: `Movies/RemoteSupportHeadset/VID_YYYYMMDD_HHMMSS.mp4`

After a successful MediaStore insert the app-private copy in `/sdcard/Android/data/com.example.remotesupportheadset/files/` is deleted. The bottom thumbnail queries the public album every 2 s and loads the latest image or video thumbnail. Tapping the thumbnail opens that item in Google Photos (`com.google.android.apps.photos`) if installed, falling back to the system's generic viewer.

The custom still-image zoom/pan overlay was removed; zoom, pan, and album browsing are handled by Google Photos.

## Debug preview capture

Whenever all four corner tags of a Macbeth chart are stable, the app saves a debug frame to the app-private folder:

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

### Automated capture-lifecycle stress test

`scripts/simulate_capture_lifecycle.py` drives the app through repeated still-capture life cycles using ADB intents. It records:

- tap-to-capture-complete time,
- tap-to-live-stream-resume time,
- inter-capture wait interval,
- success/failure counts.

Run a small smoke test (create the venv first):

```bash
cd scripts
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python3 simulate_capture_lifecycle.py --count 3 --zoom
```

For full histogram data use `--count 100` and omit `--zoom` to exercise the plain capture path. The script prints statistics and, if `matplotlib` is installed, writes a histogram PNG. It also saves the full streamed logcat to `<output-dir>/full_logcat.log`.

To break the capture time into per-phase components (intent→start, drain, command, command→`STILL_LEN`, payload, trailer, save) run:

```bash
python3 scripts/parse_capture_timing.py /tmp/simcap/full_logcat.log
```

The harness treats `FPS > 0` as the health signal, auto-recovers from a stalled Android USB host by escalating from `svc usb resetUsbPort` to `svc usb enableUsbDataSignal false && svc usb enableUsbDataSignal true`, and decodes logcat with replacement characters so binary CDC/UVC traffic cannot crash the reader. A recent run completed 100 randomized captures with 100/100 success and stream-resume.

### Automated audio loopback qualification test

`scripts/audio_loopback_test.py` is a host-side regression test for the Android app's USB audio path. The ESP32-P4 stays connected to the Android phone, and a MacBook provides the reference speaker and microphone:

  1. MacBook plays a tone; the Android app records from the ESP32 microphone over USB.
  2. Android app plays a tone through the ESP32 speaker over USB; MacBook records it.

For each direction the script reports detected peak frequency, SNR, RMS level, and dropout duration. It exits non-zero and saves a PNG/JSON report if either direction fails the thresholds. The default thresholds are intentionally lenient on SNR so the test passes with the modest acoustic coupling of a MacBook speaker/mic; the critical regression signal is the absence of dropouts (packetised break-up).

Run it from the repo root:

```bash
cd scripts
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

python3 audio_loopback_test.py \
    --mac-speaker "MacBook Air Speakers" \
    --mac-mic "MacBook Air Microphone" \
    --duration 5 \
    -o audio_loopback_report.png \
    --report audio_loopback_report.json
```

The script defaults to `--mac-volume 75`, `--mac-input-volume 50`, and `--esp32-volume 75`. Raise `--mac-input-volume` or `--esp32-volume` if the acoustic path is weak, and lower them if the MacBook mic clips.

Requirements on the MacBook host: `adb`, `SwitchAudioSource` (switchaudio-osx), `ffmpeg` with Audiotoolbox/AVFoundation support, and `numpy`/`matplotlib`.

The Android side of the test is implemented by `AudioLoopbackTest.kt` and is triggered from `DualCameraActivity` via the ADB intent extras documented in `scripts/audio_loopback_test.py`. The ESP32 speaker volume is set directly on the ES8311 codec with the `spkvol` CDC command so it is independent of the host's UAC2 volume mapping.

**Known choppiness remedies.** The microphone direction originally showed regular 5–10 ms dropouts (and occasional much larger gaps) while the camera preview was active. Root cause: the ESP32 audio tasks ran at FreeRTOS priority 5, the same as `video_task`, so camera work could starve the USB audio pipeline. The fix is to raise the audio task priorities to 7 in `audio_pipeline.c`. A second issue where the ESP32 speaker remained silent after a host mute request was fixed by unmuting the codec whenever a non-zero volume is set via `spkvol`.

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

## Video test source

The app has an in-app "video test source" mode that replays a directory of JPEG frames as a synthetic camera feed. It is useful for validating YOLO person detection and AprilTag detection without an attached UVC camera.

To prepare a test clip:

```bash
python3 scripts/prepare_yolo_test_video.py
```

This downloads the default clip (`f6Qu3eeRz4c`) with `yt-dlp`, re-encodes it to 640x480 @ 15 FPS with black-bar padding, and extracts JPEG frames into `scripts/test_video_assets/test_frames/`. The script prints the exact `adb push` and `adb shell am start` commands.

Push the frames to the device and launch test mode:

```bash
adb shell rm -rf /sdcard/Android/data/com.example.remotesupportheadset/files/TestFrames
adb push scripts/test_video_assets/test_frames/ /sdcard/Android/data/com.example.remotesupportheadset/files/TestFrames/
adb shell am start -S -n com.example.remotesupportheadset/.DualCameraActivity \
    --es video_test_path /sdcard/Android/data/com.example.remotesupportheadset/files/TestFrames/
```

You can also start the test source from **Settings → Video test source** while the app is running. It stops any open UVC camera and plays the persisted/default frame directory. Still image capture is disabled in video-test mode because it relies on the ESP32 CDC/UVC protocol.

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
- If a firmware flash fails part-way through, the ESP32 may remain in ROM download mode (USB VID/PID `303a:0012`) and will not enumerate as a UVC+CDC device. When `adb shell dumpsys usb` shows `kernel_state=DISCONNECTED` and no `/sys/bus/usb/devices/*/idVendor` appears after reconnecting, press the **RST/RESET** button on the ESP32-P4 board to reboot into the application firmware (PID `0x4022`).
- The Android USB host stack can get into a stalled state where it provides VBUS power (`host_connected=true`) but never enumerates the attached device (`kernel_state=DISCONNECTED`, no `USB_DEVICE_ATTACHED` intent). In that state, unplugging and replugging the device cable is usually not enough.
- **ADB OTG/host recovery (first try):** The phone can reset/power-cycle the host port from shell without root. Run the full sequence and wait for re-enumeration:
  ```bash
  adb shell "am force-stop com.example.remotesupportheadset; \
             svc usb enableUsbDataSignal false; \
             sleep 2; \
             svc usb resetUsbPort; \
             sleep 2; \
             svc usb enableUsbDataSignal true; \
             sleep 7; \
             dumpsys usb | grep -E 'kernel_state|host_connected'"
  ```
  `enableUsbDataSignal false` drops the data lines, `resetUsbPort` resets the first host port, and `enableUsbDataSignal true` brings the PHY back up. The `sleep 7` gives the device time to re-enumerate. The automated test harnesses in `scripts/simulate_capture_lifecycle.py` and `scripts/simulate_record_lifecycle.py` use this same escalation.
- **Physical OTG adapter/hub power-cycle (fallback):** If the ADB recovery leaves `kernel_state=DISCONNECTED`, power-cycle the OTG adapter/hub itself (unplug it from the phone, wait a moment, plug it back in) so the phone's USB host controller fully re-initialises.
