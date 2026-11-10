#!/bin/sh
# 外部看门：机顶盒上通过 ADB 检查投影里的「题了个题」，被杀了就拉起来，无障碍被关了就写回去，并推送 ntfy 通知。
# 环境变量：TV=投影地址:端口  NTFY=通知地址  INTERVAL=检查间隔秒  OFFLINE_NOTIFY=投影离线多少分钟后通知一次（0=不通知）
TV="${TV:-192.168.0.98:5555}"
NTFY="${NTFY:-}"
INTERVAL="${INTERVAL:-30}"
OFFLINE_NOTIFY="${OFFLINE_NOTIFY:-0}"
PKG=com.studyontv.tv
SVC="$PKG/.GateService"
A11Y="$PKG/$PKG.WatchAccessibilityService"
HOST="${TV%:*}"; PORT="${TV#*:}"
off_since=0; off_sent=0

say() { echo "$(date '+%F %T') $*"; }
notify() {
  say "通知: $*"
  [ -n "$NTFY" ] && curl -s -m 8 -H "Title: 投影保活" -d "$*" "$NTFY" >/dev/null 2>&1
}
tvsh() { timeout 10 adb -s "$TV" shell "$@" 2>/dev/null | tr -d '\r'; }

say "启动：看管 $TV，每 ${INTERVAL}s 检查一次"
while true; do
  # 投影没开机 / 没联网：只做一次很轻的端口探测，不跑 adb，隔久一点再看
  if ! nc -z -w 2 "$HOST" "$PORT" 2>/dev/null; then
    now=$(date +%s)
    [ "$off_since" = 0 ] && off_since=$now
    if [ "$OFFLINE_NOTIFY" -gt 0 ] && [ "$off_sent" = 0 ] && [ $((now - off_since)) -ge $((OFFLINE_NOTIFY * 60)) ]; then
      notify "投影已离线超过 ${OFFLINE_NOTIFY} 分钟（$TV 连不上）"; off_sent=1
    fi
    sleep 60; continue
  fi
  off_since=0; off_sent=0

  adb devices 2>/dev/null | grep -q "^$TV[[:space:]]*device" || timeout 6 adb connect "$TV" >/dev/null 2>&1

  pid=$(tvsh pidof "$PKG")
  if [ -z "$pid" ]; then
    sleep 15                                   # 先等应用自己的自唤醒（10 秒一次）
    pid=$(tvsh pidof "$PKG")
    if [ -z "$pid" ]; then
      tvsh am start-foreground-service -n "$SVC" >/dev/null
      sleep 5
      if [ -n "$(tvsh pidof "$PKG")" ]; then notify "应用进程不在，已由机顶盒拉起"; else notify "应用进程不在，拉起失败（检查投影 ADB 授权）"; fi
    fi
  fi

  cur=$(tvsh settings get secure enabled_accessibility_services)
  case "$cur" in
    *"$PKG"*) ;;
    *)
      if [ -z "$cur" ] || [ "$cur" = "null" ]; then new="$A11Y"; else new="$cur:$A11Y"; fi
      tvsh settings put secure enabled_accessibility_services "$new" >/dev/null
      tvsh settings put secure accessibility_enabled 1 >/dev/null
      notify "无障碍被关了，已由机顶盒写回"
      ;;
  esac
  sleep "$INTERVAL"
done
