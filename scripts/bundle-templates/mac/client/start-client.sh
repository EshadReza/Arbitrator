#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Participant - one-click client start (macOS)
# Edit server.properties (server.host) to point at the instructor PC first.
# ==============================================================================
set -euo pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

# Files downloaded through a browser are quarantined by Gatekeeper, which
# blocks the bundled JRE from executing.
xattr -dr com.apple.quarantine "$DIR" 2>/dev/null || true

if [ -x "$DIR/jre/bin/java" ]; then
    JAVA_CMD="$DIR/jre/bin/java"
elif [ -x "$DIR/jre/Contents/Home/bin/java" ]; then
    JAVA_CMD="$DIR/jre/Contents/Home/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "Error: No bundled JRE ($DIR/jre) and no system java found. Install Java 17." >&2
    exit 1
fi

# JavaFX natives are architecture-specific: Apple Silicon (arm64) needs the
# aarch64 JAR, Intel Macs need the x64 one.
if [ "$(uname -m)" = "arm64" ]; then
    JAR_FILE="$DIR/arbitrator-client-mac-aarch64.jar"
else
    JAR_FILE="$DIR/arbitrator-client-mac.jar"
fi
if [ ! -f "$JAR_FILE" ]; then
    echo "Error: $(basename "$JAR_FILE") not found in $DIR" >&2
    exit 1
fi

echo "Starting Arbitrator Client ($(uname -m))..."
exec "$JAVA_CMD" -jar "$JAR_FILE" "$@"
