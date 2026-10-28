# SPDX-License-Identifier: AGPL-3.0-only
"""Run device regressions in com.lad.music.qa only; the release account is never touched."""
import argparse
import shutil
import subprocess
import sys
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='Explicit adb serial of the authorized test phone')
parser.add_argument('--adb', default=shutil.which('adb') or 'adb')
parser.add_argument('--cleanup', action='store_true', help='Remove only the two isolated QA packages after success')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
adb = [args.adb, '-s', args.serial]
def command(*command_args):
    return subprocess.check_output(adb + list(command_args), stderr=subprocess.STDOUT)

for apk in ('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
    path = root / apk
    if not path.is_file():
        raise SystemExit('Build :app:assembleDebug :app:assembleDebugAndroidTest first')
    print(command('install', '-r', str(path)).decode(), end='', flush=True)
api = int(command('shell', 'getprop', 'ro.build.version.sdk').strip())
if api >= 33:
    command('shell', 'pm', 'grant', 'com.lad.music.qa', 'android.permission.POST_NOTIFICATIONS')
log = root / 'build/device-regressions.log'
log.parent.mkdir(parents=True, exist_ok=True)
with log.open('w', encoding='utf-8') as output:
    process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class',
        'com.lad.music.EngineRaceTest,com.lad.music.PhoneVolumeTest',
        'com.lad.music.qa.test/android.test.InstrumentationTestRunner'], stdout=output, stderr=subprocess.STDOUT)
    launched = False
    deadline = time.monotonic() + 90
    while process.poll() is None and time.monotonic() < deadline:
        if not launched and 'PhoneVolumeTest:' in log.read_text(encoding='utf-8'):
            # MIUI can block activity launches from an instrumentation process.
            time.sleep(.5)
            command('shell', 'am', 'start', '-n', 'com.lad.music.qa/com.lad.music.MainActivity')
            launched = True
        time.sleep(.2)
    if process.poll() is None:
        command('shell', 'am', 'force-stop', 'com.lad.music.qa')
        raise SystemExit('QA timed out; inspect ' + str(log))
result = log.read_text(encoding='utf-8')
print(result)
if 'OK (4 tests)' not in result:
    raise SystemExit(1)
if args.cleanup:
    for package in ('com.lad.music.qa.test', 'com.lad.music.qa'):
        print(command('uninstall', package).decode(), end='')
