#!/usr/bin/env bash
set -euo pipefail

reports="app/build/reports/androidTests"
mkdir -p "$reports"
package=com.aistudio.dialoguelens.vkpzmx
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"

adb logcat -c
adb logcat -v threadtime > "$reports/logcat.txt" 2>&1 &
log_pid=$!
(
  while true; do
    date -u
    free -m
    ps -eo pid,rss,%cpu,comm --sort=-rss | head -n 9 || true
    sleep 15
  done
) > "$reports/host-memory.txt" &
monitor_pid=$!
finish() {
  local result=$?
  kill "$log_pid" "$monitor_pid" 2>/dev/null || true
  {
    adb devices -l
    df -h .
    sudo dmesg --level=emerg,alert,crit,err,warn | tail -n 100
  } > "$reports/host-final.txt" 2>&1 || true
  if ((result != 0)); then
    timeout 8s adb exec-out screencap -p > "$reports/failure.png" || true
    if [[ ! -s "$reports/failure.png" ]]; then rm -f "$reports/failure.png"; fi
    timeout 8s adb shell dumpsys activity activities > "$reports/activity.txt" || true
    tail -n 80 "$reports/logcat.txt" || true
    tail -n 16 "$reports/host-final.txt" || true
  fi
}
trap finish EXIT

adb shell wm size 720x1280
adb shell wm density 320
{
  adb shell getprop ro.build.fingerprint
  adb shell getprop ro.boot.qemu.hwcodec.avcdec
  adb shell dumpsys webviewupdate
} > "$reports/device.txt"

adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
timeout 15m adb shell am instrument -w -r "$runner" | tr -d '\r' | tee "$reports/instrumentation.txt"

# The shell can return success even for a failed assertion or an interrupted instrumentation.
# Require AndroidJUnitRunner's final success summary and every advertised individual test.
expected=$(awk -F= '/^INSTRUMENTATION_STATUS: numtests=/ {print $2; exit}' "$reports/instrumentation.txt")
passed=$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$reports/instrumentation.txt" || true)
[[ "$expected" =~ ^[1-9][0-9]*$ ]] && [[ "$passed" == "$expected" ]] || {
  echo "AndroidJUnitRunner reported $passed passing tests of ${expected:-unknown} expected."
  exit 1
}
grep -Eq '^OK \([1-9][0-9]* tests?\)$' "$reports/instrumentation.txt"
