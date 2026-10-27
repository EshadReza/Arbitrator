#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Admin - one-click server start (Ubuntu 26.04 "Resolute Raccoon", x86_64)
#
#  1. Installs Docker (Ubuntu's docker.io + docker-buildx) if missing, starts
#     the service, and grants this user socket access
#  2. Loads or builds the judge sandbox image (arbitrator-judge:latest)
#  3. Loads or pulls mysql:8.0, creates/starts the arbitrator-mysql container and syncs the DB user
#  4. Launches the server with the bundled JRE (or installs a system Java)
#
# Every step is skipped when it is already done, so re-running is safe.
# Images in images/ (from prepare-zero-download-bundles.py --with-images) are
# loaded instead of downloaded. Network steps are retried.
# Override defaults with DB_USER, DB_PASS, ARBITRATOR_DOCKER_IMAGE and
# ARBITRATOR_DOCKER_PLATFORM environment variables. Pass --setup-only to
# prepare Docker and MySQL without starting the server; any other arguments
# are passed to the server.
# ==============================================================================
set -euo pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

# Started from the file manager without a terminal: reopen inside one so the
# output is visible (Ptyxis is Ubuntu 26.04's default terminal).
if [ ! -t 1 ] && [ -z "${ARBITRATOR_IN_TERMINAL:-}" ]; then
    export ARBITRATOR_IN_TERMINAL=1
    for term in ptyxis gnome-terminal; do
        if command -v "$term" >/dev/null 2>&1; then
            exec "$term" -- bash "$DIR/start-server.sh" "$@"
        fi
    done
    if command -v x-terminal-emulator >/dev/null 2>&1; then
        exec x-terminal-emulator -e bash "$DIR/start-server.sh" "$@"
    fi
fi

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

# apt-get update that repairs a leftover Docker apt source. Earlier bundle
# versions added download.docker.com with a key fetched by curl; a timed-out
# fetch leaves an empty key file, and every apt-get update then fails with
# NO_PUBKEY. This bundle installs Docker from the Ubuntu archive instead, so
# such a source is disabled (renamed, not deleted).
apt_update() {
    local out
    sudo -v  # ask for the password up front; the update's output is captured below
    if out="$(sudo apt-get update 2>&1)" && ! grep -q "NO_PUBKEY" <<<"$out"; then
        return 0
    fi
    if grep -q "download.docker.com" <<<"$out"; then
        log_warn "The Docker apt repository on this PC has a missing or broken signing key. Disabling it."
        for src in /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
            if [ -f "$src" ] && grep -q "download.docker.com" "$src"; then
                sudo mv "$src" "$src.disabled-by-arbitrator"
                log_info "Disabled $src (renamed to $src.disabled-by-arbitrator)"
            fi
        done
        retry sudo apt-get update && return 0
    fi
    echo "$out" >&2
    log_err "apt-get update failed. Check the network or the APT sources listed above."
    return 1
}

# Makes an image available: already present, else bundled images/<tar>, else
# pulled from the registry with retries.
ensure_image() {
    local image="$1" tarball="$DIR/images/$2"
    if docker image inspect "$image" >/dev/null 2>&1; then
        return 0
    fi
    if [ -f "$tarball" ]; then
        log_info "Loading ${image} from the bundle (no download)..."
        docker load -i "$tarball" >/dev/null
        docker image inspect "$image" >/dev/null 2>&1 && return 0
        log_warn "$(basename "$tarball") did not contain ${image}."
    fi
    [ "$#" -ge 3 ] && [ "$3" = "--no-pull" ] && return 1
    log_info "Downloading ${image}..."
    if ! retry docker pull "$image"; then
        log_err "Could not download ${image}. Check the internet connection, or rebuild the bundle with --with-images."
        exit 1
    fi
}

# Copies over FAT/exFAT/NTFS USB drives and some network shares lose the Unix
# executable bit, and some mounts forbid running programs at all (noexec).
# Restore the bit on the bundled JRE (no sudo needed for files we own), and
# if it still cannot run, explain how to move the folder somewhere that allows it.
bundled_java_usable() {
    local java="$DIR/jre/bin/java"
    [ -f "$java" ] || return 1
    if [ ! -x "$java" ] || [ ! -x "$DIR/jre/lib/jspawnhelper" ]; then
        chmod u+x "$DIR"/jre/bin/* "$DIR/jre/lib/jspawnhelper" 2>/dev/null || true
    fi
    if "$java" -version >/dev/null 2>&1; then
        return 0
    fi
    local mount_info
    mount_info="$(findmnt -n -o FSTYPE,OPTIONS -T "$DIR" 2>/dev/null || true)"
    echo "[ERROR] The bundled Java in $DIR/jre cannot run from this location." >&2
    echo "        Drive type/options: ${mount_info:-unknown}" >&2
    echo "        This happens on USB drives formatted FAT32/exFAT/NTFS, on network" >&2
    echo "        folders, and on drives mounted 'noexec'. Copy the whole folder into" >&2
    echo "        your home folder and run it from there (no sudo needed):" >&2
    echo "            cp -r \"$DIR\" ~/$(basename "$DIR")" >&2
    echo "            bash ~/$(basename "$DIR")/$(basename "$0")" >&2
    echo "        If it still fails there, the home folder is noexec too; ask the lab" >&2
    echo "        administrator for a folder that allows running programs." >&2
    exit 1
}

# True when this account may use sudo (root, or a member of sudo/admin).
can_sudo() { [ "$EUID" -eq 0 ] || id -nG | grep -qwE 'sudo|admin|wheel'; }

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
echo -e "${BOLD}      Arbitrator Admin - Server Start (Ubuntu)       ${NC}"
echo -e "${BOLD}====================================================${NC}"

if [ -r /etc/os-release ]; then
    . /etc/os-release
    if [ "${ID:-}" != "ubuntu" ] || [ "${VERSION_CODENAME:-}" != "resolute" ]; then
        log_warn "This bundle targets Ubuntu 26.04 (resolute); detected ${PRETTY_NAME:-unknown}. Continuing anyway."
    fi
fi

# ------------------------------------------------------------------------------
# STEP 1: Docker
# ------------------------------------------------------------------------------
log_info "Step 1/4: Checking Docker installation..."

NO_SUDO_DOCKER_HELP="Ask the lab administrator to run once:
        sudo apt-get install -y docker.io docker-buildx
        sudo usermod -aG docker $(id -un)
    then log out and back in, and run this script again."

if ! command -v docker >/dev/null 2>&1; then
    if ! can_sudo; then
        log_err "Docker is not installed, and this account cannot install software (no sudo)."
        echo "    $NO_SUDO_DOCKER_HELP" >&2
        exit 1
    fi
    if ! command -v apt-get >/dev/null 2>&1; then
        log_err "Docker is missing and automatic install supports Ubuntu (apt) only."
        exit 1
    fi
    # Ubuntu's own archive packages: no third-party repository or signing key,
    # and they work through a lab's local Ubuntu mirror.
    log_info "Installing Docker from the Ubuntu archive (requires sudo)..."
    apt_update
    retry sudo apt-get install -y docker.io docker-buildx
    log_ok "Docker installation completed."
else
    log_ok "Docker is installed: $(docker --version)"
fi

if ! systemctl is-active --quiet docker 2>/dev/null && can_sudo; then
    log_info "Starting docker.service..."
    sudo systemctl enable --now docker
fi

if ! docker ps >/dev/null 2>&1 && ! can_sudo; then
    log_err "This account cannot use Docker (not in the 'docker' group, or the service is stopped), and has no sudo."
    echo "    $NO_SUDO_DOCKER_HELP" >&2
    exit 1
fi

if ! docker ps >/dev/null 2>&1; then
    log_warn "Current session cannot access the Docker daemon. Fixing permissions..."
    if [ "$EUID" -ne 0 ] && ! id -nG | grep -qw docker; then
        sudo usermod -aG docker "$(id -un)" 2>/dev/null || true
    fi
    # Group membership only applies to new logins; open the socket for this session.
    sudo chmod 666 /var/run/docker.sock 2>/dev/null || true
fi

if ! docker ps >/dev/null 2>&1; then
    log_err "Failed to access the Docker daemon. Check: systemctl status docker"
    exit 1
fi
log_ok "Docker daemon is active and accessible."

# ------------------------------------------------------------------------------
# STEP 2: Judge sandbox image
# ------------------------------------------------------------------------------
log_info "Step 2/4: Checking judge sandbox image (${IMAGE})..."

if ensure_image "$IMAGE" arbitrator-judge.tar.gz --no-pull; then
    log_ok "Judge sandbox image is ready."
else
    if [ ! -f "$DIR/docker/Dockerfile" ]; then
        log_err "docker/Dockerfile is missing from this bundle; cannot build ${IMAGE}."
        exit 1
    fi
    log_info "Building ${IMAGE} for ${PLATFORM} (first run downloads Ubuntu + toolchains)..."
    if docker buildx version >/dev/null 2>&1; then
        BUILD=(docker buildx build --platform "$PLATFORM" -t "$IMAGE" --load "$DIR/docker")
    else
        BUILD=(docker build -t "$IMAGE" "$DIR/docker")
    fi
    if ! retry "${BUILD[@]}"; then
        log_err "Could not build ${IMAGE} (it downloads Ubuntu packages). Check the internet connection, or rebuild the bundle with --with-images."
        exit 1
    fi
    log_ok "Judge sandbox image built successfully."
fi

# ------------------------------------------------------------------------------
# STEP 3: MySQL 8 container
# ------------------------------------------------------------------------------
log_info "Step 3/4: Checking MySQL 8 database container..."

container_running() { docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; }
container_exists()  { docker ps -a --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; }
port_in_use()       { ss -tln 2>/dev/null | grep -qE "[:.]$1[[:space:]]"; }
container_port()    { docker port "$MYSQL_CONTAINER" 3306/tcp 2>/dev/null | head -n1 | sed 's/.*://'; }

# The server always uses this bundle's own MySQL container, never another
# MySQL that happens to be installed on the PC. If 3306 is taken (for example
# by Ubuntu's mysql-server), the container is published on the next free port.
create_container() {
    local port
    for port in 3306 $(seq 3307 3330); do
        if ! port_in_use "$port"; then
            break
        fi
    done
    if port_in_use "$port"; then
        log_err "No free port between 3306 and 3330 for MySQL."
        exit 1
    fi
    if [ "$port" != 3306 ]; then
        log_warn "Port 3306 is used by another program (probably a MySQL installed on this PC); using port ${port}."
    fi
    ensure_image mysql:8.0 mysql-8.0.tar.gz
    log_info "Creating MySQL 8 container '${MYSQL_CONTAINER}' on 127.0.0.1:${port}..."
    # Published on loopback only: the database is not reachable from the LAN.
    # Data lives in the arbitrator-mysql-data volume, which survives re-creation.
    docker run -d \
        --name "$MYSQL_CONTAINER" \
        --restart unless-stopped \
        -p "127.0.0.1:${port}:3306" \
        -e MYSQL_DATABASE=arbitrator \
        -e MYSQL_USER="$DB_USER" \
        -e MYSQL_PASSWORD="$DB_PASS" \
        -e MYSQL_ROOT_PASSWORD="$DB_PASS" \
        -v arbitrator-mysql-data:/var/lib/mysql \
        mysql:8.0 >/dev/null
    log_ok "MySQL container created."
}

if container_running; then
    log_ok "MySQL container '${MYSQL_CONTAINER}' is already running."
elif container_exists; then
    log_info "Starting existing MySQL container '${MYSQL_CONTAINER}'..."
    if ! docker start "$MYSQL_CONTAINER" >/dev/null 2>&1; then
        # Usually its old port is now taken by another program. The data is in
        # the named volume, so re-creating the container keeps it.
        log_warn "The existing container could not start (its port is probably taken). Re-creating it; stored data is kept."
        docker rm "$MYSQL_CONTAINER" >/dev/null
        create_container
    fi
else
    create_container
fi

if ! container_running; then
    log_err "MySQL container '${MYSQL_CONTAINER}' is not running. Check: docker logs ${MYSQL_CONTAINER}"
    exit 1
fi
MYSQL_PORT="$(container_port)"
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

java_major() { "$1" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'; }

JAVA_CMD=""
if bundled_java_usable; then
    JAVA_CMD="$DIR/jre/bin/java"
elif command -v java >/dev/null 2>&1 && [ "$(java_major java)" -ge 17 ] 2>/dev/null; then
    JAVA_CMD="java"
elif ! can_sudo; then
    log_err "Java 17+ is not installed, and this account cannot install software (no sudo)."
    log_err "Use a bundle that includes the jre/ folder, or ask the lab administrator to run: sudo apt-get install openjdk-21-jre-headless"
    exit 1
elif command -v apt-get >/dev/null 2>&1; then
    # Spring Boot 3.2 supports Java 17-21, so prefer 21 over Ubuntu's newer default JDK.
    log_info "No bundled JRE or Java 17+ found. Installing OpenJDK 21 (requires sudo)..."
    apt_update
    retry sudo apt-get install -y openjdk-21-jre-headless || sudo apt-get install -y openjdk-17-jre-headless
    for candidate in /usr/lib/jvm/java-21-openjdk-amd64/bin/java /usr/lib/jvm/java-17-openjdk-amd64/bin/java; do
        if [ -x "$candidate" ]; then
            JAVA_CMD="$candidate"
            break
        fi
    done
fi
if [ -z "$JAVA_CMD" ]; then
    log_err "No usable Java found. Bundle a JRE or install Java 17/21."
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
    ss -tlnp 2>/dev/null | grep -E "[:.]${SERVER_PORT}[[:space:]]" || true
    log_err "Close that server's window (or press Ctrl+C in it), then run this script again."
    exit 1
fi

echo -e "\n${BOLD}${GREEN}==> Launching Arbitrator Server...${NC}"
echo -e "${BOLD}${GREEN}==> Instructor Console: http://localhost:${SERVER_PORT}/admin${NC}"
echo -e "${BOLD}${GREEN}==> Keep this window open while the contest runs; Ctrl+C stops the server.${NC}\n"

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
