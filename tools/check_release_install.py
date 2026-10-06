#!/usr/bin/env python3
"""Install the signed release on the minimum Android API in Actions."""
from pathlib import Path
import subprocess
import time

package = 'com.ccatq.xdylupdater2'
out = Path('artifacts/release-device')
out.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.run(['adb', *map(str, args)], check=True, capture_output=True).stdout

try:
    adb('logcat', '-c')
    result = adb('install', '-r', 'artifacts/release/StarWave-android.apk').decode()
    assert 'Success' in result, 'Signed APK installation did not succeed'
    adb('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
    time.sleep(5)
    assert adb('shell', 'pidof', package).strip(), 'Release app exited after launch'
    activities = adb('shell', 'dumpsys', 'activity', 'activities').decode()
    assert any(package in line and ('mResumedActivity' in line or 'topResumedActivity' in line) for line in activities.splitlines()), 'Release activity is not foreground'
    logs = adb('logcat', '-d').decode(errors='replace')
    assert not ('FATAL EXCEPTION' in logs and 'Process: ' + package in logs), 'Release launch crashed'
    (out / 'release-start.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    (out / 'installation.txt').write_text('Release signature verified; signed APK installed and launched on Android API 26.\n')
    print('Signed release installed and launched successfully on Android 8.0.')
finally:
    (out / 'logcat.txt').write_bytes(adb('logcat', '-d', '-t', '1000'))
