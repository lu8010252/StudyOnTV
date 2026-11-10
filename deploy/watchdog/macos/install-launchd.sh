#!/bin/sh
# Install/remove a current-user LaunchAgent. Run this script on macOS only.
set -eu
[ "$(uname -s)" = Darwin ] || { printf '%s\n' 'This installer requires macOS.' >&2; exit 1; }
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)
LABEL=com.studyontv.watchdog
USER_UID=$(id -u)
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
if [ "${1:-}" = --uninstall ]; then
    launchctl bootout "gui/$USER_UID/$LABEL" 2>/dev/null || true
    rm -f "$PLIST"
    printf '%s\n' 'Removed StudyOnTV LaunchAgent; ADB keys and logs are preserved.'
    exit 0
fi
[ "$#" -ge 1 ] || { printf '%s\n' 'Usage: install-launchd.sh TV_SERIAL [PYTHON_PATH] [ADB_PATH] [REPAIR_0_OR_1]' >&2; exit 1; }
TV_SERIAL=$1
PYTHON_BIN=${2:-$(command -v python3)}
ADB_BIN=${3:-$(command -v adb)}
REPAIR=${4:-0}
"$PYTHON_BIN" - "$PROJECT_DIR" "$PLIST" "$TV_SERIAL" "$PYTHON_BIN" "$ADB_BIN" "$REPAIR" <<'PY'
import importlib.util
from pathlib import Path
import plistlib
import sys
sys.dont_write_bytecode = True
if sys.version_info < (3, 10):
    raise SystemExit('Python 3.10+ is required; pass its absolute path as the second argument.')
root, target, serial, python, adb, repair = sys.argv[1:]
spec = importlib.util.spec_from_file_location('sotv_watchdog', Path(root) / 'scripts/device-watchdog.py')
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
module.Watchdog(serial, 'com.studyontv.tv', adb)
if repair not in ('0', '1'):
    raise SystemExit('REPAIR must be 0 or 1')
for executable in (python, adb):
    if not Path(executable).is_absolute() or not Path(executable).is_file():
        raise SystemExit('Use existing absolute executable paths for Python and ADB')
logs = Path.home() / 'Library/Logs/StudyOnTV'
logs.mkdir(parents=True, exist_ok=True)
args = [python, str(Path(root) / 'scripts/device-watchdog.py'), 'watch', '--serial', serial,
        '--adb', adb, '--interval', '30', '--log-file', str(logs / 'watchdog.log')]
if repair == '1':
    args.append('--repair-accessibility')
path = Path(target)
path.parent.mkdir(parents=True, exist_ok=True)
with path.open('wb') as output:
    plistlib.dump({'Label': 'com.studyontv.watchdog', 'ProgramArguments': args,
                  'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 15,
                  'WorkingDirectory': root, 'StandardOutPath': '/dev/null',
                  'StandardErrorPath': '/dev/null'}, output)
path.chmod(0o600)
PY
launchctl bootout "gui/$USER_UID/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$USER_UID" "$PLIST"
launchctl kickstart "gui/$USER_UID/$LABEL"
printf '%s\n' 'Installed current-user StudyOnTV LaunchAgent. Keep the project directory in place.'
