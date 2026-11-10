#!/usr/bin/env python3
"""Generate a dedicated ADB key and export the RSA values needed by MicroPython.

Uses the installed adb keygen command. Never prints private key material. The
output directory belongs on this computer/board, not in version control.
"""
import argparse
import base64
import json
from pathlib import Path
import shutil
import subprocess
import sys


def tlv(data, offset=0):
    if offset + 2 > len(data):
        raise ValueError("Truncated DER key")
    tag, size = data[offset], data[offset + 1]
    offset += 2
    if size & 128:
        count = size & 127
        if count < 1 or count > 4 or offset + count > len(data):
            raise ValueError("Invalid DER length")
        size = int.from_bytes(data[offset:offset + count], "big")
        offset += count
    if offset + size > len(data):
        raise ValueError("Truncated DER value")
    return tag, data[offset:offset + size], offset + size


def children(data):
    result = []
    offset = 0
    while offset < len(data):
        tag, value, offset = tlv(data, offset)
        result.append((tag, value))
    return result


ANDROID_KEY_WORDS = 64


def parse_android_public_key(blob):
    """Validate an Android ``RSAPublicKey`` blob and return (modulus, exponent).

    Real layout produced by ``adb keygen`` (and read by adbd when the key is
    offered), 524 bytes, little endian, words of 4 bytes::

        [0:4]     len(n) in 32-bit words (64 for a 2048-bit key)
        [4:8]     n0inv, the Montgomery constant (NOT the length)
        [8:264]   n, 256 bytes
        [264:520] rr, n^2 mod 2^2048, 256 bytes
        [520:524] e

    272-byte blobs come from a different (pre-Android-O / vendor) encoding whose
    field offsets cannot be confirmed here, so they are refused explicitly rather
    than silently mis-parsed. Regenerate with ``adb keygen`` instead.
    """
    if len(blob) == 272:
        raise ValueError("Unsupported 272-byte Android public key blob; regenerate the key with "
                         "`adb keygen` (a 524-byte RSAPublicKey is required)")
    if len(blob) != 524:
        raise ValueError("Invalid Android ADB public key length: expected 524 bytes, got %d" % len(blob))
    words = int.from_bytes(blob[0:4], "little")
    if words != ANDROID_KEY_WORDS:
        raise ValueError("Unexpected RSAPublicKey length field: expected %d words, got %d"
                         % (ANDROID_KEY_WORDS, words))
    modulus = int.from_bytes(blob[8:264], "little")
    exponent = int.from_bytes(blob[520:524], "little")
    if exponent not in (3, 65537):
        raise ValueError("Unexpected RSAPublicKey exponent: %d" % exponent)
    return modulus, exponent


def export_key(private_pem, adb_public):
    if "ENCRYPTED" in private_pem:
        raise ValueError("Use a dedicated, unencrypted adb keygen key")
    encoded = "".join(line.strip() for line in private_pem.splitlines() if not line.startswith("-----"))
    raw = base64.b64decode(encoded, validate=True)
    tag, root, end = tlv(raw)
    if tag != 0x30 or end != len(raw):
        raise ValueError("Expected a DER RSA key sequence")
    fields = children(root)
    # adb/OpenSSL may emit either PKCS#1 or PKCS#8.
    if len(fields) >= 3 and fields[1][0] == 0x30 and fields[2][0] == 0x04:
        tag, root, end = tlv(fields[2][1])
        if tag != 0x30 or end != len(fields[2][1]):
            raise ValueError("Invalid PKCS#8 RSA key")
        fields = children(root)
    if len(fields) != 9 or any(tag != 0x02 for tag, _ in fields):
        raise ValueError("Expected an RSA private key")
    numbers = [int.from_bytes(value, "big") for _, value in fields]
    _, modulus, exponent, private, _, _, _, _, _ = numbers
    if modulus.bit_length() != 2048 or exponent not in (3, 65537) or not 1 < private < modulus:
        raise ValueError("Expected a 2048-bit ADB RSA key")
    public_text = adb_public.strip()
    parts = public_text.split()
    if not parts:
        raise ValueError("Missing ADB public key")
    public = base64.b64decode(parts[0], validate=True)
    public_modulus, _ = parse_android_public_key(public)
    if public_modulus != modulus:
        raise ValueError("Public/private ADB keys do not match")
    # The TV performs the authoritative check when the key is offered.
    return {"n": format(modulus, "x"), "d": format(private, "x"), "public_key": public_text}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).parent / "local")
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--authorize", metavar="HOST:PORT", help="use the new key to request normal authorization on a TV")
    parser.add_argument("--authorize-wait", type=float, default=20.0,
                        help="seconds to wait for the TV's authorization dialog after offering the key")
    args = parser.parse_args(argv)
    directory = args.output_dir.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    directory.chmod(0o700)
    private = directory / "adbkey"
    public = directory / "adbkey.pub"
    if private.exists() != public.exists():
        parser.error("Both adbkey and adbkey.pub must exist together; refusing to overwrite either")
    if not private.exists():
        try:
            subprocess.run([args.adb, "keygen", str(private)], check=True, capture_output=True, timeout=30)
        except (OSError, subprocess.SubprocessError):
            parser.error("adb keygen failed; provide an installed adb binary with --adb")
    try:
        key = export_key(private.read_text(encoding="ascii"), public.read_text(encoding="ascii"))
    except (ValueError, OSError) as error:
        parser.error(str(error))
    target = directory / "auth.json"
    target.write_text(json.dumps(key) + "\n", encoding="utf-8")
    target.chmod(0o600)
    private.chmod(0o600)
    print("Created board authentication file:", target)
    print("Keep adbkey and auth.json private; re-use them when updating the board.")
    if args.authorize:
        host, separator, port = args.authorize.rpartition(":")
        if not separator or not port.isdigit():
            parser.error("--authorize expects HOST:PORT for traditional TCP ADB")
        from adb_client import AdbAuthorizationError, AdbClient
        print("Requesting TV authorization; accept this dedicated key on the TV if prompted.", flush=True)
        print("Waiting up to %ds for the prompt; the TV only grants the key to a new connection,"
              " so the client reconnects once afterwards." % int(args.authorize_wait), flush=True)
        client = AdbClient(host, int(port), key, timeout=60, authorize_wait=args.authorize_wait)
        try:
            client.connect()
            if "studyontv-paired" not in client.shell("echo studyontv-paired"):
                raise RuntimeError("Unexpected authorization probe response")
        except AdbAuthorizationError as error:
            print("Authorization is still pending:", error, file=sys.stderr)
            print("On the TV open 'Allow USB debugging?' and tap 'Always allow' + 'Allow', then run "
                  "this command again (auth.json is already valid).", file=sys.stderr)
            return 2
        except Exception as error:
            print("Authorization failed:", type(error).__name__, error, file=sys.stderr)
            return 2
        finally:
            client.close()
        print("Authorized; the ESP32 can now use the same key (copy auth.json to the board).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
