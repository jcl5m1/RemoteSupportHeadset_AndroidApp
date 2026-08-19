#!/usr/bin/env python3
"""
Simulate the user-facing capture/record -> gallery -> return lifecycle.

The script launches the app, then for each iteration:
  1. Sends a capture or record intent.
  2. Waits for the app to report completion (SIMCAP/SIMREC complete).
  3. Waits briefly so the gallery (Google Photos) opens.
  4. Brings the RemoteSupportHeadset activity back to the foreground.
  5. Waits for the live UVC preview to resume (FPS > 0).

This exercises the lifecycle transition that was failing: taking a photo/video,
viewing it in Google Photos, and returning to live view.

Usage:
    python3 scripts/simulate_gallery_return_lifecycle.py \
        --count 20 --mode capture --output-dir /tmp/simgallery

Modes:
    capture  - still-image capture (default)
    record   - short video recording (opens gallery automatically)
    both     - alternate capture and record iterations
"""

import argparse
import os
import queue
import re
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

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

# Log patterns for capture and record lifecycle events.
SIMCAP_COMPLETE_RE = re.compile(
    r"SIMCAP complete i=(\d+) t=(\d+) dt_complete=(\d+)ms success=(true|false)"
)
SIMREC_COMPLETE_RE = re.compile(
    r"SIMREC complete i=(\d+) t=(\d+) dt_complete=(\d+)ms success=(true|false) file=(.*)"
)
FPS_RE = re.compile(r"FPS: ([1-9]\d*)")
STALL_RE = re.compile(
    r"No camera devices initially found|"
    r"Camera health check: no camera open|"
    r"Could not open CDC port|"
    r"Permission request timed out"
)
CDC_CMD_FAIL_RE = re.compile(r"Failed to send capture command \(bulkTransfer returned -?\d+\)")


@dataclass
class IterationResult:
    index: int
    mode: str
    complete_dt_ms: Optional[int] = None
    complete_success: Optional[bool] = None
    return_to_live_ms: Optional[int] = None
    error: Optional[str] = None


class LogcatReader:
    """Stream ADB logcat in a background thread and expose parsed events."""

    def __init__(self, full_log_path: Optional[Path] = None) -> None:
        self.lock = threading.Lock()
        self.complete_events: Dict[str, Dict[int, Dict[str, any]]] = {
            "capture": {},
            "record": {},
        }
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
            if self.recent_lines.full():
                try:
                    self.recent_lines.get_nowait()
                except queue.Empty:
                    pass
            self.recent_lines.put(line)

    def _parse_line(self, line: str) -> None:
        m = SIMCAP_COMPLETE_RE.search(line)
        if m:
            with self.lock:
                self.complete_events["capture"][int(m.group(1))] = {
                    "t": int(m.group(2)),
                    "dt": int(m.group(3)),
                    "success": m.group(4) == "true",
                }
            return
        m = SIMREC_COMPLETE_RE.search(line)
        if m:
            with self.lock:
                self.complete_events["record"][int(m.group(1))] = {
                    "t": int(m.group(2)),
                    "dt": int(m.group(3)),
                    "success": m.group(4) == "true",
                    "file": m.group(5),
                }

    def get_complete(self, mode: str, index: int) -> Optional[Dict[str, any]]:
        with self.lock:
            return self.complete_events[mode].get(index)

    def recent_log(self) -> str:
        return "\n".join(list(self.recent_lines.queue))


def adb(args: List[str], check: bool = True) -> str:
    cmd = ["adb"] + args
    result = subprocess.run(cmd, capture_output=True, text=True, check=check)
    return result.stdout + result.stderr


def start_app() -> None:
    adb(["shell", "am", "start", "-S", "-n", ACTIVITY])


def bring_app_to_foreground() -> None:
    adb(["shell", "am", "start", "-n", ACTIVITY])


def send_capture_intent(index: int) -> None:
    adb([
        "shell", "am", "start",
        "-n", ACTIVITY,
        "--ez", "capture_now", "true",
        "--ei", "simulated_capture_index", str(index),
    ])


def send_record_intent(index: int, duration_ms: int) -> None:
    adb([
        "shell", "am", "start",
        "-n", ACTIVITY,
        "--ez", "record_start", "true",
        "--el", "record_duration_ms", str(duration_ms),
        "--ei", "simulated_record_index", str(index),
    ])


def is_camera_healthy(log: str) -> bool:
    fps_match = FPS_RE.search(log)
    return fps_match is not None and int(fps_match.group(1)) > 0


def is_camera_stalled(log: str) -> bool:
    return bool(STALL_RE.search(log)) or bool(CDC_CMD_FAIL_RE.search(log))


def wait_for_complete(reader: LogcatReader, mode: str, index: int, timeout: float = 60.0) -> Optional[Dict[str, any]]:
    deadline = time.time() + timeout
    while time.time() < deadline:
        ev = reader.get_complete(mode, index)
        if ev is not None:
            return ev
        time.sleep(0.1)
    return None


def wait_for_camera_healthy(reader: LogcatReader, timeout: float = 60.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if is_camera_healthy(reader.recent_log()):
            return True
        time.sleep(0.5)
    return False


def recover_usb_host(level: int = 1) -> None:
    if level == 1:
        print("  [recover] Resetting Android USB host port (soft)...")
        adb(["shell", "svc", "usb", "resetUsbPort"], check=False)
        time.sleep(4.0)
    else:
        print("  [recover] Toggling Android USB data signals (hard)...")
        adb(["shell", "svc", "usb", "enableUsbDataSignal", "false"], check=False)
        time.sleep(2.0)
        adb(["shell", "svc", "usb", "resetUsbPort"], check=False)
        time.sleep(2.0)
        adb(["shell", "svc", "usb", "enableUsbDataSignal", "true"], check=False)
        time.sleep(7.0)


def ensure_camera_healthy(reader: LogcatReader, recovery_attempts: int = 3) -> bool:
    if is_camera_healthy(reader.recent_log()):
        return True
    for attempt in range(1, recovery_attempts + 1):
        print(f"  [recover] Camera not healthy (attempt {attempt}/{recovery_attempts})")
        recover_usb_host(level=1 if attempt == 1 else 2)
        if wait_for_camera_healthy(reader, timeout=60.0):
            print("  [recover] Camera healthy again")
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
    plt.axvline(mean, color="red", linestyle="--", label=f"mean={mean:.1f}")
    plt.legend()
    stats_text = f"mean={mean:.1f}\nmedian={p50:.1f}\np95={p95:.1f}"
    plt.figtext(0.75, 0.65, stats_text, fontsize=10, family="monospace")
    plt.tight_layout()
    plt.savefig(output_path)
    plt.close()
    print(f"  saved {output_path}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Stress test capture/record -> gallery -> return to live view"
    )
    parser.add_argument("--count", type=int, default=20, help="Number of iterations")
    parser.add_argument("--mode", type=str, default="capture", choices=["capture", "record", "both"],
                        help="Type of media to capture each iteration")
    parser.add_argument("--record-duration-ms", type=int, default=2000, help="Video recording duration")
    parser.add_argument("--gallery-wait-s", type=float, default=2.0,
                        help="Time to wait for the gallery to open")
    parser.add_argument("--output-dir", type=str, default="/tmp/simgallery", help="Directory for outputs")
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    reader = LogcatReader(full_log_path=out_dir / "full_logcat.log")
    reader.start()

    print("Starting app...")
    start_app()
    time.sleep(3.0)

    print("Ensuring camera is healthy before starting...")
    if not ensure_camera_healthy(reader):
        print("ERROR: Camera did not become healthy; aborting.")
        reader.stop()
        return 1

    results: List[IterationResult] = []

    for i in range(args.count):
        mode = args.mode
        if mode == "both":
            mode = "capture" if i % 2 == 0 else "record"

        print(f"\nIteration {i + 1}/{args.count}: mode={mode}")
        result = IterationResult(index=i, mode=mode)
        results.append(result)

        if not ensure_camera_healthy(reader):
            print(f"  ERROR: Camera not healthy before iteration {i + 1}")
            result.error = "camera_not_healthy_before"
            if not ensure_camera_healthy(reader, recovery_attempts=3):
                print("  ERROR: Could not recover camera; aborting.")
                break

        t0 = time.time()
        if mode == "capture":
            print(f"  Sending capture intent (index={i})...")
            send_capture_intent(i)
        else:
            print(f"  Sending record intent (index={i}, duration={args.record_duration_ms}ms)...")
            send_record_intent(i, args.record_duration_ms)

        print(f"  Waiting for {mode} complete...")
        ev = wait_for_complete(reader, mode, i, timeout=60.0)
        if ev is None:
            print(f"  ERROR: {mode} did not complete in time")
            result.error = f"{mode}_not_complete"
            ensure_camera_healthy(reader, recovery_attempts=3)
            continue

        result.complete_dt_ms = ev["dt"]
        result.complete_success = ev["success"]
        print(f"  {mode} complete: dt={ev['dt']}ms success={ev['success']}")

        if not ev["success"]:
            result.error = f"{mode}_failed"
            print(f"  ERROR: {mode} reported failure")
            ensure_camera_healthy(reader, recovery_attempts=3)
            continue

        print(f"  Waiting {args.gallery_wait_s}s for gallery to open...")
        time.sleep(args.gallery_wait_s)

        print("  Bringing RemoteSupportHeadset back to foreground...")
        t_return = time.time()
        bring_app_to_foreground()

        print("  Waiting for live preview to resume (FPS > 0)...")
        if wait_for_camera_healthy(reader, timeout=60.0):
            result.return_to_live_ms = int((time.time() - t_return) * 1000)
            print(f"  Live preview resumed: return_to_live={result.return_to_live_ms}ms")
        else:
            result.error = "live_preview_not_resumed"
            print("  ERROR: Live preview did not resume after returning")
            if not ensure_camera_healthy(reader, recovery_attempts=3):
                print("  ERROR: Could not recover camera; aborting.")
                break

    reader.stop()

    success_count = sum(1 for r in results if r.error is None)
    fail_count = len(results) - success_count

    print(f"\nResults: {success_count}/{len(results)} successful, {fail_count} failures")
    for r in results:
        status = "OK" if r.error is None else f"FAIL:{r.error}"
        print(f"  iter={r.index + 1} mode={r.mode} complete_dt={r.complete_dt_ms}ms "
              f"success={r.complete_success} return_to_live={r.return_to_live_ms}ms [{status}]")

    complete_times = [r.complete_dt_ms for r in results if r.complete_dt_ms is not None]
    return_times = [r.return_to_live_ms for r in results if r.return_to_live_ms is not None]

    if complete_times:
        print(f"\n  complete dt: mean={np.mean(complete_times):.1f}ms "
              f"median={np.percentile(complete_times, 50):.1f}ms "
              f"p95={np.percentile(complete_times, 95):.1f}ms")
    if return_times:
        print(f"  return-to-live: mean={np.mean(return_times):.1f}ms "
              f"median={np.percentile(return_times, 50):.1f}ms "
              f"p95={np.percentile(return_times, 95):.1f}ms")

    print("\nGenerating histograms...")
    if complete_times:
        histogram(complete_times, "Capture/Record Complete Time", "ms", out_dir / "complete_time.png")
    if return_times:
        histogram(return_times, "Return to Live Preview", "ms", out_dir / "return_to_live.png")

    csv_path = out_dir / "results.csv"
    with open(csv_path, "w") as f:
        f.write("index,mode,complete_dt_ms,complete_success,return_to_live_ms,error\n")
        for r in results:
            f.write(
                f"{r.index},{r.mode},"
                f"{r.complete_dt_ms if r.complete_dt_ms is not None else ''},"
                f"{r.complete_success if r.complete_success is not None else ''},"
                f"{r.return_to_live_ms if r.return_to_live_ms is not None else ''},"
                f"{r.error if r.error is not None else ''}\n"
            )
    print(f"  saved {csv_path}")

    return 0 if fail_count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
