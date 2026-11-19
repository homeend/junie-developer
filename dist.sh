#!/usr/bin/env bash
# Build the distributable JunieDeveloper plugin zip.
# Usage: ./dist.sh [deploy-dir]
#   deploy-dir (optional): the fresh zip is copied in, replacing older
#   junie-developer-*.zip files there. Nothing else in the dir is touched.
set -euo pipefail
cd "$(dirname "$0")"
TARGET_DIR="${1:-}"
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in "$HOME"/.sdkman/candidates/java/21*; do
        [ -x "$candidate/bin/java" ] && export JAVA_HOME="$candidate" && break
    done
fi
rm -f build/distributions/*.zip
./gradlew buildPlugin
ZIP="$(ls -1 "$PWD"/build/distributions/*.zip)"
DEPLOYED=""
if [ -n "$TARGET_DIR" ]; then
    mkdir -p "$TARGET_DIR"
    TARGET_DIR="$(cd "$TARGET_DIR" && pwd)"
    rm -f "$TARGET_DIR"/junie-developer-*.zip
    cp "$ZIP" "$TARGET_DIR"/
    DEPLOYED="$TARGET_DIR/$(basename "$ZIP")"
fi

echo
echo "Plugin built:"
echo "  $ZIP"
if [ -n "$DEPLOYED" ]; then
    echo "Copied to:"
    echo "  $DEPLOYED"
fi
echo 'Install via: Settings > Plugins > gear icon > "Install Plugin from Disk..."'
