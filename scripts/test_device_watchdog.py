import importlib.util
from pathlib import Path
import shlex
import subprocess
import sys
import unittest

spec = importlib.util.spec_from_file_location("device_watchdog", Path(__file__).with_name("device-watchdog.py"))
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class MockAdb:
    def __init__(self, awake="Awake", process=True, service=True, accessibility="null", authorized=True):
        self.awake, self.process, self.service = awake, process, service
        self.accessibility, self.authorized = accessibility, authorized
        self.calls = []

    def __call__(self, command, **kwargs):
        self.calls.append(command)
        if command[1:2] == ["connect"]:
            return subprocess.CompletedProcess(command, 0, "connected", "")
        args = command[3:]
        if args == ["get-state"]:
            return subprocess.CompletedProcess(command, 0, "device" if self.authorized else "unauthorized", "")
        self.assert_shell(args)
        shell = shlex.split(args[1])
        if shell == ["dumpsys", "power"]:
            output = "mWakefulness=" + self.awake
        elif shell[:1] == ["pidof"]:
            output = "1234" if self.process else ""
        elif shell == ["ps"]:
            output = ""
        elif shell[:3] == ["dumpsys", "activity", "services"]:
            target_pkg = shell[3] if len(shell) > 3 else "com.studyontv.tv"
            output = f"ServiceRecord{{123 u0 {target_pkg}/com.studyontv.tv.GateService}}\n isForeground=true\n" if self.service else ""
        elif shell[:4] == ["settings", "get", "secure", "enabled_accessibility_services"]:
            output = self.accessibility
        elif shell[:3] == ["settings", "put", "secure"]:
            output = ""
        elif shell[:2] == ["am", "start"]:
            output = "Starting: Intent"
        else:
            raise AssertionError(shell)
        return subprocess.CompletedProcess(command, 0, output, "")

    @staticmethod
    def assert_shell(args):
        assert args[0] == "shell" and len(args) == 2, args

    def mutations(self):
        return [shlex.split(call[4]) for call in self.calls if len(call) == 5 and call[3] == "shell"
                and shlex.split(call[4])[:2] in (["am", "start"], ["settings", "put"])]


class WatchdogTest(unittest.TestCase):
    def watchdog(self, mock, package="com.studyontv.tv"):
        return module.Watchdog("emulator-5554", package, runner=mock)

    def test_healthy_is_read_only(self):
        mock = MockAdb()
        result = self.watchdog(mock).ensure()
        self.assertEqual(result["actions"], [])
        self.assertEqual(mock.mutations(), [])

    def test_missing_service_recovers_even_when_process_exists(self):
        mock = MockAdb(service=False)
        result = self.watchdog(mock).ensure()
        self.assertEqual(result["actions"], ["start_application"])
        self.assertEqual(len(mock.mutations()), 1)

    def test_sleeping_or_unknown_never_launches_or_changes_permissions(self):
        for power in ["Asleep", "Dozing", "unknown"]:
            mock = MockAdb(awake=power, process=False, service=False)
            result = self.watchdog(mock).ensure(True)
            self.assertEqual(result["actions"], [])
            self.assertEqual(mock.mutations(), [])

    def test_repair_preserves_other_services_and_drops_null(self):
        mock = MockAdb(accessibility="null:other.app/.Guard:other.app/.Guard")
        result = self.watchdog(mock).ensure(True)
        self.assertEqual(result["actions"], ["restore_accessibility"])
        written = mock.mutations()[0][-1]
        self.assertEqual(written, "other.app/.Guard:com.studyontv.tv/com.studyontv.tv.WatchAccessibilityService")

    def test_abbreviated_existing_component_is_not_duplicated(self):
        mock = MockAdb(accessibility="com.studyontv.tv/.WatchAccessibilityService")
        self.assertEqual(self.watchdog(mock).ensure(True)["actions"], [])

    def test_mobile_and_debug_accessibility_components(self):
        # Mobile flavor uses applicationId com.studyontv.mobile with service class in com.studyontv.tv namespace
        for enabled in [
            "com.studyontv.mobile/com.studyontv.tv.WatchAccessibilityService",
            "com.studyontv.mobile/.WatchAccessibilityService",
            "com.studyontv.mobile/com.studyontv.mobile.WatchAccessibilityService",
        ]:
            self.assertTrue(module.own_accessibility(enabled, "com.studyontv.mobile"), enabled)
            self.assertFalse(module.own_accessibility(enabled, "com.studyontv.tv"), enabled)
            mock = MockAdb(accessibility=enabled)
            result = self.watchdog(mock, package="com.studyontv.mobile").ensure(True)
            self.assertTrue(result["state"]["accessibility_enabled"])
            self.assertEqual(result["actions"], [])
            self.assertEqual(mock.mutations(), [])

        # Debug flavors work for both full class and shorthand
        for pkg in ["com.studyontv.tv.debug", "com.studyontv.mobile.debug"]:
            self.assertTrue(module.own_accessibility(f"{pkg}/com.studyontv.tv.WatchAccessibilityService", pkg))
            self.assertTrue(module.own_accessibility(f"{pkg}/.WatchAccessibilityService", pkg))

        # Cross-flavor isolation: TV service enabled does not satisfy Mobile watchdog, and repair preserves TV entry
        mock = MockAdb(accessibility="com.studyontv.tv/.WatchAccessibilityService")
        result = self.watchdog(mock, package="com.studyontv.mobile").ensure(True)
        self.assertFalse(result["state"]["accessibility_enabled"])
        self.assertEqual(result["actions"], ["restore_accessibility"])
        written = mock.mutations()[0][-1]
        self.assertEqual(
            written,
            "com.studyontv.tv/.WatchAccessibilityService:com.studyontv.mobile/com.studyontv.tv.WatchAccessibilityService",
        )

    def test_nested_component_shell_quoting(self):
        mock = MockAdb(accessibility="other.app/other.app.Service$Inner")
        self.watchdog(mock).ensure(True)
        self.assertIn("$Inner", mock.mutations()[0][-1])
        command = [call[4] for call in mock.calls if len(call) == 5 and "settings put" in call[4]][0]
        self.assertIn("'other.app/other.app.Service$Inner:", command)

    def test_unauthorized_device_rejected(self):
        mock = MockAdb(authorized=False)
        with self.assertRaises(module.AdbError):
            self.watchdog(mock).ensure(True)
        self.assertEqual(mock.mutations(), [])

    def test_invalid_serial_or_package(self):
        for serial, package in [("a; reboot", "com.studyontv.tv"), ("--help", "com.studyontv.tv"), ("emulator-5554", "other.app")]:
            with self.assertRaises(ValueError):
                module.Watchdog(serial, package)

    def test_old_power_formats(self):
        self.assertTrue(module.wakefulness("Display Power: state=ON"))
        self.assertFalse(module.wakefulness("mScreenOn=false"))
        self.assertIsNone(module.wakefulness("unsupported"))


class MainLoopTest(unittest.TestCase):
    def test_log_file_and_stdout(self):
        import contextlib
        import io
        import json
        import tempfile

        class FakeWatchdog:
            def __init__(self, *args, **kwargs):
                self.calls = 0

            def status(self):
                return module.DeviceState("serial", "com.studyontv.tv", True, True, True, True, "x")

            def ensure(self, repair=False):
                return {"ok": True, "actions": [], "state": {}}

        original = module.Watchdog
        module.Watchdog = FakeWatchdog
        try:
            with tempfile.TemporaryDirectory() as directory:
                log = Path(directory) / "nested" / "watchdog.log"
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    code = module.main(["status", "--serial", "serial", "--log-file", str(log)])
                self.assertEqual(code, 0)
                lines = log.read_text(encoding="utf-8").strip().splitlines()
                self.assertEqual(len(lines), 1)
                self.assertTrue(json.loads(lines[0])["ok"])
                self.assertEqual(json.loads(output.getvalue()) ["state"]["process"], True)
        finally:
            module.Watchdog = original

    def test_invalid_serial_is_a_parser_error(self):
        import contextlib
        import io
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                module.main(["status", "--serial", "--help"])
        self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
