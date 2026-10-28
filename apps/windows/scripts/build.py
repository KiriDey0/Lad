# SPDX-License-Identifier: AGPL-3.0-only
"""Build the local desktop app, including its standalone Python sidecar."""
from pathlib import Path
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

if not PYTHON.exists():
    venv.create(ENV, with_pip=True)
run(PYTHON, '-m', 'pip', '--disable-pip-version-check', 'install', '-r', ROOT / 'backend/requirements.txt', 'pyinstaller==6.22.3')
run(NPM, 'ci', '--no-audit', '--no-fund')
run(NPM, 'run', 'build')
run(PYTHON, '-m', 'PyInstaller', '--noconfirm', '--clean', '--name', 'lad-backend',
    '--distpath', ROOT / 'build/backend', '--workpath', ROOT / 'build/pyinstaller',
    '--specpath', ROOT / 'build', '--paths', ROOT / 'backend', '--collect-all', 'yandex_music',
    '--collect-all', 'zeroconf', '--collect-all', 'certifi', ROOT / 'backend/app.py')
run(NPM, 'exec', 'electron-builder', '--', *(['--mac', 'dir'] if sys.platform == 'darwin' else ['--win', 'nsis', '--x64', '--publish', 'never']))

if sys.platform == 'win32':
    run(PYTHON, ROOT / 'scripts/package-windows.py')
