"""ADB frame-time stress capture. Python 3.10+, standard library only.

Run on a real TV, with its normal animation settings. This does not install,
uninstall, clear app data, change HOME or change security settings.
"""
import argparse
import csv
import datetime
import json
import math
import pathlib
import re
import subprocess
import time


def parse_frames(text):
    columns = None
    frames = {}
    for line in text.splitlines():
        row = next(csv.reader([line.strip()]))
        if "Flags" in row and "IntendedVsync" in row and "FrameCompleted" in row:
            columns = row
            continue
        if not columns or len(row) < len(columns):
            continue
        try:
            values = dict(zip(columns, row))
            start, end = int(values["IntendedVsync"]), int(values["FrameCompleted"])
            if int(values["Flags"]) != 0 or not (0 < start < end < 2**63 - 1):
                continue
            frames[(start, end)] = (end - start) / 1_000_000
        except (KeyError, ValueError):
            continue
    return frames


def summarize(frames, hz):
    samples = sorted(frames.values())
    if not samples:
        return {"status": "INSUFFICIENT_DATA", "frames": 0}
    budget = 1000 / hz
    percentile = lambda p: samples[max(0, math.ceil(p * len(samples)) - 1)]
    jank = 100 * sum(value > budget for value in samples) / len(samples)
    # Project acceptance targets; this is frame completion time, not measured FPS.
    passed = percentile(.95) <= 2 * budget and jank <= 5 and max(samples) < 700
    return {"status": ("PASS" if passed else "FAIL") if len(samples) >= 300 else "INSUFFICIENT_DATA",
            "frames": len(samples), "configured_hz": hz, "budget_ms": budget,
            "p50_ms": percentile(.5), "p95_ms": percentile(.95),
            "p99_ms": percentile(.99), "maximum_ms": max(samples),
            "over_budget_percent": jank,
            "frames_over_700ms": sum(value >= 700 for value in samples)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.example.homeezch.usb")
    parser.add_argument("--hz", type=float, required=True, help="Actual TV refresh rate")
    parser.add_argument("--rounds", type=int, default=50)
    parser.add_argument("--out", default="tv-stress-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.]+", args.package) or not 20 <= args.hz <= 240 or not 1 <= args.rounds <= 500:
        parser.error("Invalid package, refresh rate or rounds")
    output = pathlib.Path(args.out)
    output.mkdir(parents=True, exist_ok=False)

    def adb(*command, timeout=30):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    def save(name, value):
        (output / name).write_text(value, encoding="utf-8")

    def foreground():
        state = adb("shell", "dumpsys", "window", "windows")
        focus = [line for line in state.splitlines() if "mCurrentFocus=" in line]
        if not any(args.package + "/" in line for line in focus):
            raise RuntimeError("Launcher lost foreground; capture is invalid: " + str(focus))

    frames = {}
    try:
        if adb("get-state").strip() != "device":
            raise RuntimeError("Device is not ready")
        save("package.txt", adb("shell", "dumpsys", "package", args.package))
        save("display.txt", adb("shell", "dumpsys", "display"))
        save("device.txt", adb("shell", "getprop", "ro.product.model") + adb("shell", "getprop", "ro.build.version.release"))
        for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            value = adb("shell", "settings", "get", "global", name).strip()
            save(name + ".txt", value)
            if value in ("0", "0.0"):
                raise RuntimeError("Animations are disabled; enable normal animations before measuring")
        component = args.package + "/com.example.homeezch.MainActivity"
        save("start.txt", adb("shell", "am", "start", "-W", "-n", component,
                              "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"))
        time.sleep(2)
        foreground()
        save("memory-before.txt", adb("shell", "dumpsys", "meminfo", args.package))
        pid = adb("shell", "pidof", args.package).strip()
        if not pid or not pid.isdigit():
            raise RuntimeError("Expected one launcher process")
        for index in range(args.rounds):
            foreground()
            adb("shell", "dumpsys", "gfxinfo", args.package, "reset")
            # Six cards each way; collect often enough to avoid the usual 120-frame ring wrapping.
            adb("shell", "for key in 22 22 22 22 22 22 21 21 21 21 21 21; do input keyevent $key; sleep 0.04; done")
            raw = adb("shell", "dumpsys", "gfxinfo", args.package, "framestats")
            save(f"frames-{index:03d}.txt", raw)
            frames.update(parse_frames(raw))
            foreground()
            if adb("shell", "pidof", args.package).strip() != pid:
                raise RuntimeError("Launcher process restarted")
            print(f"Round {index + 1}/{args.rounds}; captured {len(frames)} frames", flush=True)
        save("memory-after.txt", adb("shell", "dumpsys", "meminfo", args.package))
        save("logcat.txt", adb("logcat", "-d", "--pid=" + pid, "-v", "threadtime"))
        result = summarize(frames, args.hz)
        result.update(package=args.package, input_events=args.rounds * 12,
                      scope="Horizontal navigation only; captured frame subset, not end-to-end FPS or install verification")
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        result = {"status": "BLOCKED", "reason": str(error), "captured_frames": len(frames)}
    save("summary.json", json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
