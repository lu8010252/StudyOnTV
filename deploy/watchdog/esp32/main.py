"""ESP32 MicroPython entrypoint. Copy config.json and the generated auth.json too."""
import json
import time
from adb_client import AdbAuthorizationError, AdbClient
from watchdog_policy import PACKAGES, inspect


DEFAULT_MAX_OUTPUT = 49152


def run():
    import network
    import machine
    with open("config.json") as file:
        config = json.load(file)
    with open("auth.json") as file:
        key = json.load(file)
    package = config.get("package", "com.studyontv.tv")
    interval = int(config.get("interval", 30))
    max_output = int(config.get("max_output", DEFAULT_MAX_OUTPUT))
    if package not in PACKAGES or interval < 10 or interval > 3600 or not config.get("wifi_ssid"):
        raise ValueError("Invalid watchdog configuration")
    if max_output < 4096 or max_output > 262144:
        raise ValueError("Invalid watchdog configuration")
    wlan = network.WLAN(network.STA_IF)
    wlan.active(True)
    client = AdbClient(config["tv_host"], int(config.get("tv_port", 5555)), key, timeout=8,
                       max_output=max_output, authorize_wait=5)
    hardware_watchdog = None
    try:
        hardware_watchdog = machine.WDT(timeout=120000)
    except (AttributeError, ValueError):
        pass

    def feed():
        if hardware_watchdog:
            hardware_watchdog.feed()

    while True:
        feed()
        try:
            if not wlan.isconnected():
                client.close()
                wlan.connect(config["wifi_ssid"], config.get("wifi_password", ""))
                for _ in range(20):
                    feed()
                    if wlan.isconnected():
                        break
                    time.sleep(1)
                if not wlan.isconnected():
                    raise OSError("Wi-Fi unavailable")
            result = inspect(client, package, bool(config.get("repair_accessibility", False)))
            print(json.dumps(result))
        except AdbAuthorizationError as error:
            # The TV waits for a human to accept the key. Skip this cycle
            # instead of blocking for the full operation timeout; the next
            # cycle retries (and connect() already reconnected once).
            client.close()
            print(json.dumps({"ok": False, "status": "authorization_pending", "message": str(error)}))
        except Exception as error:
            client.close()
            print(json.dumps({"ok": False, "status": "connection_or_authorization_error", "type": type(error).__name__}))
        # Frequent feeds keep long configured check intervals compatible with the hardware watchdog.
        for _ in range(interval):
            feed()
            time.sleep(1)


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        print(json.dumps({"ok": False, "status": "invalid_configuration", "type": type(error).__name__}))
