# SPDX-License-Identifier: AGPL-3.0-only
"""Verify the shipped ZIP and run its updater against the real packaged app."""
import json
import os
from pathlib import Path
import plistlib
import signal
import subprocess
import sys
import tempfile

archive = Path(sys.argv[1]).resolve()
version = json.loads((Path(__file__).resolve().parents[1] / 'package.json').read_text())['version']
helper = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else Path(__file__).resolve().parents[1] / 'electron/update-macos.sh'

with tempfile.TemporaryDirectory(prefix='lad packaged update ') as temporary:
    root = Path(temporary)
    app = root / 'installed/Лад.app'
    ack, journal = root / 'launch.json', root / 'result.json'
    try:
        subprocess.run(['/usr/bin/ditto', '-x', '-k', archive, root / 'incoming'], check=True)
        incoming = root / 'incoming/Лад.app'
        metadata = plistlib.loads((incoming / 'Contents/Info.plist').read_bytes())
        assert metadata['CFBundleIdentifier'] == 'com.lad.music'
        assert metadata['CFBundleShortVersionString'] == version
        subprocess.run(['/usr/bin/codesign', '--verify', '--deep', '--strict', incoming], check=True)
        # A real previous bundle exercises replacement, while the incoming app
        # must launch Electron, start its backend and acknowledge healthy startup.
        subprocess.run(['/usr/bin/ditto', incoming, app], check=True)
        result = subprocess.run(['/bin/sh', helper, '99999999', app, archive, version, ack, journal],
                                env=dict(os.environ, LAD_USER_DATA=str(root / 'profile')), timeout=120)
        assert result.returncode == 0, journal.read_text() if journal.exists() else 'No installation result'
        assert json.loads(journal.read_text())['ok']
        health = json.loads(ack.read_text())
        assert health['version'] == version
        assert health['authenticated'] is False
        subprocess.run(['/usr/bin/codesign', '--verify', '--deep', '--strict', app], check=True)
        assert not list((root / 'installed').glob('.lad-update.*')), 'Staging directory was not cleaned'
        print(f'Packaged {version}: signature, replacement and real backend launch verified')
    except BaseException:
        if Path(str(journal) + '.log').exists():
            print(Path(str(journal) + '.log').read_text(errors='replace')[-12000:], file=sys.stderr)
        raise
    finally:
        # Only processes inside this temporary QA installation are touched.
        processes = subprocess.check_output(['/bin/ps', '-axo', 'pid=,command='], text=True)
        for line in processes.splitlines():
            fields = line.strip().split(None, 1)
            if len(fields) == 2 and (str(app) in fields[1] or str(app).replace('/var/', '/private/var/', 1) in fields[1]):
                try:
                    os.kill(int(fields[0]), signal.SIGTERM)
                except ProcessLookupError:
                    pass
