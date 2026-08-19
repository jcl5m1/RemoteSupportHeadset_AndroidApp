#!/usr/bin/env python3
"""
Android-aware audio loopback qualification test for RemoteSupportHeadset.

The ESP32-P4 wearable webcam is connected to the Android phone, so the phone is
the USB audio host.  This script runs on a MacBook and uses the MacBook's
speaker/microphone as the acoustic reference:

  1. MacBook plays a tone -> Android records from the ESP32 microphone.
  2. Android plays a tone through the ESP32 speaker -> MacBook records.

Both recordings are analysed for SNR and dropouts.  The script exits non-zero
if either direction fails the configured thresholds.

Requirements (MacBook host):
  - adb on PATH
  - SwitchAudioSource (brew install switchaudio-osx)
  - ffmpeg with audiotoolbox/avfoundation support
  - numpy, matplotlib

Example:
    python3 scripts/audio_loopback_test.py \
        --mac-speaker "MacBook Air Speakers" \
        --mac-mic "MacBook Air Microphone" \
        --esp32-volume 75 \
        --duration 5 \
        -o audio_loopback_report.png \
        --report audio_loopback_report.json
"""

import argparse
import json
import os
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
import wave
from pathlib import Path

import numpy as np

matplotlib = None
plt = None


def _ensure_matplotlib():
    global matplotlib, plt
    if plt is not None:
        return
    import matplotlib as _mpl
    _mpl.use('Agg')
    import matplotlib.pyplot as _plt
    matplotlib = _mpl
    plt = _plt


SAMPLE_RATE = 48000
PACKAGE = "com.example.remotesupportheadset"
ACTIVITY = f"{PACKAGE}/.DualCameraActivity"
ANDROID_TAG = "DualCameraActivity"

SWITCH_AUDIO = "SwitchAudioSource"


# ---------------------------------------------------------------------------
# Subprocess / macOS audio helpers
# ---------------------------------------------------------------------------

def run(args, check=True):
    """Run a subprocess and print the command."""
    print('$', ' '.join(args), file=sys.stderr)
    return subprocess.run(args, check=check, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE)


def get_current_output():
    result = subprocess.run([SWITCH_AUDIO, '-c', '-t', 'output'],
                            capture_output=True, text=True)
    return result.stdout.strip()


def get_current_input():
    result = subprocess.run([SWITCH_AUDIO, '-c', '-t', 'input'],
                            capture_output=True, text=True)
    return result.stdout.strip()


def set_output(name):
    run([SWITCH_AUDIO, '-s', name, '-t', 'output'])


def set_input(name):
    run([SWITCH_AUDIO, '-s', name, '-t', 'input'])


def set_output_volume(percent):
    clamped = max(0, min(100, int(percent)))
    run(["osascript", "-e", f"set volume output volume {clamped}"])


def set_input_volume(percent):
    clamped = max(0, min(100, int(percent)))
    run(["osascript", "-e", f"set volume input volume {clamped}"])


def generate_tone(freq, duration, amplitude=0.5):
    t = np.arange(0, duration, 1.0 / SAMPLE_RATE)
    y = amplitude * np.sin(2.0 * np.pi * freq * t)
    y = np.clip(y, -1.0, 1.0)
    return (y * 32767).astype(np.int16)


def write_wav(path, samples):
    with wave.open(path, 'wb') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(samples.tobytes())


def load_wav(path):
    with wave.open(path, 'rb') as wf:
        nchannels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        framerate = wf.getframerate()
        nframes = wf.getnframes()
        raw = wf.readframes(nframes)
    assert sampwidth == 2
    data = np.frombuffer(raw, dtype=np.int16)
    if nchannels > 1:
        data = data.reshape(-1, nchannels).mean(axis=1)
    return data.astype(np.float64) / 32768.0, framerate


def play_wav(path):
    run(['ffmpeg', '-y', '-i', path, '-f', 'audiotoolbox', ''])


def get_avfoundation_audio_index(name):
    """Return the avfoundation audio device index for [name], or 0 as fallback."""
    result = subprocess.run(
        ['ffmpeg', '-f', 'avfoundation', '-list_devices', 'true', '-i', ''],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    output = result.stdout.decode('utf-8', errors='replace')
    # Audio devices are listed in their own section; indices restart at [0].
    audio_section = False
    for line in output.splitlines():
        if '[AVFoundation' in line and 'audio devices' in line.lower():
            audio_section = True
            continue
        if '[AVFoundation' in line and 'video devices' in line.lower():
            audio_section = False
            continue
        if audio_section:
            match = re.search(r'\[(\d+)\]\s*(.+)', line)
            if match and name.lower() in match.group(2).lower():
                return int(match.group(1))
    return 0


def record_microphone(duration_sec, out_path, device_name=None):
    idx = get_avfoundation_audio_index(device_name) if device_name else 0
    cmd = [
        'ffmpeg', '-y', '-f', 'avfoundation', '-i', f':{idx}',
        '-t', str(duration_sec), '-ar', str(SAMPLE_RATE), '-ac', '1',
        '-sample_fmt', 's16', out_path
    ]
    return subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


# ---------------------------------------------------------------------------
# ADB helpers
# ---------------------------------------------------------------------------

def adb_base(device=None):
    cmd = ['adb']
    if device:
        cmd.extend(['-s', device])
    return cmd


def adb_shell(args, device=None, check=True):
    return run(adb_base(device) + ['shell'] + args, check=check)


def adb_pull(remote, local, device=None, check=True):
    return run(adb_base(device) + ['pull', remote, local], check=check)


def adb_mkdir(path, device=None):
    adb_shell(['mkdir', '-p', path], device=device, check=False)


def start_loopback_intent(device, action, extras):
    """Launch DualCameraActivity with audio-loopback extras."""
    cmd = adb_base(device) + [
        'shell', 'am', 'start', '-n', ACTIVITY,
        '--ez', 'audio_loopback_test', 'true',
        '--es', 'audio_loopback_action', action,
    ]
    for key, value in extras.items():
        if isinstance(value, bool):
            cmd.extend(['--ez', key, 'true' if value else 'false'])
        elif isinstance(value, int):
            cmd.extend(['--ei', key, str(value)])
        elif isinstance(value, str):
            cmd.extend(['--es', key, value])
    return run(cmd)


# ---------------------------------------------------------------------------
# Logcat reader
# ---------------------------------------------------------------------------

class LogcatReader:
    """Stream ADB logcat and wait for specific tag patterns."""

    def __init__(self, device=None, tags=None):
        self.device = device
        self.tags = tags or [ANDROID_TAG, 'AudioLoopbackTest']
        self._proc = None
        self._thread = None
        self._queue = queue.Queue()
        self._stop = threading.Event()

    def start(self):
        subprocess.run(adb_base(self.device) + ['logcat', '-c'], capture_output=True)
        cmd = adb_base(self.device) + ['logcat', '-v', 'threadtime', '-s']
        for t in self.tags:
            cmd.append(f'{t}:V')
        self._proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self._thread = threading.Thread(target=self._reader, daemon=True)
        self._thread.start()

    def _reader(self):
        if self._proc.stdout is None:
            return
        for raw in self._proc.stdout:
            if self._stop.is_set():
                break
            line = raw.decode('utf-8', errors='replace').rstrip('\n')
            self._queue.put(line)

    def stop(self):
        self._stop.set()
        if self._proc is not None:
            try:
                self._proc.terminate()
            except Exception:
                pass
        if self._thread is not None:
            self._thread.join(timeout=2.0)

    def wait_for(self, pattern, timeout=20.0):
        regex = re.compile(pattern)
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                line = self._queue.get(timeout=0.5)
            except queue.Empty:
                continue
            if regex.search(line):
                return line
        return None


# ---------------------------------------------------------------------------
# Audio analysis
# ---------------------------------------------------------------------------

def detect_tone_gaps(rms, hop, fs, threshold, min_gap_ms=5.0):
    ms_per_win = 1000.0 * hop / fs
    min_len = max(1, int(min_gap_ms / ms_per_win))
    gaps = []
    in_gap = False
    start = 0
    for i, below in enumerate(rms < threshold):
        if below and not in_gap:
            in_gap = True
            start = i
        elif not below and in_gap:
            in_gap = False
            length = i - start
            if length >= min_len:
                gaps.append((start, i))
    if in_gap:
        length = len(rms) - start
        if length >= min_len:
            gaps.append((start, len(rms)))
    return gaps


def analyze_tone(path, expected_freq, expected_duration, fs=SAMPLE_RATE):
    data, framerate = load_wav(path)
    if framerate != fs:
        old_t = np.arange(len(data)) / framerate
        new_n = int(len(data) * fs / framerate)
        new_t = np.arange(new_n) / fs
        data = np.interp(new_t, old_t, data)

    n = len(data)
    win = fs // 200
    hop = win // 2
    n_win = max(0, (n - win) // hop + 1)
    if n_win == 0:
        return {'error': 'recording too short'}

    rms = np.zeros(n_win)
    for i in range(n_win):
        seg = data[i * hop:i * hop + win]
        rms[i] = np.sqrt(np.mean(seg ** 2))

    noise_floor = np.percentile(rms, 10)
    threshold = max(noise_floor * 5.0, np.median(rms) * 0.1)

    min_above = max(1, int(0.100 * fs / hop))
    above = rms > threshold
    start_win = 0
    for i in range(len(above) - min_above + 1):
        if np.all(above[i:i + min_above]):
            start_win = i
            break
    end_win = len(above) - 1
    for i in range(len(above) - min_above, -1, -1):
        if np.all(above[i:i + min_above]):
            end_win = i + min_above - 1
            break

    start_sample = start_win * hop
    end_sample = min(end_win * hop + win, n)
    if end_sample <= start_sample:
        return {'error': 'no tone detected in recording'}

    active = data[start_sample:end_sample]
    nfft = 1 << (len(active) - 1).bit_length()
    if nfft < 2048:
        nfft = 2048
    fft_in = active[:nfft] if len(active) >= nfft else np.pad(
        active, (0, nfft - len(active)), mode='constant')
    window = np.hanning(len(fft_in))
    spec = np.fft.rfft(fft_in * window)
    mag = np.abs(spec)
    freqs = np.fft.rfftfreq(nfft, 1.0 / fs)

    search = np.abs(freqs - expected_freq) < expected_freq * 0.05
    if not np.any(search):
        return {'error': f'expected frequency {expected_freq} Hz not in FFT range'}
    peak_bin = np.argmax(mag[search]) + np.nonzero(search)[0][0]
    peak_freq = freqs[peak_bin]

    bw = 3
    sig_slice = slice(max(0, peak_bin - bw), min(len(mag), peak_bin + bw + 1))
    signal_power = np.sum(mag[sig_slice] ** 2)
    noise_power = max(np.sum(mag ** 2) - signal_power, 1e-18)
    snr_db = 10.0 * np.log10(signal_power / noise_power)

    active_rms = rms[start_win:end_win + 1]
    gaps = detect_tone_gaps(active_rms, hop, fs, threshold)
    gap_ms = [((e - s) * 1000.0 * hop / fs) for s, e in gaps]
    total_dropout_ms = float(sum(gap_ms))
    max_dropout_ms = float(max(gap_ms)) if gap_ms else 0.0

    gap_regions = []
    for s, e in gaps:
        g_start = (start_win + s) * hop
        g_end = min((start_win + e) * hop + win, n)
        gap_regions.append({
            'start_sample': int(g_start),
            'end_sample': int(g_end),
            'start_s': float(g_start / fs),
            'end_s': float(g_end / fs),
        })

    return {
        'peak_freq_hz': float(peak_freq),
        'rms_db': float(20.0 * np.log10(np.median(active_rms) + 1e-12)),
        'snr_db': float(snr_db),
        'dropout_count': int(len(gaps)),
        'total_dropout_ms': total_dropout_ms,
        'max_dropout_ms': max_dropout_ms,
        'threshold': float(threshold),
        'start_sample': int(start_sample),
        'end_sample': int(end_sample),
        'sample_rate': int(fs),
        'gaps': gap_regions,
    }


def plot_loopback_report(results, output_path):
    _ensure_matplotlib()
    fig, axes = plt.subplots(2, 2, figsize=(14, 8))
    for col, (label, result, data) in enumerate(results):
        if 'error' in result:
            axes[0, col].text(0.5, 0.5, f'{label}\n{result["error"]}',
                              ha='center', va='center',
                              transform=axes[0, col].transAxes)
            axes[0, col].set_axis_off()
            axes[1, col].set_axis_off()
            continue

        times = np.arange(len(data)) / SAMPLE_RATE
        ax_wav = axes[0, col]
        ax_wav.plot(times, data, linewidth=0.5, color='steelblue')
        for g in result['gaps']:
            ax_wav.axvspan(g['start_s'], g['end_s'], color='red', alpha=0.3)
        ax_wav.set_xlabel('Time (s)')
        ax_wav.set_ylabel('Normalized amplitude')
        ax_wav.set_title(
            f'{label}\nSNR {result["snr_db"]:.1f} dB, '
            f'dropouts {result["total_dropout_ms"]:.0f} ms')

        active = data[result['start_sample']:result['end_sample']]
        nfft = 1 << (len(active) - 1).bit_length()
        if nfft < 2048:
            nfft = 2048
        fft_in = active[:nfft] if len(active) >= nfft else np.pad(
            active, (0, nfft - len(active)), mode='constant')
        spec = np.abs(np.fft.rfft(fft_in * np.hanning(len(fft_in))))
        freqs = np.fft.rfftfreq(nfft, 1.0 / SAMPLE_RATE)

        ax_spec = axes[1, col]
        ax_spec.semilogx(freqs, 20.0 * np.log10(spec + 1e-12),
                         color='steelblue', linewidth=0.8)
        ax_spec.axvline(result['peak_freq_hz'], color='green', linestyle='--',
                        label='detected peak')
        ax_spec.set_xlim(50, SAMPLE_RATE / 2)
        ax_spec.set_xlabel('Frequency (Hz)')
        ax_spec.set_ylabel('Magnitude (dB)')
        ax_spec.legend()

    plt.tight_layout()
    fig.savefig(output_path, dpi=150)
    print(f'Saved plot to {output_path}')


def print_tone_result(label, result):
    if 'error' in result:
        print(f'{label}: ERROR - {result["error"]}')
        return
    print(f'{label}:')
    print(f'  peak frequency = {result["peak_freq_hz"]:.1f} Hz')
    print(f'  RMS level      = {result["rms_db"]:.1f} dBFS')
    print(f'  SNR            = {result["snr_db"]:.1f} dB')
    print(f'  dropouts       = {result["dropout_count"]}')
    print(f'  total gap      = {result["total_dropout_ms"]:.1f} ms')
    print(f'  max gap        = {result["max_dropout_ms"]:.1f} ms')


# ---------------------------------------------------------------------------
# Main test flow
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description='Android-aware audio loopback qualification for RemoteSupportHeadset.')
    parser.add_argument('--adb-device', default=None,
                        help='ADB device serial (default: only device attached)')
    parser.add_argument('--package', default=PACKAGE,
                        help='Android package name (default: %(default)s)')
    parser.add_argument('--mac-speaker', default='MacBook Air Speakers',
                        help='MacBook speaker device name for SwitchAudioSource')
    parser.add_argument('--mac-mic', default='MacBook Air Microphone',
                        help='MacBook microphone device name for SwitchAudioSource')
    parser.add_argument('--mac-volume', type=int, default=75,
                        help='MacBook speaker volume 0-100 (default: %(default)s)')
    parser.add_argument('--mac-input-volume', type=int, default=50,
                        help='MacBook microphone gain 0-100 (default: %(default)s)')
    parser.add_argument('--esp32-volume', type=int, default=75,
                        help='ESP32 codec volume 0-100 (default: %(default)s)')
    parser.add_argument('--freq', type=int, default=1000,
                        help='Tone frequency in Hz (default: %(default)s)')
    parser.add_argument('--duration', type=float, default=5.0,
                        help='Tone duration in seconds (default: %(default)s)')
    parser.add_argument('--pass-snr-db', type=float, default=-10.0,
                        help='Minimum SNR for a passing direction (default is lenient for acoustic laptop loops)')
    parser.add_argument('--pass-dropout-ms', type=float, default=50.0,
                        help='Maximum total dropout duration for a pass')
    parser.add_argument('--pass-max-dropout-ms', type=float, default=20.0,
                        help='Maximum single dropout duration for a pass')
    parser.add_argument('-o', '--output', type=str, required=True,
                        help='Output plot path (PNG)')
    parser.add_argument('--report', type=str, default=None,
                        help='Optional JSON report path')
    parser.add_argument('--keep-files', action='store_true',
                        help='Do not delete temporary WAV files')
    args = parser.parse_args()

    duration_ms = int(args.duration * 1000)
    record_duration_ms = duration_ms + 1000  # extra capture padding
    output_dir = f'/sdcard/Android/data/{args.package}/files/audio_loopback'
    android_record_path = f'{output_dir}/esp32_mic.wav'

    adb_mkdir(output_dir, device=args.adb_device)

    reader = LogcatReader(device=args.adb_device)
    reader.start()

    original_output = get_current_output()
    original_input = get_current_input()

    tmpdir = tempfile.mkdtemp(prefix='audio_loopback_')
    tone_path = os.path.join(tmpdir, 'tone.wav')
    write_wav(tone_path, generate_tone(args.freq, args.duration, amplitude=0.5))

    try:
        # -------------------------------------------------------------------
        # Direction 1: MacBook speaker -> ESP32 mic (recorded on Android)
        # -------------------------------------------------------------------
        print('\n=== MacBook speaker -> ESP32 microphone ===')
        set_output(args.mac_speaker)
        set_output_volume(args.mac_volume)

        start_loopback_intent(
            args.adb_device, 'record',
            {
                'audio_loopback_output': android_record_path,
                'audio_loopback_duration_ms': record_duration_ms,
            }
        )
        time.sleep(0.5)
        play_wav(tone_path)

        line = reader.wait_for(r'AudioLoopbackTest: record complete success=true', timeout=30.0)
        if line is None:
            print('ERROR: Android recording did not complete in time', file=sys.stderr)
            sys.exit(1)

        local_record_path = os.path.join(tmpdir, 'mac_to_esp32.wav')
        adb_pull(android_record_path, local_record_path, device=args.adb_device)
        res_mac_to_esp = analyze_tone(local_record_path, args.freq, args.duration)
        data_mac_to_esp, _ = load_wav(local_record_path)
        print_tone_result('MacBook -> ESP32 mic', res_mac_to_esp)

        # -------------------------------------------------------------------
        # Direction 2: ESP32 speaker -> MacBook microphone
        # -------------------------------------------------------------------
        print('\n=== ESP32 speaker -> MacBook microphone ===')
        set_input(args.mac_mic)
        set_input_volume(args.mac_input_volume)
        # Digital full scale; the ESP32 codec volume is set by the Android side.
        set_output_volume(100)

        start_loopback_intent(
            args.adb_device, 'play',
            {
                'audio_loopback_freq': args.freq,
                'audio_loopback_duration_ms': duration_ms,
                'audio_loopback_volume': args.esp32_volume,
            }
        )
        time.sleep(0.5)

        local_mac_record = os.path.join(tmpdir, 'esp32_to_mac.wav')
        record_proc = record_microphone(args.duration + 1.5, local_mac_record,
                                        device_name=args.mac_mic)
        line = reader.wait_for(r'AudioLoopbackTest: play complete success=true', timeout=30.0)
        record_proc.wait()
        if line is None:
            print('ERROR: Android playback did not complete in time', file=sys.stderr)
            sys.exit(1)

        res_esp_to_mac = analyze_tone(local_mac_record, args.freq, args.duration)
        data_esp_to_mac, _ = load_wav(local_mac_record)
        print_tone_result('ESP32 speaker -> MacBook mic', res_esp_to_mac)

    finally:
        reader.stop()
        set_output(original_output)
        set_input(original_input)
        if not args.keep_files:
            import shutil
            shutil.rmtree(tmpdir, ignore_errors=True)

    def passed(result):
        if 'error' in result:
            return False
        return (result['snr_db'] >= args.pass_snr_db and
                result['total_dropout_ms'] <= args.pass_dropout_ms and
                result['max_dropout_ms'] <= args.pass_max_dropout_ms)

    res_mac_to_esp['passed'] = passed(res_mac_to_esp)
    res_esp_to_mac['passed'] = passed(res_esp_to_mac)
    overall = res_mac_to_esp['passed'] and res_esp_to_mac['passed']

    print('\n=== RESULT ===')
    print(f"MacBook -> ESP32 mic: {'PASS' if res_mac_to_esp['passed'] else 'FAIL'}")
    print(f"ESP32 speaker -> MacBook mic: {'PASS' if res_esp_to_mac['passed'] else 'FAIL'}")
    print(f"Overall: {'PASS' if overall else 'FAIL'}")

    plot_loopback_report([
        ('MacBook -> ESP32 mic', res_mac_to_esp, data_mac_to_esp),
        ('ESP32 speaker -> MacBook mic', res_esp_to_mac, data_esp_to_mac),
    ], args.output)

    if args.report:
        with open(args.report, 'w') as f:
            json.dump({
                'mac_to_esp32': res_mac_to_esp,
                'esp32_to_mac': res_esp_to_mac,
                'passed': overall,
            }, f, indent=2)
        print(f'Saved JSON report to {args.report}')

    sys.exit(0 if overall else 1)


if __name__ == '__main__':
    main()
