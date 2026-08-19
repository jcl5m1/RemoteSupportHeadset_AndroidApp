#!/usr/bin/env python3
"""
Autonomous Android USB OTG host recovery.

Run this from the MacBook host when the Android phone's USB host port is
stalled: it provides VBUS power (`host_connected=true`) but the attached
ESP32-P4 never enumerates (`kernel_state=DISCONNECTED`).

The script escalates through the ADB-accessible recovery steps that do not
require root:

  1. Reset the first connected USB host port.
  2. Toggle USB data signaling off/on around a port reset.
  3. Force-stop the camera app so the next launch starts with a clean stack.

After each level it polls `dumpsys usb` and `lsusb` until the device comes
back or a timeout expires.

Usage:
    python3 scripts/reset_android_otg.py [--level {1,2,3}] [--wait N]

Exit codes:
    0 - USB host port recovered and an ESP32 device is enumerated.
    1 - Recovery did not succeed within the timeout.
"""

import argparse
import re
import subprocess
import sys
import time
from typing import Optional

PACKAGE = "com.example.remotesupportheadset"


def adb(args: list[str], check: bool = True, timeout: float = 30.0) -> str:
    cmd = ["adb"] + args
    result = subprocess.run(cmd, capture_output=True, text=True, check=check, timeout=timeout)
    return result.stdout + result.stderr


def usb_state() -> dict:
    """Parse interesting fields from `dumpsys usb`."""
    out = adb(["shell", "dumpsys", "usb"], check=False)
    state = {
        "host_connected": None,
        "kernel_state": None,
        "ports": [],
        "raw": out,
    }
    for line in out.splitlines():
        m = re.search(r"host_connected=(true|false)", line)
        if m:
            state["host_connected"] = m.group(1) == "true"
        m = re.search(r"kernel_state=(\S+)", line)
        if m:
            state["kernel_state"] = m.group(1)
        m = re.search(r"id=(port\d+)", line)
        if m:
            state["ports"].append(m.group(1))
    return state


def lsusb_has_esp32() -> bool:
    out = adb(["shell", "lsusb"], check=False)
    return "303a" in out or "4022" in out or "0012" in out


def is_healthy() -> bool:
    state = usb_state()
    return (
        state["host_connected"] is True
        and state["kernel_state"] == "CONNECTED"
        and lsusb_has_esp32()
    )


def first_port() -> Optional[str]:
    ports = usb_state()["ports"]
    return ports[0] if ports else None


def force_stop_app() -> None:
    adb(["shell", "am", "force-stop", PACKAGE], check=False)


def reset_port(port: Optional[str]) -> None:
    cmd = ["shell", "svc", "usb", "resetUsbPort"]
    if port:
        cmd.append(port)
    adb(cmd, check=False)


def toggle_data_signal() -> None:
    adb(["shell", "svc", "usb", "enableUsbDataSignal", "false"], check=False)
    time.sleep(2.0)
    adb(["shell", "svc", "usb", "resetUsbPort"], check=False)
    time.sleep(2.0)
    adb(["shell", "svc", "usb", "enableUsbDataSignal", "true"], check=False)


def recover(level: int, port: Optional[str]) -> None:
    if level >= 1:
        print("[otg] Level 1: resetting USB host port...")
        reset_port(port)
        time.sleep(4.0)
    if level >= 2:
        print("[otg] Level 2: toggling USB data signaling...")
        toggle_data_signal()
        time.sleep(7.0)
    if level >= 3:
        print("[otg] Level 3: force-stopping app and repeating level 2...")
        force_stop_app()
        time.sleep(1.0)
        toggle_data_signal()
        time.sleep(7.0)


def wait_for_healthy(timeout: float) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if is_healthy():
            return True
        time.sleep(0.5)
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Reset Android USB OTG host port")
    parser.add_argument(
        "--level",
        type=int,
        default=3,
        choices=[1, 2, 3],
        help="Maximum recovery escalation level (default: 3)",
    )
    parser.add_argument(
        "--wait",
        type=float,
        default=30.0,
        help="Seconds to wait for re-enumeration after the final recovery step",
    )
    args = parser.parse_args()

    if is_healthy():
        print("[otg] USB host port is already healthy.")
        return 0

    port = first_port()
    print(f"[otg] Detected USB port: {port or 'default'}")

    for lvl in range(1, args.level + 1):
        recover(lvl, port)
        print(f"[otg] Waiting up to {args.wait}s for re-enumeration after level {lvl}...")
        if wait_for_healthy(args.wait):
            print("[otg] USB OTG recovered.")
            return 0
        print(f"[otg] Level {lvl} did not recover the port.")

    print("[otg] ERROR: OTG recovery failed.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
