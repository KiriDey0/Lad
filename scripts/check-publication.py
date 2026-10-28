#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Check the tracked public tree; never print matched secret values."""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
forbidden = {'session.enc', 'queue.json', 'window.json', 'local.properties', '.DS_Store'}
private_dirs = {'node_modules', '.venv', '.gradle', '.kotlin', '__pycache__', 'userData', 'work'}
patterns = [
    re.compile(rb'-----BEGIN (?:OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----'),
    re.compile(rb'(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{30,})'),
    re.compile(rb'y0_[A-Za-z0-9_-]{40,}'),
    re.compile(rb'/Users/[A-Za-z0-9_.-]+/'),
    re.compile(rb'[A-Za-z]:\\Users\\[A-Za-z0-9_.-]+\\'),
    re.compile(rb'/var/folders/[A-Za-z0-9/_.-]+'),
]
issues = []
for name in filter(None, paths):
    path = ROOT / name
    rel = Path(name)
    if path.is_symlink() or not path.is_file():
        continue
    if rel.name in forbidden or any(p in private_dirs for p in rel.parts) or rel.suffix in {'.enc', '.jks', '.keystore', '.p12', '.pfx', '.apk', '.exe'}:
        issues.append((name, 'private/runtime file'))
        continue
    data = path.read_bytes()
    if any(pattern.search(data) for pattern in patterns):
        issues.append((name, 'private path or credential pattern'))
for name, reason in issues:
    print(f'{name}: {reason}')
if issues:
    raise SystemExit(1)
print(f'Public tree check passed: {len([p for p in paths if p])} tracked files')
