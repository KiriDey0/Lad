# SPDX-License-Identifier: AGPL-3.0-only
"""Read-only OS energy saving status. No shell or hardware monitoring service."""
import ctypes
import re
import subprocess
import sys
import time
import uuid
from functools import lru_cache


@lru_cache(maxsize=2)
def _dll(name):
    return ctypes.WinDLL(name, use_last_error=True)

_cached_at = -100.0
_cached = None


def windows_saving():
    class PowerStatus(ctypes.Structure):
        _fields_ = [(name, ctypes.c_ubyte) for name in
                    ('ac', 'battery', 'percent', 'saving')] + [
                        ('life', ctypes.c_uint32), ('full', ctypes.c_uint32)]
    status = PowerStatus()
    call = _dll('kernel32').GetSystemPowerStatus
    call.argtypes = [ctypes.POINTER(PowerStatus)]
    call.restype = ctypes.c_int
    if not call(ctypes.byref(status)) or status.saving not in (0, 1):
        return None
    if status.saving == 1:
        return True
    return windows_plan_saving()


def windows_plan_saving():
    pointer = ctypes.c_void_p()
    call = _dll('powrprof').PowerGetActiveScheme
    call.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
    call.restype = ctypes.c_uint32
    free = _dll('kernel32').LocalFree
    free.argtypes, free.restype = [ctypes.c_void_p], ctypes.c_void_p
    if call(None, ctypes.byref(pointer)) != 0 or not pointer.value:
        return None
    try:
        scheme = uuid.UUID(bytes_le=ctypes.string_at(pointer, 16))
        return str(scheme) == 'a1841308-3541-4fab-bc81-f71556f20b4a'
    finally:
        free(pointer)


def parse_pmset(text):
    match = re.search(r'^\s*lowpowermode\s+([01])\s*$', text, re.MULTILINE)
    return match.group(1) == '1' if match else None


def saving_status():
    global _cached_at, _cached
    try:
        if sys.platform == 'win32':
            return windows_saving()
        if sys.platform == 'darwin':
            now = time.monotonic()
            if now - _cached_at >= 15:
                _cached_at, _cached = now, None
                result = subprocess.run(['/usr/bin/pmset', '-g'], capture_output=True,
                                        text=True, timeout=2, check=True)
                _cached, _cached_at = parse_pmset(result.stdout), now
            return _cached
    except (OSError, subprocess.SubprocessError):
        return None
    return None
