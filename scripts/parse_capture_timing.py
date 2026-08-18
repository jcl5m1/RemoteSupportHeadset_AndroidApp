#!/usr/bin/env python3
"""
Parse an ADB logcat file (or stream) and summarize per-phase still-capture timing.

The app logs a line like:

  doSingleCapture complete: total=1934ms, dtIntentToStart=3ms, dtDrain=206ms,
      dtOut=1ms, dtCmdToLen=1089ms, dtPayload=76ms, dtTrailer=1ms,
      dtSave=562ms (raw=2ms correct=550ms metadata=6ms scan=0ms)

This script extracts those numbers, reports means/medians/p95, and optionally
emits a CSV for plotting.

Usage:
    python3 scripts/parse_capture_timing.py /tmp/simcap/full_logcat.log
    python3 scripts/parse_capture_timing.py /tmp/simcap/full_logcat.log --csv /tmp/timing.csv
"""

import argparse
import re
import sys
from dataclasses import dataclass, fields
from pathlib import Path
from typing import List, Optional

# Matches the single-line summary from doSingleCapture().
COMPLETE_RE = re.compile(
    r"doSingleCapture complete: "
    r"total=(\d+)ms, "
    r"dtIntentToStart=(\d+)ms, "
    r"dtDrain=(\d+)ms, "
    r"dtOut=(\d+)ms, "
    r"dtCmdToLen=(\d+)ms, "
    r"dtPayload=(\d+)ms, "
    r"dtTrailer=(\d+)ms, "
    r"dtSave=(\d+)ms "
    r"\(raw=(\d+)ms correct=(\d+)ms metadata=(\d+)ms scan=(\d+)ms\)"
)

SKIP_RE = re.compile(r"Skipping full-res correction for .* no Macbeth chart seen")
CORRECT_RE = re.compile(r"correctFullResJpeg total: (\d+)ms for (.*)")

# Stall / recovery / failure signatures.
CDC_OUT_RETRY_RE = re.compile(r"CDC OUT bulkTransfer failed \(attempt (\d+)/(\d+)\)")
CAPTURE_ATTEMPT_FAIL_RE = re.compile(r"Capture attempt (\d+)/(\d+) failed")
INCOMPLETE_JPEG_RE = re.compile(r"Incomplete JPEG: got (\d+)/(\d+)")
USB_RECOVERY_RE = re.compile(r"RECOVER CAMERA|Camera health check FAILED|Camera stack dead|enableUsbDataSignal|resetUsbPort")
NO_CAMERA_RE = re.compile(r"No camera devices initially found|Could not open CDC port|Camera health check: no camera open")


@dataclass
class TimingRecord:
    total: int
    intent_to_start: int
    drain: int
    out: int
    cmd_to_len: int
    payload: int
    trailer: int
    save: int
    save_raw: int
    save_correct: int
    save_metadata: int
    save_scan: int

    @classmethod
    def from_match(cls, m: re.Match) -> "TimingRecord":
        return cls(
            total=int(m.group(1)),
            intent_to_start=int(m.group(2)),
            drain=int(m.group(3)),
            out=int(m.group(4)),
            cmd_to_len=int(m.group(5)),
            payload=int(m.group(6)),
            trailer=int(m.group(7)),
            save=int(m.group(8)),
            save_raw=int(m.group(9)),
            save_correct=int(m.group(10)),
            save_metadata=int(m.group(11)),
            save_scan=int(m.group(12)),
        )


@dataclass
class StallSummary:
    cdc_out_retries: int
    capture_attempt_failures: int
    incomplete_jpeg_events: int
    usb_recovery_events: int
    no_camera_events: int


def parse_log(path: Path) -> tuple[List[TimingRecord], int, int, List[int], StallSummary]:
    records: List[TimingRecord] = []
    skipped = 0
    corrected = 0
    correct_times: List[int] = []
    stalls = StallSummary(0, 0, 0, 0, 0)
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            m = COMPLETE_RE.search(line)
            if m:
                records.append(TimingRecord.from_match(m))
            if SKIP_RE.search(line):
                skipped += 1
            cm = CORRECT_RE.search(line)
            if cm:
                corrected += 1
                correct_times.append(int(cm.group(1)))
            if CDC_OUT_RETRY_RE.search(line):
                stalls.cdc_out_retries += 1
            if CAPTURE_ATTEMPT_FAIL_RE.search(line):
                stalls.capture_attempt_failures += 1
            if INCOMPLETE_JPEG_RE.search(line):
                stalls.incomplete_jpeg_events += 1
            if USB_RECOVERY_RE.search(line):
                stalls.usb_recovery_events += 1
            if NO_CAMERA_RE.search(line):
                stalls.no_camera_events += 1
    return records, skipped, corrected, correct_times, stalls


def fmt_stats(values: List[int]) -> str:
    if not values:
        return "n=0"
    import statistics
    mean = statistics.mean(values)
    median = statistics.median(values)
    p95 = sorted(values)[int(len(values) * 0.95)] if len(values) > 1 else values[0]
    return f"n={len(values):>3} mean={mean:>7.1f} median={median:>7.1f} p95={p95:>7.1f} min={min(values):>4} max={max(values):>4}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Parse still-capture per-phase timing from logcat")
    parser.add_argument("logcat", type=Path, help="Path to logcat file")
    parser.add_argument("--csv", type=Path, help="Write parsed records to CSV")
    args = parser.parse_args()

    records, skipped, corrected, correct_times, stalls = parse_log(args.logcat)

    print("Stall / recovery summary:")
    print(f"  CDC OUT bulkTransfer retries:       {stalls.cdc_out_retries}")
    print(f"  Capture attempt failures:           {stalls.capture_attempt_failures}")
    print(f"  Incomplete JPEG payload events:     {stalls.incomplete_jpeg_events}")
    print(f"  USB host recovery / health failures:{stalls.usb_recovery_events}")
    print(f"  No-camera / CDC-open failures:      {stalls.no_camera_events}")
    print()

    if not records:
        print("No doSingleCapture complete lines found (older log without per-phase timing).")
        return 0

    print(f"Parsed {len(records)} capture completion events")
    print(f"  full-res correction runs: {corrected}")
    print(f"  full-res correction skipped: {skipped}")
    print()

    field_names = [
        ("total", "Total capture time"),
        ("intent_to_start", "Intent → capture thread start"),
        ("drain", "Drain stale CDC input"),
        ("out", "Send STILL_CAPTURE command"),
        ("cmd_to_len", "Command → STILL_LEN (firmware capture + encode)"),
        ("payload", "JPEG bulk transfer"),
        ("trailer", "Read STILL_END trailer"),
        ("save", "File save total"),
        ("save_raw", "  └─ write raw JPEG"),
        ("save_correct", "  └─ full-res rotate/AWB/encode"),
        ("save_metadata", "  └─ write EXIF metadata"),
        ("save_scan", "  └─ MediaScanner scan"),
    ]

    total_mean = sum(r.total for r in records) / len(records)
    for attr, label in field_names:
        values = [getattr(r, attr) for r in records]
        mean = sum(values) / len(values)
        pct = (mean / total_mean * 100) if total_mean > 0 else 0
        print(f"{label:45} {fmt_stats(values):>60}  ({pct:5.1f}%)")

    if correct_times:
        print()
        print(f"{'correctFullResJpeg() standalone':45} {fmt_stats(correct_times):>60}")

    if args.csv:
        with args.csv.open("w") as f:
            f.write(
                "total,intent_to_start,drain,out,cmd_to_len,payload,trailer,"
                "save,save_raw,save_correct,save_metadata,save_scan\n"
            )
            for r in records:
                f.write(
                    f"{r.total},{r.intent_to_start},{r.drain},{r.out},"
                    f"{r.cmd_to_len},{r.payload},{r.trailer},"
                    f"{r.save},{r.save_raw},{r.save_correct},"
                    f"{r.save_metadata},{r.save_scan}\n"
                )
        print(f"\nWrote CSV: {args.csv}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
