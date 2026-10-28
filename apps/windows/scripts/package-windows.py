# SPDX-License-Identifier: AGPL-3.0-only
"""Create a portable Windows ZIP with standard forward-slash paths and no user data."""
from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[1]
release = root / 'release'
version = json.loads((root / 'package.json').read_text(encoding='utf-8'))['version']
app = release / 'win-unpacked'
assert (app / 'Lad.exe').is_file(), 'Build the Windows app first'
archive = release / f'Lad-Windows-x64-{version}.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for file in sorted(app.rglob('*')):
        if file.is_file():
            z.write(file, 'Lad-Windows-x64/' + file.relative_to(app).as_posix())
    for name in ('README.md',):
        file = root / name
        if file.exists():
            z.write(file, 'Lad-Windows-x64/' + name)
    for name in ('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md'):
        file = root.parent.parent / name
        if file.is_file():
            z.write(file, 'Lad-Windows-x64/' + name)
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
manifest = {}
for file in (release / f'Lad-Setup-{version}-x64.exe', archive):
    if file.exists():
        manifest[file.name] = {'bytes': file.stat().st_size, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()}
(release / 'checksums.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(f'Portable archive: {archive}')
