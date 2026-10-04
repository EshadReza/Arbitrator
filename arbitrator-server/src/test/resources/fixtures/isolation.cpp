// Test-only, bounded probes. No real secrets or network payloads.
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
void require(bool condition) { if (!condition) throw std::runtime_error("isolation assertion failed"); }
int main() {
    std::string mode; std::getline(std::cin, mode);
    if (mode == "control") { std::cout << "control-ok\n"; return 0; }
    if (mode == "loop") { for (;;) {} }
    if (mode == "stdout" || mode == "stderr") {
        (mode == "stdout" ? std::cout : std::cerr) << std::string(2 * 1024 * 1024, 'x');
        return 0;
    }
    require(mode == "isolation");
    std::string host, sibling, value; std::getline(std::cin, host); std::getline(std::cin, sibling);
    std::ifstream control("/sandbox/positive-control.txt"); control >> value; require(value == "mounted-control");
    require(getuid() != 0);
    require(access("/var/run/docker.sock", F_OK) != 0);
    require(access("/run/docker.sock", F_OK) != 0);
    for (const auto &path : {host, sibling, "/proc/1/root" + host, "/proc/1/root" + sibling,
            std::string("../fake-host-secret.txt"), std::string("../fake-sibling/fake-secret.txt"),
            std::string("/var/run/docker.sock")}) {
        int fd = open(path.c_str(), O_RDONLY); if (fd >= 0) close(fd); require(fd < 0);
    }
    for (const auto &path : {host, sibling, std::string("/etc/passwd"), std::string("/etc/forbidden-write"),
            std::string("/sandbox/forbidden-write"), std::string("/sys/forbidden-write"),
            std::string("/proc/sys/kernel/hostname")}) {
        int fd = open(path.c_str(), O_WRONLY | O_CREAT, 0600); if (fd >= 0) close(fd); require(fd < 0);
    }
    { std::ofstream tmp("/tmp/allowed-control"); tmp << "private-tmp"; require(tmp.good()); }
    std::ifstream tmp("/tmp/allowed-control"); tmp >> value; require(value == "private-tmp");
    require(std::string(getenv("HOME")) == "/tmp");
    require(std::string(getenv("PATH")) == "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
    for (const char *key : {"LD_PRELOAD", "LD_LIBRARY_PATH", "LIBRARY_PATH", "CPATH", "CPLUS_INCLUDE_PATH",
            "PYTHONHOME", "PYTHONPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "ENV",
            "BASH_ENV", "ARBITRATOR_JWT_SECRET", "DB_PASSWORD", "AWS_SECRET_ACCESS_KEY"}) {
        const char *v = getenv(key); require(v == nullptr || *v == '\0');
    }
    std::ifstream routes("/proc/net/route"); require(routes.good()); std::getline(routes, value);
    while (std::getline(routes, value)) { std::istringstream row(value); std::string iface; if (row >> iface) require(iface == "lo"); }
    int local = socket(AF_INET, SOCK_STREAM, 0); require(local >= 0);
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    require(bind(local, (sockaddr*)&address, sizeof(address)) == 0); require(listen(local, 1) == 0);
    socklen_t length = sizeof(address); require(getsockname(local, (sockaddr*)&address, &length) == 0);
    int client = socket(AF_INET, SOCK_STREAM, 0); require(connect(client, (sockaddr*)&address, length) == 0);
    int accepted = accept(local, nullptr, nullptr); require(accepted >= 0); close(accepted); close(client); close(local);
    for (const auto &target : {std::pair<const char*, int>{"169.254.169.254", 80}, {"1.1.1.1", 53}}) {
        int fd = socket(AF_INET, SOCK_STREAM, 0); require(fd >= 0);
        address.sin_port = htons(target.second); inet_pton(AF_INET, target.first, &address.sin_addr);
        int result = connect(fd, (sockaddr*)&address, sizeof(address)); int error = errno; close(fd);
        require(result < 0 && (error == ENETUNREACH || error == EHOSTUNREACH));
    }
    std::cout << "isolation-ok\n";
}
