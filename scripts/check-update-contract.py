#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Keep duplicated platform adapters aligned without exposing signing material."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
key=(root/'update-public-key.txt').read_text().strip()
assert len(key)==64 and all(c in '0123456789abcdef' for c in key)
for name in ('update-protocol.cjs','updates.cjs','update-macos.sh','update-windows.ps1'):
 a=root/'apps/macos/electron'/name;b=root/'apps/windows/electron'/name
 assert a.read_bytes()==b.read_bytes(),f'Desktop update contract drift: {name}'
for name in ('UpdateProtocol.kt','AppUpdates.kt'):
 a=root/'apps/android/app/src/main/java/com/lad/music'/name;b=root/'apps/tv/app/src/main/java/com/lad/music'/name
 assert a.read_bytes()==b.read_bytes(),f'Native update contract drift: {name}'
for name in ('apps/macos/electron/update-protocol.cjs','apps/android/app/src/main/java/com/lad/music/UpdateProtocol.kt'):
 assert key in (root/name).read_text(),f'Embedded public key mismatch: {name}'
print('Update protocols, helpers and embedded public keys match')
