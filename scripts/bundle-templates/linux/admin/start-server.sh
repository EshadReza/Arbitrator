#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Admin - one-click server start (Linux)
# Runs setup.sh (Docker, judge image, MySQL), then launches the server.
# ==============================================================================
set -euo pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

BOLD='\033[1m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

export DB_USER="${DB_USER:-arbitrator}"
export DB_PASS="${DB_PASS:-changeme}"

if [ -f "$DIR/setup.sh" ]; then
    bash "$DIR/setup.sh"
fi

if [ -x "$DIR/jre/bin/java" ]; then
    JAVA_CMD="$DIR/jre/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo -e "${RED}[ERROR] No bundled JRE ($DIR/jre/bin/java) and no system java found. Install Java 17.${NC}" >&2
    exit 1
fi

JAR_FILE=""
for f in "$DIR"/arbitrator-server*.jar; do
    if [ -f "$f" ]; then
        JAR_FILE="$f"
        break
    fi
done
if [ -z "$JAR_FILE" ]; then
    echo -e "${RED}[ERROR] Server JAR not found in $DIR${NC}" >&2
    exit 1
fi

echo -e "\n${BOLD}${GREEN}==> Launching Arbitrator Server...${NC}"
echo -e "${BOLD}${GREEN}==> Instructor Console: http://localhost:8080/admin${NC}\n"

exec "$JAVA_CMD" -jar "$JAR_FILE" \
    --spring.datasource.username="${DB_USER}" \
    --spring.datasource.password="${DB_PASS}" \
    "$@"
