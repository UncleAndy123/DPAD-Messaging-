#!/usr/bin/env bash
# Smoke test for DPAD Messaging
# Launch app, wait, open first conversation, send a message, go back

set -euo pipefail

PKG="com.dpad.messaging"
ACTIVITY="${PKG}/.MainActivity"

echo "Starting app..."
adb shell am start -n "$ACTIVITY"
sleep 2

echo "Tapping first thread row"
# Tap roughly where the first thread is (device specific); adjust if needed
adb shell input tap 240 180
sleep 2

echo "Typing test message"
adb shell input text "smoke_test"
sleep 1
adb shell input keyevent 66
sleep 1

echo "Returning to home"
adb shell input keyevent 3

echo "Smoke test complete"
