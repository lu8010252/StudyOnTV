"""Small traditional TCP/ADB-RSA client for ESP32 MicroPython and CPython.

No adb executable, TLS wireless pairing, shell library, or external crypto package.
Only pre-authorized keys / a TV's normal first-use authorization dialog are used.
"""
import socket
import struct
import time

CNXN = 0x4E584E43
AUTH = 0x48545541
OPEN = 0x4E45504F
OKAY = 0x59414B4F
WRTE = 0x45545257
CLSE = 0x45534C43
STLS = 0x534C5453
SHA1_PREFIX = b"\x30\x21\x30\x09\x06\x05\x2b\x0e\x03\x02\x1a\x05\x00\x04\x14"
# Frames for an already closed stream may stay queued (adbd answers the last
# WRTE ack with one extra CLSE). They are dropped, but never without a limit.
STALE_FRAME_LIMIT = 8
AUTHORIZATION_HINT = ("TV has not authorized this device key; accept the debugging prompt on the TV"
                     " (this cycle is skipped, the next cycle retries)")


class AdbError(Exception):
    pass


class AdbAuthorizationError(AdbError):
    """The TV asked for a key but never confirmed it, i.e. it waits for a human."""
    pass


def clock_ms():
    return time.ticks_ms() if hasattr(time, "ticks_ms") else int(time.monotonic() * 1000)


def elapsed_ms(start):
    return time.ticks_diff(time.ticks_ms(), start) if hasattr(time, "ticks_diff") else clock_ms() - start


def rsa_signature(token, key):
    if len(token) != 20 or len(key.get("n", "")) != 512:
        raise AdbError("ADB requires a 20-byte token and a 2048-bit RSA key")
    modulus = int(key["n"], 16)
    exponent = int(key["d"], 16)
    encoded = b"\x00\x01" + b"\xff" * (256 - len(SHA1_PREFIX) - 23) + b"\x00" + SHA1_PREFIX + token
    value = int.from_bytes(encoded, "big")
    try:
        signed = pow(value, exponent, modulus)
    except (TypeError, NotImplementedError):
        # Some MicroPython builds omit the third pow argument.
        signed = 1
        value %= modulus
        while exponent:
            if exponent & 1:
                signed = (signed * value) % modulus
            exponent >>= 1
            value = (value * value) % modulus
    return signed.to_bytes(256, "big")


class AdbClient:
    def __init__(self, host, port, key, timeout=8, max_output=49152, authorize_wait=5):
        if not host or any(char in host for char in "\r\n\x00/ ") or not 1 <= int(port) <= 65535:
            raise AdbError("Invalid ADB address")
        self.host = host
        self.port = int(port)
        self.key = key
        self.timeout = timeout
        self.max_output = max_output
        # adbd replies only after a human taps "allow", so waiting for the full
        # operation timeout there would silently block the caller for a minute.
        self.authorize_wait = max(1, int(authorize_wait))
        self.sock = None
        self.stream_id = 0
        self.started = clock_ms()
        self.budget = int(timeout * 3000)

    def _deadline(self):
        if elapsed_ms(self.started) > self.budget:
            raise AdbError("ADB operation timed out")

    def _exact(self, count):
        data = bytearray()
        while len(data) < count:
            self._deadline()
            part = self.sock.recv(count - len(data))
            if not part:
                raise AdbError("ADB connection closed")
            data.extend(part)
        return bytes(data)

    def _packet(self):
        command, first, second, size, checksum, magic = struct.unpack("<6I", self._exact(24))
        if magic != (command ^ 0xFFFFFFFF) or size > 4096:
            raise AdbError("Invalid or oversized ADB frame")
        data = self._exact(size) if size else b""
        # ADB 1.0.1 permits zero checksums; legacy nonzero checksums must match.
        if checksum and checksum != (sum(data) & 0xFFFFFFFF):
            raise AdbError("ADB checksum mismatch")
        return command, first, second, data

    def _send(self, command, first=0, second=0, data=b""):
        packet = struct.pack("<6I", command, first, second, len(data), sum(data) & 0xFFFFFFFF, command ^ 0xFFFFFFFF) + data
        # MicroPython socket.sendall may not exist on all firmware variants.
        view = memoryview(packet)
        while len(view):
            self._deadline()
            sent = self.sock.send(view)
            if not sent:
                raise AdbError("ADB send failed")
            view = view[sent:]

    def connect(self, retry_after_authorize=True):
        """Handshake with an already authorized key or the normal TV prompt.

        When the TV only offers a first-use prompt, the granting connection stays
        unauthenticated (adbd answers new connections only), so one immediate
        reconnect is made instead of waiting for the next watchdog interval.
        """
        self.close()
        self.started = clock_ms()
        self.budget = int(self.timeout * 3000)
        address = socket.getaddrinfo(self.host, self.port, 0, socket.SOCK_STREAM)[0]
        try:
            try:
                return self._authenticate(address, offer_public_key=True)
            except AdbAuthorizationError as pending:
                self.close()
                if not retry_after_authorize:
                    raise
                time.sleep(min(self.authorize_wait, 3))
                try:
                    return self._authenticate(address, offer_public_key=False)
                except Exception:
                    raise pending
        except Exception:
            self.close()
            raise

    def _authenticate(self, address, offer_public_key=True):
        self.sock = socket.socket(address[0], address[1], address[2])
        self.sock.settimeout(self.timeout)
        self.sock.connect(address[-1])
        self._send(CNXN, 0x01000001, 4096, b"host::\x00")
        signed = False
        offered = False
        for _ in range(5):
            try:
                command, first, _, data = self._packet()
            except (AdbError, OSError):
                # No CNXN after offering our key: the TV is waiting for a human.
                if offered:
                    raise AdbAuthorizationError(AUTHORIZATION_HINT)
                raise
            if command == CNXN:
                self.sock.settimeout(self.timeout)
                return self
            if command == STLS:
                raise AdbError("TLS wireless debugging is unsupported; use authorized traditional TCP ADB")
            if command != AUTH or first != 1:
                raise AdbError("Unexpected ADB authentication response")
            if not signed:
                self._send(AUTH, 2, 0, rsa_signature(data, self.key))
                signed = True
            elif offer_public_key and not offered:
                public_key = self.key.get("public_key", "").strip()
                if not public_key or "\x00" in public_key or len(public_key) > 2048:
                    raise AdbError("Invalid ADB public key")
                self._send(AUTH, 3, 0, public_key.encode() + b"\x00")
                offered = True
                # Wait for the prompt, but not for the whole operation timeout.
                self.sock.settimeout(self.authorize_wait)
            else:
                raise AdbAuthorizationError(AUTHORIZATION_HINT)
        raise AdbError("ADB authorization did not complete")

    def shell(self, command):
        if self.sock is None:
            self.connect()
        if not command or "\x00" in command or len(command) > 2048:
            raise AdbError("Invalid shell command")
        self.started = clock_ms()
        self.budget = int(self.timeout * 2000)
        self.stream_id += 1
        local = self.stream_id
        remote = None
        stale = 0
        output = bytearray()
        self._send(OPEN, local, 0, b"shell:" + command.encode() + b"\x00")
        try:
            while True:
                kind, first, second, data = self._packet()
                if second != local:
                    # A frame for an older local id is history: adbd answers the
                    # ack for the last WRTE of a closed stream with one extra
                    # CLSE. Real adb clients drop unknown local ids as well.
                    # Bounded by STALE_FRAME_LIMIT and by the operation deadline.
                    stale += 1
                    if stale > STALE_FRAME_LIMIT:
                        raise AdbError("ADB streams out of sync (too many stale frames)")
                    continue
                if kind == OKAY and remote is None:
                    remote = first
                elif kind == WRTE and remote == first:
                    if len(output) + len(data) > self.max_output:
                        raise AdbError("ADB output exceeded memory limit (%d bytes, max_output=%d);"
                                       " filter the command on the TV side" % (len(output) + len(data), self.max_output))
                    output.extend(data)
                    self._send(OKAY, local, remote)
                elif kind == CLSE and (remote is None or remote == first):
                    self._send(CLSE, local, first)
                    return bytes(output).decode()
                else:
                    raise AdbError("Unexpected ADB shell frame")
        except Exception:
            self.close()
            raise

    def close(self):
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None
