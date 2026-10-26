#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Participant - one-click client start (Linux)
# Edit server.properties (server.host) to point at the instructor PC first.
# ==============================================================================
set -euo pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

if [ -x "$DIR/jre/bin/java" ]; then
    JAVA_CMD="$DIR/jre/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "Error: No bundled JRE ($DIR/jre/bin/java) and no system java found. Install Java 17." >&2
    exit 1
fi

JAR_FILE="$DIR/arbitrator-client-linux.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "Error: arbitrator-client-linux.jar not found in $DIR" >&2
    exit 1
fi

echo "Starting Arbitrator Client..."
exec "$JAVA_CMD" -jar "$JAR_FILE" "$@"
