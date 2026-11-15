"""Constants for the StudyOnTV integration."""

from __future__ import annotations

from homeassistant.const import Platform

DOMAIN = "studyontv"
NAME = "StudyOnTV"

PLATFORMS: list[Platform] = [Platform.BINARY_SENSOR, Platform.SENSOR]

# Configuration keys
CONF_SCAN_INTERVAL = "scan_interval"

# Defaults and limits
DEFAULT_PORT = 8099
DEFAULT_SCAN_INTERVAL = 30
MIN_SCAN_INTERVAL = 10
MAX_SCAN_INTERVAL = 600
REQUEST_TIMEOUT = 10

# Device API paths. They are served by the LAN HTTP server on the TV.
API_STATE = "/api/state"
API_DEVICE = "/api/device"

# learning.reason values returned by /api/state. An empty reason means the
# device is currently usable, which is exposed as "available".
REASON_OPTIONS: tuple[str, ...] = (
    "available",
    "quota",
    "rounds",
    "schedule",
    "timeup",
    "locked",
    "rest",
    "feedback",
    "unknown",
)
