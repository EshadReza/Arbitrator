// Memory hog: allocates and TOUCHES far more than any sane limit,
// so peak RSS blows past memory_limit_kb -> MLE (TBD-02 resolved).
#include <cstring>
#include <vector>
int main() {
    std::vector<char*> blocks;
    for (int i = 0; i < 4096; i++) {              // up to 4 GiB attempted
        char* p = new (std::nothrow) char[1 << 20];
        if (!p) break;
        std::memset(p, 1, 1 << 20);               // touch every page
        blocks.push_back(p);
    }
    return 0;
}
