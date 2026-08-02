#!/usr/bin/env bash
# sandbox-run.sh — owner: Mahir (rules.md Rule 1)
#
# Runs one untrusted program inside the Arbitrator sandbox (FR-10, NFR-S03/S04,
# NFR-R04, FMEA-02/08). Linux only (decision D2).
#
#   unshare -Urnpf new user + network + PID namespace -> no network, and every
#                  process dies with the namespace (fork bombs cannot escape)
#   prlimit        CPU seconds, address space (2x memory limit), process
#                  count (fork bombs), output file size (TBD-05), open files
#   timeout -k     SIGKILL at 2x the time limit  -> NFR-R04
#   /usr/bin/time  peak RSS (kbytes)             -> real MLE verdict (TBD-02)
#
# Usage:
#   sandbox-run.sh TL_MS MEM_KB WORKDIR INFILE OUTFILE ERRFILE METRICS -- cmd...
#
# Writes java.util.Properties-style metrics to METRICS:
#   exit=N  time_ms=N  peak_kb=N  timed_out=0|1
#
# Prerequisites (Ubuntu 22.04): util-linux (unshare, prlimit), coreutils
# (timeout), time (/usr/bin/time). Verify unprivileged user namespaces are
# enabled on the lab image: `unshare -Urnpf --mount-proc true` must succeed
# (risk #2 in
# WORKFLOW_PLAN §9).

set -u

TL_MS=$1; MEM_KB=$2; WORKDIR=$3; INFILE=$4; OUTFILE=$5; ERRFILE=$6; METRICS=$7
shift 7
[ "${1:-}" = "--" ] && shift

HARD_S=$(( (TL_MS * 2 + 999) / 1000 ))     # SIGKILL at 2x TL (NFR-R04)
CPU_S=$(( TL_MS / 1000 + 1 ))
AS_BYTES=$(( MEM_KB * 2 * 1024 ))          # hard cap at 2x; >1x is already MLE

# Address-space limits break the JVM (it reserves large virtual mappings).
# Java heap is capped via -Xmx in languages.yml instead; refined in S3-A1.
AS_OPT="--as=$AS_BYTES"
case "$1" in
    *java*) AS_OPT="" ;;
esac

RUSAGE="$WORKDIR/__rusage.txt"
rm -f "$RUSAGE"

TIME_PREFIX=""
if [ -x /usr/bin/time ]; then
    TIME_PREFIX="/usr/bin/time -o $RUSAGE -v"
fi

START=$(date +%s%3N)

# unshare flags, and why each one matters:
#   -U  user namespace      the program is never root on the host
#   -r  map to root inside  so it can set up its own namespaces
#   -n  network namespace   no network at all (NFR-S04)
#   -p  PID namespace       THE containment guarantee: the submission becomes
#                           PID 1 of its own namespace, and when PID 1 dies the
#                           kernel destroys every other process in it. A fork
#                           bomb therefore cannot outlive the kill or leave
#                           orphans behind — which is exactly what happens
#                           without -p, where children reparent to init and
#                           survive forever (FMEA-02/08).
#   -f  fork                required with -p: unshare itself cannot become the
#                           new PID 1, so it forks the child into the namespace.
#   --mount-proc            gives that namespace its own /proc, so the program
#                           cannot see or signal host processes.
#
# --mount-proc implies a mount namespace, which is also the hook for real
# filesystem isolation later (pivot_root into the work dir) — see STATUS.md.
# shellcheck disable=SC2086
timeout -k 1 "${HARD_S}s" \
    unshare -Urnpf --mount-proc \
    prlimit --cpu=$CPU_S $AS_OPT --nproc=64 --fsize=67108864 --nofile=64 \
    $TIME_PREFIX "$@" < "$INFILE" > "$OUTFILE" 2> "$ERRFILE"
EXIT=$?
END=$(date +%s%3N)
ELAPSED=$(( END - START ))

PEAK=-1
if [ -f "$RUSAGE" ]; then
    PEAK=$(grep -oP 'Maximum resident set size \(kbytes\): \K[0-9]+' "$RUSAGE" 2>/dev/null || echo -1)
    [ -z "$PEAK" ] && PEAK=-1
fi

# 124 = timeout fired, 137 = SIGKILL, 152 = SIGXCPU (CPU rlimit hit)
TIMED_OUT=0
if [ "$EXIT" -eq 124 ] || [ "$EXIT" -eq 137 ] || [ "$EXIT" -eq 152 ]; then
    TIMED_OUT=1
fi

{
    echo "exit=$EXIT"
    echo "time_ms=$ELAPSED"
    echo "peak_kb=$PEAK"
    echo "timed_out=$TIMED_OUT"
} > "$METRICS"

exit 0
