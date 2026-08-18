#!/usr/bin/env python3
"""Download a YouTube clip and prepare 640x480 15 FPS JPEG frames for YOLO validation.

The output is suitable for the in-app "video test source" mode in
RemoteSupportHeadset: the frames are pushed to the device and played back by
DualCameraActivity as a synthetic camera feed so YOLO / AprilTag detection can
be validated without a physical UVC camera.
"""

import argparse
import os
import subprocess
import sys

DEFAULT_VIDEO_ID = "f6Qu3eeRz4c"
DEFAULT_OUTPUT_DIR = "scripts/test_video_assets"
DEVICE_FRAME_DIR = "/sdcard/Android/data/com.example.remotesupportheadset/files/TestFrames"


def run(cmd: list[str]) -> None:
    """Run a command, echoing it first."""
    print("+ " + " ".join(cmd))
    subprocess.run(cmd, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prepare a YOLO test video source for RemoteSupportHeadset"
    )
    parser.add_argument(
        "--video-id",
        default=DEFAULT_VIDEO_ID,
        help="YouTube video ID to download (default: %(default)s)",
    )
    parser.add_argument(
        "--output-dir",
        default=DEFAULT_OUTPUT_DIR,
        help="Directory for the downloaded MP4 and extracted frames (default: %(default)s)",
    )
    args = parser.parse_args()

    output_dir = os.path.abspath(args.output_dir)
    os.makedirs(output_dir, exist_ok=True)

    video_path = os.path.join(output_dir, "test_video.mp4")
    frames_dir = os.path.join(output_dir, "test_frames")
    os.makedirs(frames_dir, exist_ok=True)

    print(f"Downloading clip {args.video_id}...")
    run(
        [
            "yt-dlp",
            "--extractor-args",
            "youtube:player_client=android",
            "-f",
            "best[height<=720]",
            "-o",
            video_path,
            args.video_id,
        ]
    )

    print("Re-encoding to 640x480 @ 15 FPS and extracting JPEG frames...")
    # Letter/pillar-box to 640x480 with black bars so every frame is exactly
    # 640x480, matching the app's default preview resolution.
    run(
        [
            "ffmpeg",
            "-y",
            "-i",
            video_path,
            "-vf",
            "fps=15,scale=640:480:force_original_aspect_ratio=decrease,pad=640:480:(ow-iw)/2:(oh-ih)/2",
            "-q:v",
            "2",
            os.path.join(frames_dir, "frame_%05d.jpg"),
        ]
    )

    frame_count = len(
        [f for f in os.listdir(frames_dir) if f.lower().endswith((".jpg", ".jpeg"))]
    )
    print(f"\nDone. Extracted {frame_count} frames to {frames_dir}")
    print("\nPush frames to the device:")
    print(f"  adb shell rm -rf {DEVICE_FRAME_DIR}")
    print(f"  adb push {frames_dir}/ {DEVICE_FRAME_DIR}/")
    print("\nLaunch the app in video-test mode:")
    print(
        "  adb shell am start -S -n com.example.remotesupportheadset/.DualCameraActivity "
        f"--es video_test_path {DEVICE_FRAME_DIR}/"
    )
    print("\nEnable person detection from the Settings menu, or via intent:")
    print(
        "  adb shell am start -S -n com.example.remotesupportheadset/.DualCameraActivity "
        f"--es video_test_path {DEVICE_FRAME_DIR}/ --ez yolo_enabled true"
    )

    return 0


if __name__ == "__main__":
    sys.exit(main())
