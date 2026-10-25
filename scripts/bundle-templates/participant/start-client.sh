#!/usr/bin/env bash
set -e
echo "Starting Arbitrator Client..."
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

# Prefer bundled JRE if present, otherwise fallback to system java
if [ -x "$DIR/jre/bin/java" ]; then
    JAVA_CMD="$DIR/jre/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "Error: Neither bundled JRE ($DIR/jre/bin/java) nor system 'java' was found." >&2
    exit 1
fi

# Locate client JAR (prefer arbitrator-client-linux.jar, then any arbitrator-client*.jar)
JAR_FILE=""
if [ -f "$DIR/arbitrator-client-linux.jar" ]; then
    JAR_FILE="$DIR/arbitrator-client-linux.jar"
else
    for f in "$DIR"/arbitrator-client*.jar; do
        if [ -f "$f" ]; then
            JAR_FILE="$f"
            break
        fi
    done
fi

if [ -z "$JAR_FILE" ] || [ ! -f "$JAR_FILE" ]; then
    echo "Error: Client JAR not found in $DIR" >&2
    exit 1
fi

exec "$JAVA_CMD" -jar "$JAR_FILE" "$@"
