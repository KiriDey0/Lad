# SPDX-License-Identifier: AGPL-3.0-only
"""Optional native output-volume integration; no hooks, shell commands or global key grabs."""
import ctypes as c
import sys

class MacVolume:
    class Address(c.Structure):
        _fields_ = [("selector", c.c_uint32), ("scope", c.c_uint32), ("element", c.c_uint32)]
    def __init__(self):
        self.lib = c.CDLL("/System/Library/Frameworks/CoreAudio.framework/CoreAudio")
    def address(self, selector, scope="glob", element=0):
        return self.Address(int.from_bytes(selector.encode(), "big"), int.from_bytes(scope.encode(), "big"), element)
    def get(self, device, selector, scope="glob", element=0, kind=c.c_uint32):
        a = self.address(selector, scope, element); v = kind(); size = c.c_uint32(c.sizeof(v))
        if self.lib.AudioObjectGetPropertyData(device, c.byref(a), 0, None, c.byref(size), c.byref(v)):
            raise OSError("Этот аудиовыход не поддерживает системную громкость")
        return v.value
    def set(self, device, selector, value, element=0):
        a = self.address(selector, "outp", element)
        if self.lib.AudioObjectSetPropertyData(device, c.byref(a), 0, None, c.sizeof(value), c.byref(value)):
            raise OSError("Не удалось изменить системную громкость")
    def capture(self):
        d = self.get(1, "dOut")
        try: values = [(0, self.get(d, "volm", "outp", kind=c.c_float))]
        except OSError: values = [(el, self.get(d, "volm", "outp", el, c.c_float)) for el in (1, 2)]
        try: mute = self.get(d, "mute", "outp")
        except OSError: mute = None
        return d, values, mute
    def restore(self, saved):
        d, values, mute = saved
        for el, value in values: self.set(d, "volm", c.c_float(value), el)
        if mute is not None: self.set(d, "mute", c.c_uint32(mute))
        return self.read()
    def read(self):
        d = self.get(1, "dOut")
        try:
            if self.get(d, "mute", "outp"): return 0.0
        except OSError: pass
        try: return self.get(d, "volm", "outp", kind=c.c_float)
        except OSError: return sum(self.get(d, "volm", "outp", el, c.c_float) for el in (1, 2)) / 2
    def write(self, value):
        d = self.get(1, "dOut"); value = max(0.0, min(1.0, value))
        try: self.set(d, "volm", c.c_float(value))
        except OSError:
            for el in (1, 2): self.set(d, "volm", c.c_float(value), el)
        try: self.set(d, "mute", c.c_uint32(value == 0))
        except OSError: pass
        return self.read()

class WindowsVolume:
    def __init__(self):
        from pycaw.pycaw import AudioUtilities
        self.devices = AudioUtilities
    def capture(self):
        v = self.devices.GetSpeakers().EndpointVolume
        return v, float(v.GetMasterVolumeLevelScalar()), bool(v.GetMute())
    def restore(self, saved):
        v, level, mute = saved
        v.SetMasterVolumeLevelScalar(level, None)
        v.SetMute(mute, None)
        return self.read()
    def read(self):
        v = self.devices.GetSpeakers().EndpointVolume
        return 0.0 if v.GetMute() else float(v.GetMasterVolumeLevelScalar())
    def write(self, value):
        v = self.devices.GetSpeakers().EndpointVolume
        value = max(0.0, min(1.0, value))
        v.SetMasterVolumeLevelScalar(value, None)
        v.SetMute(value == 0, None)
        return self.read()

def native_volume():
    if sys.platform == "darwin": return MacVolume()
    if sys.platform == "win32": return WindowsVolume()
    raise OSError("Синхронизация недоступна на этой системе")
