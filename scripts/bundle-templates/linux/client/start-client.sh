#!/usr/bin/env bash
# ==============================================================================
# Arbitrator Participant - one-click client start (Ubuntu 26.04, x86_64)
# Edit server.properties (server.host) to point at the instructor PC first.
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
            exec "$term" -- bash "$DIR/start-client.sh" "$@"
        fi
    done
    if command -v x-terminal-emulator >/dev/null 2>&1; then
        exec x-terminal-emulator -e bash "$DIR/start-client.sh" "$@"
    fi
fi

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

java_major() { "$1" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'; }

JAVA_CMD=""
if bundled_java_usable; then
    JAVA_CMD="$DIR/jre/bin/java"
elif command -v java >/dev/null 2>&1 && [ "$(java_major java)" -ge 17 ] 2>/dev/null; then
    JAVA_CMD="java"
elif ! can_sudo; then
    echo "Error: Java 17+ is not installed, and this account cannot install software." >&2
    echo "       Use a bundle that includes the jre/ folder, or ask the lab administrator" >&2
    echo "       to run: sudo apt-get install openjdk-21-jre" >&2
    exit 1
elif command -v apt-get >/dev/null 2>&1; then
    echo "No bundled JRE or Java 17+ found. Installing OpenJDK 21 (requires sudo)..."
    sudo apt-get update
    sudo apt-get install -y openjdk-21-jre || sudo apt-get install -y openjdk-17-jre
    for candidate in /usr/lib/jvm/java-21-openjdk-amd64/bin/java /usr/lib/jvm/java-17-openjdk-amd64/bin/java; do
        if [ -x "$candidate" ]; then
            JAVA_CMD="$candidate"
            break
        fi
    done
fi
if [ -z "$JAVA_CMD" ]; then
    echo "Error: No usable Java found. Bundle a JRE or install Java 17/21." >&2
    exit 1
fi

JAR_FILE="$DIR/arbitrator-client-linux.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "Error: arbitrator-client-linux.jar not found in $DIR" >&2
    exit 1
fi

# Ubuntu 26.04 ships a Wayland-only GNOME session. JavaFX renders through
# XWayland; pin GTK to it explicitly.
export GDK_BACKEND="${GDK_BACKEND:-x11}"

echo "Starting Arbitrator Client..."
# Not exec: the script must regain control to keep the window open on failure.
"$JAVA_CMD" -jar "$JAR_FILE" "$@"
