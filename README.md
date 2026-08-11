# Arbitrator

Offline, LAN-only Online Judge for university programming labs.
Spring Boot 3.2 judge server + JavaFX 21 student client + MySQL 8, judging inside Docker. Linux only for real deployment.

**Team:** Eshad (Platform & Data) · Mahir (Judge & Real-time) · Zahin (JavaFX Client)

> Read **rules.md** before your first commit. Ownership map and sprint plan
> live in **WORKFLOW_PLAN.md**. This checkout is Bundle 1 — the runnable
> vertical slice (login → submit → judge → live verdict).

## Prerequisites (Ubuntu 22.04)

```bash
sudo apt install openjdk-17-jdk maven mysql-server
```

No compiler toolchain (`g++`, `python3.10`, etc.) is needed on the host itself —
every compile and every submission run happens inside a Docker container (see
`scripts/docker/`), never as a direct child process of the server. Install
Docker instead:

```bash
# Ubuntu 22.04 — Docker Engine (see https://docs.docker.com/engine/install/ubuntu/)
# ... then add the account running arbitrator-server to the docker group:
sudo usermod -aG docker $USER    # log out/in for this to take effect
```

On a macOS/Windows dev machine, install Docker Desktop and make sure it's running instead.

## First-time setup (each developer, ~10 minutes)

```bash
# 1. database (your own local instance — rules.md Rule 6)
sudo mysql < scripts/init-db.sql

# 2. your local credentials (git-ignored)
cp arbitrator-server/src/main/resources/application-local.yml.example \
   arbitrator-server/src/main/resources/application-local.yml
# edit the password to match what you set in init-db.sql

# 3. build the sandbox image (once per machine; rebuild after scripts/docker/Dockerfile changes)
bash scripts/docker/build-sandbox-image.sh

# 4. build everything
mvn clean install
```

If you run the server from an IDE launcher (Eclipse "Run As → Java
Application") rather than a terminal, the launched process may not inherit
your shell's `PATH` and can fail to find `docker`. If so, add to
`application-local.yml`:
```yaml
arbitrator:
  judge:
    docker-binary: /usr/local/bin/docker   # wherever `which docker` points
```

## Run

```bash
# server (Flyway migrates, demo data seeds on first boot:
#         admin/admin123, alice/alice123, contest "Lab Contest #1")
mvn -pl arbitrator-server spring-boot:run

# client (in a second terminal)
mvn -pl arbitrator-client javafx:run

# client against canned data, no server needed (Zahin's daily mode)
mvn -pl arbitrator-client javafx:run -Darbitrator.mock=true
```

Admin panel: http://localhost:8080/admin — server machine only (loopback
lock + ADMIN JWT, decision D3).

## Verify the vertical slice

1. `mvn test -pl arbitrator-server` — VerdictEvaluator tests pass anywhere;
   the sandbox fixture suite (6 verdicts + fork bomb) passes wherever Docker
   runs, not just Linux, since Docker is the only execution path now.
2. Start server, start client, log in as `alice`.
3. Open problem A, paste the reference solution, submit:

```cpp
#include <iostream>
int main() { long long a, b; std::cin >> a >> b; std::cout << a + b << "\n"; }
```

4. A green **✓ Accepted** banner arrives over WebSocket within seconds.

## Module map

| Module | Owner | Contents |
|---|---|---|
| `arbitrator-common` | frozen contract | DTOs, enums, path/topic constants |
| `arbitrator-server` | Eshad + Mahir (see rules.md Rule 1) | REST, auth, judge engine, STOMP |
| `arbitrator-client` | Zahin | JavaFX UI, Codeforces-style theme |

Eclipse: `File → Import → Maven → Existing Maven Projects` → repo root.
Import `config/eclipse-formatter.xml`, enable format-on-save. Never commit
`.classpath` / `.project` / `.settings` (rules.md Rule 3).

## Problem packages (FR-05)

Instructors add problems by uploading a ZIP at http://localhost:8080/admin
(server machine only). Layout:

```
config.json                 {"code":"B","title":"Max of Three",
                             "timeLimitMs":1000,"memoryLimitKb":131072}
statement/statement.html    rendered in the client (.txt and .md also accepted)
tests/01.in  01.out         one pair per test, evaluated in ascending order
tests/02.in  02.out
```

Zipping the *folder* rather than its contents works too — a single wrapping
directory is stripped automatically.

A package is imported whole or not at all. Rejections list every problem found
(missing statement, an `.in` without its `.out`, duplicate problem code,
out-of-range limits), so one upload tells you everything to fix.
