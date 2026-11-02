#!/usr/bin/env bash
# Downloads the small English Vosk model into the app's assets (about 40 MB).
set -euo pipefail
MODEL=vosk-model-small-en-us-0.15
URL="https://alphacephei.com/vosk/models/$MODEL.zip"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/model-en-us"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading $URL"
curl -fL --retry 3 -o "$TMP/model.zip" "$URL"
unzip -q "$TMP/model.zip" -d "$TMP"
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "$TMP/$MODEL" "$DEST"
# Vosk's StorageService compares this file to decide whether to re-copy the model on device.
echo "$MODEL" > "$DEST/uuid"
echo "Model installed at $DEST"
