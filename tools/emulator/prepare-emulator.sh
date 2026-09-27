#!/usr/bin/env bash
# Prepare the test emulator before any connected run. Idempotent; safe to run every time.
# The AVD is started with -no-snapshot-save, so device settings do not survive a restart:
# every gate and every connected session must call this first.
#
# 1. Gboard's stylus-handwriting onboarding ("Try out your stylus") steals the focused field and
#    swallows injected text (found 2026-09-23 during the 1.3.0 share-intake proofs).
set -euo pipefail
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" shell settings put secure stylus_handwriting_enabled 0
echo "prepared $SERIAL: stylus_handwriting_enabled=$("$ADB" -s "$SERIAL" shell settings get secure stylus_handwriting_enabled | tr -d '\r')"
