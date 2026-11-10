#!/bin/sh
# OpenWrt/BusyBox runner: requires adb + timeout, but no Python.
# Usage: SOTV_SERIAL=192.168.1.20:5555 sh watchdog.sh [watch|ensure|status]
set -u
MODE=${1:-watch}
SERIAL=${SOTV_SERIAL:-}
PKG=${SOTV_PACKAGE:-com.studyontv.tv}
ADB=${SOTV_ADB:-/usr/bin/adb}
INTERVAL=${SOTV_INTERVAL:-30}
REPAIR=${SOTV_REPAIR_ACCESSIBILITY:-0}
LIMIT=${SOTV_TIMEOUT:-10}
case "$MODE" in watch|ensure|status) ;; *) printf '%s\n' 'Invalid mode' >&2; exit 1 ;; esac
case "$PKG" in com.studyontv.tv|com.studyontv.mobile|com.studyontv.tv.debug|com.studyontv.mobile.debug) ;; *) printf '%s\n' 'Unsupported package' >&2; exit 1 ;; esac
printf '%s' "$SERIAL" | grep -Eq '^[A-Za-z0-9_.-]+:[0-9]+$' || { printf '%s\n' 'Set SOTV_SERIAL to an IPv4/hostname:port target' >&2; exit 1; }
case "$INTERVAL:$LIMIT:$REPAIR" in *[!0-9:]*|'') printf '%s\n' 'Invalid numeric settings' >&2; exit 1 ;; esac
[ "$INTERVAL" -ge 10 ] && [ "$INTERVAL" -le 3600 ] && [ "$LIMIT" -ge 1 ] && [ "$LIMIT" -le 30 ] || { printf '%s\n' 'Interval must be 10..3600 and timeout 1..30 seconds' >&2; exit 1; }
[ "$REPAIR" = 0 ] || [ "$REPAIR" = 1 ] || exit 1
command -v "$ADB" >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1 || { printf '%s\n' 'Install adb and timeout on this router first' >&2; exit 1; }
COMPONENT="$PKG/com.studyontv.tv.WatchAccessibilityService"
adb_run() { timeout "$LIMIT" "$ADB" -s "$SERIAL" "$@" 2>/dev/null; }
report() {
    printf '{"ok":%s,"serial":"%s","status":"%s","process":%s,"service":%s,"accessibility":%s,"action":"%s"}\n' \
        "$1" "$SERIAL" "$2" "${PROCESS:-false}" "${SERVICE:-false}" "${A11Y:-false}" "$3"
}
check_once() {
    PROCESS=false; SERVICE=false; A11Y=false
    timeout "$LIMIT" "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
    [ "$(adb_run get-state)" = device ] || { report false adb_unavailable none; return 2; }
    POWER=$(adb_run shell 'dumpsys power') || { report false power_unavailable none; return 2; }
    AWAKE=$(printf '%s\n' "$POWER" | awk '
        /mWakefulness=Awake|mWakefulness=Dreaming/ { print "true"; exit }
        /mWakefulness=Asleep|mWakefulness=Dozing/ { print "false"; exit }
        /Display Power: state=ON|mScreenOn=true/ { legacy="true" }
        /Display Power: state=OFF|mScreenOn=false/ { legacy="false" }
        END { if (legacy!="") print legacy }' | head -n 1)
    PIDS=$(adb_run shell "pidof $PKG" || true)
    if printf '%s\n' "$PIDS" | grep -Eq '^[0-9]+( [0-9]+)*$'; then PROCESS=true
    elif adb_run shell ps | awk -v pkg="$PKG" '$NF==pkg { found=1 } END { exit !found }'; then PROCESS=true; fi
    SERVICES=$(adb_run shell "dumpsys activity services $PKG") || { report false service_unavailable none; return 2; }
    if [ "$PROCESS" = true ] && printf '%s\n' "$SERVICES" | awk -v pkg="$PKG" '
        /ServiceRecord\{/ { active=(index($0,pkg "/com.studyontv.tv.GateService") || index($0,pkg "/.GateService")) }
        active && /isForeground=true/ { found=1 }
        END { exit !found }'; then SERVICE=true; fi
    CURRENT=$(adb_run shell 'settings get secure enabled_accessibility_services') || { report false accessibility_unavailable none; return 2; }
    NORMALIZED=$(printf '%s' "$CURRENT" | tr -d '\r\n' | awk -F: '
        { for(i=1;i<=NF;i++) if($i!="" && $i!="null" && !seen[$i]++) { if(out!="") out=out ":"; out=out $i } } END { print out }')
    case ":$NORMALIZED:" in *":$COMPONENT:"*) A11Y=true ;; esac
    if [ "$PKG" = com.studyontv.tv ]; then
        case ":$NORMALIZED:" in *":com.studyontv.tv/.WatchAccessibilityService:"*) A11Y=true ;; esac
    fi
    if [ "$MODE" = status ]; then report true diagnostic none; return 0; fi
    case "$AWAKE" in false) report true sleeping none; return 0 ;; true) ;; *) report true unknown_power none; return 0 ;; esac
    ACTION=none
    if [ "$REPAIR" = 1 ] && [ "$A11Y" = false ]; then
        # Quote the complete remote value, including '$' in nested Java class names.
        if printf '%s' "$NORMALIZED" | grep -q '[^A-Za-z0-9_.$/:]'; then report false unexpected_accessibility none; return 2; fi
        UPDATED=${NORMALIZED:+$NORMALIZED:}$COMPONENT
        adb_run shell "settings put secure enabled_accessibility_services '$UPDATED'" >/dev/null && \
            adb_run shell 'settings put secure accessibility_enabled 1' >/dev/null || { report false repair_failed none; return 2; }
        ACTION=restore_accessibility
    fi
    if [ "$PROCESS" = false ] || [ "$SERVICE" = false ]; then
        OUTPUT=$(adb_run shell "am start -n $PKG/com.studyontv.tv.MainActivity") || { report false start_failed "$ACTION"; return 2; }
        if printf '%s' "$OUTPUT" | grep -Eqi 'Error:|Permission Denial|Exception'; then report false start_rejected "$ACTION"; return 2; fi
        ACTION=start_application
    fi
    if [ "$ACTION" = none ]; then report true healthy none; else report true recovery_requested "$ACTION"; fi
}
while :; do
    check_once
    RESULT=$?
    [ "$MODE" = watch ] || exit "$RESULT"
    sleep "$INTERVAL"
done
