#!/usr/bin/env bash
# Run against an already booted emulator/test device. Never grants CALL_PHONE.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mkdir -p artifacts/device
collect_device_evidence() {
  local test_status=$?
  trap - EXIT
  adb logcat -d > artifacts/device/logcat.txt 2>&1 || true
  adb shell dumpsys alarm > artifacts/device/alarm-service.txt 2>&1 || true
  adb shell dumpsys package cn.zhundian.app > artifacts/device/package.txt 2>&1 || true
  return "$test_status"
}
trap collect_device_evidence EXIT
if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; then
  echo 'Device has not completed boot; tests were not run.' >&2
  exit 1
fi
adb logcat -c
./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest "$@"
adb shell pm revoke cn.zhundian.app android.permission.CALL_PHONE
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
./gradlew --no-daemon :app:connectedDebugAndroidTest "$@"
