#!/usr/bin/env python3
"""
Simulate 100 tap-to-capture lifecycle events on the RemoteSupportHeadset app.

The script launches the app once, then repeatedly sends a capture intent over
ADB.  After each capture it waits for the device to report:
  - SIMCAP complete  (JPEG saved)
  - SIMCAP resumed   (live preview stream resumed)

It then waits a random 3-10 s before the next tap.  At the end it prints
statistics and writes three histograms:
  - tap_to_complete_ms
  - tap_to_resume_ms
  - resume_to_next_tap_ms (the random wait interval)

Usage:
    python3 scripts/simulate_capture_lifecycle.py [--count 100] [--output-dir /tmp/simcap]
"""

import argparse
import os
import random
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

# Force unbuffered output so progress is visible through tee/pipes.
os.environ.setdefault("PYTHONUNBUFFERED", "1")

try:
    import matplotlib.pyplot as plt
    import numpy as np
except ImportError as e:
    print(f"Missing dependency: {e}")
    print("Install with: python3 -m pip install matplotlib numpy")
    sys.exit(1)


PACKAGE = "com.example.remotesupportheadset"
ACTIVITY = f"{PACKAGE}/.DualCameraActivity"
TAG = "DualCameraActivity"

# SIMCAP log patterns (monotonic timestamps from SystemClock.elapsedRealtime()).
START_RE = re.compile(r"SIMCAP start i=(\d+) t=(\d+)")
COMPLETE_RE = re.compile(r"SIMCAP complete i=(\d+) t=(\d+) dt_complete=(\d+)ms success=(true|false)")
RESUMED_RE = re.compile(r"SIMCAP resumed i=(\d+) t=(\d+) dt_resume=(\d+)ms")


@dataclass
class Event:
    index: int
    start_ms: int
    complete_ms: Optional[int]
    complete_dt_ms: Optional[int]
    complete_success: Optional[bool]
    resumed_ms: Optional[int]
    resumed_dt_ms: Optional[int]
    wait_before_next_s: Optional[float]


def adb(args: List[str], check: bool = True) -> str:
    cmd = ["adb"] + args
    result = subprocess.run(cmd, capture_output=True, text=True, check=check)
    return result.stdout + result.stderr


def send_capture_intent(index: int) -> None:
    adb([
        "shell", "am", "start",
        "-n", ACTIVITY,
        "--ez", "capture_now", "true",
        "--ei", "simulated_capture_index", str(index),
    ])


def clear_logcat() -> None:
    adb(["logcat", "-c"])


def dump_logcat() -> str:
    # Use verbose level so we can see the periodic "Camera health check OK"
    # (verbose) and the "No camera devices initially found" (debug) messages.
    return adb(["logcat", "-d", "-s", f"{TAG}:V"])


def parse_logcat(log: str, events: dict) -> None:
    for line in log.splitlines():
        m = START_RE.search(line)
        if m:
            idx = int(m.group(1))
            events[idx] = Event(
                index=idx,
                start_ms=int(m.group(2)),
                complete_ms=None,
                complete_dt_ms=None,
                complete_success=None,
                resumed_ms=None,
                resumed_dt_ms=None,
                wait_before_next_s=None,
            )
            continue
        m = COMPLETE_RE.search(line)
        if m:
            idx = int(m.group(1))
            if idx not in events:
                events[idx] = Event(index=idx, start_ms=0)
            events[idx].complete_ms = int(m.group(2))
            events[idx].complete_dt_ms = int(m.group(3))
            events[idx].complete_success = m.group(4) == "true"
            continue
        m = RESUMED_RE.search(line)
        if m:
            idx = int(m.group(1))
            if idx not in events:
                events[idx] = Event(index=idx, start_ms=0)
            events[idx].resumed_ms = int(m.group(2))
            events[idx].resumed_dt_ms = int(m.group(3))


def wait_for_event(events: dict, index: int, field: str, timeout: float = 30.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        parse_logcat(dump_logcat(), events)
        ev = events.get(index)
        if ev and getattr(ev, field) is not None:
            return True
        time.sleep(0.1)
    return False


# Patterns used to detect an Android USB host stall vs. healthy streaming.
STALL_RE = re.compile(
    r"No camera devices initially found|"
    r"Camera health check: no camera open|"
    r"Could not open CDC port"
)
HEALTHY_RE = re.compile(r"Camera health check OK|FPS: \d|AprilTag cycle:")

# CDC command failure emitted by DualCameraActivity.doSingleCapture().
CDC_CMD_FAIL_RE = re.compile(r"Failed to send capture command \(bulkTransfer returned -?\d+\)")


def is_camera_stalled(log: str) -> bool:
    """Return True if the app reports no camera / CDC available."""
    return bool(STALL_RE.search(log))


def is_camera_healthy(log: str) -> bool:
    """Return True if the app is receiving preview frames."""
    return bool(HEALTHY_RE.search(log))


def has_cdc_command_failure(log: str) -> bool:
    """Return True if a CDC OUT bulkTransfer failed (separate from preview health)."""
    return bool(CDC_CMD_FAIL_RE.search(log))


def recover_usb_host() -> None:
    """Reset the Android USB host port via ADB shell (no root required)."""
    print("  [recover] Resetting Android USB host port...")
    try:
        adb(["shell", "svc", "usb", "resetUsbPort"], check=False)
    except Exception as e:
        print(f"  [recover] resetUsbPort failed: {e}")
    time.sleep(2.0)
    try:
        adb(["shell", "svc", "usb", "enableUsbDataSignal", "false"], check=False)
        time.sleep(1.0)
        adb(["shell", "svc", "usb", "enableUsbDataSignal", "true"], check=False)
    except Exception as e:
        print(f"  [recover] enableUsbDataSignal toggle failed: {e}")
    time.sleep(2.0)


def wait_for_camera_healthy(timeout: float = 60.0) -> bool:
    """Poll logcat until the camera is streaming, or timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        log = dump_logcat()
        if is_camera_healthy(log):
            return True
        time.sleep(0.5)
    return False


def ensure_camera_healthy(recovery_attempts: int = 3) -> bool:
    """Ensure the camera is streaming, running USB host recovery if needed."""
    if is_camera_healthy(dump_logcat()):
        return True

    for attempt in range(1, recovery_attempts + 1):
        print(f"  [recover] Camera not healthy (attempt {attempt}/{recovery_attempts})")
        recover_usb_host()
        if wait_for_camera_healthy(timeout=60.0):
            print("  [recover] Camera healthy again")
            return True

    return False


def recover_from_cdc_failure() -> bool:
    """If a CDC command failure is present, reset the Android USB host port.

    The preview can remain healthy while the CDC OUT endpoint becomes stale,
    so this checks the failure pattern explicitly and only then runs recovery.
    """
    if not has_cdc_command_failure(dump_logcat()):
        return False
    print("  [recover] Detected CDC command failure, resetting USB host port...")
    recover_usb_host()
    if wait_for_camera_healthy(timeout=60.0):
        print("  [recover] CDC path recovered (camera healthy)")
        return True
    return False


def histogram(values: List[float], title: str, xlabel: str, output_path: Path) -> None:
    plt.figure(figsize=(8, 5))
    plt.hist(values, bins="auto", edgecolor="black")
    plt.title(title)
    plt.xlabel(xlabel)
    plt.ylabel("Count")
    mean = np.mean(values)
    p50 = np.percentile(values, 50)
    p95 = np.percentile(values, 95)
    p99 = np.percentile(values, 99)
    stats_text = f"mean={mean:.1f}\nmedian={p50:.1f}\np95={p95:.1f}\np99={p99:.1f}"
    plt.axvline(mean, color="red", linestyle="--", label=f"mean={mean:.1f}")
    plt.legend()
    plt.figtext(0.75, 0.65, stats_text, fontsize=10, family="monospace")
    plt.tight_layout()
    plt.savefig(output_path)
    plt.close()
    print(f"  saved {output_path}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Simulate 100 capture lifecycle events")
    parser.add_argument("--count", type=int, default=100, help="Number of capture events")
    parser.add_argument("--output-dir", type=str, default="/tmp/simcap", help="Directory for histograms")
    parser.add_argument("--wait-min", type=float, default=3.0, help="Minimum wait after resume (s)")
    parser.add_argument("--wait-max", type=float, default=10.0, help="Maximum wait after resume (s)")
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    print("Starting app...")
    adb(["shell", "am", "start", "-S", "-n", ACTIVITY])
    time.sleep(3.0)

    print(f"Running {args.count} simulated captures...")
    events: dict[int, Event] = {}
    clear_logcat()

    print("Ensuring camera is healthy before starting...")
    if not ensure_camera_healthy():
        print("ERROR: Camera did not become healthy; aborting.")
        return 1

    for i in range(args.count):
        wait_s = random.uniform(args.wait_min, args.wait_max)
        if i > 0:
            prev = events.get(i - 1)
            if prev and prev.resumed_ms:
                # Record the wait interval we used for event i (time from previous resume to this tap).
                prev.wait_before_next_s = wait_s
            print(f"  waiting {wait_s:.2f}s after resume before tap {i}...")
            time.sleep(wait_s)

        print(f"Tap {i}: sending capture intent...")
        send_capture_intent(i)

        print(f"Tap {i}: waiting for complete...")
        if not wait_for_event(events, i, "complete_ms", timeout=60.0):
            print(f"  WARNING: capture {i} did not complete in time")
            # Keep going; we'll report missing data at the end.

        print(f"Tap {i}: waiting for stream resume...")
        if not wait_for_event(events, i, "resumed_ms", timeout=60.0):
            print(f"  WARNING: capture {i} stream did not resume in time")

        # If the camera disappeared after this capture, try a software-only
        # USB host reset before proceeding.
        if i < args.count - 1 and not ensure_camera_healthy():
            # Preview may still be running while the CDC command path is dead.
            # Look for that specific failure pattern and reset just the host port.
            if not recover_from_cdc_failure():
                print(f"ERROR: Camera did not recover after capture {i}; aborting.")
                return 1

        ev = events.get(i)
        if ev:
            print(f"  complete={ev.complete_dt_ms}ms resume={ev.resumed_dt_ms}ms success={ev.complete_success}")

    # Pull final logs in case anything was missed.
    parse_logcat(dump_logcat(), events)

    complete_times = [e.complete_dt_ms for e in events.values() if e.complete_dt_ms is not None]
    resume_times = [e.resumed_dt_ms for e in events.values() if e.resumed_dt_ms is not None]
    wait_intervals = [e.wait_before_next_s for e in events.values() if e.wait_before_next_s is not None]

    print("\nResults:")
    print(f"  Total events: {args.count}")
    print(f"  Completed:    {len(complete_times)} / {args.count}")
    print(f"  Resumed:      {len(resume_times)} / {args.count}")
    print(f"  Success:      {sum(1 for e in events.values() if e.complete_success)} / {args.count}")

    if complete_times:
        print(f"  tap→complete: mean={np.mean(complete_times):.1f}ms median={np.percentile(complete_times, 50):.1f}ms p95={np.percentile(complete_times, 95):.1f}ms")
    if resume_times:
        print(f"  tap→resume:   mean={np.mean(resume_times):.1f}ms median={np.percentile(resume_times, 50):.1f}ms p95={np.percentile(resume_times, 95):.1f}ms")

    print("\nGenerating histograms...")
    if complete_times:
        histogram(complete_times, "Tap to Capture Complete", "ms", out_dir / "tap_to_complete.png")
    if resume_times:
        histogram(resume_times, "Tap to Live Stream Resume", "ms", out_dir / "tap_to_resume.png")
    if wait_intervals:
        histogram(wait_intervals, "Resume to Next Tap Wait Interval", "s", out_dir / "resume_to_next_tap.png")

    # Save raw data as CSV for further analysis.
    csv_path = out_dir / "events.csv"
    with open(csv_path, "w") as f:
        f.write("index,start_ms,complete_ms,complete_dt_ms,success,resumed_ms,resumed_dt_ms,wait_before_next_s\n")
        for e in sorted(events.values(), key=lambda x: x.index):
            f.write(
                f"{e.index},{e.start_ms},"
                f"{e.complete_ms if e.complete_ms is not None else ''},"
                f"{e.complete_dt_ms if e.complete_dt_ms is not None else ''},"
                f"{e.complete_success if e.complete_success is not None else ''},"
                f"{e.resumed_ms if e.resumed_ms is not None else ''},"
                f"{e.resumed_dt_ms if e.resumed_dt_ms is not None else ''},"
                f"{e.wait_before_next_s if e.wait_before_next_s is not None else ''}\n"
            )
    print(f"  saved {csv_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
