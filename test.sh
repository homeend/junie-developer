#!/usr/bin/env bash
# Run the JunieDeveloper test suite. Extra args pass through to Gradle.
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in "$HOME"/.sdkman/candidates/java/21*; do
        [ -x "$candidate/bin/java" ] && export JAVA_HOME="$candidate" && break
    done
fi
./gradlew test --rerun "$@"
