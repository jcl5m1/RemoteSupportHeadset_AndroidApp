# RemoteSupportHeadset AndroidApp

Android application that connects to two USB cameras via OTG and displays both feeds side-by-side, with live mic and speaker VU meters.

## Features

- Dual USB camera preview side-by-side (640×480 @ 30fps)
- Auto-launches when a UVC camera is plugged in
- Queues USB permission requests for both cameras
- Camera labels auto-hide after 5 seconds (tap screen to show again)
- Live microphone input level meter
- Live speaker output level meter (via system audio visualizer)
- Full-screen immersive mode

## Requirements

- Android 5.0+ (API 21)
- Physical device with USB OTG host support
- ARM device (armeabi-v7a or arm64-v8a)
- One or two UVC-compliant USB cameras (most webcams)
- USB OTG adapter or hub

## Build

Open the project in **Android Studio** — it will sync Gradle and download dependencies automatically.

Or from the command line:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Dependencies

| Library | Purpose |
|---|---|
| `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7` | UVC camera driver + multi-camera client |
| `com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7` | Native UVC protocol implementation |
| AndroidX AppCompat, Core KTX, ConstraintLayout | UI framework |

## Usage

1. Launch the app — it opens directly to the dual camera view
2. Plug in a USB camera via OTG adapter (Android will show a "Open with RemoteSupportHeadset?" dialog on first connection — tap **OK** and grant USB permission)
3. Plug in a second camera if desired — it appears in the right slot
4. Tap the screen to show/hide camera labels
5. MIC and SPK meters at the bottom show live audio levels

## Notes

- Cameras are assigned left→right in the order they are detected
- If a camera appears upside-down, its device ID can be added to the `invertedDeviceIds` list in `DualCameraActivity.kt` (`shouldRotateDevice()`)
- The speaker meter uses Android's `Visualizer` API and requires `RECORD_AUDIO` permission; some devices restrict global audio capture
- USB cameras must be UVC class (0xEF or 0x0E) — proprietary-protocol cameras will not appear
