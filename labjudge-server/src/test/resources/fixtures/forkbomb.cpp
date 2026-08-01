// Fork bomb (FMEA-08): prlimit --nproc=64 must contain it, and the
// process-group SIGKILL from timeout -k must reap every child.
#include <unistd.h>
int main() {
    while (true) { fork(); }
}
