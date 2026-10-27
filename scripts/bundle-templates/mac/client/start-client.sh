#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Participant - one-click client start (macOS, Intel or Apple Silicon)
# Set server.host in server.properties to the instructor machine's address first.
# macOS ships bash 3.2: keep this script compatible with it.
# ==============================================================================
set -euo pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

# Files downloaded through a browser are quarantined by Gatekeeper, which
# blocks the bundled Java from running.
xattr -dr com.apple.quarantine "$DIR" 2>/dev/null || true

# Output is also saved under logs/, and a failure keeps the window open until
# Enter is pressed.
mkdir -p "$DIR/logs"
LOG_FILE="$DIR/logs/start-client-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -i -a "$LOG_FILE") 2>&1

on_exit() {
    local code=$?
    # 130 is Ctrl+C.
    if [ "$code" -ne 0 ] && [ "$code" -ne 130 ]; then
        echo
        echo "[ERROR] The Arbitrator client stopped with exit code ${code}."
        echo "The full output is saved in: $LOG_FILE"
        if [ -r /dev/tty ]; then
            read -r -p "Press Enter to close this window..." _ </dev/tty || true
        fi
    fi
}
trap on_exit EXIT

# Java and the JavaFX natives in the client JAR are both CPU-specific.
if [ "$(uname -m)" = "arm64" ]; then
    JRE_DIR="$DIR/jre-aarch64"
    JAR_FILE="$DIR/arbitrator-client-mac-aarch64.jar"
else
    JRE_DIR="$DIR/jre-x64"
    JAR_FILE="$DIR/arbitrator-client-mac.jar"
fi
JAVA_CMD="$JRE_DIR/bin/java"

if [ ! -f "$JAR_FILE" ] || [ ! -f "$JAVA_CMD" ]; then
    echo "Error: this folder is incomplete for this Mac ($(uname -m)): need $(basename "$JAR_FILE") and $(basename "$JRE_DIR")/." >&2
    echo "       Copy the complete client folder again." >&2
    exit 1
fi
chmod u+x "$JRE_DIR"/bin/* "$JRE_DIR/lib/jspawnhelper" 2>/dev/null || true
if ! "$JAVA_CMD" -version >/dev/null 2>&1; then
    echo "Error: the bundled Java in $JRE_DIR does not start. Copy the client folder into" >&2
    echo "       your home folder (not a USB drive or network folder) and run it from there." >&2
    exit 1
fi

if grep -Eq '^server\.host=(127\.0\.0\.1|localhost)[[:space:]]*$' "$DIR/server.properties" 2>/dev/null; then
    echo "[WARN] server.properties points at this Mac itself. On a participant Mac, set"
    echo "       server.host to the instructor machine's LAN address first."
fi

echo "Starting Arbitrator Client ($(uname -m))..."
# Not exec: the script must regain control to keep the window open on failure.
"$JAVA_CMD" -jar "$JAR_FILE" "$@"
