#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Judge Server - Startup Script
# ==============================================================================
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

# Text formatting helpers
BOLD='\033[1m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

# Run setup to ensure Docker, sandbox image, and MySQL container are 100% ready
if [ -f "$DIR/setup.sh" ]; then
    bash "$DIR/setup.sh"
fi

# Locate Java runtime: prefer bundled JRE
if [ -x "$DIR/jre/bin/java" ]; then
    JAVA_CMD="$DIR/jre/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo -e "${RED}[ERROR] Neither bundled JRE ($DIR/jre/bin/java) nor system java was found.${NC}" >&2
    exit 1
fi

# Locate server JAR
JAR_FILE=""
if [ -f "$DIR/arbitrator-server-0.1.0-SNAPSHOT.jar" ]; then
    JAR_FILE="$DIR/arbitrator-server-0.1.0-SNAPSHOT.jar"
else
    for f in "$DIR"/arbitrator-server*.jar; do
        case "$f" in
            *.original) ;;
            *)
                if [ -f "$f" ]; then
                    JAR_FILE="$f"
                    break
                fi
                ;;
        esac
    done
fi

if [ -z "$JAR_FILE" ] || [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}[ERROR] Server JAR not found in $DIR${NC}" >&2
    exit 1
fi

# Explicitly export credentials and pass as CLI arguments to eliminate any mismatch
export DB_USER="${DB_USER:-arbitrator}"
export DB_PASS="${DB_PASS:-changeme}"

echo -e "\n${BOLD}${GREEN}==> Launching Arbitrator Server...${NC}"
echo -e "${BOLD}${GREEN}==> Instructor Console will be at: http://localhost:8080/admin${NC}\n"

exec "$JAVA_CMD" -jar "$JAR_FILE" \
    --spring.datasource.username="${DB_USER}" \
    --spring.datasource.password="${DB_PASS}" \
    "$@"
