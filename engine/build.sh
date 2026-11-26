#!/usr/bin/env bash
# Builds app/libs/waengine.aar with gomobile.
# Needs: Go 1.26+, Android SDK + NDK (ANDROID_HOME, ANDROID_NDK_HOME), python3.
set -euo pipefail
cd "$(dirname "$0")"
export GOTOOLCHAIN=auto
export PATH="$PATH:$(go env GOPATH)/bin"
command -v gomobile >/dev/null || go install golang.org/x/mobile/cmd/gomobile@latest
go mod download
# Patched copy of whatsmeow (see patch_whatsmeow.py).
WM_DIR="$(go list -m -f '{{.Dir}}' go.mau.fi/whatsmeow)"
rm -rf build/whatsmeow && mkdir -p build && cp -r "$WM_DIR" build/whatsmeow && chmod -R u+w build/whatsmeow
python3 patch_whatsmeow.py build/whatsmeow
cp go.mod go.mod.bak && cp go.sum go.sum.bak
trap 'mv go.mod.bak go.mod; mv go.sum.bak go.sum' EXIT
go mod edit -replace go.mau.fi/whatsmeow=./build/whatsmeow
gomobile bind -target android/arm64,android/arm -androidapi 24 -javapkg com.audiostickerwa \
  -ldflags "-s -w" -o ../app/libs/waengine.aar .
echo "Built app/libs/waengine.aar"
