#!/usr/bin/env bash
echo "Starting Arbitrator Judge Server..."
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
"$DIR/jre/bin/java" -jar "$DIR/arbitrator-server-0.1.0-SNAPSHOT.jar"
