#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Judge Server - Environment Setup Script
# Focus: Ubuntu 22.04 LTS (Jammy Jellyfish, x86_64) / Linux
#
# Automates:
#  1. Docker Engine & Buildx installation and service verification
#  2. User permissions on /var/run/docker.sock
#  3. Building the judge sandbox Docker image (arbitrator-judge:latest)
#  4. Launching and configuring the MySQL 8 Docker container (arbitrator-mysql)
# ==============================================================================
set -e

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
log_err()  { echo -e "${RED}[ERROR]${NC} $1"; }

echo -e "${BOLD}====================================================${NC}"
echo -e "${BOLD}      Arbitrator Admin - Host & Docker Setup         ${NC}"
echo -e "${BOLD}====================================================${NC}"

# ------------------------------------------------------------------------------
# STEP 1: Verify / Install Docker Engine
# ------------------------------------------------------------------------------
log_info "Step 1/3: Checking Docker installation..."

if ! command -v docker >/dev/null 2>&1; then
    log_warn "Docker is not installed."
    log_info "Installing Docker Engine & Buildx for Ubuntu (requires sudo)..."
    sudo apt-get update -y
    sudo apt-get install -y ca-certificates curl gnupg
    sudo install -m 0755 -d /etc/apt/keyrings
    if [ ! -f /etc/apt/keyrings/docker.gpg ]; then
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
        sudo chmod a+r /etc/apt/keyrings/docker.gpg
    fi
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
    sudo apt-get update -y
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin
    sudo systemctl enable --now docker
    sudo usermod -aG docker "$USER"
    sudo chmod 666 /var/run/docker.sock 2>/dev/null || true
    log_ok "Docker installation completed."
else
    log_ok "Docker is installed: $(docker --version)"
fi

# Ensure Docker service is active
if ! systemctl is-active --quiet docker 2>/dev/null; then
    log_warn "Docker service is not active. Starting docker.service..."
    sudo systemctl enable --now docker
fi

# Ensure user can communicate with Docker daemon socket
if ! docker ps >/dev/null 2>&1; then
    log_warn "Current session cannot access Docker daemon. Fixing permissions..."
    sudo chmod 666 /var/run/docker.sock 2>/dev/null || true
    if [ "$EUID" -ne 0 ] && ! groups | grep -qw docker; then
        sudo usermod -aG docker "$USER" 2>/dev/null || true
    fi
fi

if ! docker ps >/dev/null 2>&1; then
    log_err "Failed to access Docker daemon. Please check systemctl status docker."
    exit 1
fi
log_ok "Docker daemon is active and accessible."

# ------------------------------------------------------------------------------
# STEP 2: Ensure Judge Sandbox Image (arbitrator-judge:latest)
# ------------------------------------------------------------------------------
log_info "Step 2/3: Checking judge sandbox image (arbitrator-judge:latest)..."

if ! docker image inspect arbitrator-judge:latest >/dev/null 2>&1; then
    log_info "Building arbitrator-judge:latest sandbox image..."
    if [ -d "$DIR/docker" ] && [ -f "$DIR/docker/Dockerfile" ]; then
        docker buildx build --platform linux/amd64 -t arbitrator-judge:latest "$DIR/docker"
    else
        docker build -t arbitrator-judge:latest - << 'EOF'
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y --no-install-recommends \
        g++ \
        openjdk-17-jdk-headless \
        python3 \
        time \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin sandbox
USER sandbox
WORKDIR /sandbox
EOF
    fi
    log_ok "Judge sandbox image built successfully."
else
    log_ok "Judge sandbox image is already built."
fi

# ------------------------------------------------------------------------------
# STEP 3: Ensure MySQL 8 is Running in Docker & Configured
# ------------------------------------------------------------------------------
log_info "Step 3/3: Checking MySQL 8 database container..."

MYSQL_CONTAINER="arbitrator-mysql"
DB_PASS="${DB_PASS:-changeme}"
DB_USER="${DB_USER:-arbitrator}"

if docker ps --format '{{.Names}}' | grep -Eq "^${MYSQL_CONTAINER}\$"; then
    log_ok "MySQL container '${MYSQL_CONTAINER}' is currently running."
elif docker ps -a --format '{{.Names}}' | grep -Eq "^${MYSQL_CONTAINER}\$"; then
    log_info "Starting existing MySQL container '${MYSQL_CONTAINER}'..."
    docker start "$MYSQL_CONTAINER" >/dev/null
    log_ok "MySQL container started."
else
    # Check if port 3306 is bound by another service (e.g. host MySQL)
    if ss -tlpn 2>/dev/null | grep -q ":3306 "; then
        log_warn "Port 3306 is already bound on host (possibly by host MySQL service)."
    else
        log_info "Creating and starting new MySQL 8 container '${MYSQL_CONTAINER}'..."
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
fi

# If container is running, wait until ready and synchronize user credentials
if docker ps --format '{{.Names}}' | grep -Eq "^${MYSQL_CONTAINER}\$"; then
    log_info "Waiting for MySQL database to become ready..."
    MAX_ATTEMPTS=45
    ATTEMPT=0
    until docker exec "$MYSQL_CONTAINER" mysqladmin ping -uroot -p"$DB_PASS" --silent >/dev/null 2>&1; do
        ATTEMPT=$((ATTEMPT + 1))
        if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
            log_err "MySQL container did not become ready after $MAX_ATTEMPTS seconds."
            exit 1
        fi
        sleep 1
    done
    log_ok "MySQL is accepting connections."

    # Guarantee user, database, and exact password matching for both '%' and 'localhost'
    log_info "Synchronizing database user '${DB_USER}' permissions and password..."
    docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$DB_PASS" >/dev/null 2>&1 <<EOF
CREATE DATABASE IF NOT EXISTS arbitrator;
CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
ALTER USER '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON arbitrator.* TO '${DB_USER}'@'%';
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON arbitrator.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
EOF
    log_ok "Database user '${DB_USER}' synchronized successfully."
fi

echo -e "${GREEN}====================================================${NC}"
echo -e "${GREEN}  Host and Docker environment setup complete!       ${NC}"
echo -e "${GREEN}====================================================${NC}"
