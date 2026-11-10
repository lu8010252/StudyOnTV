#!/bin/sh
# Self-contained tests for deploy/watchdog/openwrt/watchdog.sh.
#
# No network, no jq, no Python, no bash-only syntax. Fake "adb" and "timeout"
# executables are created in a temporary directory and placed first in PATH.
#
# Run from the repository root:  sh deploy/watchdog/tests/test_openwrt.sh

set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WATCHDOG=$SCRIPT_DIR/../openwrt/watchdog.sh
[ -f "$WATCHDOG" ] || { printf 'FAIL missing %s\n' "$WATCHDOG" >&2; exit 1; }

TMP=$(mktemp -d "${TMPDIR:-/tmp}/sotv-watchdog-test.XXXXXX") || exit 1
trap 'rm -rf "$TMP"' 0 1 2 15

BIN=$TMP/bin
STATE=$TMP/state
mkdir -p "$BIN" "$STATE"

# ---------------------------------------------------------------------------
# Fake binaries
# ---------------------------------------------------------------------------

cat >"$BIN/adb" <<'FAKE_ADB'
#!/bin/sh
# Minimal adb stand-in. Responses come from files under $FAKE_STATE.
set -u
DIR=${FAKE_STATE:?FAKE_STATE is not set}
mkdir -p "$DIR"
printf '%s\n' "$*" >>"$DIR/calls.log"

# watchdog.sh calls: adb -s SERIAL <cmd...>  or  adb connect SERIAL
if [ "${1:-}" = "-s" ]; then
    shift
    [ "$#" -eq 0 ] || shift
fi
cmd=${1:-}
[ "$#" -eq 0 ] || shift

# Print the contents of $DIR/$1, or $2 when that file does not exist.
file_or() {
    if [ -f "$DIR/$1" ]; then
        cat "$DIR/$1"
    else
        printf '%s' "$2"
    fi
}

# Print the exit status stored in $DIR/$1 (default 0).
exit_from() {
    if [ -f "$DIR/$1" ]; then
        cat "$DIR/$1"
    else
        printf '0'
    fi
}

case $cmd in
    connect)
        printf 'connected to %s\n' "${1:-unknown}"
        exit 0
        ;;
    get-state)
        file_or get-state device
        exit "$(exit_from get-state-exit)"
        ;;
    shell)
        remote=$*
        case $remote in
            'dumpsys power'*)
                file_or power 'mWakefulness=Awake'
                exit "$(exit_from power-exit)"
                ;;
            'pidof '*)
                file_or pidof '4242'
                exit 0
                ;;
            'ps'|'ps '*)
                file_or ps ''
                exit 0
                ;;
            'dumpsys activity services '*)
                file_or services ''
                exit "$(exit_from services-exit)"
                ;;
            'settings get secure enabled_accessibility_services')
                file_or a11y 'com.studyontv.tv/.WatchAccessibilityService'
                exit "$(exit_from a11y-exit)"
                ;;
            'settings put '*)
                printf '%s\n' "$remote" >>"$DIR/put.log"
                exit "$(exit_from put-exit)"
                ;;
            'am start '*)
                printf '%s\n' "$remote" >>"$DIR/am.log"
                file_or start-output 'Starting: Intent { cmp=com.studyontv.tv/com.studyontv.tv.MainActivity }'
                printf '\n'
                exit "$(exit_from start-exit)"
                ;;
            *)
                exit 0
                ;;
        esac
        ;;
    *)
        exit 0
        ;;
esac
FAKE_ADB
chmod +x "$BIN/adb"

cat >"$BIN/timeout" <<'FAKE_TIMEOUT'
#!/bin/sh
# Drop the timeout value and run the rest of the command unchanged.
[ "$#" -eq 0 ] || shift
exec "$@"
FAKE_TIMEOUT
chmod +x "$BIN/timeout"

PATH=$BIN:$PATH
export PATH

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

PASS=0
FAIL=0
RC=0
OUT_TEXT=''
ERR_TEXT=''
OUT_FILE=$TMP/stdout
ERR_FILE=$TMP/stderr

pass() {
    PASS=$((PASS + 1))
    printf 'PASS %s\n' "$1"
}

fail() {
    FAIL=$((FAIL + 1))
    printf 'FAIL %s\n' "$1"
    printf '  rc=%s\n  stdout=%s\n  stderr=%s\n' "$RC" "$OUT_TEXT" "$ERR_TEXT"
}

reset_state() {
    rm -rf "$STATE"
    mkdir -p "$STATE"
}

begin_case() {
    unset CASE_SERIAL CASE_PACKAGE CASE_INTERVAL CASE_TIMEOUT CASE_REPAIR
    reset_state
}

state_put() {
    printf '%s' "$2" >"$STATE/$1"
}

# Healthy defaults used by most cases.
state_healthy() {
    state_put power 'mWakefulness=Awake
'
    state_put pidof '4242'
    state_put services 'ServiceRecord{1a2b u0 com.studyontv.tv/.GateService}
    isForeground=true
'
    state_put a11y 'com.studyontv.tv/com.studyontv.tv.WatchAccessibilityService
'
}

run_watchdog() {
    mode=$1
    env \
        SOTV_SERIAL="${CASE_SERIAL-192.168.1.20:5555}" \
        SOTV_PACKAGE="${CASE_PACKAGE-com.studyontv.tv}" \
        SOTV_ADB="$BIN/adb" \
        SOTV_INTERVAL="${CASE_INTERVAL-30}" \
        SOTV_TIMEOUT="${CASE_TIMEOUT-10}" \
        SOTV_REPAIR_ACCESSIBILITY="${CASE_REPAIR-0}" \
        FAKE_STATE="$STATE" \
        sh "$WATCHDOG" "$mode" >"$OUT_FILE" 2>"$ERR_FILE"
    RC=$?
    OUT_TEXT=$(cat "$OUT_FILE")
    ERR_TEXT=$(cat "$ERR_FILE")
}

check_rc() {
    if [ "$RC" -eq "$1" ]; then
        pass "$2 (rc=$RC)"
    else
        fail "$2 (expected rc $1)"
    fi
}

check_rc_nonzero() {
    if [ "$RC" -ne 0 ]; then
        pass "$1 (rc=$RC)"
    else
        fail "$1 (expected non-zero rc)"
    fi
}

check_contains() {
    case $OUT_TEXT in
        *"$1"*)
            pass "$2"
            ;;
        *)
            fail "$2 (stdout missing '$1')"
            ;;
    esac
}

check_not_contains() {
    case $OUT_TEXT in
        *"$1"*)
            fail "$2 (stdout unexpectedly contains '$1')"
            ;;
        *)
            pass "$2"
            ;;
    esac
}

check_file_absent() {
    if [ -e "$1" ]; then
        fail "$2 ($1 exists)"
    else
        pass "$2"
    fi
}

check_log_line() {
    # check_log_line FILE PATTERN EXPECTED_COUNT NAME
    if [ -f "$1" ]; then
        n=$(grep -Fc "$2" "$1" 2>/dev/null || true)
    else
        n=0
    fi
    [ -n "$n" ] || n=0
    if [ "$n" -eq "$3" ]; then
        pass "$4 ($2 x$n)"
    else
        fail "$4 (expected '$2' x$3, got x$n)"
    fi
}

check_log_absent_pattern() {
    # check_log_absent_pattern FILE PATTERN NAME
    if [ -f "$1" ] && grep -Fq "$2" "$1" 2>/dev/null; then
        fail "$3 (found '$2' in $1)"
    else
        pass "$3"
    fi
}

# ---------------------------------------------------------------------------
# 1. Sleeping TV: no recovery, exit 0
# ---------------------------------------------------------------------------
begin_case
state_put power 'mWakefulness=Asleep
'
state_put pidof ''
state_put services ''
run_watchdog ensure
check_rc 0 'sleeping: exits 0'
check_contains '"status":"sleeping"' 'sleeping: reports sleeping'
check_contains '"action":"none"' 'sleeping: no action'
check_file_absent "$STATE/am.log" 'sleeping: never calls am start'

# ---------------------------------------------------------------------------
# 2. Unknown power state: no recovery
# ---------------------------------------------------------------------------
begin_case
state_put power 'Power manager state:
  mIsPowered=true
'
state_put pidof ''
state_put services ''
run_watchdog ensure
check_rc 0 'unknown power: exits 0'
check_contains '"status":"unknown_power"' 'unknown power: reports unknown_power'
check_contains '"action":"none"' 'unknown power: no action'
check_file_absent "$STATE/am.log" 'unknown power: never calls am start'

# ---------------------------------------------------------------------------
# 3. Healthy process + foreground GateService
# ---------------------------------------------------------------------------
begin_case
state_healthy
run_watchdog ensure
check_rc 0 'healthy: exits 0'
check_contains '"status":"healthy"' 'healthy: reports healthy'
check_contains '"process":true' 'healthy: process true'
check_contains '"service":true' 'healthy: service true'
check_contains '"accessibility":true' 'healthy: accessibility true'
check_contains '"action":"none"' 'healthy: no action'
check_file_absent "$STATE/am.log" 'healthy: never calls am start'
check_file_absent "$STATE/put.log" 'healthy: never writes settings'

# ---------------------------------------------------------------------------
# 4. Process alive but GateService not foreground: am start
# ---------------------------------------------------------------------------
begin_case
state_healthy
state_put services 'ServiceRecord{1a2b u0 com.studyontv.tv/.GateService}
    isForeground=false
'
run_watchdog ensure
check_rc 0 'background service: exits 0 after requesting start'
check_contains '"status":"recovery_requested"' 'background service: recovery requested'
check_contains '"action":"start_application"' 'background service: action start_application'
check_log_line "$STATE/am.log" 'am start -n com.studyontv.tv/com.studyontv.tv.MainActivity' 1 \
    'background service: am start called once'

# ---------------------------------------------------------------------------
# 5. Process missing: am start
# ---------------------------------------------------------------------------
begin_case
state_healthy
state_put pidof ''
state_put ps ''
run_watchdog ensure
check_rc 0 'missing process: exits 0 after requesting start'
check_contains '"process":false' 'missing process: process false'
check_contains '"status":"recovery_requested"' 'missing process: recovery requested'
check_contains '"action":"start_application"' 'missing process: action start_application'
check_log_line "$STATE/am.log" 'am start -n com.studyontv.tv/com.studyontv.tv.MainActivity' 1 \
    'missing process: am start called once'

# ---------------------------------------------------------------------------
# 6a. Repair accessibility from "null" (nothing else to preserve)
# ---------------------------------------------------------------------------
begin_case
state_healthy
state_put a11y 'null'
CASE_REPAIR=1
run_watchdog ensure
check_rc 0 'repair null: exits 0'
check_contains '"status":"recovery_requested"' 'repair null: recovery requested'
check_contains '"action":"restore_accessibility"' 'repair null: action restore_accessibility'
check_log_line "$STATE/put.log" \
    "settings put secure enabled_accessibility_services 'com.studyontv.tv/com.studyontv.tv.WatchAccessibilityService'" 1 \
    'repair null: writes only our component'
check_log_absent_pattern "$STATE/put.log" 'null' 'repair null: null entry is dropped'
check_log_line "$STATE/put.log" 'settings put secure accessibility_enabled 1' 1 \
    'repair null: enables accessibility exactly once'
check_file_absent "$STATE/am.log" 'repair null: healthy process is not restarted'

# ---------------------------------------------------------------------------
# 6b. Repair accessibility while keeping another app's component
# ---------------------------------------------------------------------------
begin_case
state_healthy
state_put a11y 'com.other.app/.OtherService:null'
CASE_REPAIR=1
run_watchdog ensure
check_rc 0 'repair other app: exits 0'
check_log_line "$STATE/put.log" \
    "settings put secure enabled_accessibility_services 'com.other.app/.OtherService:com.studyontv.tv/com.studyontv.tv.WatchAccessibilityService'" 1 \
    'repair other app: preserves other component and appends ours'
check_log_absent_pattern "$STATE/put.log" 'null' 'repair other app: null entry is dropped'
check_log_line "$STATE/put.log" 'settings put secure accessibility_enabled 1' 1 \
    'repair other app: enables accessibility exactly once'

# ---------------------------------------------------------------------------
# 7. Abbreviated component already present: no duplicate write
# ---------------------------------------------------------------------------
begin_case
state_healthy
state_put a11y 'com.studyontv.tv/.WatchAccessibilityService'
CASE_REPAIR=1
run_watchdog ensure
check_rc 0 'abbreviated component: exits 0'
check_contains '"accessibility":true' 'abbreviated component: detected as enabled'
check_contains '"status":"healthy"' 'abbreviated component: stays healthy'
check_file_absent "$STATE/put.log" 'abbreviated component: never rewrites settings'
check_file_absent "$STATE/am.log" 'abbreviated component: never restarts app'

# ---------------------------------------------------------------------------
# 8. Unsafe accessibility value (semicolon): refuse to write
# ---------------------------------------------------------------------------
begin_case
state_healthy
state_put a11y 'com.other/.Svc;reboot'
CASE_REPAIR=1
run_watchdog ensure
check_rc_nonzero 'unsafe accessibility: exits non-zero'
check_contains '"status":"unexpected_accessibility"' 'unsafe accessibility: reports unexpected_accessibility'
check_file_absent "$STATE/put.log" 'unsafe accessibility: never writes settings'
check_file_absent "$STATE/am.log" 'unsafe accessibility: does not continue to am start'

# ---------------------------------------------------------------------------
# 9. am start returns "Error:" -> non-zero exit
# ---------------------------------------------------------------------------
begin_case
state_healthy
state_put pidof ''
state_put start-output 'Error: Activity not started, unable to resolve Intent'
run_watchdog ensure
check_rc_nonzero 'am start error: exits non-zero'
check_contains '"status":"start_rejected"' 'am start error: reports start_rejected'
check_log_line "$STATE/am.log" 'am start -n com.studyontv.tv/com.studyontv.tv.MainActivity' 1 \
    'am start error: am start was attempted once'

# ---------------------------------------------------------------------------
# 10a. Invalid SOTV_SERIAL: non-zero and never touches adb
# ---------------------------------------------------------------------------
begin_case
CASE_SERIAL='not-a-serial'
run_watchdog ensure
check_rc_nonzero 'invalid serial: exits non-zero'
check_file_absent "$STATE/calls.log" 'invalid serial: adb is never called'

# 10b. Invalid (too small) SOTV_INTERVAL: non-zero and never touches adb
begin_case
CASE_INTERVAL=5
run_watchdog ensure
check_rc_nonzero 'invalid interval (5): exits non-zero'
check_file_absent "$STATE/calls.log" 'invalid interval (5): adb is never called'

# 10c. Non-numeric SOTV_INTERVAL: non-zero and never touches adb
begin_case
CASE_INTERVAL='abc'
run_watchdog ensure
check_rc_nonzero 'invalid interval (abc): exits non-zero'
check_file_absent "$STATE/calls.log" 'invalid interval (abc): adb is never called'

# ---------------------------------------------------------------------------
# 11. status is read-only even when repair would apply
# ---------------------------------------------------------------------------
begin_case
state_healthy
state_put pidof ''
state_put a11y 'null'
CASE_REPAIR=1
run_watchdog status
check_rc 0 'status read-only: exits 0'
check_contains '"status":"diagnostic"' 'status read-only: reports diagnostic'
check_file_absent "$STATE/am.log" 'status read-only: never calls am start'
check_file_absent "$STATE/put.log" 'status read-only: never writes settings'

# 11b. status stays read-only while the TV sleeps, even with repair enabled
begin_case
state_put power 'mWakefulness=Asleep
'
state_put pidof ''
state_put services ''
state_put a11y 'null'
CASE_REPAIR=1
run_watchdog status
check_rc 0 'status sleeping: exits 0'
check_contains '"status":"diagnostic"' 'status sleeping: reports diagnostic'
check_file_absent "$STATE/am.log" 'status sleeping: never calls am start'
check_file_absent "$STATE/put.log" 'status sleeping: never writes settings'

# 11c. status stays read-only when ADB is offline
begin_case
state_put get-state 'offline
'
state_put a11y 'null'
CASE_REPAIR=1
run_watchdog status
check_rc_nonzero 'status offline: reports a failure'
check_contains '"status":"adb_unavailable"' 'status offline: reports adb_unavailable'
check_file_absent "$STATE/am.log" 'status offline: never calls am start'
check_file_absent "$STATE/put.log" 'status offline: never writes settings'

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
