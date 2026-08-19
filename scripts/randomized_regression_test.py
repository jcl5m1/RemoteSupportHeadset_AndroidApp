#!/usr/bin/env python3
"""
Randomized regression test for the RemoteSupportHeadset Android app.

This harness exercises the app's intent-controllable surface in a random order
for a configurable number of actions/state transitions. It is designed to run
unattended as part of a standard regression suite:

  - Still capture
  - Video record start/stop
  - Open gallery / return to live view
  - AprilTag detection toggle
  - YOLO person-detection toggle
  - Diagnostics panel toggle
  - Enter/exit video test source
  - Anti-banding analysis (short run with forced dismissal)

After each action the harness verifies app health (live preview FPS or a
successful mode transition), logs errors, escalates USB recovery automatically,
and writes a structured JSON/CSV report.

Usage:
    python3 scripts/randomized_regression_test.py \
        --count 100 \
        --output-dir /tmp/rrtest \
        --seed 42

Exit codes:
    0 - all requested actions completed without unrecoverable failures
    1 - one or more actions failed or the camera never became healthy
"""

import argparse
import json
import os
import queue
import random
import re
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

# Force unbuffered output so CI logs stay live.
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
ALBUM_NAME = "RemoteSupportHeadset"
DEFAULT_VIDEO_TEST_PATH = "/sdcard/Android/data/com.example.remotesupportheadset/files/TestFrames"


class State(Enum):
    LIVE = "LIVE"
    RECORDING = "RECORDING"
    GALLERY = "GALLERY"
    VIDEO_TEST = "VIDEO_TEST"


# Log patterns -----------------------------------------------------------------

RRTEST_RE = re.compile(r"RRTEST action=(\S+) (.*)")

# Capture/record lifecycle events (already emitted by DualCameraActivity).
SIMCAP_START_RE = re.compile(r"SIMCAP start i=(\d+) t=(\d+)")
SIMCAP_COMPLETE_RE = re.compile(
    r"SIMCAP complete i=(\d+) t=(\d+) dt_complete=(\d+)ms success=(true|false)"
)
SIMCAP_RESUMED_RE = re.compile(r"SIMCAP resumed i=(\d+) t=(\d+) dt_resume=(\d+)ms")

SIMREC_START_RE = re.compile(r"SIMREC start i=(\d+) t=(\d+)")
SIMREC_COMPLETE_RE = re.compile(
    r"SIMREC complete i=(\d+) t=(\d+) dt_complete=(\d+)ms success=(true|false) file=(.*)"
)
SIMREC_RESUMED_RE = re.compile(r"SIMREC resumed i=(\d+) t=(\d+) dt_resume=(\d+)ms")

# Health signals.
FPS_RE = re.compile(r"FPS: ([1-9]\d*)")
CAMERA_HEALTH_OK_RE = re.compile(r"Camera health check OK")
VIDEO_TEST_ENTERED_RE = re.compile(r"RRTEST action=ENTER_VIDEO_TEST result=success")
VIDEO_TEST_EXITED_RE = re.compile(r"RRTEST action=EXIT_VIDEO_TEST result=success")

# Failure / stall signals.
STALL_RE = re.compile(
    r"No camera devices initially found|"
    r"Camera health check: no camera open|"
    r"Could not open CDC port|"
    r"Permission request timed out|"
    r"Failed to send capture command \(bulkTransfer returned -?\d+\)"
)
CRASH_RE = re.compile(r"AndroidRuntime|FATAL EXCEPTION|Process .* has died")
ANR_RE = re.compile(r"ANR in |Reason: .*ANR")


@dataclass
class ActionResult:
    index: int
    action: str
    state_before: str
    state_after: str
    success: bool
    duration_ms: Optional[int] = None
    complete_dt_ms: Optional[int] = None
    resume_dt_ms: Optional[int] = None
    error: Optional[str] = None
    recovery_level: int = 0


@dataclass
class SessionReport:
    seed: int
    requested_count: int
    completed_actions: List[ActionResult] = field(default_factory=list)
    errors: List[Dict] = field(default_factory=list)
    start_time: float = field(default_factory=time.time)
    end_time: Optional[float] = None

    @property
    def success_count(self) -> int:
        return sum(1 for r in self.completed_actions if r.success)

    @property
    def fail_count(self) -> int:
        return len(self.completed_actions) - self.success_count

    def to_dict(self) -> Dict:
        return {
            "seed": self.seed,
            "requested_count": self.requested_count,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "duration_seconds": (self.end_time or time.time()) - self.start_time,
            "success_count": self.success_count,
            "fail_count": self.fail_count,
            "actions": [
                {
                    "index": r.index,
                    "action": r.action,
                    "state_before": r.state_before,
                    "state_after": r.state_after,
                    "success": r.success,
                    "duration_ms": r.duration_ms,
                    "complete_dt_ms": r.complete_dt_ms,
                    "resume_dt_ms": r.resume_dt_ms,
                    "error": r.error,
                    "recovery_level": r.recovery_level,
                }
                for r in self.completed_actions
            ],
            "errors": self.errors,
        }


class LogcatReader:
    """Stream ADB logcat in a background thread and expose parsed events."""

    def __init__(self, full_log_path: Optional[Path] = None) -> None:
        self.lock = threading.Lock()
        self.rrtest_events: List[Dict] = []
        self.simcap_events: Dict[int, Dict] = {}
        self.simrec_events: Dict[int, Dict] = {}
        self.recent_lines: queue.Queue[str] = queue.Queue(maxsize=20000)
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
        # Capture DualCameraActivity plus VideoFrameSource and the AndroidRuntime/ANR tags.
        cmd = [
            "adb", "logcat", "-v", "threadtime",
            "-s", f"{TAG}:V", "VideoFrameSource:V", "AndroidRuntime:E", "ActivityManager:W",
        ]
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
        m = RRTEST_RE.search(line)
        if m:
            with self.lock:
                self.rrtest_events.append({"action": m.group(1), "rest": m.group(2), "line": line})
            return

        m = SIMCAP_START_RE.search(line)
        if m:
            idx = int(m.group(1))
            with self.lock:
                self.simcap_events.setdefault(idx, {})["start_ms"] = int(m.group(2))
            return
        m = SIMCAP_COMPLETE_RE.search(line)
        if m:
            idx = int(m.group(1))
            with self.lock:
                ev = self.simcap_events.setdefault(idx, {})
                ev["complete_ms"] = int(m.group(2))
                ev["complete_dt_ms"] = int(m.group(3))
                ev["success"] = m.group(4) == "true"
            return
        m = SIMCAP_RESUMED_RE.search(line)
        if m:
            idx = int(m.group(1))
            with self.lock:
                ev = self.simcap_events.setdefault(idx, {})
                ev["resumed_ms"] = int(m.group(2))
                ev["resume_dt_ms"] = int(m.group(3))
            return

        m = SIMREC_START_RE.search(line)
        if m:
            idx = int(m.group(1))
            with self.lock:
                self.simrec_events.setdefault(idx, {})["start_ms"] = int(m.group(2))
            return
        m = SIMREC_COMPLETE_RE.search(line)
        if m:
            idx = int(m.group(1))
            with self.lock:
                ev = self.simrec_events.setdefault(idx, {})
                ev["complete_ms"] = int(m.group(2))
                ev["complete_dt_ms"] = int(m.group(3))
                ev["success"] = m.group(4) == "true"
                ev["file"] = m.group(5)
            return
        m = SIMREC_RESUMED_RE.search(line)
        if m:
            idx = int(m.group(1))
            with self.lock:
                ev = self.simrec_events.setdefault(idx, {})
                ev["resumed_ms"] = int(m.group(2))
                ev["resume_dt_ms"] = int(m.group(3))
            return

    def get_simcap(self, index: int) -> Optional[Dict]:
        with self.lock:
            return self.simcap_events.get(index)

    def get_simrec(self, index: int) -> Optional[Dict]:
        with self.lock:
            return self.simrec_events.get(index)

    def rrtest_actions(self) -> List[str]:
        with self.lock:
            return [e["action"] for e in self.rrtest_events]

    def wait_for_rrtest(self, action: str, timeout: float = 10.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                if any(e["action"] == action for e in self.rrtest_events):
                    return True
            time.sleep(0.1)
        return False

    def recent_log(self) -> str:
        return "\n".join(list(self.recent_lines.queue))

    def has_crash_or_anr(self, log: str) -> bool:
        return bool(CRASH_RE.search(log)) or bool(ANR_RE.search(log))


def adb(args: List[str], check: bool = True, timeout: float = 60.0) -> str:
    cmd = ["adb"] + args
    result = subprocess.run(cmd, capture_output=True, text=True, check=check, timeout=timeout)
    return result.stdout + result.stderr


def start_app(cold: bool = True) -> None:
    if cold:
        adb(["shell", "am", "start", "-S", "-n", ACTIVITY])
    else:
        adb(["shell", "am", "start", "-n", ACTIVITY])


def bring_app_to_foreground() -> None:
    adb(["shell", "am", "start", "-n", ACTIVITY])


def send_intent_extras(extras: List[str]) -> None:
    adb(["shell", "am", "start", "-n", ACTIVITY] + extras)


def send_capture_intent(index: int) -> None:
    send_intent_extras(["--ez", "capture_now", "true", "--ei", "simulated_capture_index", str(index)])


def send_record_intent(index: int, duration_ms: int) -> None:
    send_intent_extras([
        "--ez", "record_start", "true",
        "--ez", "record_no_gallery", "true",
        "--el", "record_duration_ms", str(duration_ms),
        "--ei", "simulated_record_index", str(index),
    ])


def send_gallery_intent() -> None:
    send_intent_extras(["--ez", "open_gallery", "true"])


def send_toggle_apriltag(enabled: bool) -> None:
    send_intent_extras(["--ez", "apriltag_enabled", "true" if enabled else "false"])


def send_toggle_yolo(enabled: bool) -> None:
    send_intent_extras(["--ez", "yolo_enabled", "true" if enabled else "false"])


def send_toggle_diagnostics(visible: bool) -> None:
    send_intent_extras(["--ez", "diagnostics", "true" if visible else "false"])


def send_enter_video_test(path: str) -> None:
    send_intent_extras(["--es", "video_test_path", path])


def send_exit_video_test() -> None:
    send_intent_extras(["--ez", "exit_video_test", "true"])


def send_anti_banding() -> None:
    send_intent_extras(["--ez", "anti_band_now", "true"])


def press_back() -> None:
    adb(["shell", "input", "keyevent", "KEYCODE_BACK"], check=False)


def is_camera_healthy(log: str) -> bool:
    fps_match = FPS_RE.search(log)
    fps_healthy = fps_match is not None and int(fps_match.group(1)) > 0
    return fps_healthy or bool(CAMERA_HEALTH_OK_RE.search(log))


def is_stalled(log: str) -> bool:
    return bool(STALL_RE.search(log))


def usb_host_state() -> Dict[str, Optional[str]]:
    out = adb(["shell", "dumpsys", "usb"], check=False)
    state: Dict[str, Optional[str]] = {"host_connected": None, "kernel_state": None}
    for line in out.splitlines():
        m = re.search(r"host_connected=(true|false)", line)
        if m:
            state["host_connected"] = m.group(1)
        m = re.search(r"kernel_state=(\S+)", line)
        if m:
            state["kernel_state"] = m.group(1)
    return state


def reset_usb_host(level: int = 1) -> None:
    if level >= 1:
        print("    [recovery] resetUsbPort")
        adb(["shell", "svc", "usb", "resetUsbPort"], check=False)
        time.sleep(4.0)
    if level >= 2:
        print("    [recovery] toggle USB data signal")
        adb(["shell", "svc", "usb", "enableUsbDataSignal", "false"], check=False)
        time.sleep(2.0)
        adb(["shell", "svc", "usb", "enableUsbDataSignal", "true"], check=False)
        time.sleep(7.0)
    if level >= 3:
        print("    [recovery] force-stop app + PHY toggle")
        adb(["shell", "am", "force-stop", PACKAGE], check=False)
        time.sleep(1.0)
        adb(["shell", "svc", "usb", "enableUsbDataSignal", "false"], check=False)
        time.sleep(2.0)
        adb(["shell", "svc", "usb", "enableUsbDataSignal", "true"], check=False)
        time.sleep(7.0)


def wait_for_camera_healthy(reader: LogcatReader, timeout: float = 60.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if is_camera_healthy(reader.recent_log()):
            return True
        time.sleep(0.5)
    return False


def wait_for_stable_camera_health(reader: LogcatReader, stable_s: float = 2.0, timeout: float = 60.0) -> bool:
    """Wait until the camera reports FPS > 0 for [stable_s] consecutive seconds."""
    deadline = time.time() + timeout
    first_healthy_time: Optional[float] = None
    while time.time() < deadline:
        if is_camera_healthy(reader.recent_log()):
            now = time.time()
            if first_healthy_time is None:
                first_healthy_time = now
            elif now - first_healthy_time >= stable_s:
                return True
        else:
            first_healthy_time = None
        time.sleep(0.25)
    return False


def ensure_camera_healthy(reader: LogcatReader, max_level: int = 3) -> Tuple[bool, int]:
    """Return (healthy, highest_recovery_level_used)."""
    if is_camera_healthy(reader.recent_log()):
        return True, 0
    highest = 0
    for level in range(1, max_level + 1):
        print(f"    [recovery] Camera not healthy, escalating to level {level}/{max_level}")
        highest = level
        reset_usb_host(level=level)
        if wait_for_camera_healthy(reader, timeout=60.0):
            print("    [recovery] Camera healthy again")
            return True, highest
    return False, highest


def ensure_app_running() -> None:
    out = adb(["shell", "dumpsys", "activity", "activities"], check=False)
    if PACKAGE not in out:
        print("    [recovery] App not running; restarting")
        start_app(cold=True)
        time.sleep(3.0)


class RandomizedRegressionTest:
    def __init__(
        self,
        reader: LogcatReader,
        output_dir: Path,
        seed: int,
        count: int,
        video_test_path: str,
    ) -> None:
        self.reader = reader
        self.output_dir = output_dir
        self.seed = seed
        self.count = count
        self.video_test_path = video_test_path
        self.rng = random.Random(seed)
        self.state = State.LIVE
        self.has_media = False
        self.apriltag_enabled = False
        self.yolo_enabled = False
        self.diagnostics_visible = False
        self.capture_index = 0
        self.record_index = 0
        self.report = SessionReport(seed=seed, requested_count=count)

    def log_error(self, action: str, message: str, details: Optional[str] = None) -> None:
        entry = {"time": time.time(), "action": action, "message": message}
        if details:
            entry["details"] = details
        self.report.errors.append(entry)
        print(f"    [error] {action}: {message}")

    def choose_action(self) -> str:
        """Choose a random valid action given the current state."""
        weights: List[Tuple[str, float]] = []

        if self.state == State.LIVE:
            weights = [
                ("CAPTURE", 25.0),
                ("RECORD_START", 12.0),
                ("OPEN_GALLERY", 8.0 if self.has_media else 0.0),
                ("TOGGLE_APRILTAG", 10.0),
                ("TOGGLE_YOLO", 10.0),
                ("TOGGLE_DIAGNOSTICS", 5.0),
                ("ENTER_VIDEO_TEST", 5.0),
                ("ANTI_BANDING", 2.0),
            ]
        elif self.state == State.RECORDING:
            weights = [("RECORD_STOP", 1.0)]
        elif self.state == State.GALLERY:
            weights = [("RETURN_APP", 1.0)]
        elif self.state == State.VIDEO_TEST:
            weights = [("EXIT_VIDEO_TEST", 1.0)]

        actions, w = zip(*[(a, w) for a, w in weights if w > 0])
        return self.rng.choices(actions, weights=w, k=1)[0]

    def run_action(self, action: str, index: int) -> ActionResult:
        state_before = self.state.value
        t0 = time.time()
        result = ActionResult(
            index=index,
            action=action,
            state_before=state_before,
            state_after=state_before,
            success=False,
        )

        try:
            handler = getattr(self, f"action_{action.lower()}")
            handler(result)
        except Exception as e:
            result.error = f"exception: {e}"
            self.log_error(action, str(e))

        result.duration_ms = int((time.time() - t0) * 1000)
        result.state_after = self.state.value
        return result

    # ---- Individual action handlers -----------------------------------------

    def _ensure_live_health(self, result: ActionResult) -> bool:
        healthy, level = ensure_camera_healthy(self.reader)
        result.recovery_level = max(result.recovery_level, level)
        if not healthy:
            result.error = "camera_not_healthy"
            self.log_error(result.action, "Camera did not become healthy after recovery")
        return healthy

    def action_capture(self, result: ActionResult) -> None:
        if not self._ensure_live_health(result):
            return

        idx = self.capture_index
        self.capture_index += 1
        print(f"    Sending capture intent (index={idx})...")
        send_capture_intent(idx)

        if not self._wait_simcap_complete(idx, timeout=60.0):
            result.error = "capture_timeout"
            self.log_error("CAPTURE", f"SIMCAP complete not seen for index {idx}")
            return

        ev = self.reader.get_simcap(idx)
        if ev and ev.get("success"):
            self.has_media = True
            result.complete_dt_ms = ev.get("complete_dt_ms")
        else:
            result.error = "capture_failed"
            self.log_error("CAPTURE", f"SIMCAP reported failure: {ev}")
            return

        # Also wait for stream resume before declaring success.
        if not self._wait_simcap_resumed(idx, timeout=60.0):
            result.error = "resume_timeout"
            self.log_error("CAPTURE", f"SIMCAP resume not seen for index {idx}")
            return

        ev = self.reader.get_simcap(idx)
        result.resume_dt_ms = ev.get("resume_dt_ms") if ev else None
        result.success = True

    def _wait_simcap_complete(self, idx: int, timeout: float) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            ev = self.reader.get_simcap(idx)
            if ev and "complete_ms" in ev:
                return True
            time.sleep(0.1)
        return False

    def _wait_simcap_resumed(self, idx: int, timeout: float) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            ev = self.reader.get_simcap(idx)
            if ev and "resumed_ms" in ev:
                return True
            time.sleep(0.1)
        return False

    def action_record_start(self, result: ActionResult) -> None:
        if not self._ensure_live_health(result):
            return

        idx = self.record_index
        self.record_index += 1
        duration_ms = self.rng.randint(1500, 3500)
        print(f"    Sending record intent (index={idx}, duration={duration_ms}ms)...")
        send_record_intent(idx, duration_ms)
        self.state = State.RECORDING

        # Wait for the auto-stop to complete and the stream to resume.
        timeout = (duration_ms / 1000.0) + 20.0
        if not self._wait_simrec_complete(idx, timeout=timeout):
            result.error = "record_timeout"
            self.log_error("RECORD_START", f"SIMREC complete not seen for index {idx}")
            self.state = State.LIVE
            return

        ev = self.reader.get_simrec(idx)
        if ev and ev.get("success"):
            self.has_media = True
            result.complete_dt_ms = ev.get("complete_dt_ms")
        else:
            result.error = "record_failed"
            self.log_error("RECORD_START", f"SIMREC reported failure: {ev}")
            self.state = State.LIVE
            return

        if not self._wait_simrec_resumed(idx, timeout=30.0):
            result.error = "resume_timeout"
            self.log_error("RECORD_START", f"SIMREC resume not seen for index {idx}")
            self.state = State.LIVE
            return

        ev = self.reader.get_simrec(idx)
        result.resume_dt_ms = ev.get("resume_dt_ms") if ev else None
        self.state = State.LIVE
        result.success = True

    def _wait_simrec_complete(self, idx: int, timeout: float) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            ev = self.reader.get_simrec(idx)
            if ev and "complete_ms" in ev:
                return True
            time.sleep(0.1)
        return False

    def _wait_simrec_resumed(self, idx: int, timeout: float) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            ev = self.reader.get_simrec(idx)
            if ev and "resumed_ms" in ev:
                return True
            time.sleep(0.1)
        return False

    def action_record_stop(self, result: ActionResult) -> None:
        print("    Sending record stop intent...")
        adb(["shell", "am", "start", "-n", ACTIVITY, "--ez", "record_stop", "true"])
        # Give the stop a moment to propagate; the next health check will confirm live state.
        time.sleep(1.0)
        if not self._ensure_live_health(result):
            return
        self.state = State.LIVE
        result.success = True

    def action_open_gallery(self, result: ActionResult) -> None:
        if not self.has_media:
            result.error = "no_media"
            return
        print("    Opening gallery...")
        send_gallery_intent()
        # Wait briefly for the gallery activity to come up.
        time.sleep(2.0)
        self.state = State.GALLERY
        result.success = True

    def action_return_app(self, result: ActionResult) -> None:
        print("    Returning to RemoteSupportHeadset...")
        bring_app_to_foreground()
        healthy, level = self._ensure_stable_live_health(result)
        if not healthy:
            result.error = "camera_not_healthy_after_return"
            self.log_error("RETURN_APP", "Camera not healthy after returning from gallery")
            self.state = State.LIVE  # Best-effort.
            return
        self.state = State.LIVE
        result.success = True

    def action_toggle_apriltag(self, result: ActionResult) -> None:
        self.apriltag_enabled = not self.apriltag_enabled
        print(f"    Toggling AprilTag enabled={self.apriltag_enabled}...")
        send_toggle_apriltag(self.apriltag_enabled)
        if self.reader.wait_for_rrtest("TOGGLE_APRILTAG", timeout=10.0):
            result.success = True
        else:
            result.error = "rrtest_timeout"

    def action_toggle_yolo(self, result: ActionResult) -> None:
        self.yolo_enabled = not self.yolo_enabled
        print(f"    Toggling YOLO enabled={self.yolo_enabled}...")
        send_toggle_yolo(self.yolo_enabled)
        if self.reader.wait_for_rrtest("TOGGLE_YOLO", timeout=10.0):
            result.success = True
        else:
            result.error = "rrtest_timeout"

    def action_toggle_diagnostics(self, result: ActionResult) -> None:
        self.diagnostics_visible = not self.diagnostics_visible
        print(f"    Toggling diagnostics visible={self.diagnostics_visible}...")
        send_toggle_diagnostics(self.diagnostics_visible)
        if self.reader.wait_for_rrtest("TOGGLE_DIAGNOSTICS", timeout=10.0):
            result.success = True
        else:
            result.error = "rrtest_timeout"

    def action_enter_video_test(self, result: ActionResult) -> None:
        if not self._ensure_live_health(result):
            return
        print(f"    Entering video test mode (path={self.video_test_path})...")
        send_enter_video_test(self.video_test_path)
        if self.reader.wait_for_rrtest("ENTER_VIDEO_TEST", timeout=10.0):
            self.state = State.VIDEO_TEST
            result.success = True
        else:
            result.error = "enter_video_test_timeout"
            self.log_error("ENTER_VIDEO_TEST", "Did not see ENTER_VIDEO_TEST success log")

    def action_exit_video_test(self, result: ActionResult) -> None:
        print("    Exiting video test mode...")
        send_exit_video_test()
        if self.reader.wait_for_rrtest("EXIT_VIDEO_TEST", timeout=10.0):
            # Wait for the UVC preview to come back and stay healthy.
            healthy, level = self._ensure_stable_live_health(result)
            if healthy:
                self.state = State.LIVE
                result.success = True
            else:
                result.error = "camera_not_healthy_after_exit"
                self.log_error("EXIT_VIDEO_TEST", "Camera not healthy after exiting video test")
        else:
            result.error = "exit_video_test_timeout"
            self.log_error("EXIT_VIDEO_TEST", "Did not see EXIT_VIDEO_TEST success log")

    def _ensure_stable_live_health(self, result: ActionResult) -> Tuple[bool, int]:
        """Like _ensure_live_health but require FPS > 0 for 2s to avoid transient opens."""
        if wait_for_stable_camera_health(self.reader, stable_s=2.0, timeout=60.0):
            return True, 0
        healthy, level = ensure_camera_healthy(self.reader)
        result.recovery_level = max(result.recovery_level, level)
        if healthy and wait_for_stable_camera_health(self.reader, stable_s=2.0, timeout=30.0):
            return True, level
        return False, level

    def action_anti_banding(self, result: ActionResult) -> None:
        if not self._ensure_live_health(result):
            return
        print("    Starting anti-banding analysis...")
        send_anti_banding()
        # Let it run briefly, then dismiss the dialog to avoid blocking the suite.
        time.sleep(5.0)
        press_back()
        # Wait a moment for the dialog to dismiss.
        time.sleep(1.0)
        # Best-effort: consider success if no crash and we can get back to healthy.
        healthy, level = ensure_camera_healthy(self.reader)
        result.recovery_level = max(result.recovery_level, level)
        if healthy:
            result.success = True
        else:
            result.error = "camera_not_healthy_after_anti_banding"
            self.log_error("ANTI_BANDING", "Camera not healthy after anti-banding")

    # ---- Main loop ----------------------------------------------------------

    def run(self) -> int:
        print(f"\nStarting randomized regression test: seed={self.seed}, count={self.count}")
        print(f"Initial state: {self.state.value}\n")

        for i in range(self.count):
            # Detect crashes/ANRs early.
            if self.reader.has_crash_or_anr(self.reader.recent_log()):
                self.log_error("global", "Crash or ANR detected in recent logcat")
                ensure_app_running()
                healthy, level = ensure_camera_healthy(self.reader)
                if not healthy:
                    print("ERROR: Could not recover after crash/ANR; aborting.")
                    break

            action = self.choose_action()
            print(f"[{i + 1}/{self.count}] {action} (state={self.state.value})")

            result = self.run_action(action, i)
            self.report.completed_actions.append(result)

            status = "OK" if result.success else f"FAIL:{result.error}"
            print(f"    -> {status} in {result.duration_ms}ms")

            # Short pause between actions to let the app settle.
            time.sleep(0.5)

            # If a live-camera action failed, try to get back to a known good state.
            if not result.success and self.state in (State.LIVE, State.RECORDING):
                print("    Attempting recovery before next action...")
                healthy, level = ensure_camera_healthy(self.reader)
                result.recovery_level = max(result.recovery_level, level)
                if healthy:
                    self.state = State.LIVE

        self.report.end_time = time.time()
        return 0 if self.report.fail_count == 0 else 1


def histogram(values: List[float], title: str, xlabel: str, output_path: Path) -> None:
    plt.figure(figsize=(8, 5))
    plt.hist(values, bins="auto", edgecolor="black")
    plt.title(title)
    plt.xlabel(xlabel)
    plt.ylabel("Count")
    mean = float(np.mean(values))
    p50 = float(np.percentile(values, 50))
    p95 = float(np.percentile(values, 95))
    stats_text = f"mean={mean:.1f}\nmedian={p50:.1f}\np95={p95:.1f}"
    plt.axvline(mean, color="red", linestyle="--", label=f"mean={mean:.1f}")
    plt.legend()
    plt.figtext(0.75, 0.65, stats_text, fontsize=10, family="monospace")
    plt.tight_layout()
    plt.savefig(output_path)
    plt.close()
    print(f"  saved {output_path}")


def push_test_frames_if_needed(video_test_path: str) -> bool:
    out = adb(["shell", f"test -d {video_test_path} && echo present || echo missing"], check=False).strip()
    if out == "present":
        print(f"Video test frames already present at {video_test_path}")
        return True

    local_dir = Path("scripts/test_video_assets/test_frames")
    if not local_dir.is_dir():
        print(f"Local test frames not found at {local_dir}; skipping video-test actions")
        return False

    print(f"Pushing test frames from {local_dir} to {video_test_path}...")
    adb(["shell", f"mkdir -p {video_test_path}"], check=False)
    adb(["push", str(local_dir) + "/", video_test_path + "/"], timeout=120)
    return True


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Randomized regression test for RemoteSupportHeadset"
    )
    parser.add_argument("--count", type=int, default=100, help="Number of randomized actions")
    parser.add_argument("--seed", type=int, default=None, help="Random seed (default: current time)")
    parser.add_argument("--output-dir", type=str, default="/tmp/rrtest", help="Directory for reports")
    parser.add_argument("--video-test-path", type=str, default=DEFAULT_VIDEO_TEST_PATH,
                        help="On-device directory containing JPEG test frames")
    parser.add_argument("--skip-video-test", action="store_true",
                        help="Do not enter video test source mode")
    args = parser.parse_args()

    seed = args.seed if args.seed is not None else int(time.time())
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    reader = LogcatReader(full_log_path=out_dir / "full_logcat.log")
    reader.start()

    print("Starting app...")
    start_app(cold=True)
    time.sleep(3.0)

    print("Ensuring camera is healthy before starting...")
    healthy, _ = ensure_camera_healthy(reader)
    if not healthy:
        print("ERROR: Camera did not become healthy; aborting.")
        reader.stop()
        return 1

    video_test_path = args.video_test_path
    if args.skip_video_test:
        video_test_path = ""
    else:
        if not push_test_frames_if_needed(video_test_path):
            video_test_path = ""

    test = RandomizedRegressionTest(
        reader=reader,
        output_dir=out_dir,
        seed=seed,
        count=args.count,
        video_test_path=video_test_path,
    )

    exit_code = test.run()
    reader.stop()

    # Save JSON report.
    json_path = out_dir / "report.json"
    with open(json_path, "w") as f:
        json.dump(test.report.to_dict(), f, indent=2)
    print(f"\n  saved {json_path}")

    # Save CSV report.
    csv_path = out_dir / "results.csv"
    with open(csv_path, "w") as f:
        f.write("index,action,state_before,state_after,success,duration_ms,complete_dt_ms,resume_dt_ms,error,recovery_level\n")
        for r in test.report.completed_actions:
            f.write(
                f"{r.index},{r.action},{r.state_before},{r.state_after},"
                f"{r.success},{r.duration_ms or ''},{r.complete_dt_ms or ''},"
                f"{r.resume_dt_ms or ''},{r.error or ''},{r.recovery_level}\n"
            )
    print(f"  saved {csv_path}")

    # Histograms for successful capture/record timings.
    cap_complete = [r.complete_dt_ms for r in test.report.completed_actions
                    if r.action == "CAPTURE" and r.complete_dt_ms is not None]
    cap_resume = [r.resume_dt_ms for r in test.report.completed_actions
                  if r.action == "CAPTURE" and r.resume_dt_ms is not None]
    rec_complete = [r.complete_dt_ms for r in test.report.completed_actions
                    if r.action == "RECORD_START" and r.complete_dt_ms is not None]
    rec_resume = [r.resume_dt_ms for r in test.report.completed_actions
                  if r.action == "RECORD_START" and r.resume_dt_ms is not None]

    print("\nGenerating histograms...")
    if cap_complete:
        histogram(cap_complete, "Capture Complete Time", "ms", out_dir / "capture_complete.png")
    if cap_resume:
        histogram(cap_resume, "Capture Resume Time", "ms", out_dir / "capture_resume.png")
    if rec_complete:
        histogram(rec_complete, "Record Complete Time", "ms", out_dir / "record_complete.png")
    if rec_resume:
        histogram(rec_resume, "Record Resume Time", "ms", out_dir / "record_resume.png")

    # Summary.
    print("\n" + "=" * 60)
    print(f"Randomized regression test complete")
    print(f"  seed:            {seed}")
    print(f"  requested:       {args.count}")
    print(f"  completed:       {len(test.report.completed_actions)}")
    print(f"  success:         {test.report.success_count}")
    print(f"  failures:        {test.report.fail_count}")
    print(f"  duration:        {test.report.end_time - test.report.start_time:.1f}s")
    if test.report.errors:
        print(f"  error log entries: {len(test.report.errors)}")
        for e in test.report.errors[:10]:
            print(f"    - {e['action']}: {e['message']}")
    print("=" * 60)

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
