#!/bin/sh
# Shared launcher for systemd, launchd and containers. Configuration is environment-only.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
: "${SOTV_SERIAL:?Set SOTV_SERIAL to an already authorized ADB device}"
set -- "$PROJECT_DIR/scripts/device-watchdog.py" watch \
    --serial "$SOTV_SERIAL" \
    --package "${SOTV_PACKAGE:-com.studyontv.tv}" \
    --interval "${SOTV_INTERVAL:-30}" \
    --adb "${SOTV_ADB:-adb}"
if [ -n "${SOTV_LOG_FILE:-}" ]; then set -- "$@" --log-file "$SOTV_LOG_FILE"; fi
case "${SOTV_REPAIR_ACCESSIBILITY:-0}" in
    1) set -- "$@" --repair-accessibility ;;
    0) ;;
    *) printf '%s\n' 'SOTV_REPAIR_ACCESSIBILITY must be 0 or 1' >&2; exit 1 ;;
esac
exec "${SOTV_PYTHON:-python3}" "$@"
