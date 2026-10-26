#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Admin - Docker & MySQL setup (macOS, Intel or Apple Silicon)
#
#  1. Installs Docker Desktop via Homebrew if missing
#  2. Launches Docker Desktop and waits for its engine
#  3. Builds the judge sandbox image (arbitrator-judge:latest)
#  4. Creates/starts the arbitrator-mysql container and syncs the DB user
#
# Override defaults with DB_USER, DB_PASS, ARBITRATOR_DOCKER_IMAGE and
# ARBITRATOR_DOCKER_PLATFORM environment variables. The judge image defaults to
# linux/amd64 (the lab target); Apple Silicon runs it under emulation, so set
# ARBITRATOR_DOCKER_PLATFORM=linux/arm64 when this Mac itself hosts the contest.
# ==============================================================================
set -euo pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

BOLD='\033[1m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_err()  { echo -e "${RED}[ERROR]${NC} $1" >&2; }

DB_USER="${DB_USER:-arbitrator}"
DB_PASS="${DB_PASS:-changeme}"
IMAGE="${ARBITRATOR_DOCKER_IMAGE:-arbitrator-judge:latest}"
PLATFORM="${ARBITRATOR_DOCKER_PLATFORM:-linux/amd64}"
MYSQL_CONTAINER="arbitrator-mysql"

echo -e "${BOLD}====================================================${NC}"
echo -e "${BOLD}   Arbitrator Admin - Docker & MySQL Setup (macOS)  ${NC}"
echo -e "${BOLD}====================================================${NC}"

# ------------------------------------------------------------------------------
# STEP 1: Docker Desktop
# ------------------------------------------------------------------------------
log_info "Step 1/3: Checking Docker Desktop..."

if ! command -v docker >/dev/null 2>&1 && [ -x /Applications/Docker.app/Contents/Resources/bin/docker ]; then
    export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi

if ! command -v docker >/dev/null 2>&1; then
    log_warn "Docker Desktop is not installed."
    if command -v brew >/dev/null 2>&1; then
        log_info "Installing Docker Desktop with Homebrew (an administrator password may be requested)..."
        brew install --cask docker
        export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
    else
        log_err "Install Docker Desktop for Mac from https://www.docker.com/products/docker-desktop/ and re-run."
        exit 1
    fi
fi

if ! docker info >/dev/null 2>&1; then
    log_info "Docker engine is not running. Launching Docker Desktop..."
    open -a Docker || { log_err "Could not launch Docker Desktop. Start it from Applications and re-run."; exit 1; }
    WAITED=0
    until docker info >/dev/null 2>&1; do
        WAITED=$((WAITED + 2))
        if [ "$WAITED" -ge 180 ]; then
            log_err "Docker Desktop did not start within 180 seconds. Finish its first-run setup, then re-run."
            exit 1
        fi
        sleep 2
    done
fi
log_ok "Docker is active and accessible: $(docker --version)"

# ------------------------------------------------------------------------------
# STEP 2: Judge sandbox image
# ------------------------------------------------------------------------------
log_info "Step 2/3: Checking judge sandbox image (${IMAGE})..."

if docker image inspect "$IMAGE" >/dev/null 2>&1; then
    log_ok "Judge sandbox image is already built."
else
    if [ ! -f "$DIR/docker/Dockerfile" ]; then
        log_err "docker/Dockerfile is missing from this bundle; cannot build ${IMAGE}."
        exit 1
    fi
    log_info "Building ${IMAGE} for ${PLATFORM} (first run downloads Ubuntu + toolchains)..."
    if docker buildx version >/dev/null 2>&1; then
        docker buildx build --platform "$PLATFORM" -t "$IMAGE" --load "$DIR/docker"
    else
        docker build -t "$IMAGE" "$DIR/docker"
    fi
    log_ok "Judge sandbox image built successfully."
fi

# ------------------------------------------------------------------------------
# STEP 3: MySQL 8 container
# ------------------------------------------------------------------------------
log_info "Step 3/3: Checking MySQL 8 database container..."

container_running() { docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; }
container_exists()  { docker ps -a --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; }

if container_running; then
    log_ok "MySQL container '${MYSQL_CONTAINER}' is already running."
elif container_exists; then
    log_info "Starting existing MySQL container '${MYSQL_CONTAINER}'..."
    docker start "$MYSQL_CONTAINER" >/dev/null
elif lsof -nP -iTCP:3306 -sTCP:LISTEN >/dev/null 2>&1; then
    log_warn "Port 3306 is already in use (probably a host MySQL service)."
    log_warn "Skipping the MySQL container; the server will use the existing MySQL on port 3306."
    log_warn "Make sure user '${DB_USER}' exists there (see init-db.sql)."
else
    log_info "Creating MySQL 8 container '${MYSQL_CONTAINER}'..."
    docker run -d \
        --name "$MYSQL_CONTAINER" \
        --restart unless-stopped \
        -p 3306:3306 \
        -e MYSQL_DATABASE=arbitrator \
        -e MYSQL_USER="$DB_USER" \
        -e MYSQL_PASSWORD="$DB_PASS" \
        -e MYSQL_ROOT_PASSWORD="$DB_PASS" \
        -v arbitrator-mysql-data:/var/lib/mysql \
        mysql:8.0 >/dev/null
    log_ok "MySQL container created."
fi

if container_running; then
    # TCP ping only succeeds once the final server is up, not the entrypoint's
    # temporary socket-only init server.
    log_info "Waiting for MySQL to accept connections..."
    WAITED=0
    until docker exec -e MYSQL_PWD="$DB_PASS" "$MYSQL_CONTAINER" \
            mysqladmin ping -h 127.0.0.1 --protocol=tcp -uroot --silent >/dev/null 2>&1; do
        WAITED=$((WAITED + 2))
        if [ "$WAITED" -ge 120 ]; then
            log_err "MySQL did not become ready within 120 seconds. Check: docker logs ${MYSQL_CONTAINER}"
            exit 1
        fi
        sleep 2
    done
    log_ok "MySQL is accepting connections."

    log_info "Synchronizing database user '${DB_USER}'..."
    if ! docker exec -i -e MYSQL_PWD="$DB_PASS" "$MYSQL_CONTAINER" mysql -h 127.0.0.1 --protocol=tcp -uroot <<EOF
CREATE DATABASE IF NOT EXISTS arbitrator CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'%';
ALTER USER '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON arbitrator.* TO '${DB_USER}'@'%';
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost';
ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON arbitrator.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
EOF
    then
        log_err "Could not log in to MySQL as root. The container was probably created with a different DB_PASS."
        log_err "Re-run with that DB_PASS. (Resetting it with: docker rm -f ${MYSQL_CONTAINER} && docker volume rm arbitrator-mysql-data DELETES all stored contest data.)"
        exit 1
    fi
    log_ok "Database user '${DB_USER}' synchronized."
fi

echo -e "${GREEN}====================================================${NC}"
echo -e "${GREEN}   Docker & MySQL environment is ready.              ${NC}"
echo -e "${GREEN}====================================================${NC}"
