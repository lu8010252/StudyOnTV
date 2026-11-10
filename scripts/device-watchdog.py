#!/usr/bin/env python3
"""StudyOnTV external ADB watchdog, using only Python's standard library.

status is read-only; ensure repairs once; watch repeats ensure. Requires an
already authorized ADB connection. Does not enable ADB, reboot, force-stop apps,
change manufacturer settings, or modify a host's scheduled tasks.
"""
from __future__ import annotations

import argparse
import json
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from typing import Callable

PACKAGES = {"com.studyontv.tv", "com.studyontv.mobile", "com.studyontv.tv.debug", "com.studyontv.mobile.debug"}
SERVICE = "com.studyontv.tv.WatchAccessibilityService"


class AdbError(RuntimeError):
    pass


@dataclass
class DeviceState:
    serial: str
    package: str
    awake: bool | None
    process: bool
    service: bool
    accessibility_enabled: bool
    accessibility_services: str


def accessibility_list(raw: str) -> list[str]:
    return list(dict.fromkeys(item.strip() for item in raw.strip().split(":")
                              if item.strip() and item.strip().lower() != "null"))


def own_accessibility(component: str, package: str) -> bool:
    if "/" not in component:
        return False
    owner, cls = component.split("/", 1)
    full = owner + cls if cls.startswith(".") else cls
    return owner == package and full == SERVICE


def wakefulness(raw: str) -> bool | None:
    match = re.search(r"mWakefulness\s*=\s*(Awake|Dreaming|Asleep|Dozing)", raw, re.I)
    if match:
        return match.group(1).lower() in {"awake", "dreaming"}
    match = re.search(r"(?:Display Power:\s*state|mScreenState)\s*=\s*(ON|OFF|DOZE)", raw, re.I)
    if match:
        return match.group(1).upper() == "ON"
    match = re.search(r"mScreenOn\s*=\s*(true|false)", raw, re.I)
    return match.group(1).lower() == "true" if match else None


class Watchdog:
    def __init__(self, serial: str, package: str, adb: str = "adb", timeout: float = 12,
                 runner: Callable[..., subprocess.CompletedProcess] = subprocess.run):
        if package not in PACKAGES:
            raise ValueError("Only official StudyOnTV package names are supported")
        if not serial or serial.startswith("-") or not re.fullmatch(r"[A-Za-z0-9_.:\[\]-]+", serial):
            raise ValueError("Invalid ADB serial")
        self.serial, self.package, self.adb, self.timeout, self.runner = serial, package, adb, timeout, runner

    def adb_call(self, *args: str, optional: bool = False) -> str:
        try:
            command = [self.adb, "-s", self.serial, *args]
            if args and args[0] == "shell":
                command = [self.adb, "-s", self.serial, "shell", shlex.join(args[1:])]
            result = self.runner(command, capture_output=True,
                                 text=True, encoding="utf-8", errors="replace", timeout=self.timeout)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise AdbError("ADB unavailable or timed out; check the authorized device connection") from error
        if result.returncode and not optional:
            raise AdbError("ADB command failed; check device authorization and connection")
        return result.stdout.strip() if result.returncode == 0 else ""

    def connect(self) -> None:
        # TCP reconnect is local to this target; never kill a shared ADB server.
        if re.fullmatch(r"[A-Za-z0-9_.-]+:\d+", self.serial):
            try:
                self.runner([self.adb, "connect", self.serial], capture_output=True,
                            text=True, timeout=self.timeout)
            except (OSError, subprocess.TimeoutExpired) as error:
                raise AdbError("Cannot reconnect to device") from error
        if self.adb_call("get-state") != "device":
            raise AdbError("Device is offline or has not authorized ADB")

    def status(self) -> DeviceState:
        self.connect()
        power = self.adb_call("shell", "dumpsys", "power")
        pid = self.adb_call("shell", "pidof", self.package, optional=True)
        if not pid:
            # Older TVs lack pidof. Match the exact package, not similarly named processes.
            processes = self.adb_call("shell", "ps", optional=True)
            running = any(line.split() and line.split()[-1] == self.package for line in processes.splitlines())
        else:
            running = bool(re.fullmatch(r"\d+(?:\s+\d+)*", pid))
        services = self.adb_call("shell", "dumpsys", "activity", "services", self.package)
        record = re.search(r"ServiceRecord\{[^\n]*" + re.escape(self.package) + r"/(?:\.GateService|com\.studyontv\.tv\.GateService)\b", services)
        service_block = ""
        if record:
            tail = services[record.end():]
            service_block = tail.split("ServiceRecord{", 1)[0]
        foreground = bool(re.search(r"isForeground\s*=\s*true", service_block))
        raw = self.adb_call("shell", "settings", "get", "secure", "enabled_accessibility_services")
        components = accessibility_list(raw)
        return DeviceState(self.serial, self.package, wakefulness(power), running,
                           foreground and running, any(own_accessibility(item, self.package) for item in components),
                           ":".join(components))

    def ensure(self, repair_accessibility: bool = False) -> dict:
        state = self.status()
        actions: list[str] = []
        if state.awake is not True:
            return {"ok": True, "state": asdict(state), "actions": actions,
                    "note": "sleeping" if state.awake is False else "unknown screen state; no recovery attempted"}
        if repair_accessibility and not state.accessibility_enabled:
            # Re-read immediately before updating; preserve other services and remove null entries.
            current = accessibility_list(self.adb_call("shell", "settings", "get", "secure", "enabled_accessibility_services"))
            if not any(own_accessibility(item, self.package) for item in current):
                current.append(self.package + "/" + SERVICE)
                value = ":".join(current)
                if not re.fullmatch(r"[A-Za-z0-9_.$/:]+", value):
                    raise AdbError("Unexpected accessibility setting; refusing to overwrite it")
                self.adb_call("shell", "settings", "put", "secure", "enabled_accessibility_services", value)
                self.adb_call("shell", "settings", "put", "secure", "accessibility_enabled", "1")
                actions.append("restore_accessibility")
        if not state.process or not state.service:
            response = self.adb_call("shell", "am", "start", "-n", self.package + "/com.studyontv.tv.MainActivity")
            if re.search(r"(?:Error:|Permission Denial|Exception)", response, re.I):
                raise AdbError("System rejected the recovery activity")
            actions.append("start_application")
        # Next polling cycle verifies service health; no forced restarts or global ADB changes.
        return {"ok": True, "state": asdict(state), "actions": actions,
                "note": "recovery requested; next check verifies state" if actions else "healthy"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["status", "ensure", "watch"])
    parser.add_argument("--serial", required=True, help="authorized ADB serial, e.g. 192.168.1.20:5555")
    parser.add_argument("--package", default="com.studyontv.tv", choices=sorted(PACKAGES))
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--interval", type=int, default=30, help="watch interval in seconds, minimum 10")
    parser.add_argument("--log-file", help="optional rotating JSON log (1 MiB, two backups)")
    parser.add_argument("--repair-accessibility", action="store_true", help="also restore this app's missing accessibility entry")
    args = parser.parse_args(argv)
    if args.interval < 10:
        parser.error("--interval must be at least 10 seconds")
    try:
        watchdog = Watchdog(args.serial, args.package, args.adb)
    except ValueError as error:
        parser.error(str(error))
    log_handler = None
    if args.log_file:
        try:
            Path(args.log_file).parent.mkdir(parents=True, exist_ok=True)
            log_handler = RotatingFileHandler(args.log_file, maxBytes=1024 * 1024, backupCount=2, encoding="utf-8")
        except OSError as error:
            parser.error("Cannot create log file: " + str(error))

    def emit(result: dict) -> None:
        line = json.dumps(result, ensure_ascii=False)
        print(line, flush=True)
        if log_handler:
            log_handler.emit(logging.LogRecord("studyontv.watchdog", logging.INFO, __file__, 0, line, (), None))

    try:
        while True:
            try:
                result = {"ok": True, "state": asdict(watchdog.status())} if args.command == "status" else watchdog.ensure(args.repair_accessibility)
                emit(result)
                if args.command != "watch":
                    return 0
            except (AdbError, ValueError) as error:
                emit({"ok": False, "error": str(error)})
                if args.command != "watch":
                    return 2
            time.sleep(args.interval)
    except KeyboardInterrupt:
        return 0
    finally:
        if log_handler:
            log_handler.close()


if __name__ == "__main__":
    sys.exit(main())
