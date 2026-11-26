#!/usr/bin/env bash
# Records the real app on an emulator (see .github/workflows/demo-video.yml).
# Output: demo-out/*.mp4 (screen recordings, no sound) and *.png (screenshots).
set -x
PKG=com.kish.wastickers
OUT=demo-out
mkdir -p "$OUT"
adb wait-for-device
adb install -r -g app/build/outputs/apk/debug/app-debug.apk

# Clean look: dark mode, visible touches, demo status bar (9:41, full battery, no notifications).
adb shell cmd uimode night yes
adb shell settings put system show_touches 1
adb shell settings put global sysui_demo_allowed 1
for c in "enter" "clock -e hhmm 0941" "battery -e level 100 -e plugged false" \
         "network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4" "notifications -e visible false"; do
  adb shell am broadcast -a com.android.systemui.demo -e command $c >/dev/null
done

rec()  { adb shell screenrecord --bit-rate 16000000 --time-limit 178 "/sdcard/$1.mp4" & sleep 2; }
stop() { adb shell pkill -INT screenrecord || true; sleep 3; adb pull "/sdcard/$1.mp4" "$OUT/" || true; }
flow() { maestro test --no-ansi ".maestro/$1.yaml" || echo "FLOW FAILED: $1" | tee -a "$OUT/failures.txt"; }
D=/sdcard/Android/data/$PKG/files
media() { adb shell mkdir -p "$D"; adb push docs/demo/beach_party.mp4 docs/demo/dance.gif docs/demo/sunny.png "$D/"; }
# Share a file to the app, like "Share" from a gallery app.
share() { adb shell am start -a android.intent.action.SEND -t "$2" --eu android.intent.extra.STREAM "file://$D/$1" -n "$PKG/.MainActivity"; }
dump() { maestro hierarchy > "$OUT/hierarchy-$1.json" 2>/dev/null || true; }

# Segment 1: home -> share a video -> audio sticker
rec seg1
flow 01-home
media
share beach_party.mp4 video/mp4
flow 02-audio-sticker || dump seg1
stop seg1

# Segment 2: sticker pack from a video, a GIF and an image
rec seg2
flow 03-pack-create
share beach_party.mp4 video/mp4
flow 04-pack-add-clip
share dance.gif image/gif
flow 04-pack-add-clip
share sunny.png image/png
flow 05-pack-add-image
dump seg2
stop seg2

# Segment 3: home and settings
rec seg3
flow 06-settings
stop seg3

adb logcat -d -t 2000 > "$OUT/logcat.txt" || true
ls -la "$OUT"
exit 0
