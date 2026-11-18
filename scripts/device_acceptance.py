"""Run real-device switch measurements and an optional two-hour soak. Requires the test APK."""
import argparse
import json
import pathlib
import re
import statistics
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", required=True)
parser.add_argument("--playlist", required=True)
parser.add_argument("--switches", type=int, default=30)
parser.add_argument("--soak-seconds", type=int, default=0)
args = parser.parse_args()
output = pathlib.Path(__file__).resolve().parents[1] / "output" / "device"
output.mkdir(parents=True, exist_ok=True)

def adb(*items, timeout=30):
    result = subprocess.run([args.adb, "-s", args.serial, *items], capture_output=True, timeout=timeout)
    text = (result.stdout + result.stderr).decode("utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(text)
    return text

if "device" not in adb("get-state"):
    raise SystemExit("An authorized physical Android device is required.")
device = adb("shell", "getprop ro.product.model").strip()
sdk = adb("shell", "getprop ro.build.version.sdk").strip()
result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
             "app.swifttv.player.DeviceSmokeTest#launchActivityWithOptionalControlledPlaylist",
             "-e", "playlistUrl", args.playlist,
             "app.swifttv.player.test/androidx.test.runner.AndroidJUnitRunner", timeout=120)
(output / "instrumentation.txt").write_text(result, encoding="utf-8")
if "OK (1 test)" not in result:
    raise SystemExit("Instrumentation failed; see output/device/instrumentation.txt")
adb("shell", "am", "start", "-W", "-n", "app.swifttv.player/.MainActivity", "--ez", "skipBuiltinsForTest", "true")
time.sleep(6)
metrics = []
for index in range(args.switches):
    adb("logcat", "-c")
    adb("shell", "input", "keyevent", "KEYCODE_DPAD_UP")
    deadline = time.monotonic() + 12
    found = None
    while time.monotonic() < deadline:
        log = adb("logcat", "-d", "-v", "brief", "-s", "SwiftTVMetric:I", "*:S")
        matches = re.findall(r"first_frame channel=(\S+) ms=(\d+).*?probe=false", log)
        if matches:
            found = {"channel": matches[-1][0], "firstFrameMs": int(matches[-1][1])}
            break
        time.sleep(.2)
    metrics.append(found or {"error": "No first frame within 12 seconds"})
    print("SWITCH", index + 1, metrics[-1], flush=True)
    time.sleep(.8)

values = sorted(x["firstFrameMs"] for x in metrics if "firstFrameMs" in x)
summary = {"device": device, "sdk": sdk, "measurement": "remote-key event to video first-frame callback",
           "switches": metrics, "successCount": len(values), "total": args.switches}
if values:
    summary["p50Ms"] = statistics.median(values)
    summary["p95Ms"] = values[max(0, __import__("math").ceil(len(values) * .95) - 1)]
summary["switchGate"] = ("pass" if len(values) == args.switches and summary["p50Ms"] <= 2000 and summary["p95Ms"] <= 4000 else "fail")
start = time.monotonic()
samples = []
while time.monotonic() - start < args.soak_seconds:
    samples.append({"elapsedSeconds": round(time.monotonic() - start),
                    "focus": adb("shell", "dumpsys window | grep mCurrentFocus").strip(),
                    "memory": adb("shell", "dumpsys meminfo app.swifttv.player"),
                    "errors": adb("logcat", "-d", "-v", "brief", "-s", "AndroidRuntime:E", "SwiftTVMetric:W", "*:S")})
    (output / "soak-progress.json").write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding="utf-8")
    print("SOAK_SECONDS", samples[-1]["elapsedSeconds"], flush=True)
    time.sleep(min(30, max(0, args.soak_seconds - (time.monotonic() - start))))
summary["soakSecondsObserved"] = round(time.monotonic() - start) if args.soak_seconds else 0
summary["soakSamples"] = samples
summary["soakGate"] = "manual_review_required" if args.soak_seconds else "untested"
# Focus/memory logs alone cannot establish audio sync, rendered motion or rebuffer ratio.
summary["audioVideoSync"] = "untested"
(output / "acceptance.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({k: v for k, v in summary.items() if k not in ("switches", "soakSamples")}, ensure_ascii=False))
