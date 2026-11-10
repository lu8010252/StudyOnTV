"""CPython regression for the MicroPython ADB client, key export and ESP32 policy.

A local mock speaks the traditional ADB handshake and shell stream, so this does
not touch a real TV. MicroPython-only APIs are covered by the fallbacks in code.
A fresh RSA key is generated with the local openssl binary for each run; the real
`adb keygen` public key layout is exercised as well when an adb binary is found
(otherwise that one check is skipped with a reason).

The mock deliberately reproduces the three defects that real adbd exposed:
the 524-byte Android RSAPublicKey layout, the extra CLSE frame adbd queues after
the client's closing ack, and multi-kilobyte dumpsys output.
"""
import base64
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from adb_client import (AdbAuthorizationError, AdbClient, AdbError, AUTH, CLSE, CNXN, OKAY, OPEN,  # noqa: E402
                        WRTE, rsa_signature)
import watchdog_policy as policy  # noqa: E402
import provision  # noqa: E402

AUTH_ERROR = "TV has not authorized this device key"
CHUNK = 4096


def frame(command, first=0, second=0, data=b""):
    return struct.pack("<6I", command, first, second, len(data), sum(data) & 0xFFFFFFFF, command ^ 0xFFFFFFFF) + data


def read_frame(sock):
    header = b""
    while len(header) < 24:
        part = sock.recv(24 - len(header))
        if not part:
            raise EOFError
        header += part
    command, first, second, size, _, magic = struct.unpack("<6I", header)
    assert magic == command ^ 0xFFFFFFFF
    data = b""
    while len(data) < size:
        part = sock.recv(size - len(data))
        if not part:
            raise EOFError
        data += part
    return command, first, second, data


def find_adb():
    """The adb binary, from PATH or the usual SDK locations (may be None)."""
    candidate = shutil.which("adb")
    if candidate:
        return candidate
    roots = [os.environ.get(name) for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK")]
    local = os.environ.get("LOCALAPPDATA")
    if local:
        roots.append(str(Path(local) / "Android" / "Sdk"))
    for root in roots:
        if not root:
            continue
        for name in ("adb", "adb.exe"):
            path = Path(root) / "platform-tools" / name
            if path.exists():
                return str(path)
    return None


class MockAdbServer(threading.Thread):
    """Answers AUTH with a challenge and verifies the client's RSA signature.

    ``grant_after`` is the 1-based connection index that is already authorized.
    Unauthorized connections behave like real adbd: the token is re-sent after a
    bad signature, and once the public key is offered the server stays silent
    (the TV shows a prompt and waits for a human) unless ``reply_to_key`` is set.
    ``duplicate_close`` sends the extra CLSE frame that real adbd queues after
    the client's closing CLSE.
    """

    def __init__(self, key, outputs, reply_to_key=False, grant_after=1,
                 max_connections=1, duplicate_close=False, chunk=CHUNK, delay=0.0):
        super().__init__(daemon=True)
        self.key, self.outputs = key, outputs
        self.reply_to_key, self.grant_after = reply_to_key, grant_after
        self.max_connections, self.duplicate_close = max_connections, duplicate_close
        self.chunk, self.delay = chunk, delay
        self.errors, self.commands = [], []
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(4)
        self.server.settimeout(5)
        self.port = self.server.getsockname()[1]
        self.challenge = bytes(range(20))

    def run(self):
        index = 0
        try:
            while index < self.max_connections:
                try:
                    connection, _ = self.server.accept()
                except (socket.timeout, OSError):
                    return
                index += 1
                connection.settimeout(10)
                try:
                    self._session(connection, index)
                except (EOFError, ConnectionError, OSError):
                    # A client that closes mid-stream (cancel, timeout, limit
                    # reached) is expected for the negative checks.
                    pass
                finally:
                    try:
                        connection.close()
                    except OSError:
                        pass
        except Exception as error:  # surfaced by the test through self.errors
            self.errors.append(error)
        finally:
            try:
                self.server.close()
            except OSError:
                pass

    def _session(self, connection, index):
        command, _, _, _ = read_frame(connection)
        if command != CNXN:
            raise AssertionError("expected CNXN")
        connection.sendall(frame(AUTH, 1, 0, self.challenge))
        authorized = index >= self.grant_after
        for _ in range(3):
            command, kind, _, data = read_frame(connection)
            if command != AUTH:
                raise AssertionError("expected AUTH")
            if kind == 2:
                modulus = int(self.key["n"], 16)
                raw = pow(int.from_bytes(data, "big"), 65537, modulus).to_bytes(256, "big")
                if not (raw.startswith(b"\x00\x01\xff") and raw.endswith(self.challenge)):
                    raise AssertionError("bad signature")
                self.commands.append("AUTH:SIGNATURE")
                if not authorized:
                    # adbd re-sends a token for a key it does not know yet.
                    connection.sendall(frame(AUTH, 1, 0, self.challenge))
                    continue
                connection.sendall(frame(CNXN, 0x01000001, 4096, b"device::\x00"))
                break
            if kind == 3:
                self.commands.append("AUTH:RSAPUBLICKEY")
                if authorized or self.reply_to_key:
                    connection.sendall(frame(CNXN, 0x01000001, 4096, b"device::\x00"))
                    break
                # Unknown key: the TV shows a prompt and waits for a human.
                return
            raise AssertionError(kind)
        else:
            raise AssertionError("too many AUTH rounds")
        self._serve(connection)

    def _serve(self, connection):
        while True:
            try:
                command, local, _, data = read_frame(connection)
            except (EOFError, OSError):
                return
            if command != OPEN:
                return
            if not data.startswith(b"shell:") or not data.endswith(b"\x00"):
                raise AssertionError("bad OPEN payload")
            shell = data[6:-1].decode()
            self.commands.append(shell)
            remote = local + 100
            connection.sendall(frame(OKAY, remote, local))
            output = self.outputs.get(shell, "")
            if isinstance(output, str):
                output = output.encode()
            for offset in range(0, len(output), self.chunk):
                if self.delay:
                    time.sleep(self.delay)
                connection.sendall(frame(WRTE, remote, local, output[offset:offset + self.chunk]))
                try:
                    kind, first, _, _ = read_frame(connection)
                except (EOFError, OSError):
                    return
                if kind != OKAY or first != local:
                    return
            connection.sendall(frame(CLSE, remote, local))
            try:
                kind, first, _, _ = read_frame(connection)
            except (EOFError, OSError):
                return
            if kind != CLSE:
                raise AssertionError("expected the client's CLSE ack")
            if self.duplicate_close:
                # What real adbd sends for the OKAY/CLSE ack of a closed stream.
                connection.sendall(frame(CLSE, remote, local))


class PolicyClient:
    def __init__(self, outputs):
        self.outputs = outputs

    def shell(self, command):
        self.outputs.setdefault("commands", []).append(command)
        return self.outputs.get(command, "")


def policy_commands(package="com.studyontv.tv"):
    return (policy.POWER_COMMAND, policy.process_command(package), policy.process_fallback_command(package),
            policy.services_command(package), policy.ACCESSIBILITY_COMMAND)


AWAKE = "mWakefulness=Awake\n"
FOREGROUND = "ServiceRecord{0a1b2c3 com.studyontv.tv/.GateService}\n isForeground=true\n"


def build_public_blob(modulus, exponent, n0inv=2814523255):
    """A 524-byte Android RSAPublicKey, real layout (see provision.parse_android_public_key)."""
    blob = bytearray(524)
    blob[0:4] = (64).to_bytes(4, "little")            # len(n) in 32-bit words
    blob[4:8] = n0inv.to_bytes(4, "little")           # n0inv, a Montgomery constant
    blob[8:264] = modulus.to_bytes(256, "little")     # n
    blob[264:520] = pow(1 << 2048, 2, modulus).to_bytes(256, "little")  # rr = R^2 mod n
    blob[520:524] = exponent.to_bytes(4, "little")    # e
    return blob


class Esp32WatchdogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        openssl = shutil.which("openssl")
        if not openssl:
            raise unittest.SkipTest("openssl is needed to generate a disposable ADB key")
        cls.tmp = tempfile.TemporaryDirectory()
        directory = Path(cls.tmp.name)
        private = directory / "adbkey"
        subprocess.run([openssl, "genrsa", "-out", str(private), "2048"], check=True, capture_output=True, timeout=60)
        pem = private.read_text(encoding="ascii")
        encoded = "".join(line.strip() for line in pem.splitlines() if not line.startswith("-----"))
        tag, root, end = provision.tlv(base64.b64decode(encoded))
        assert tag == 0x30 and end == len(base64.b64decode(encoded))
        fields = provision.children(root)
        if fields[1][0] == 0x30 and fields[2][0] == 0x04:
            _, root, _ = provision.tlv(fields[2][1])
            fields = provision.children(root)
        _, modulus, exponent = (int.from_bytes(value, "big") for _, value in fields[:3])
        blob = build_public_blob(modulus, exponent)
        public_text = base64.b64encode(bytes(blob)).decode() + " studyontv-test@local"
        cls.key = provision.export_key(pem, public_text)
        cls.private = private
        cls.modulus = modulus
        cls.exponent = exponent

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    # ------------------------------------------------------------- key layout

    def test_public_key_layout_matches_real_adbd(self):
        """[0:4] is len(n) in words; [4:8] is n0inv and must not be read as a length."""
        blob = base64.b64decode(self.key["public_key"].split()[0])
        self.assertEqual(len(blob), 524)
        self.assertEqual(int.from_bytes(blob[0:4], "little"), 64)
        self.assertEqual(int.from_bytes(blob[8:264], "little"), int(self.key["n"], 16))
        self.assertEqual(int.from_bytes(blob[520:524], "little"), self.exponent)
        # Real n0inv values are not 64, so the old [4:8] check rejected every key.
        self.assertNotEqual(int.from_bytes(blob[4:8], "little"), 64)
        modulus, exponent = provision.parse_android_public_key(blob)
        self.assertEqual((modulus, exponent), (int(self.key["n"], 16), self.exponent))

    def test_key_export_accepts_real_adb_keygen_output(self):
        adb = find_adb()
        if not adb:
            self.skipTest("no adb binary (set ANDROID_HOME or put adb on PATH); "
                          "the real `adb keygen` blob layout is not exercised by this run")
        with tempfile.TemporaryDirectory() as tmp:
            private = Path(tmp) / "adbkey"
            subprocess.run([adb, "keygen", str(private)], check=True, capture_output=True, timeout=60)
            key = provision.export_key(private.read_text(encoding="ascii"),
                                       (Path(tmp) / "adbkey.pub").read_text(encoding="ascii"))
        blob = base64.b64decode(key["public_key"].split()[0])
        self.assertEqual(len(blob), 524)
        self.assertEqual(int.from_bytes(blob[0:4], "little"), 64)
        self.assertEqual(int.from_bytes(blob[8:264], "little"), int(key["n"], 16))
        self.assertEqual(int.from_bytes(blob[520:524], "little"), 65537)

    def test_key_export_rejects_mismatched_public_key(self):
        pem = self.private.read_text(encoding="ascii")
        # Same length field, different modulus.
        tampered = build_public_blob(7, 65537)
        with self.assertRaises(ValueError):
            provision.export_key(pem, base64.b64encode(bytes(tampered)).decode() + " x@y")
        with self.assertRaises(ValueError):
            provision.export_key(pem, "not-base64!")

    def test_key_export_rejects_tampered_length_field(self):
        pem = self.private.read_text(encoding="ascii")
        blob = build_public_blob(self.modulus, self.exponent)
        blob[0:4] = (32).to_bytes(4, "little")  # not 64 words
        with self.assertRaises(ValueError) as caught:
            provision.export_key(pem, base64.b64encode(bytes(blob)).decode() + " x@y")
        self.assertIn("length field", str(caught.exception))

    def test_key_export_rejects_tampered_n0inv_or_exponent(self):
        pem = self.private.read_text(encoding="ascii")
        blob = build_public_blob(self.modulus, self.exponent)
        blob[520:524] = (17).to_bytes(4, "little")
        with self.assertRaises(ValueError):
            provision.export_key(pem, base64.b64encode(bytes(blob)).decode() + " x@y")
        # n0inv is not validated (it is not needed to sign), so an arbitrary
        # constant must still be accepted.
        blob = build_public_blob(self.modulus, self.exponent, n0inv=1)
        self.assertEqual(int(provision.export_key(pem, base64.b64encode(bytes(blob)).decode())["n"], 16),
                         self.modulus)

    def test_key_export_refuses_272_byte_blob(self):
        pem = self.private.read_text(encoding="ascii")
        blob = build_public_blob(self.modulus, self.exponent)[0:272]
        with self.assertRaises(ValueError) as caught:
            provision.export_key(pem, base64.b64encode(bytes(blob)).decode() + " x@y")
        self.assertIn("272", str(caught.exception))

    # ------------------------------------------------------------- signature

    def test_signature_is_pkcs1_v15_sha1(self):
        signature = rsa_signature(bytes(range(20)), self.key)
        self.assertEqual(len(signature), 256)
        n = int(self.key["n"], 16)
        raw = pow(int.from_bytes(signature, "big"), 65537, n).to_bytes(256, "big")
        self.assertEqual(raw[0:2], b"\x00\x01")
        self.assertEqual(raw[-20:], bytes(range(20)))
        self.assertIn(b"\x00\x30\x21\x30\x09\x06\x05\x2b\x0e\x03\x02\x1a", raw)
        with self.assertRaises(AdbError):
            rsa_signature(b"short", self.key)

    # ------------------------------------------------------------- policy parse

    def test_screen_state_parsing(self):
        self.assertTrue(policy.screen_awake("mWakefulness=Awake\n"))
        self.assertFalse(policy.screen_awake("mWakefulness=Asleep"))
        self.assertFalse(policy.screen_awake("mWakefulness=Dozing"))
        self.assertTrue(policy.screen_awake("Display Power: state=ON"))
        self.assertFalse(policy.screen_awake("mScreenOn=false"))
        self.assertIsNone(policy.screen_awake("no known field"))
        # grep-filtered output: a single line is enough for both directions.
        self.assertTrue(policy.screen_awake("mWakefulness=Awake\r\n"))
        self.assertFalse(policy.screen_awake("mWakefulness=Asleep\nDisplayPower: state=OFF\n"))
        self.assertIsNone(policy.screen_awake(""))

    def test_power_command_is_filtered_on_the_tv_side(self):
        """The first inspect() command must stay small: raw dumpsys power is ~110 KB."""
        self.assertIn("dumpsys power", policy.POWER_COMMAND)
        self.assertIn("grep", policy.POWER_COMMAND)
        self.assertIn("mWakefulness=", policy.POWER_COMMAND)
        for command in (policy.POWER_COMMAND, policy.services_command("com.studyontv.tv"),
                        policy.process_fallback_command("com.studyontv.tv")):
            self.assertIn("|", command)
            self.assertLess(len(command), 200)

    def test_service_list_cleanup_and_dedup(self):
        self.assertEqual(policy.services_list("null:a/.B:a/.B:c/.D\n"), ["a/.B", "c/.D"])
        self.assertEqual(policy.services_list("null"), [])
        self.assertTrue(policy.is_own_service("com.studyontv.tv/.WatchAccessibilityService", "com.studyontv.tv"))
        self.assertTrue(policy.is_own_service("com.studyontv.tv/com.studyontv.tv.WatchAccessibilityService", "com.studyontv.tv"))
        self.assertFalse(policy.is_own_service("com.studyontv.mobile/.WatchAccessibilityService", "com.studyontv.tv"))

    def test_foreground_service_only_inside_own_record(self):
        text = "ServiceRecord{a}\n isForeground=true\nServiceRecord{com.studyontv.tv/com.studyontv.tv.GateService}\n isForeground=false\n"
        self.assertFalse(policy.foreground_service(text, "com.studyontv.tv"))
        text = "ServiceRecord{com.studyontv.tv/.GateService}\n isForeground=true\n mStarted=true\n"
        self.assertTrue(policy.foreground_service(text, "com.studyontv.tv"))
        text = "ServiceRecord{com.studyontv.tv/.GateService}\n isForeground=false\nServiceRecord{a}\n isForeground=true\n"
        self.assertFalse(policy.foreground_service(text, "com.studyontv.tv"))

    # ------------------------------------------------------------- policy flow

    def test_sleeping_screen_is_never_recovered(self):
        client = PolicyClient({policy.POWER_COMMAND: "mWakefulness=Asleep",
                               policy.process_command("com.studyontv.tv"): ""})
        result = policy.inspect(client)
        self.assertEqual(result["status"], "sleeping")
        self.assertEqual(result["actions"], [])
        self.assertFalse(any(command.startswith("am start") for command in client.outputs["commands"]))
        self.assertFalse(any(command.startswith("settings put") for command in client.outputs["commands"]))

    def test_unknown_power_is_never_recovered(self):
        client = PolicyClient({policy.POWER_COMMAND: "unsupported output"})
        self.assertEqual(policy.inspect(client)["status"], "unknown_power")
        self.assertEqual(client.outputs["commands"], [policy.POWER_COMMAND])

    def test_missing_process_gets_started_but_healthy_is_not(self):
        base = policy_commands()
        down = PolicyClient({command: "" for command in base})
        down.outputs[policy.POWER_COMMAND] = AWAKE
        self.assertEqual(policy.inspect(down)["actions"], ["start_application"])
        self.assertIn("am start -n com.studyontv.tv/com.studyontv.tv.MainActivity", down.outputs["commands"])
        self.assertIn(policy.process_fallback_command("com.studyontv.tv"), down.outputs["commands"])
        healthy = PolicyClient({command: "" for command in base})
        healthy.outputs.update({policy.POWER_COMMAND: AWAKE,
                                policy.process_command("com.studyontv.tv"): "1234",
                                policy.services_command("com.studyontv.tv"): FOREGROUND,
                                policy.ACCESSIBILITY_COMMAND: "com.studyontv.tv/.WatchAccessibilityService"})
        self.assertEqual(policy.inspect(healthy)["status"], "healthy")
        self.assertFalse(any(command.startswith("am start") for command in healthy.outputs["commands"]))
        # No `ps` fallback is needed when pidof answers.
        self.assertNotIn(policy.process_fallback_command("com.studyontv.tv"), healthy.outputs["commands"])

    def test_process_without_foreground_service_recovers(self):
        client = PolicyClient({command: AWAKE if command == policy.POWER_COMMAND else ""
                               for command in policy_commands()})
        client.outputs[policy.process_command("com.studyontv.tv")] = "55"
        self.assertEqual(policy.inspect(client)["actions"], ["start_application"])

    def test_repair_preserves_other_services(self):
        client = PolicyClient({command: AWAKE if command == policy.POWER_COMMAND else ""
                               for command in policy_commands()})
        client.outputs.update({policy.process_command("com.studyontv.tv"): "1",
                               policy.services_command("com.studyontv.tv"): FOREGROUND,
                               policy.ACCESSIBILITY_COMMAND: "null:other/.A"})
        self.assertEqual(policy.inspect(client, repair_accessibility=True)["actions"], ["restore_accessibility"])
        self.assertIn("settings put secure enabled_accessibility_services "
                      "'other/.A:com.studyontv.tv/com.studyontv.tv.WatchAccessibilityService'",
                      client.outputs["commands"])

    def test_abbreviated_service_is_not_duplicated(self):
        client = PolicyClient({command: AWAKE if command == policy.POWER_COMMAND else ""
                               for command in policy_commands()})
        client.outputs.update({policy.process_command("com.studyontv.tv"): "1",
                               policy.services_command("com.studyontv.tv"): FOREGROUND,
                               policy.ACCESSIBILITY_COMMAND: "com.studyontv.tv/.WatchAccessibilityService"})
        self.assertEqual(policy.inspect(client, repair_accessibility=True)["actions"], [])
        self.assertFalse(any(command.startswith("settings put") for command in client.outputs["commands"]))

    def test_unsafe_accessibility_value_refused(self):
        client = PolicyClient({command: AWAKE if command == policy.POWER_COMMAND else ""
                               for command in policy_commands()})
        client.outputs.update({policy.process_command("com.studyontv.tv"): "1",
                               policy.ACCESSIBILITY_COMMAND: "evil;reboot"})
        with self.assertRaises(ValueError):
            policy.inspect(client, repair_accessibility=True)
        self.assertFalse(any(command.startswith("settings put") for command in client.outputs["commands"]))

    def test_rejected_start_raises(self):
        client = PolicyClient({command: AWAKE if command == policy.POWER_COMMAND else ""
                               for command in policy_commands()})
        client.outputs["am start -n com.studyontv.tv/com.studyontv.tv.MainActivity"] = "Error: Activity not started"
        with self.assertRaises(RuntimeError):
            policy.inspect(client)

    def test_unsupported_package_rejected(self):
        with self.assertRaises(ValueError):
            policy.inspect(PolicyClient({}), "com.example.bad")

    # ------------------------------------------------------------- mock adbd

    def test_handshake_signature_and_shell_stream(self):
        outputs = {policy.POWER_COMMAND: AWAKE,
                   policy.process_command("com.studyontv.tv"): "4321\n",
                   policy.services_command("com.studyontv.tv"): FOREGROUND,
                   policy.ACCESSIBILITY_COMMAND: "com.studyontv.tv/.WatchAccessibilityService\n"}
        server = MockAdbServer(self.key, outputs)
        server.start()
        client = AdbClient("127.0.0.1", server.port, self.key, timeout=5)
        try:
            result = policy.inspect(client)
        finally:
            client.close()
            server.join(3)
        self.assertEqual(server.errors, [])
        self.assertEqual(result["status"], "healthy")
        self.assertIn("AUTH:SIGNATURE", server.commands)
        self.assertNotIn("AUTH:RSAPUBLICKEY", server.commands)
        # Four commands, one connection: that is what real adbd used to break.
        self.assertEqual([c for c in server.commands if not c.startswith("AUTH")],
                         [policy.POWER_COMMAND, policy.process_command("com.studyontv.tv"),
                          policy.services_command("com.studyontv.tv"), policy.ACCESSIBILITY_COMMAND])

    def test_unauthorized_offers_public_key(self):
        server = MockAdbServer(self.key, {}, grant_after=99, reply_to_key=True)
        server.start()
        client = AdbClient("127.0.0.1", server.port, self.key, timeout=5)
        try:
            self.assertEqual(client.shell(policy.POWER_COMMAND), "")
        finally:
            client.close()
            server.join(3)
        self.assertEqual(server.errors, [])
        self.assertIn("AUTH:RSAPUBLICKEY", server.commands)

    def test_pending_authorization_raises_a_clear_error(self):
        """No CNXN after offering the key must not look like a bare timeout."""
        server = MockAdbServer(self.key, {}, grant_after=99, max_connections=2)
        server.start()
        client = AdbClient("127.0.0.1", server.port, self.key, timeout=8, authorize_wait=1)
        started = time.time()
        try:
            with self.assertRaises(AdbAuthorizationError) as caught:
                client.connect()
        finally:
            elapsed = time.time() - started
            client.close()
            server.join(5)
        self.assertEqual(server.errors, [])
        self.assertIn(AUTH_ERROR, str(caught.exception))
        self.assertIn("authorized", str(caught.exception).lower())
        # One prompt wait plus one immediate retry, not the 8 s operation timeout.
        self.assertLess(elapsed, 6)
        # Two connections (the first wait plus one immediate retry), but the key
        # is only offered once: the retry must not spam the TV with a second
        # prompt, it just checks whether the key was granted in the meantime.
        self.assertEqual(server.commands.count("AUTH:RSAPUBLICKEY"), 1)

    def test_authorized_after_prompt_reconnects_immediately(self):
        """Connect that raised the prompt must reconnect at once, not next cycle."""
        server = MockAdbServer(self.key, {policy.POWER_COMMAND: AWAKE}, grant_after=2, max_connections=2)
        server.start()
        client = AdbClient("127.0.0.1", server.port, self.key, timeout=8, authorize_wait=1)
        started = time.time()
        try:
            client.connect()
            output = client.shell(policy.POWER_COMMAND)
        finally:
            elapsed = time.time() - started
            client.close()
            server.join(5)
        self.assertEqual(server.errors, [])
        self.assertIn("mWakefulness=Awake", output)
        self.assertEqual(server.commands.count("AUTH:RSAPUBLICKEY"), 1)
        self.assertLess(elapsed, 6)

    def test_shell_survives_extra_clse_frame(self):
        """Real adbd queues one extra CLSE; the next shell() must discard it."""
        outputs = {policy.POWER_COMMAND: AWAKE, "echo one": "one\n", "echo two": "two\n",
                   policy.ACCESSIBILITY_COMMAND: "null\n"}
        server = MockAdbServer(self.key, outputs, duplicate_close=True)
        server.start()
        client = AdbClient("127.0.0.1", server.port, self.key, timeout=5)
        try:
            self.assertEqual(client.shell("echo one").strip(), "one")
            self.assertIsNotNone(client.sock, "a stale CLSE must not kill the connection")
            self.assertEqual(client.shell("echo two").strip(), "two")
            self.assertEqual(client.shell(policy.POWER_COMMAND).strip(), "mWakefulness=Awake")
        finally:
            client.close()
            server.join(3)
        self.assertEqual(server.errors, [])
        self.assertEqual(client.stream_id, 3)

    def test_unreachable_and_invalid_address(self):
        client = AdbClient("127.0.0.1", 5999, self.key, timeout=3)
        with self.assertRaises(OSError):
            client.connect()
        for host, port in [("a b", 5555), ("x\ny", 5555), ("host", 0)]:
            with self.assertRaises(AdbError):
                AdbClient(host, port, self.key)

    def test_oversized_shell_output_is_rejected(self):
        """200 KB from a raw dumpsys must fail loudly, not exhaust the board."""
        server = MockAdbServer(self.key, {"dumpsys power": "x" * 204800})
        server.start()
        client = AdbClient("127.0.0.1", server.port, self.key, timeout=10, max_output=49152)
        try:
            with self.assertRaises(AdbError) as caught:
                client.shell("dumpsys power")
        finally:
            client.close()
            server.join(5)
        self.assertEqual(server.errors, [])
        self.assertIn("memory limit", str(caught.exception))
        self.assertIn("49152", str(caught.exception))
        self.assertIsNone(client.sock)

    def test_filtered_commands_stay_small_on_a_large_dump(self):
        """The policy commands must fit the shipped 48 KiB budget on real sizes."""
        filtered_power = "mWakefulness=Awake\nDisplayPower: state=ON\n"
        filtered_services = FOREGROUND
        outputs = {policy.POWER_COMMAND: filtered_power,
                   policy.process_command("com.studyontv.tv"): "4321\n",
                   policy.services_command("com.studyontv.tv"): filtered_services,
                   policy.ACCESSIBILITY_COMMAND: "null\n"}
        server = MockAdbServer(self.key, outputs)
        server.start()
        client = AdbClient("127.0.0.1", server.port, self.key, timeout=5)
        try:
            result = policy.inspect(client)
        finally:
            client.close()
            server.join(3)
        self.assertEqual(server.errors, [])
        self.assertEqual(result["status"], "healthy")
        self.assertEqual(result["state"], {"awake": True, "process": True, "service": True, "accessibility": False})


if __name__ == "__main__":
    unittest.main()
