#!/usr/bin/env python3
"""Real-adbd verification for deploy/watchdog/esp32 (ESP32 watchdog ADB client).

`test_esp32.py` talks to a mock ADB server that we wrote ourselves, so it can only
prove self-consistency. This script drives the *unmodified* client
(`adb_client.AdbClient`) and policy (`watchdog_policy`) against a real Android
`adbd` over traditional TCP ADB (``127.0.0.1:<emulator adb port>``) and reports
exactly what does and does not work.

It is repeatable: the key lives in ``--key-dir`` (default
``deploy/watchdog/esp32/local/``, which is gitignored) and is reused, so only the
first run has to tap the on-device "Allow USB debugging?" dialog
(``--auto-accept`` taps it after matching the RSA fingerprint).

Every check is reported as PASS / FAIL / N-A and dumped as JSON. Failures are
never hidden, and the as-shipped client is the thing under test: the three
blocking defects found in the first run (provision.py key export, a leaked CLSE
frame, an undersized receive buffer) are now regression checks against real
adbd, not worked around.

See docs/verification-esp32-emulator.md for the recorded runs.

Usage
-----
    python run_emulator_check.py --port 5557 --serial emulator-5556 \
        --adb "C:/Users/me/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
        --package com.studyontv.mobile.debug \
        --apk ../app/native/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk \
        --auto-accept --json /tmp/esp32-emulator.json

Exit code: 0 when no check failed, 1 when at least one check failed, 2 on a
setup error.
"""
import argparse
import base64
import hashlib
import json
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ESP32 = HERE.parent
sys.path.insert(0, str(ESP32))

from adb_client import AdbAuthorizationError, AdbClient, CLSE, OKAY, OPEN, WRTE  # noqa: E402
import watchdog_policy as policy  # noqa: E402

try:
    import provision
except Exception:  # pragma: no cover - provision is optional for the checks
    provision = None

FRAME_NAMES = {
    0x4E584E43: "CNXN", 0x48545541: "AUTH", 0x4E45504F: "OPEN",
    0x59414B4F: "OKAY", 0x45545257: "WRTE", 0x45534C43: "CLSE", 0x534C5453: "STLS",
}
DEFAULT_PACKAGE = "com.studyontv.mobile.debug"
DEFAULT_ACTIVITY = "MainActivity"
FOREGROUND_SERVICE_CLASS = "/com.studyontv.tv.GateService"


# --------------------------------------------------------------------------- key

def _der_numbers(pem_text):
    """(modulus, private_exponent) from a keygen PEM, via provision's DER helpers."""
    encoded = "".join(line.strip() for line in pem_text.splitlines() if not line.startswith("-----"))
    raw = base64.b64decode(encoded)
    tag, root, end = provision.tlv(raw)
    if tag != 0x30 or end != len(raw):
        raise ValueError("Expected a DER RSA key sequence")
    fields = provision.children(root)
    if len(fields) >= 3 and fields[1][0] == 0x30 and fields[2][0] == 0x04:
        tag, root, end = provision.tlv(fields[2][1])
        fields = provision.children(root)
    numbers = [int.from_bytes(value, "big") for _, value in fields]
    if len(numbers) != 9:
        raise ValueError("Expected an RSA private key")
    return numbers[1], numbers[3]


def export_key_compat(pem_text, pub_text):
    """Independent re-implementation of the key export, used for throwaway keys.

    ``provision.export_key`` now validates the real Android ``RSAPublicKey``
    layout; this duplicate keeps the check honest (two implementations must agree
    on the same 524-byte blob) and gives the negative control a key that does not
    come from the code under test. Real layout::

        [0:4]     = len(n) in 32-bit words (64)
        [4:8]     = n0inv (Montgomery constant, arbitrary)
        [8:264]   = n
        [264:520] = rr
        [520:524] = e
    """
    modulus, private = _der_numbers(pem_text)
    parts = pub_text.split()
    if not parts:
        raise ValueError("Missing ADB public key")
    blob = base64.b64decode(parts[0])
    if len(blob) != 524:
        raise ValueError(f"Expected a 524-byte Android ADB public key, got {len(blob)}")
    if int.from_bytes(blob[0:4], "little") != 64:
        raise ValueError("Unexpected RSAPublicKey len field (expected 64 words)")
    if int.from_bytes(blob[8:264], "little") != modulus:
        raise ValueError("Public/private ADB keys do not match")
    if int.from_bytes(blob[520:524], "little") not in (3, 65537):
        raise ValueError("Unexpected RSAPublicKey exponent")
    return {"n": format(modulus, "x"), "d": format(private, "x"), "public_key": pub_text.strip()}


def fingerprint(pub_text):
    return ":".join(f"{byte:02X}" for byte in hashlib.md5(base64.b64decode(pub_text.split()[0])).digest())


def load_key(adb, key_dir, new_key):
    """Generate (once) and load the dedicated board key. Returns (key, notes, shipped_error)."""
    key_dir = Path(key_dir).resolve()
    key_dir.mkdir(parents=True, exist_ok=True)
    private, public, auth = key_dir / "adbkey", key_dir / "adbkey.pub", key_dir / "auth.json"
    if new_key:
        for path in (private, public, auth):
            path.unlink(missing_ok=True)
    if not private.exists():
        subprocess.run([adb, "keygen", str(private)], check=True, capture_output=True, timeout=60)
    pem_text = private.read_text(encoding="ascii")
    pub_text = public.read_text(encoding="ascii")
    shipped_error, key = None, None
    if provision is not None:
        try:
            key = provision.export_key(pem_text, pub_text)
        except ValueError as error:
            shipped_error = str(error)
    if key is None:
        key = json.loads(auth.read_text(encoding="utf-8")) if auth.exists() else export_key_compat(pem_text, pub_text)
    auth.write_text(json.dumps(key) + "\n", encoding="utf-8")
    return key, key_dir, shipped_error


# ------------------------------------------------------------------------ clients

def _name(command):
    return FRAME_NAMES.get(command, hex(command))


class TracingClient(AdbClient):
    """Records the ADB handshake frames so the AUTH exchange can be reported."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.frames = []

    def _packet(self):
        command, first, second, data = super()._packet()
        self.frames.append(["<", _name(command), first, second, len(data)])
        return command, first, second, data

    def _send(self, command, first=0, second=0, data=b""):
        self.frames.append([">", _name(command), first, second, len(data)])
        return super()._send(command, first, second, data)


class CompatClient(AdbClient):
    """Host-side helper: fresh connection per shell and a large receive buffer.

    Still needed by this script for the *raw* probes (raw ``dumpsys power`` is
    68-110 KB, which the board must never buffer). The shipped client is now
    checked separately and must pass on its own.
    """

    def __init__(self, *args, **kwargs):
        kwargs.setdefault("max_output", 1 << 20)
        super().__init__(*args, **kwargs)
        self.commands = []

    def shell(self, command):
        self.commands.append(command)
        self.close()
        self.connect()
        try:
            return AdbClient.shell(self, command)
        finally:
            self.close()


# ------------------------------------------------------------------ host control

class Device:
    """Host-side adb channel. Only used for things the ESP32 cannot do itself:
    installing the APK and tapping the authorization dialog."""

    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial

    def run(self, *args, timeout=180):
        # The device prints non-ASCII text (e.g. Chinese UI labels); never let a
        # console code page decide whether a check crashes.
        return subprocess.run([self.adb, "-s", self.serial, *args],
                              capture_output=True, text=True, timeout=timeout,
                              encoding="utf-8", errors="replace")

    def shell(self, *args):
        return self.run("shell", *args).stdout

    def ui_xml(self):
        self.shell("uiautomator", "dump", "/sdcard/ui.xml")
        return self.shell("cat", "/sdcard/ui.xml")

    @staticmethod
    def bounds(xml, resource_id):
        match = re.search(rf'resource-id="{re.escape(resource_id)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if not match:
            match = re.search(rf'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="{re.escape(resource_id)}"', xml)
        if not match:
            return None
        x1, y1, x2, y2 = (int(value) for value in match.groups())
        return (x1 + x2) // 2, (y1 + y2) // 2

    def tap(self, point):
        self.shell("input", "tap", str(point[0]), str(point[1]))

    def dialog_fingerprint(self, xml):
        found = re.search(r"RSA key fingerprint is:&#10;([0-9A-F:]{47})", xml)
        return found.group(1) if found else None

    def dismiss_stale_dialog(self, timeout=10, fingerprint=None):
        """Cancel any authorization dialog left over from an earlier attempt.

        adbd shows one dialog at a time, so a stale one blocks the next attempt.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            xml = self.ui_xml()
            if "Allow USB debugging?" not in xml:
                time.sleep(1.5)
                continue
            shown = self.dialog_fingerprint(xml)
            if fingerprint and shown and shown != fingerprint:
                time.sleep(1.5)
                continue
            point = self.bounds(xml, "android:id/button2")
            if point:
                self.tap(point)
                time.sleep(1.0)
                return True
        return False

    def accept_dialog(self, expected, timeout=60):
        """Tap 'Always allow' + 'Allow' on the real authorization dialog.

        A dialog left over from an earlier (dismissed/failed) attempt carries a
        different RSA fingerprint and blocks ours, so it is cancelled first.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            xml = self.ui_xml()
            if "Allow USB debugging?" not in xml:
                time.sleep(1.5)
                continue
            shown = self.dialog_fingerprint(xml)
            if shown and shown != expected:
                point = self.bounds(xml, "android:id/button2")
                if point:
                    self.tap(point)
                    print(f"cancelled a stale dialog for a different key ({shown})", flush=True)
                    time.sleep(1.0)
                continue
            for resource in ("android:id/alwaysUse", "android:id/button1"):
                point = self.bounds(xml, resource)
                if point:
                    self.tap(point)
                    time.sleep(0.5)
            return "tapped"
        return "timeout"


# ------------------------------------------------------------------- check runner

class Report:
    def __init__(self):
        self.checks, self.notes, self.limitations = [], [], []

    def add(self, name, ok, detail, data=None):
        entry = {"check": name, "ok": ok, "detail": detail}
        if data is not None:
            entry["data"] = data
        self.checks.append(entry)
        print(f"[{'PASS' if ok is True else 'FAIL' if ok is False else 'N/A '}] {name}: {detail}", flush=True)
        return entry

    def summary(self):
        return {
            "passed": [c["check"] for c in self.checks if c["ok"] is True],
            "failed": [c["check"] for c in self.checks if c["ok"] is False],
            "unproven": [c["check"] for c in self.checks if c["ok"] is None],
            "checks": self.checks,
            "notes": self.notes,
            "limitations": self.limitations,
        }


def probe_trailing_frames(client, command="echo studyontv-probe", drain=1.0):
    """Replay one shell by hand and return the frames adbd keeps sending afterwards.

    Proves the cause of the old second-``shell()`` failure: after adbd closes the
    stream the client sends ``OKAY`` for the last ``WRTE``, adbd answers an OKAY
    for an already-closed stream with a second ``CLSE``, and that frame stays
    queued. ``shell()`` must discard it (unknown local id) instead of raising
    "ADB stream ID mismatch".
    """
    client.stream_id += 1
    local, remote, frames, output = client.stream_id, None, [], 0
    client._send(OPEN, local, 0, b"shell:" + command.encode() + b"\x00")
    while True:
        kind, first, second, data = client._packet()
        frames.append([_name(kind), first, second, len(data)])
        if kind == OKAY and remote is None:
            remote = first
        elif kind == WRTE and remote == first:
            output += len(data)
            client._send(OKAY, local, remote)
        elif kind == CLSE:
            frames.append([">", "CLSE", local, first, 0])
            client._send(CLSE, local, first)
            break
    trailing = []
    client.sock.settimeout(drain)
    try:
        while True:
            kind, first, second, data = client._packet()
            trailing.append([_name(kind), first, second, len(data)])
    except Exception:  # noqa: BLE001 - timeout == clean socket
        pass
    finally:
        client.sock.settimeout(client.timeout)
    return frames, trailing, output


def first_wakefulness(text):
    for line in text.splitlines():
        if "mWakefulness=" in line:
            return line.strip()
    return None


def handshake_text(frames):
    return " ".join(f"{f[1]}({f[3]},{f[4]})" for f in frames)


def run(args):
    report = Report()
    adb = args.adb or shutil.which("adb") or "adb"
    device = Device(adb, args.serial) if args.serial else None
    key, key_dir, shipped_export_error = load_key(adb, args.key_dir, args.new_key)
    pub_fingerprint = fingerprint(key["public_key"])
    report.notes.append(f"key dir {key_dir}, RSA fingerprint {pub_fingerprint}")

    # ---- 0. environment ---------------------------------------------------
    if device:
        secure = device.shell("getprop", "ro.adb.secure").strip()
        model = device.shell("getprop", "ro.product.model").strip()
        sdk = device.shell("getprop", "ro.build.version.sdk").strip()
        report.add("device", True, f"serial={args.serial} model={model} sdk={sdk} ro.adb.secure={secure}")
        if secure == "0":
            report.limitations.append("ro.adb.secure=0: adbd skipped RSA authentication, the signature path was NOT exercised.")

    # ---- 1. provision.py key export (as shipped) --------------------------
    if shipped_export_error:
        report.add("provision_export_key_as_shipped", False,
                   "provision.export_key() rejects a real `adb keygen` key: " + shipped_export_error
                   + " (real layout: len(n) at blob[0:4], n0inv at [4:8], n at [8:264])")
    else:
        # Cross-check against an independent implementation of the same layout.
        key_dir = Path(args.key_dir).resolve()
        compat = export_key_compat((key_dir / "adbkey").read_text(encoding="ascii"),
                                   (key_dir / "adbkey.pub").read_text(encoding="ascii"))
        same = compat["n"] == key["n"] and compat["d"] == key["d"]
        report.add("provision_export_key_as_shipped", same,
                   "provision.export_key() accepted the `adb keygen` key" +
                   ("" if same else "; it disagrees with the independent layout check"))

    if args.check_provision_cli:
        probe_dir = Path(args.key_dir).resolve() / "provision-probe"
        # 1) keygen + export from scratch (no network, no authorization needed).
        result = subprocess.run(
            [sys.executable, str(ESP32 / "provision.py"), "--adb", adb, "--output-dir", str(probe_dir)],
            capture_output=True, text=True, timeout=180)
        tail = (result.stderr or result.stdout).strip().splitlines()[-1:] or ["no output"]
        report.add("provision_export_cli", result.returncode == 0, f"exit={result.returncode} {tail[0]}",
                   {"stdout": result.stdout, "stderr": result.stderr})

    # ---- 2. handshake on real adbd ---------------------------------------
    prober = TracingClient(args.host, args.port, key, timeout=args.timeout)
    error = None
    try:
        prober.connect()
        first_frames = prober.frames
    except Exception as exception:  # noqa: BLE001 - reported verbatim
        error = f"{type(exception).__name__}: {exception}"
        first_frames = prober.frames
    finally:
        prober.close()

    if error and device and args.auto_accept:
        print("not authorized yet; waiting for the on-device dialog ...", flush=True)
        holder = {}

        def attempt():
            client = TracingClient(args.host, args.port, key, timeout=120)
            try:
                client.connect()
                holder["client"] = client
            except Exception as exception:  # noqa: BLE001
                holder["error"] = f"{type(exception).__name__}: {exception}"

        thread = threading.Thread(target=attempt, daemon=True)
        thread.start()
        tapped = device.accept_dialog(pub_fingerprint, timeout=args.authorize_timeout)
        report.notes.append(f"authorization dialog: {tapped}")
        if tapped != "tapped" and device:
            # adbd only shows one prompt at a time and keeps a dead request
            # "pending", in which case it logs 'prompt currently pending, skipping'
            # and no dialog ever appears. Rebooting the emulator clears that state.
            log = device.run("logcat", "-d", "-s", "adbd").stdout.strip().splitlines()[-6:]
            report.notes.append("adbd log tail: " + " | ".join(log))
            report.notes.append("hint: if adbd logs 'prompt currently pending, skipping', "
                                "reboot the emulator and run again")
        thread.join(timeout=20)
        if "client" in holder:
            first_frames = holder["client"].frames
            holder["client"].close()
            error = None
        for attempt in range(1, 4):
            if error is None:
                break
            # The emulator's ADB relay often never completes the connection that
            # raised the dialog; a fresh connection carries the now-trusted key.
            time.sleep(2 * attempt)
            prober = TracingClient(args.host, args.port, key, timeout=args.timeout)
            try:
                prober.connect()
                first_frames = prober.frames
                error = None
                report.notes.append(f"authorization took effect on retry #{attempt}")
            except Exception as exception:  # noqa: BLE001
                error = f"{type(exception).__name__}: {exception}"
            finally:
                prober.close()

    if error:
        report.add("connect", False, f"{args.host}:{args.port} handshake failed: {error}",
                   {"frames": first_frames})
        report.limitations.append("No authorized connection: the client/policy checks below could not run.")
        report.limitations.extend(scope_limitations())
        return report.summary()

    auth_kinds = [f[2] for f in first_frames if f[1] == "AUTH"]
    report.add("connect", True, f"CNXN accepted by real adbd ({handshake_text(first_frames)})",
               {"frames": first_frames})
    report.add("auth_signature", 2 in auth_kinds,
               "adbd sent AUTH TOKEN(1) and accepted AUTH SIGNATURE(2) (256-byte RSA block)"
               if 2 in auth_kinds else "adbd accepted the connection without a token challenge",
               {"auth_arg0": auth_kinds, "fingerprint": pub_fingerprint})

    # ---- 2b. provision.py --authorize (key is granted by now) -------------
    # It has to run after the authorization step above: before a human taps the
    # dialog, --authorize must fail *clearly* (exit 2 with an authorization
    # message) instead of hanging for the full 60 s timeout.
    if args.check_provision_cli:
        result = subprocess.run(
            [sys.executable, str(ESP32 / "provision.py"), "--adb", adb,
             "--output-dir", str(Path(args.key_dir).resolve()),
             "--authorize", f"{args.host}:{args.port}",
             "--authorize-wait", str(args.authorize_wait)],
            capture_output=True, text=True, timeout=180)
        tail = (result.stderr or result.stdout).strip().splitlines()[-1:] or ["no output"]
        report.add("provision_authorize_cli", result.returncode == 0,
                   f"exit={result.returncode} {tail[0]}",
                   {"argv": [str(ESP32 / "provision.py"), "--authorize", f"{args.host}:{args.port}"],
                    "stdout": result.stdout, "stderr": result.stderr})

    # ---- 3. negative control: an unknown key must be refused -------------
    if args.negative_control:
        throwaway_dir = Path(args.key_dir).resolve() / "throwaway"
        throwaway_dir.mkdir(parents=True, exist_ok=True)
        t_private = throwaway_dir / "adbkey"
        if not t_private.exists():
            subprocess.run([adb, "keygen", str(t_private)], check=True, capture_output=True, timeout=60)
        t_key = export_key_compat(t_private.read_text(encoding="ascii"),
                                 (throwaway_dir / "adbkey.pub").read_text(encoding="ascii"))
        probe = TracingClient(args.host, args.port, t_key, timeout=args.timeout,
                             authorize_wait=args.authorize_wait)
        error, raised = None, None
        try:
            # A real TV shows a prompt and waits for a human, so this must come
            # back as a clear authorization error instead of a bare timeout.
            probe.connect(retry_after_authorize=False)
            error = "an unrelated key was ACCEPTED - adbd did not verify the signature"
        except Exception as exception:  # noqa: BLE001
            raised = exception
            error = f"{type(exception).__name__}: {exception}"
        finally:
            frames = probe.frames
            probe.close()
        handshake_ok = any(frame[0] == "<" and frame[1] == "CNXN" for frame in frames)
        if device:
            device.dismiss_stale_dialog(fingerprint=fingerprint(t_key["public_key"]))
        mapped = isinstance(raised, AdbAuthorizationError)
        report.add("auth_negative_control", not handshake_ok and mapped,
                   f"unrelated key was refused, never reached CNXN ({error}); "
                   f"mapped to AdbAuthorizationError={mapped}",
                   {"frames": frames, "error": error, "mapped_authorization_error": mapped})

    # ---- 4. shell primitives, exactly as shipped -------------------------
    shipped = AdbClient(args.host, args.port, key, timeout=args.timeout)
    try:
        shipped.connect()
        probe = shipped.shell("echo studyontv-probe").strip()
        report.add("shell_echo_as_shipped", probe == "studyontv-probe", f"first shell() returned {probe!r}")
        second_error = None
        try:
            shipped.shell("getprop ro.product.model")
        except Exception as exception:  # noqa: BLE001
            second_error = f"{type(exception).__name__}: {exception}"
        report.add("shell_second_command_as_shipped", second_error is None,
                   "FAILED: " + second_error if second_error
                   else "two shells on one connection worked")
    except Exception as exception:  # noqa: BLE001
        report.add("shell_echo_as_shipped", False, f"{type(exception).__name__}: {exception}")
    finally:
        shipped.close()

    leak = AdbClient(args.host, args.port, key, timeout=args.timeout)
    try:
        leak.connect()
        frames, trailing, output = probe_trailing_frames(leak)
        # The extra frame is queued on the socket right now; the next shell() on
        # the *same* connection has to drop it and still work.
        recovered = leak.shell("echo after-leak").strip()
        report.add("shell_clse_frame_leak", recovered == "after-leak",
                   f"real adbd queued {len(trailing)} extra frame(s) after the closing ack; "
                   f"the next shell() on the same connection returned {recovered!r}",
                   {"frames": frames, "trailing": trailing, "output_bytes": output,
                    "next_shell": recovered})
    except Exception as exception:  # noqa: BLE001
        report.add("shell_clse_frame_leak", False,
                   f"the extra CLSE frame was not tolerated: {type(exception).__name__}: {exception}")
    finally:
        leak.close()

    # The product-level consequence: the ESP32 main loop calls exactly this.
    shipped = AdbClient(args.host, args.port, key, timeout=args.timeout)
    try:
        shipped.connect()
        result = policy.inspect(shipped, args.package)
        report.add("inspect_as_shipped", True,
                   f"inspect() succeeded on the unmodified client: status={result.get('status')}",
                   {"result": result})
    except Exception as exception:  # noqa: BLE001
        report.add("inspect_as_shipped", False,
                   f"inspect() on the unmodified client raises {type(exception).__name__}: {exception} "
                   "-> main.py reports 'connection_or_authorization_error' every cycle")
    finally:
        shipped.close()

    # Raw `dumpsys power` is 68-110 KB on modern Android, so the *policy* must
    # not fetch it whole: the command is filtered on the TV side and max_output
    # (48 KiB) stays a backstop for the board's memory budget.
    raw_power = ""
    prober = CompatClient(args.host, args.port, key, timeout=args.timeout)
    try:
        raw_power = prober.shell("dumpsys power")
    except Exception as exception:  # noqa: BLE001
        report.notes.append(f"raw `dumpsys power` probe failed: {type(exception).__name__}: {exception}")
    finally:
        prober.close()

    shipped = AdbClient(args.host, args.port, key, timeout=args.timeout)
    try:
        shipped.connect()
        power = shipped.shell(policy.POWER_COMMAND)
        ok = "mWakefulness=" in power or "DisplayPower" in power or "mScreenOn" in power
        report.add("dumpsys_power_as_shipped", ok,
                   f"{policy.POWER_COMMAND!r} -> {len(power)} bytes (raw `dumpsys power` is "
                   f"{len(raw_power)} bytes; default max_output={shipped.max_output}) "
                   f"{first_wakefulness(power)!r}",
                   {"command": policy.POWER_COMMAND, "bytes": len(power),
                    "raw_bytes": len(raw_power), "max_output": shipped.max_output, "output": power})
    except Exception as exception:  # noqa: BLE001
        report.add("dumpsys_power_as_shipped", False,
                   f"FAILED: {policy.POWER_COMMAND!r} does not work on the shipped client "
                   f"({type(exception).__name__}: {exception})")
    finally:
        shipped.close()

    # ---- 5. shell primitives through the compat wrapper -------------------
    compat = CompatClient(args.host, args.port, key, timeout=args.timeout)
    try:
        power = compat.shell("dumpsys power")
        wake = first_wakefulness(power)
        report.add("dumpsys_power_wakefulness", wake is not None,
                   f"{len(power)} bytes, {wake!r}", {"bytes": len(power), "line": wake})
        model = compat.shell("getprop ro.product.model").strip()
        report.add("shell_getprop", bool(model), f"ro.product.model={model!r}")
        pids = compat.shell("pidof " + args.package).strip()
        services = compat.shell("dumpsys activity services " + args.package)
        accessibility = compat.shell("settings get secure enabled_accessibility_services").strip()
        report.add("shell_pidof", True, f"pidof {args.package} -> {pids!r}")
        report.add("shell_activity_services", True,
                   f"{len(services)} bytes, GateService listed="
                   f"{(args.package + FOREGROUND_SERVICE_CLASS) in services}, isForeground=true present="
                   f"{'isForeground=true' in services}",
                   {"bytes": len(services)})
        report.add("shell_settings_accessibility", True,
                   f"enabled_accessibility_services -> {accessibility!r}")
    except Exception as exception:  # noqa: BLE001
        report.add("dumpsys_power_wakefulness", False, f"{type(exception).__name__}: {exception}")
    finally:
        compat.close()

    # ---- 6. install + launch the app under test --------------------------
    if args.apk:
        apk = Path(args.apk).resolve()
        if not apk.exists():
            report.add("install", False, f"APK not found: {apk}")
        elif device:
            result = device.run("install", "-r", "-t", str(apk), timeout=600)
            report.add("install", "Success" in result.stdout,
                       (result.stdout or result.stderr).strip().splitlines()[-1] if
                       (result.stdout or result.stderr).strip() else "no output")
    if device:
        device.shell("input", "keyevent", "224")  # KEYCODE_WAKEUP
        device.shell("am", "start", "-n", f"{args.package}/com.studyontv.tv.{args.activity}")
        deadline = time.time() + 30
        running = ""
        while time.time() < deadline:
            running = device.shell("pidof", args.package).strip()
            if running:
                break
            time.sleep(1)
        report.add("app_running", bool(running), f"pidof {args.package} -> {running!r}")

    # ---- 7. policy: read-only inspection ---------------------------------
    inspector = CompatClient(args.host, args.port, key, timeout=args.timeout)
    try:
        started = time.time()
        result = policy.inspect(inspector, args.package, read_only=True)
        state = result.get("state", {})
        ok = (result.get("ok") is True and result.get("status") == "diagnostic"
              and {"process", "service", "accessibility"} <= set(state))
        report.add("inspect_read_only", ok,
                   f"{time.time() - started:.1f}s status={result.get('status')} state={state}",
                   {"result": result, "commands": inspector.commands})
    except Exception as exception:  # noqa: BLE001
        report.add("inspect_read_only", False, f"{type(exception).__name__}: {exception}",
                   {"commands": inspector.commands})
    finally:
        inspector.close()

    # ---- 8. policy: recovery, then no repeated interruption --------------
    control = CompatClient(args.host, args.port, key, timeout=args.timeout)
    try:
        control.shell("input keyevent 224")
        control.shell("am force-stop " + args.package)
        time.sleep(2)
        stopped = control.shell("pidof " + args.package).strip()
        report.add("force_stop", stopped == "", f"pidof after am force-stop -> {stopped!r}")

        first = CompatClient(args.host, args.port, key, timeout=args.timeout)
        try:
            result = policy.inspect(first, args.package, repair_accessibility=False)
            starts = [c for c in first.commands if c.startswith("am start")]
        finally:
            first.close()
        deadline = time.time() + 20
        running = ""
        while time.time() < deadline:
            running = control.shell("pidof " + args.package).strip()
            if running:
                break
            time.sleep(1)
        report.add("recovery_start_application",
                   result.get("actions") == ["start_application"] and bool(starts) and bool(running),
                   f"actions={result.get('actions')} am_start={bool(starts)} pid={running!r}",
                   {"result": result, "commands": first.commands})

        # How long the app needs to expose the foreground GateService, i.e. how
        # long inspect() keeps reporting "service: false" after a cold start.
        started_at = time.time()
        visible_after = None
        while time.time() - started_at < args.service_wait:
            probe = CompatClient(args.host, args.port, key, timeout=args.timeout)
            try:
                listed = probe.shell("dumpsys activity services " + args.package)
            finally:
                probe.close()
            if (args.package + FOREGROUND_SERVICE_CLASS) in listed and "isForeground=true" in listed:
                visible_after = round(time.time() - started_at, 1)
                break
            time.sleep(1)
        report.add("service_foreground_delay", visible_after is not None,
                   f"GateService isForeground=true after {visible_after}s "
                   f"(<= {args.service_wait}s budget)" if visible_after is not None
                   else f"GateService never became a foreground service within {args.service_wait}s",
                   {"seconds": visible_after})

        for attempt in (2, 3):
            time.sleep(max(args.settle, (visible_after or 0) + 1))
            again = CompatClient(args.host, args.port, key, timeout=args.timeout)
            try:
                result = policy.inspect(again, args.package, repair_accessibility=False)
                starts = [c for c in again.commands if c.startswith("am start")]
            finally:
                again.close()
            report.add(f"recovery_no_repeat_{attempt}",
                       result.get("status") == "healthy" and not starts,
                       f"status={result.get('status')} am_start={bool(starts)} state={result.get('state')}",
                       {"result": result, "commands": again.commands})
    except Exception as exception:  # noqa: BLE001
        report.add("recovery_start_application", False, f"{type(exception).__name__}: {exception}")
    finally:
        control.close()

    # ---- 9. policy: sleeping guard ---------------------------------------
    sleeper = CompatClient(args.host, args.port, key, timeout=args.timeout)
    try:
        sleeper.shell("input keyevent 224")
        sleeper.shell("am force-stop " + args.package)
        time.sleep(1)
        sleeper.shell("input keyevent 223")  # KEYCODE_SLEEP
        awake_state = None
        for _ in range(15):
            time.sleep(1)
            probe = CompatClient(args.host, args.port, key, timeout=args.timeout)
            try:
                awake_state = policy.screen_awake(probe.shell("dumpsys power"))
            finally:
                probe.close()
            if awake_state is False:
                break
        if awake_state is not False:
            report.add("sleep_guard", None,
                       f"could not force the screen off (screen_awake={awake_state}) - not proven")
        else:
            check = CompatClient(args.host, args.port, key, timeout=args.timeout)
            try:
                started = time.time()
                result = policy.inspect(check, args.package, repair_accessibility=False)
                starts = [c for c in check.commands if c.startswith("am start")]
            finally:
                check.close()
            status = result.get("status")
            report.add("sleep_guard",
                       status in ("sleeping", "unknown_power") and not starts and not result.get("actions"),
                       f"{time.time() - started:.1f}s status={status} actions={result.get('actions')} "
                       f"am_start={bool(starts)} screen_awake={awake_state}",
                       {"result": result, "commands": check.commands})
    except Exception as exception:  # noqa: BLE001
        report.add("sleep_guard", False, f"{type(exception).__name__}: {exception}")
    finally:
        sleeper.close()
        wake = CompatClient(args.host, args.port, key, timeout=args.timeout)
        try:
            wake.shell("input keyevent 224")  # always leave the device awake
            time.sleep(2)
            restored = first_wakefulness(wake.shell("dumpsys power"))
        finally:
            wake.close()
        report.add("wake_restored", "Awake" in (restored or ""), f"after KEYCODE_WAKEUP: {restored!r}")

    # ---- 10. optional: accessibility repair write path -------------------
    if args.check_accessibility_repair:
        repair = CompatClient(args.host, args.port, key, timeout=args.timeout)
        try:
            before = repair.shell("settings get secure enabled_accessibility_services").strip()
            result = policy.inspect(repair, args.package, repair_accessibility=True)
            after = repair.shell("settings get secure enabled_accessibility_services").strip()
            expected = args.package + "/" + policy.SERVICE
            report.add("accessibility_repair",
                       "restore_accessibility" in (result.get("actions") or []) and expected in after,
                       f"before={before!r} actions={result.get('actions')} after={after!r}",
                       {"result": result, "commands": repair.commands})
            repair.shell("settings delete secure enabled_accessibility_services" if before == "null"
                         else "settings put secure enabled_accessibility_services '" + before + "'")
            repair.shell("settings put secure accessibility_enabled 0" if before == "null"
                         else "settings put secure accessibility_enabled 1")
            report.add("accessibility_restored", True,
                       "accessible components now: "
                       + repair.shell("settings get secure enabled_accessibility_services").strip())
        except Exception as exception:  # noqa: BLE001
            report.add("accessibility_repair", False, f"{type(exception).__name__}: {exception}")
        finally:
            repair.close()

    report.limitations.extend(scope_limitations())
    return report.summary()


def scope_limitations():
    return [
        "Only an AOSP x86_64 emulator image was used. Vendor TV firmware (custom dumpsys "
        "sections, adbd forks, permission quirks) is NOT covered, so every conclusion here is "
        "an AOSP conclusion.",
        "ESP32 hardware is NOT covered: no MicroPython run, no Wi-Fi/network stack behaviour, "
        "no ~80 KiB RAM limit, no hardware watchdog restart, no flash/OTA.",
        "The TV-side first-use authorization dialog was tapped by the host adb channel, which is "
        "what a human would do; the ESP32 board itself was not used to press it.",
        "Only `com.studyontv.mobile.debug` was exercised, and only one build of it.",
        "Raw `dumpsys power` is still bigger than the shipped max_output on purpose: the policy "
        "filters on the TV side, and the client refuses oversized output with a clear error "
        "instead of buffering ~110 KB on the board.",
    ]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True, help="traditional TCP ADB port, e.g. 5557")
    parser.add_argument("--serial", help="host adb serial for setup, e.g. emulator-5556")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--activity", default=DEFAULT_ACTIVITY,
                        help="activity name under com.studyontv.tv. that the policy starts")
    parser.add_argument("--adb", help="host adb binary (setup + keygen)")
    parser.add_argument("--key-dir", default=str(ESP32 / "local"),
                        help="key directory; default is the gitignored deploy/watchdog/esp32/local/")
    parser.add_argument("--new-key", action="store_true", help="discard the stored key and generate a new one")
    parser.add_argument("--apk", help="APK to install before the policy checks")
    parser.add_argument("--auto-accept", action="store_true", help="tap 'Allow USB debugging?' via adb")
    parser.add_argument("--negative-control", action="store_true", default=True,
                        help="also prove that an unrelated key is refused")
    parser.add_argument("--no-negative-control", dest="negative_control", action="store_false")
    parser.add_argument("--check-provision-cli", action="store_true", default=True)
    parser.add_argument("--no-provision-cli", dest="check_provision_cli", action="store_false")
    parser.add_argument("--check-accessibility-repair", action="store_true",
                        help="also exercise the accessibility write path, then restore the setting")
    parser.add_argument("--timeout", type=int, default=15, help="per ADB operation timeout (s)")
    parser.add_argument("--settle", type=float, default=10.0,
                        help="seconds to wait after recovery before expecting 'healthy'")
    parser.add_argument("--authorize-timeout", type=float, default=45.0,
                        help="seconds to wait for the on-device authorization dialog")
    parser.add_argument("--authorize-wait", type=float, default=5.0,
                        help="seconds the client waits for CNXN after offering its public key")
    parser.add_argument("--service-wait", type=float, default=40.0,
                        help="budget for GateService to become a foreground service")
    parser.add_argument("--json", help="also write the JSON summary here")
    args = parser.parse_args(argv)
    summary = run(args)
    text = json.dumps(summary, indent=2, ensure_ascii=False)
    if args.json:
        Path(args.json).write_text(text, encoding="utf-8")
        print(f"\nJSON written to {args.json}")
    print("\n" + text)
    return 0 if not summary["failed"] else 1


if __name__ == "__main__":
    sys.exit(main())
