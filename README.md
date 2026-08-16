# RemoteSupportHeadset AndroidApp

Android companion app for the ESP32-P4 wearable webcam. It connects to one or two UVC cameras over USB-OTG, displays the feeds side-by-side, meters mic and speaker audio, detects AprilTags, corrects colour from a Macbeth chart, and can reflash the ESP32-P4 firmware without a computer.

## Features

- **Dual UVC camera preview** side-by-side (default 1280×960 from the ESP32-P4 CSI camera, 800×600 letterboxed from the DVP camera).
- **Auto-launch** when a UVC camera is plugged in.
- **Queues USB permission requests** for both cameras.
- **Camera labels** auto-hide after 5 seconds; tap the screen to show labels and controls again.
- **Live microphone input level meter** and **speaker output VU meter** (via the system `Visualizer`).
- **Full-screen immersive** landscape UI.
- **AprilTag 16h5 detection** with a temporal stability filter to remove single-frame false positives.
- **Macbeth chart colour correction**: detects the same AprilTag-coded charts as `esp32-wearable/tools`, solves a 3×4 affine CCM, and applies it to the preview and saved debug frames.
- **ESP32-P4 firmware flashing** over USB-OTG from binaries pushed to the device storage.
- **Debug preview capture** (`win_raw.jpg`, `win_annotated.jpg`, `win_corrected.jpg`) saved when a complete chart is seen.
- **Anti-banding analysis tool** (`AntiBandingTool`): stand-alone utility that sweeps CSI exposure time and reports the ESP32's auto anti-banding result versus the Android app's image-based optimum over ADB logcat.  Not wired to the normal UI.

## Requirements

- Android 5.0+ (API 21)
- Physical device with USB OTG host support
- ARM device (`armeabi-v7a` or `arm64-v8a`)
- One or two UVC-compliant USB cameras (the primary target is the ESP32-P4 composite UVC+CDC device)
- USB OTG adapter or hub
- For firmware flashing: the ESP32-P4 must be connected to the phone's USB-OTG port

## Build

Open the project in **Android Studio** — it will sync Gradle, build the NDK libraries, and download dependencies automatically.

Or from the command line:

```bash
# If you cloned without --recursive, fetch the esp-serial-flasher submodule first
git submodule update --init --recursive

./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> The Gradle wrapper (`gradlew`) is not checked into the repo. Generate it with `gradle wrapper --gradle-version 8.13` or use Android Studio.

### NDK / native libraries

The CMake build under `app/src/main/cpp/` produces two shared libraries:

- `libapriltag_jni.so` — AprilTag3 16h5 detector.
- `libesp32flasher.so` — Espressif serial-flasher wrapper for USB-CDC firmware updates.

`app/src/main/cpp/apriltag/` is vendored in the repo. `app/src/main/cpp/esp-serial-flasher/` is a Git submodule, so run `git submodule update --init --recursive` if you cloned without `--recursive`.

### Dependencies

| Library | Purpose |
|---|---|
| `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7` | UVC camera driver + multi-camera client |
| `com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7` | Native UVC protocol implementation |
| `androidx.exifinterface:exifinterface:1.3.7` | EXIF orientation handling |
| `com.github.mik3y:usb-serial-for-android:3.8.0` | Runtime CDC commands to the ESP32 (analysis tools) |
| AndroidX AppCompat, Core KTX, ConstraintLayout, Material | UI framework |

## Usage

1. Launch the app — it opens directly to the dual-camera view.
2. Plug in a USB camera via OTG. On first connection Android asks "Open with RemoteSupportHeadset?" — tap **OK** and grant USB permission.
3. Plug in a second camera if desired — it appears in the right slot.
4. Tap the screen to show/hide labels and controls.
5. The **MIC** and **SPK** meters at the bottom show live audio levels.
6. The **Firmware** button reflashes the ESP32-P4 from binaries placed in the device's `Firmware/` directory (see below).

### AprilTag / Macbeth chart detection

Point the camera at one of the supported AprilTag-coded Macbeth charts (layouts match `esp32-wearable/tools/chart_configs.py`). Once the four corner tags are stable for a few frames:

- Tag outlines and IDs are drawn on the overlay.
- A colour-correction matrix is computed from the swatches.
- Debug frames are saved to `/sdcard/Android/data/com.example.remotesupportheadset/files/Pictures/DebugPreview/`.

The colour-correction matrix is applied automatically to the live preview once it has been computed.

### ESP32-P4 firmware flashing

Push the three firmware binaries from the sibling `esp32-wearable/esp32-p4-wearable/build/` directory to the phone:

```bash
adb push build/bootloader/bootloader.bin \
         /sdcard/Android/data/com.example.remotesupportheadset/files/Firmware/bootloader.bin
adb push build/partition_table/partition-table.bin \
         /sdcard/Android/data/com.example.remotesupportheadset/files/Firmware/partition-table.bin
adb push build/usb_webcam.bin \
         /sdcard/Android/data/com.example.remotesupportheadset/files/Firmware/usb_webcam.bin
```

Then either tap **Firmware** in the app, or start flashing from `adb`:

```bash
adb shell am start -S -n com.example.remotesupportheadset/.DualCameraActivity --ez flash_now true
```

The app reboots the ESP32 into download mode and flashes each image to its correct offset.

### Anti-banding analysis tool

`AntiBandingTool` is a stand-alone analysis utility (not wired to the normal UI). Instantiate it from a debug/test path, point the camera at a uniform surface (e.g. a white wall) under the lighting you want to cancel, and call `start()`. The tool:

1. Enables the ESP32 AE loop and reads its self-computed anti-banding exposure and detected flicker frequency via `status`.
2. Disables AE and sweeps exposure time from 7 ms to 26 ms, measuring vertical-slice intensity variation for each.
3. Fine-tunes around the best coarse value and sets the exposure that minimizes banding.
4. Reports both results over ADB logcat:

```bash
adb logcat -d | grep AntiBandResult
```

Example output:

```
I AntiBandResult: FLICKER_HZ=50 ESP32_US=19999 ANDROID_US=19800 ANDROID_METRIC=0.060876 ANDROID_MEAN=147.9 DIFF_US=-199
```

- `FLICKER_HZ` is the mains frequency the firmware auto-detected.
- `ESP32_US` is the firmware's snapped anti-banding exposure.
- `ANDROID_US` is the exposure the image-based servo found.
- `ANDROID_METRIC` is the normalized vertical-slice standard deviation at that exposure (lower is better).
- `ANDROID_MEAN` is the average luminance at the chosen exposure; values near 255 indicate saturation.

## Notes

- Cameras are assigned left→right in the order they are detected.
- If a camera appears upside-down, its device ID can be added to the `invertedDeviceIds` list in `DualCameraActivity.kt` (`shouldRotateDevice()`).
- The speaker meter uses Android's `Visualizer` API and requires `RECORD_AUDIO` permission; some devices restrict global audio capture.
- USB cameras must be UVC class (0xEF or 0x0E) — proprietary-protocol cameras will not appear.
- AprilTag detection runs on the upright preview; do not add software flips in the Android app because orientation is already corrected in the ESP32 firmware.
- See `AGENTS.md` for full development conventions, project structure, and known limitations.
