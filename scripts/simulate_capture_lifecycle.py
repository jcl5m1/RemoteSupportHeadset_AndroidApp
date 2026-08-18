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
import queue
import random
import re
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional

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


class LogcatReader:
    """Stream ADB logcat in a background thread and expose parsed events."""

    def __init__(self, full_log_path: Optional[Path] = None) -> None:
        self.events: Dict[int, Event] = {}
        self.recent_lines: queue.Queue[str] = queue.Queue(maxsize=10000)
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._proc: Optional[subprocess.Popen] = None
        self._full_log_path = full_log_path
        self._full_log_file: Optional[object] = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._reader_loop, name="LogcatReader", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._proc is not None:
            try:
                self._proc.terminate()
            except Exception:
                pass
        if self._thread is not None:
            self._thread.join(timeout=2.0)
        if self._full_log_file is not None:
            try:
                self._full_log_file.close()
            except Exception:
                pass

    def _reader_loop(self) -> None:
        # Clear existing logcat first, then stream new entries.
        subprocess.run(["adb", "logcat", "-c"], capture_output=True)
        if self._full_log_path is not None:
            self._full_log_path.parent.mkdir(parents=True, exist_ok=True)
            self._full_log_file = self._full_log_path.open("w", encoding="utf-8", errors="replace")
        cmd = ["adb", "logcat", "-v", "threadtime", "-s", f"{TAG}:V"]
        self._proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if self._proc.stdout is None:
            return
        for raw in self._proc.stdout:
            if self._stop_event.is_set():
                break
            line = raw.decode("utf-8", errors="replace").rstrip("\n")
            if self._full_log_file is not None:
                self._full_log_file.write(line + "\n")
                self._full_log_file.flush()
            self._parse_line(line)
            # Keep a rolling buffer for stall/failure diagnosis.
            if self.recent_lines.full():
                try:
                    self.recent_lines.get_nowait()
                except queue.Empty:
                    pass
            self.recent_lines.put(line)

    def _parse_line(self, line: str) -> None:
        m = START_RE.search(line)
        if m:
            idx = int(m.group(1))
            self.events[idx] = Event(
                index=idx,
                start_ms=int(m.group(2)),
                complete_ms=None,
                complete_dt_ms=None,
                complete_success=None,
                resumed_ms=None,
                resumed_dt_ms=None,
                wait_before_next_s=None,
            )
            return
        m = COMPLETE_RE.search(line)
        if m:
            idx = int(m.group(1))
            ev = self.events.get(idx)
            if ev is None:
                ev = Event(index=idx, start_ms=0)
                self.events[idx] = ev
            ev.complete_ms = int(m.group(2))
            ev.complete_dt_ms = int(m.group(3))
            ev.complete_success = m.group(4) == "true"
            return
        m = RESUMED_RE.search(line)
        if m:
            idx = int(m.group(1))
            ev = self.events.get(idx)
            if ev is None:
                ev = Event(index=idx, start_ms=0)
                self.events[idx] = ev
            ev.resumed_ms = int(m.group(2))
            ev.resumed_dt_ms = int(m.group(3))

    def get_event(self, index: int) -> Optional[Event]:
        return self.events.get(index)

    def recent_log(self) -> str:
        return "\n".join(list(self.recent_lines.queue))


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


def wait_for_event(reader: LogcatReader, index: int, field: str, timeout: float = 30.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        ev = reader.get_event(index)
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
# Require a non-zero FPS. "FPS: 0" is a common symptom of the Android USB host
# stack stall where the app believes the camera is open but no frames arrive.
HEALTHY_FPS_RE = re.compile(r"FPS: ([1-9]\d*)")
HEALTHY_RE = re.compile(r"Camera health check OK|AprilTag cycle:")

# CDC command failure emitted by DualCameraActivity.doSingleCapture().
CDC_CMD_FAIL_RE = re.compile(r"Failed to send capture command \(bulkTransfer returned -?\d+\)")


def is_camera_stalled(log: str) -> bool:
    """Return True if the app reports no camera / CDC available."""
    return bool(STALL_RE.search(log))


def is_camera_healthy(log: str) -> bool:
    """Return True if the app is receiving preview frames.

    A reported FPS of 0 means the SurfaceView callback is running but no UVC
    frames have been delivered since the camera was (re-)opened. That state is
    not healthy and will not self-recover without a stronger USB reset.
    """
    fps_match = HEALTHY_FPS_RE.search(log)
    fps_healthy = fps_match is not None and int(fps_match.group(1)) > 0
    return fps_healthy or bool(HEALTHY_RE.search(log))


def has_cdc_command_failure(log: str) -> bool:
    """Return True if a CDC OUT bulkTransfer failed (separate from preview health)."""
    return bool(CDC_CMD_FAIL_RE.search(log))


def usb_host_state() -> str:
    """Return the relevant lines of 'dumpsys usb' for diagnosis."""
    try:
        out = adb(["shell", "dumpsys", "usb"], check=False)
        # Keep only the handler state block; the full dump is huge.
        start = out.find("handler={")
        end = out.find("}", start)
        if start >= 0 and end >= 0:
            return out[start:end + 1]
    except Exception as e:
        return f"<error: {e}>"
    return ""


def recover_usb_host(level: int = 1) -> None:
    """Reset the Android USB host port via ADB shell (no root required).

    level 1: soft reset via svc usb resetUsbPort.
    level 2: stronger recovery by toggling USB data signals, which power-cycles
             the host PHY and forces full re-enumeration of the attached device.
    """
    print(f"  [recover] USB host state before: {usb_host_state()}")
    if level == 1:
        print("  [recover] Resetting Android USB host port (soft)...")
        try:
            adb(["shell", "svc", "usb", "resetUsbPort"], check=False)
        except Exception as e:
            print(f"  [recover] resetUsbPort failed: {e}")
        time.sleep(4.0)
    else:
        print("  [recover] Toggling Android USB data signals (hard)...")
        try:
            adb(["shell", "svc", "usb", "enableUsbDataSignal", "false"], check=False)
            time.sleep(2.0)
            adb(["shell", "svc", "usb", "enableUsbDataSignal", "true"], check=False)
        except Exception as e:
            print(f"  [recover] enableUsbDataSignal toggle failed: {e}")
        # Full re-enumeration after a PHY power-cycle can take 5-7 s.
        time.sleep(7.0)


def wait_for_camera_healthy(reader: LogcatReader, timeout: float = 60.0) -> bool:
    """Poll logcat until the camera is streaming, or timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if is_camera_healthy(reader.recent_log()):
            return True
        time.sleep(0.5)
    return False


def ensure_camera_healthy(reader: LogcatReader, recovery_attempts: int = 3) -> bool:
    """Ensure the camera is streaming, running progressively stronger recovery."""
    if is_camera_healthy(reader.recent_log()):
        return True

    for attempt in range(1, recovery_attempts + 1):
        print(f"  [recover] Camera not healthy (attempt {attempt}/{recovery_attempts})")
        # Escalate: soft reset on first try, hard PHY toggle on subsequent tries.
        recover_usb_host(level=1 if attempt == 1 else 2)
        if wait_for_camera_healthy(reader, timeout=60.0):
            print("  [recover] Camera healthy again")
            return True

    return False


def recover_after_failed_capture(reader: LogcatReader) -> bool:
    """Recover after a capture failure and wait for the stream to return.

    The preview can remain healthy while the CDC OUT endpoint becomes stale,
    so we always run a host-port reset after a failed capture and then wait for
    both preview and CDC to come back.
    """
    print("  [recover] Capture failed; resetting USB host port...")
    recover_usb_host(level=1)
    if wait_for_camera_healthy(reader, timeout=60.0):
        print("  [recover] Camera/CDC path recovered")
        return True
    # If a soft reset was not enough, escalate to a PHY toggle once.
    print("  [recover] Soft reset insufficient; escalating to PHY toggle...")
    recover_usb_host(level=2)
    if wait_for_camera_healthy(reader, timeout=60.0):
        print("  [recover] Camera/CDC path recovered after PHY toggle")
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

    reader = LogcatReader(full_log_path=out_dir / "full_logcat.log")
    reader.start()

    print("Starting app...")
    adb(["shell", "am", "start", "-S", "-n", ACTIVITY])
    time.sleep(3.0)

    print(f"Running {args.count} simulated captures...")

    print("Ensuring camera is healthy before starting...")
    if not ensure_camera_healthy(reader):
        print("ERROR: Camera did not become healthy; aborting.")
        reader.stop()
        return 1

    for i in range(args.count):
        # Before each tap, make sure both preview and CDC are usable.  The CDC
        # command path can die while the MJPEG preview keeps running.
        if i > 0:
            if not ensure_camera_healthy(reader) and not recover_after_failed_capture(reader):
                print(f"ERROR: Camera/CDC did not recover before tap {i}; aborting.")
                reader.stop()
                return 1

        wait_s = random.uniform(args.wait_min, args.wait_max)
        if i > 0:
            prev = reader.get_event(i - 1)
            if prev and prev.resumed_ms:
                # Record the wait interval we used for event i (time from previous resume to this tap).
                prev.wait_before_next_s = wait_s
            print(f"  waiting {wait_s:.2f}s after resume before tap {i}...")
            time.sleep(wait_s)

        print(f"Tap {i}: sending capture intent...")
        send_capture_intent(i)

        print(f"Tap {i}: waiting for complete...")
        if not wait_for_event(reader, i, "complete_ms", timeout=60.0):
            print(f"  WARNING: capture {i} did not complete in time")
            # Keep going; we'll report missing data at the end.

        print(f"Tap {i}: waiting for stream resume...")
        if not wait_for_event(reader, i, "resumed_ms", timeout=60.0):
            print(f"  WARNING: capture {i} stream did not resume in time")

        ev = reader.get_event(i)
        if ev:
            print(f"  complete={ev.complete_dt_ms}ms resume={ev.resumed_dt_ms}ms success={ev.complete_success}")

        # A failed capture often leaves the CDC OUT endpoint stale even though
        # the MJPEG preview continues.  Reset the Android USB host port and wait
        # for the camera/CDC path to come back before the next tap.
        if i < args.count - 1 and ev and not ev.complete_success:
            if not recover_after_failed_capture(reader):
                print(f"ERROR: CDC path did not recover after failed capture {i}; aborting.")
                reader.stop()
                return 1

    reader.stop()

    complete_times = [e.complete_dt_ms for e in reader.events.values() if e.complete_dt_ms is not None]
    resume_times = [e.resumed_dt_ms for e in reader.events.values() if e.resumed_dt_ms is not None]
    wait_intervals = [e.wait_before_next_s for e in reader.events.values() if e.wait_before_next_s is not None]

    print("\nResults:")
    print(f"  Total events: {args.count}")
    print(f"  Completed:    {len(complete_times)} / {args.count}")
    print(f"  Resumed:      {len(resume_times)} / {args.count}")
    print(f"  Success:      {sum(1 for e in reader.events.values() if e.complete_success)} / {args.count}")

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
        for e in sorted(reader.events.values(), key=lambda x: x.index):
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
