# SPDX-License-Identifier: AGPL-3.0-only
"""Build the local desktop app, including its standalone Python sidecar."""
from pathlib import Path
import json
import os
import subprocess
import sys
import venv

ROOT = Path(__file__).resolve().parents[1]
ENV = ROOT / '.venv'
PYTHON = ENV / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
NPM = 'npm.cmd' if os.name == 'nt' else 'npm'

def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)

if sys.platform == 'darwin':
    native = ROOT / 'native'
    native.mkdir(exist_ok=True)
    run('swiftc', '-O', '-framework', 'AppKit', '-framework', 'MediaPlayer',
        native / 'lad-nowplaying.swift', '-o', native / 'lad-nowplaying')

if not PYTHON.exists():
    venv.create(ENV, with_pip=True)
run(PYTHON, '-m', 'pip', 'install', '-r', ROOT / 'backend/requirements.txt', 'pyinstaller==6.22.3')
run(NPM, 'ci', '--no-audit', '--no-fund')
run(NPM, 'run', 'build')
run(PYTHON, '-m', 'PyInstaller', '--noconfirm', '--clean', '--name', 'lad-backend',
    '--distpath', ROOT / 'build/backend', '--workpath', ROOT / 'build/pyinstaller',
    '--specpath', ROOT / 'build', '--paths', ROOT / 'backend', '--collect-all', 'yandex_music',
    '--collect-all', 'zeroconf', '--collect-all', 'certifi',
    '--collect-all', 'mutagen', '--collect-all', 'PIL', '--collect-all', 'miniaudio',
    '--hidden-import', '_miniaudio', '--hidden-import', '_cffi_backend', ROOT / 'backend/app.py')
run(NPM, 'exec', 'electron-builder', '--', '--mac' if sys.platform == 'darwin' else '--win', 'dir')
if sys.platform == 'darwin':
    # identity=null skips electron-builder signing entirely. The linker's signature
    # on the Electron executable does not seal the finished app's resources.
    product = json.loads((ROOT / 'package.json').read_text())['build']['productName']
    bundle = ROOT / 'release/mac-arm64' / f'{product}.app'
    run('/usr/bin/codesign', '--force', '--deep', '--sign', '-', bundle)
    run('/usr/bin/codesign', '--verify', '--deep', '--strict', bundle)
