"""Shared ESP32 policy; importing this file performs no network or recovery actions."""
"""Shared ESP32 policy; importing this file performs no network or recovery actions.

The commands below are filtered on the TV side with ``grep`` on purpose. Raw
``dumpsys power`` is 68-110 KB and ``dumpsys activity services`` can reach
288 KB, which does not fit the ESP32 receive budget; the filtered variants stay
in the low kilobytes while carrying every line the parsers need.
"""
PACKAGES = ("com.studyontv.tv", "com.studyontv.mobile", "com.studyontv.tv.debug", "com.studyontv.mobile.debug")
SERVICE = "com.studyontv.tv.WatchAccessibilityService"
# `shell:` runs through sh -c, so a pipe is allowed. -m stops grep early so adbd
# never produces the whole dump.
POWER_COMMAND = "dumpsys power | grep -m 4 -E 'mWakefulness=|DisplayPower: state=|mScreenOn='"
ACCESSIBILITY_COMMAND = "settings get secure enabled_accessibility_services"
SERVICES_FILTER = "ServiceRecord|isForeground"


def process_command(package):
    return "pidof " + package


def process_fallback_command(package):
    return "ps | grep ' " + package + "'"


def services_command(package):
    return "dumpsys activity services " + package + " | grep -E '" + SERVICES_FILTER + "'"



def screen_awake(text):
    """True/False from a (possibly grep-filtered) dumpsys power excerpt.

    A single `mWakefulness=...` line is enough; DisplayPower/mScreenOn are only a
    fallback for firmwares that do not print mWakefulness.
    """
    legacy = None
    for line in text.splitlines():
        line = line.replace(" ", "").replace("\t", "")
        if "mWakefulness=Awake" in line or "mWakefulness=Dreaming" in line:
            return True
        if "mWakefulness=Asleep" in line or "mWakefulness=Dozing" in line:
            return False
        if "DisplayPower:state=ON" in line or "mScreenOn=true" in line:
            legacy = True
        if "DisplayPower:state=OFF" in line or "mScreenOn=false" in line:
            legacy = False
    return legacy


def services_list(text):
    result = []
    for item in text.strip().replace("\r", "").replace("\n", "").split(":"):
        if item and item != "null" and item not in result:
            result.append(item)
    return result


def is_own_service(item, package):
    if "/" not in item:
        return False
    owner, cls = item.split("/", 1)
    if cls.startswith("."):
        cls = owner + cls
    return owner == package and cls == SERVICE


def foreground_service(text, package):
    active = False
    for line in text.splitlines():
        if "ServiceRecord{" in line:
            token = line.replace("}", " ").replace("{", " ").split()
            active = package + "/com.studyontv.tv.GateService" in token or package + "/.GateService" in token
        if active and "isForeground=true" in line.replace(" ", ""):
            return True
    return False


def inspect(client, package="com.studyontv.tv", repair_accessibility=False, read_only=False):
    if package not in PACKAGES:
        raise ValueError("Unsupported package")
    awake = screen_awake(client.shell(POWER_COMMAND))
    if awake is not True and not read_only:
        return {"ok": True, "status": "sleeping" if awake is False else "unknown_power", "actions": []}
    pids = client.shell(process_command(package)).strip()
    process = bool(pids) and all(value.isdigit() for value in pids.split())
    if not process:
        process = any(line.split() and line.split()[-1] == package
                      for line in client.shell(process_fallback_command(package)).splitlines())
    service = process and foreground_service(client.shell(services_command(package)), package)
    components = services_list(client.shell(ACCESSIBILITY_COMMAND))
    enabled = any(is_own_service(item, package) for item in components)
    state = {"awake": awake, "process": process, "service": service, "accessibility": enabled}
    actions = []
    if read_only:
        return {"ok": True, "status": "diagnostic", "state": state, "actions": actions}
    if repair_accessibility and not enabled:
        # Read again immediately before writing, to preserve concurrent changes.
        components = services_list(client.shell(ACCESSIBILITY_COMMAND))
        if not any(is_own_service(item, package) for item in components):
            components.append(package + "/" + SERVICE)
            value = ":".join(components)
            allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.$/:"
            if not all(char in allowed for char in value):
                raise ValueError("Unexpected accessibility components; refusing to overwrite")
            response = client.shell("settings put secure enabled_accessibility_services '" + value + "'")
            response += client.shell("settings put secure accessibility_enabled 1")
            if "Exception" in response or "Permission denial" in response or "Permission Denial" in response:
                raise RuntimeError("TV rejected accessibility repair")
            actions.append("restore_accessibility")
    if not process or not service:
        response = client.shell("am start -n " + package + "/com.studyontv.tv.MainActivity")
        if "Error:" in response or "Exception" in response or "Permission Denial" in response:
            raise RuntimeError("TV rejected application recovery")
        actions.append("start_application")
    return {"ok": True, "status": "recovery_requested" if actions else "healthy", "state": state, "actions": actions}
