#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Admin - one-click server start (macOS, Intel or Apple Silicon)
#
#  1. Finds or installs Docker Desktop (Homebrew) and starts it
#  2. Loads or builds the judge sandbox image (arbitrator-judge:latest)
#  3. Runs the bundle's own arbitrator-mysql container on 127.0.0.1 (3306, or
#     the next free port) and checks the database login
#  4. Launches the server with the bundled Java for this Mac's CPU
#
# Every step is skipped when it is already done, so re-running is safe.
# Images in images/ (from prepare-zero-download-bundles.py --with-images) are
# loaded instead of downloaded. Network steps are retried.
# Override defaults with DB_USER, DB_PASS, ARBITRATOR_DOCKER_IMAGE and
# ARBITRATOR_DOCKER_PLATFORM environment variables. Pass --setup-only to
# prepare Docker and MySQL without starting the server; any other arguments
# are passed to the server.
#
# macOS ships bash 3.2: no mapfile, ${var,,}, or associative arrays here.
# ==============================================================================
set -euo pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

# Files downloaded through a browser are quarantined by Gatekeeper, which
# blocks the bundled Java from running.
xattr -dr com.apple.quarantine "$DIR" 2>/dev/null || true

# Everything printed is also saved under logs/, and a failure keeps the window
# open until Enter is pressed, so the error can be read and sent on.
mkdir -p "$DIR/logs"
LOG_FILE="$DIR/logs/start-server-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -i -a "$LOG_FILE") 2>&1

on_exit() {
    local code=$?
    # 130 is Ctrl+C, the normal way to stop the server.
    if [ "$code" -ne 0 ] && [ "$code" -ne 130 ]; then
        echo
        echo -e "\033[0;31m[ERROR] Arbitrator stopped with exit code ${code}.\033[0m"
        echo "The full output is saved in: $LOG_FILE"
        if [ -r /dev/tty ]; then
            read -r -p "Press Enter to close this window..." _ </dev/tty || true
        fi
    fi
}
trap on_exit EXIT

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

# Runs "$@" up to 5 times, waiting 5/10/20/40 s between attempts, for network
# steps that fail on flaky lab connections.
retry() {
    local attempt=1 delay=5
    until "$@"; do
        if [ "$attempt" -ge 5 ]; then
            return 1
        fi
        log_warn "Attempt ${attempt}/5 failed; retrying in ${delay}s..."
        sleep "$delay"
        attempt=$((attempt + 1))
        delay=$((delay * 2))
    done
}

ARCH="$(uname -m)"

# Makes an image available: already present, else bundled images/<tar>, else
# pulled from the registry with retries. The bundled images are linux/amd64;
# on Apple Silicon a native mysql:8.0 is tried first, since an emulated
# database is slower.
ensure_image() {
    local image="$1" tarball="$DIR/images/$2" mode="${3:-pull}"
    if docker image inspect "$image" >/dev/null 2>&1; then
        return 0
    fi
    if [ "$mode" = "prefer-native" ] && [ "$ARCH" = "arm64" ]; then
        log_info "Downloading native ${image} for Apple Silicon..."
        if docker pull "$image" >/dev/null 2>&1; then
            return 0
        fi
        log_warn "Download failed; using the bundled copy instead."
    fi
    if [ -f "$tarball" ]; then
        log_info "Loading ${image} from the bundle (no download)..."
        docker load -i "$tarball" >/dev/null
        docker image inspect "$image" >/dev/null 2>&1 && return 0
        log_warn "$(basename "$tarball") did not contain ${image}."
    fi
    [ "$mode" = "no-pull" ] && return 1
    log_info "Downloading ${image}..."
    if ! retry docker pull "$image"; then
        log_err "Could not download ${image}. Check the internet connection, or rebuild the bundle with --with-images."
        exit 1
    fi
}

port_in_use() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

SETUP_ONLY=0
if [ "${1:-}" = "--setup-only" ]; then
    SETUP_ONLY=1
    shift
fi

export DB_USER="${DB_USER:-arbitrator}"
export DB_PASS="${DB_PASS:-changeme}"
IMAGE="${ARBITRATOR_DOCKER_IMAGE:-arbitrator-judge:latest}"
PLATFORM="${ARBITRATOR_DOCKER_PLATFORM:-linux/amd64}"
MYSQL_CONTAINER="arbitrator-mysql"

echo -e "${BOLD}====================================================${NC}"
echo -e "${BOLD}      Arbitrator Admin - Server Start (macOS)        ${NC}"
echo -e "${BOLD}====================================================${NC}"

# ------------------------------------------------------------------------------
# STEP 1: Docker Desktop
# ------------------------------------------------------------------------------
log_info "Step 1/4: Checking Docker Desktop..."

# Docker Desktop's CLI is not on PATH until its first launch links it.
for d in /usr/local/bin /opt/homebrew/bin /Applications/Docker.app/Contents/Resources/bin; do
    case ":$PATH:" in
        *":$d:"*) ;;
        *) [ -d "$d" ] && PATH="$PATH:$d" ;;
    esac
done
export PATH

if ! command -v docker >/dev/null 2>&1; then
    log_warn "Docker Desktop is not installed."
    if command -v brew >/dev/null 2>&1; then
        log_info "Installing Docker Desktop with Homebrew (your Mac password may be requested)..."
        retry brew install --cask docker
        PATH="$PATH:/Applications/Docker.app/Contents/Resources/bin"
    else
        log_err "Install Docker Desktop for Mac from https://www.docker.com/products/docker-desktop/"
        log_err "(choose Apple Silicon or Intel to match this Mac), open it once, then run this again."
        exit 1
    fi
fi

if ! docker info >/dev/null 2>&1; then
    log_info "Docker engine is not running. Starting Docker Desktop..."
    open -a Docker || true
    WAITED=0
    until docker info >/dev/null 2>&1; do
        WAITED=$((WAITED + 5))
        if [ "$WAITED" -ge 300 ]; then
            log_err "Docker Desktop did not start within 5 minutes."
            log_err "Open Docker Desktop, finish its first-run steps (accept the terms), then run this again."
            exit 1
        fi
        sleep 5
    done
fi
log_ok "Docker is running: $(docker --version)"

# ------------------------------------------------------------------------------
# STEP 2: Judge sandbox image
# ------------------------------------------------------------------------------
log_info "Step 2/4: Checking judge sandbox image (${IMAGE})..."

if ensure_image "$IMAGE" arbitrator-judge.tar.gz no-pull; then
    log_ok "Judge sandbox image is ready."
else
    if [ ! -f "$DIR/docker/Dockerfile" ]; then
        log_err "docker/Dockerfile is missing from this bundle; cannot build ${IMAGE}."
        exit 1
    fi
    log_info "Building ${IMAGE} for ${PLATFORM} (first run downloads Ubuntu + toolchains)..."
    if ! retry docker buildx build --platform "$PLATFORM" -t "$IMAGE" --load "$DIR/docker"; then
        log_err "Could not build ${IMAGE}. Check the internet connection, or rebuild the bundle with --with-images."
        exit 1
    fi
    log_ok "Judge sandbox image built successfully."
fi

# ------------------------------------------------------------------------------
# STEP 3: MySQL 8 container
# ------------------------------------------------------------------------------
log_info "Step 3/4: Checking MySQL 8 database container..."

container_running() { [ "$(docker container inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null)" = "true" ]; }
container_exists()  { docker container inspect "$MYSQL_CONTAINER" >/dev/null 2>&1; }
container_port()    { docker container inspect -f '{{range $k, $v := .HostConfig.PortBindings}}{{range $v}}{{.HostPort}}{{end}}{{end}}' "$MYSQL_CONTAINER" 2>/dev/null; }

# The server always uses this bundle's own MySQL container, never another
# MySQL that happens to be installed on the Mac. If 3306 is taken, the
# container is published on the next free port.
create_container() {
    ensure_image mysql:8.0 mysql-8.0.tar.gz prefer-native
    local port=3306
    while [ "$port" -le 3330 ]; do
        if port_in_use "$port"; then
            [ "$port" = 3306 ] && log_warn "Port 3306 is used by another program (probably a MySQL installed on this Mac)."
        else
            log_info "Creating MySQL 8 container '${MYSQL_CONTAINER}' on 127.0.0.1:${port}..."
            # Published on loopback only: the database is not reachable from the LAN.
            # Data lives in the arbitrator-mysql-data volume, which survives re-creation.
            if docker run -d \
                    --name "$MYSQL_CONTAINER" \
                    --restart unless-stopped \
                    -p "127.0.0.1:${port}:3306" \
                    -e MYSQL_DATABASE=arbitrator \
                    -e MYSQL_USER="$DB_USER" \
                    -e MYSQL_PASSWORD="$DB_PASS" \
                    -e MYSQL_ROOT_PASSWORD="$DB_PASS" \
                    -v arbitrator-mysql-data:/var/lib/mysql \
                    mysql:8.0 >/dev/null; then
                log_ok "MySQL container created."
                return 0
            fi
            # A port can be reserved without listening; docker refuses it.
            docker rm -f "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
        fi
        port=$((port + 1))
    done
    log_err "Could not start MySQL on any port from 3306 to 3330."
    exit 1
}

if container_running; then
    log_ok "MySQL container '${MYSQL_CONTAINER}' is already running."
elif container_exists; then
    log_info "Starting existing MySQL container '${MYSQL_CONTAINER}'..."
    if ! docker start "$MYSQL_CONTAINER" >/dev/null 2>&1; then
        # Usually its old port is now taken by another program. The data is in
        # the named volume, so re-creating the container keeps it.
        log_warn "The existing container could not start (its port is probably taken). Re-creating it; stored data is kept."
        docker rm -f "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
        create_container
    fi
else
    create_container
fi

MYSQL_PORT="$(container_port || true)"
if [ -z "$MYSQL_PORT" ]; then
    log_err "MySQL container '${MYSQL_CONTAINER}' does not publish port 3306. Remove it (docker rm -f ${MYSQL_CONTAINER}; data is kept) and re-run."
    exit 1
fi
log_ok "MySQL is published on 127.0.0.1:${MYSQL_PORT}."

# TCP ping only succeeds once the final server is up, not the entrypoint's
# temporary socket-only init server.
log_info "Waiting for MySQL to accept connections..."
WAITED=0
until docker exec -e MYSQL_PWD="$DB_PASS" "$MYSQL_CONTAINER" \
        mysqladmin ping -h 127.0.0.1 --protocol=tcp -uroot --silent >/dev/null 2>&1; do
    WAITED=$((WAITED + 2))
    if [ "$WAITED" -ge 180 ]; then
        log_err "MySQL did not become ready within 3 minutes. Its log:"
        docker logs --tail 30 "$MYSQL_CONTAINER" || true
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

# Same TCP path and credentials the server uses, so a mismatch is reported
# here in plain words instead of as a Java stack trace.
if ! docker exec -e MYSQL_PWD="$DB_PASS" "$MYSQL_CONTAINER" \
        mysql -h 127.0.0.1 --protocol=tcp -u"$DB_USER" -e "SELECT 1" arbitrator >/dev/null 2>&1; then
    log_err "User '${DB_USER}' cannot log in to the 'arbitrator' database."
    exit 1
fi
log_ok "Database login as '${DB_USER}' works."

if [ "$SETUP_ONLY" -eq 1 ]; then
    log_ok "Setup complete (--setup-only); server not started."
    exit 0
fi

# ------------------------------------------------------------------------------
# STEP 4: Java and server launch
# ------------------------------------------------------------------------------
log_info "Step 4/4: Locating Java..."

# A Java runtime is CPU-specific: jre-aarch64 for Apple Silicon, jre-x64 for Intel.
if [ "$ARCH" = "arm64" ]; then JRE_DIR="$DIR/jre-aarch64"; else JRE_DIR="$DIR/jre-x64"; fi
JAVA_CMD="$JRE_DIR/bin/java"
if [ ! -f "$JAVA_CMD" ]; then
    log_err "The bundled Java for this Mac ($ARCH) is missing: $JRE_DIR. Copy the complete admin folder again."
    exit 1
fi
chmod u+x "$JRE_DIR"/bin/* "$JRE_DIR/lib/jspawnhelper" 2>/dev/null || true
if ! "$JAVA_CMD" -version >/dev/null 2>&1; then
    log_err "The bundled Java in $JRE_DIR does not start. Copy the admin folder into your home folder"
    log_err "(not a USB drive or network folder) and run start-server.command from there."
    exit 1
fi
log_ok "Using Java: $JAVA_CMD"

JAR_FILE=""
for f in "$DIR"/arbitrator-server*.jar; do
    if [ -f "$f" ]; then
        JAR_FILE="$f"
        break
    fi
done
if [ -z "$JAR_FILE" ]; then
    log_err "Server JAR not found in $DIR"
    exit 1
fi

SERVER_PORT=8080
for arg in "$@"; do
    case "$arg" in
        --server.port=*) SERVER_PORT="${arg#--server.port=}" ;;
    esac
done
if port_in_use "$SERVER_PORT"; then
    log_err "Port ${SERVER_PORT} is already in use, probably by an Arbitrator server that is still running."
    lsof -nP -iTCP:"$SERVER_PORT" -sTCP:LISTEN || true
    log_err "Close that server's window (or press Ctrl+C in it), then run this again."
    exit 1
fi

echo -e "\n${BOLD}${GREEN}==> Launching Arbitrator Server...${NC}"
echo -e "${BOLD}${GREEN}==> Instructor Console: http://localhost:${SERVER_PORT}/admin${NC}"
echo -e "${BOLD}${GREEN}==> Keep this window open while the contest runs; Ctrl+C stops the server.${NC}"
echo -e "${BOLD}${GREEN}==> If macOS asks whether Java may accept incoming connections, choose Allow.${NC}\n"

# Not exec: the script must regain control to keep the window open on failure.
set +e
"$JAVA_CMD" -jar "$JAR_FILE" \
    --spring.datasource.url="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/arbitrator?serverTimezone=UTC&createDatabaseIfNotExist=true" \
    --spring.datasource.username="${DB_USER}" \
    --spring.datasource.password="${DB_PASS}" \
    "$@"
SERVER_EXIT=$?
set -e
if [ "$SERVER_EXIT" -ne 0 ] && [ "$SERVER_EXIT" -ne 130 ]; then
    log_err "The server failed to start or crashed. The cause is the last 'Caused by:' line above (also in $LOG_FILE)."
    exit "$SERVER_EXIT"
fi
