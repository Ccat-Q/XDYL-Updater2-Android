#!/usr/bin/env python3
"""Run prebuilt test APKs and upgrade checks on a GitHub-hosted emulator."""
from pathlib import Path
import re
import subprocess
import time

out = Path("artifacts/device")
out.mkdir(parents=True, exist_ok=True)
pkg = "com.ccatq.xdylupdater2.debug"

def adb(*args):
    return subprocess.run(["adb", *map(str, args)], check=True, capture_output=True).stdout

def apk(name):
    return next(Path("artifacts/input").rglob(name))

try:
    adb("install", "-r", apk("app-debug.apk"))
    adb("install", "-r", apk("app-debug-androidTest.apk"))
    result = adb("shell", "am", "instrument", "-w", "-r", pkg + ".test/androidx.test.runner.AndroidJUnitRunner").decode(errors="replace")
    (out / "instrumentation.txt").write_text(result)
    print(result)
    if not re.search(r"OK \([1-9][0-9]* tests?\)", result) or any(value in result for value in ["FAILURES!!!", "INSTRUMENTATION_FAILED", "shortMsg="]):
        raise SystemExit("Device tests failed or completed without a passing test count.")
    adb("install", "-r", apk("app-debug-upgrade.apk"))
    marker = adb("shell", "run-as", pkg, "cat", "files/upgrade-marker").decode().strip()
    assert marker == "persistent", "App data was lost during same-key upgrade"
    package = adb("shell", "dumpsys", "package", pkg).decode()
    assert re.search(r"versionCode=2\b", package), "Upgrade APK versionCode was not installed"
    (out / "upgrade.txt").write_text("versionCode 1 -> 2; same signing key; private app data preserved.\n")
    # Clear fixture sessions before opening the real app; screenshots make no authenticated requests.
    adb("shell", "pm", "clear", pkg)
    for mode in ["light", "dark"]:
        adb("shell", "cmd", "uimode", "night", "yes" if mode == "dark" else "no")
        adb("shell", "settings", "put", "system", "font_scale", "1.3")
        adb("shell", "am", "force-stop", pkg)
        adb("shell", "am", "start", "-n", pkg + "/com.ccatq.xdylupdater2.MainActivity")
        time.sleep(3)
        (out / (mode + "-large-font.png")).write_bytes(adb("exec-out", "screencap", "-p"))
finally:
    (out / "logcat.txt").write_bytes(adb("logcat", "-d", "-t", "1000"))
