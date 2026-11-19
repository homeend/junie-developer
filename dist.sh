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
echo
echo "Distribution ready:"
ls -1 build/distributions/*.zip
echo 'Install via: Settings > Plugins > gear icon > "Install Plugin from Disk..."'
if [ -n "$TARGET_DIR" ]; then
    mkdir -p "$TARGET_DIR"
    rm -f "$TARGET_DIR"/junie-developer-*.zip
    cp build/distributions/*.zip "$TARGET_DIR"/
    echo "Deployed to $TARGET_DIR"
fi
