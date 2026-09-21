/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */
// Fork bomb (FMEA-08): prlimit --nproc=64 must contain it, and the
// process-group SIGKILL from timeout -k must reap every child.
#include <unistd.h>
int main() {
    while (true) { fork(); }
}
