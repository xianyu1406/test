#!/usr/bin/env bash
# Source this script in a cloud checkout; use existing Android Studio/JDK elsewhere.
ZHUNDIAN_TOOLS_DIR="${ZHUNDIAN_TOOLS_DIR:-/workspace/android-toolchain}"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$ZHUNDIAN_TOOLS_DIR/jdk-21.0.9+10" ]; then
  export JAVA_HOME="$ZHUNDIAN_TOOLS_DIR/jdk-21.0.9+10"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$ZHUNDIAN_TOOLS_DIR/sdk" ]; then
  export ANDROID_HOME="$ZHUNDIAN_TOOLS_DIR/sdk"
fi
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ZHUNDIAN_TOOLS_DIR/android-user}"
export ANDROID_EMULATOR_HOME="${ANDROID_EMULATOR_HOME:-$ANDROID_USER_HOME}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/avd}"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"
export ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/android-toolchain/gradle-cache}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${ANDROID_HOME:+$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:}$PATH"
unset ZHUNDIAN_TOOLS_DIR
