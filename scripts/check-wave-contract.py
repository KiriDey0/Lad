#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Guard desktop adapter parity while all four apps share the approved wave models."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
for name in ('src/wave/WaveScene.tsx','src/wave/audio.ts','electron/wave-stream.cjs'):
 assert (root/'apps/macos'/name).read_bytes()==(root/'apps/windows'/name).read_bytes(),name
for platform in ('android','tv'):
 assert '../../../shared/wave-native/src/main/java' in (root/'apps'/platform/'app/build.gradle.kts').read_text()
 assert 'waveIntensity: Float = 100f' in (root/'apps'/platform/'app/src/main/java/com/lad/music/Models.kt').read_text()
print('Shared web model, native model and 100% defaults are wired on all four platforms')
