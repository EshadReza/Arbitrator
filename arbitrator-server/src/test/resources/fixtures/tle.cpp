// Infinite loop: the sandbox must terminate it at 2x the time limit
// (Gherkin scenario 2, NFR-R04).
#include <iostream>
int main() {
    volatile unsigned long long x = 0;
    while (true) { x++; }
    std::cout << x << "\n";
    return 0;
}
