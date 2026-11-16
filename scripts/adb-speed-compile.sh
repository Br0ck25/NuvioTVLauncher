#!/usr/bin/env bash
#
# Force Android Runtime to AOT-compile Nuvio One on a connected device.
#
# This is the optional extra step from the community "make sideloaded Nuvio faster"
# guide. The app already ships baseline profiles, so this is only for users who want
# the full `speed` compilation on a specific TV.
#
# Usage:
#   scripts/adb-speed-compile.sh [package]
#
# Environment:
#   ADB             path to adb (default: adb)
#   ANDROID_SERIAL  device serial or ip:port when more than one device is connected
#
# The package defaults to the release build. Use app.onetube.tv.test for a test
# build or com.nuvio.livetv for a debug build.
set -uo pipefail

PACKAGE="${1:-app.onetube.tv}"
ADB="${ADB:-adb}"
SERIAL="${ANDROID_SERIAL:-}"

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools or set ADB=/path/to/adb." >&2
  exit 1
fi

ADB_ARGS=()
if [ -n "$SERIAL" ]; then
  ADB_ARGS=(-s "$SERIAL")
fi

if ! "$ADB" "${ADB_ARGS[@]}" get-state >/dev/null 2>&1; then
  echo "No device. Connect the TV and run: adb connect <tv-ip>:5555" >&2
  exit 1
fi

echo "Compiling $PACKAGE. This can take a minute on a TV."
if ! "$ADB" "${ADB_ARGS[@]}" shell cmd package compile -m speed -f "$PACKAGE"; then
  # Older devices only have the pm wrapper.
  "$ADB" "${ADB_ARGS[@]}" shell pm compile -m speed -f "$PACKAGE"
fi

echo
echo "Dexopt state:"
"$ADB" "${ADB_ARGS[@]}" shell dumpsys package dexopt | grep -A 1 "$PACKAGE" || true
echo
echo "Done. Re-run this after every app update."
