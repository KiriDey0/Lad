# SPDX-License-Identifier: AGPL-3.0-only
import ctypes
import pytest
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
import power_status as power


@pytest.fixture(autouse=True)
def reset_native_handles():
    power._dll.cache_clear()
    yield
    power._dll.cache_clear()


def test_pmset_only_accepts_the_active_low_power_field():
    assert power.parse_pmset('Currently in use:\n lowpowermode 1\n') is True
    assert power.parse_pmset(' lowpowermode 0\n') is False
    assert power.parse_pmset('someotherfield 1\n') is None


def test_mac_poll_is_cached_and_read_only(monkeypatch):
    monkeypatch.setattr(power.sys, 'platform', 'darwin')
    monkeypatch.setattr(power, '_cached_at', -100)
    clock = [100.]
    monkeypatch.setattr(power.time, 'monotonic', lambda: clock[0])
    run = Mock(return_value=SimpleNamespace(stdout=' lowpowermode 1\n'))
    monkeypatch.setattr(power.subprocess, 'run', run)
    assert power.saving_status() is True
    assert power.saving_status() is True
    assert run.call_count == 1
    assert run.call_args.args[0] == ['/usr/bin/pmset', '-g']
    clock[0] += 16
    assert power.saving_status() is True
    assert run.call_count == 2


def test_windows_struct_layout_and_os_failure(monkeypatch):
    monkeypatch.setattr(power.sys, 'platform', 'win32')
    def native(status):
        obj = status._obj
        assert ctypes.sizeof(obj) == 12
        assert type(obj).saving.offset == 3
        obj.saving = 1
        return 1
    fn = Mock(side_effect=native)
    monkeypatch.setattr(ctypes, 'WinDLL', lambda *a, **kw: SimpleNamespace(GetSystemPowerStatus=fn), raising=False)
    assert power.saving_status() is True
    fn.side_effect = lambda *_: 0
    assert power.saving_status() is None


def test_real_windows_status_when_available():
    if sys.platform == 'win32':
        assert power.saving_status() in (True, False, None)


def test_windows_plan_reads_guid_and_releases_native_memory(monkeypatch):
    import uuid
    memory = ctypes.create_string_buffer(uuid.UUID('a1841308-3541-4fab-bc81-f71556f20b4a').bytes_le)
    def active(_key, out):
        out._obj.value = ctypes.addressof(memory)
        return 0
    call, free = Mock(side_effect=active), Mock()
    monkeypatch.setattr(ctypes, 'WinDLL', lambda name, **kw: SimpleNamespace(PowerGetActiveScheme=call, LocalFree=free), raising=False)
    assert power.windows_plan_saving() is True
    assert free.call_count == 1
    call.side_effect = lambda *_: 1
    assert power.windows_plan_saving() is None
    assert free.call_count == 1
