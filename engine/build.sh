#!/usr/bin/env bash
# Builds app/libs/waengine.aar with gomobile.
# Needs: Go 1.26+, Android SDK + NDK (ANDROID_HOME, ANDROID_NDK_HOME), python3.
set -euo pipefail
cd "$(dirname "$0")"
export GOTOOLCHAIN=auto
export PATH="$PATH:$(go env GOPATH)/bin"
command -v gomobile >/dev/null || go install golang.org/x/mobile/cmd/gomobile@latest
command -v gobind >/dev/null || go install golang.org/x/mobile/cmd/gobind@latest
go mod download
# Patched copy of whatsmeow (see patch_whatsmeow.py).
WM_DIR="$(go list -m -f '{{.Dir}}' go.mau.fi/whatsmeow)"
rm -rf build/whatsmeow && mkdir -p build && cp -r "$WM_DIR" build/whatsmeow && chmod -R u+w build/whatsmeow
python3 patch_whatsmeow.py build/whatsmeow
cp go.mod go.mod.bak && cp go.sum go.sum.bak
trap 'mv go.mod.bak go.mod; mv go.sum.bak go.sum' EXIT
go mod edit -replace go.mau.fi/whatsmeow=./build/whatsmeow
# arm64 only: almost all phones since ~2017. Add ",android/arm" for old 32-bit phones (+~19 MB).
# The demo-video workflow sets TARGETS=android/arm64,android/amd64 (x86_64 emulator, no ARM translation).
TARGETS="${TARGETS:-android/arm64}"
gomobile bind -target "$TARGETS" -androidapi 24 -trimpath -javapkg com.kish.wastickers \
  -ldflags "-s -w" -o ../app/libs/waengine.aar .
echo "Built app/libs/waengine.aar"
