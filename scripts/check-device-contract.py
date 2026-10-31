#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
root=Path(__file__).resolve().parents[1]
assert (root/'apps/macos/backend/device_identity.py').read_bytes()==(root/'apps/windows/backend/device_identity.py').read_bytes()
for name in ('DeviceIdentity.kt',):
 assert (root/'apps/android/app/src/main/java/com/lad/music'/name).read_bytes()==(root/'apps/tv/app/src/main/java/com/lad/music'/name).read_bytes()
print('Device identity parsing matches on desktop and native platforms')
