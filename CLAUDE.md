# Arbitrator — context for Claude

Offline LAN Online Judge for university programming labs.
**Read `STATUS.md` first** — it says what is done and what is next.

## Orientation (read in this order)

1. `STATUS.md` — current phase, what's done, what's next, known issues
2. `WORKFLOW_PLAN.md` — locked decisions (§0), ownership map (§3), sprint chunks (§6)
3. `rules.md` — the nine collaboration rules; Rule 1 (file ownership) governs every edit

Everything project-related lives in **this folder** — there are no docs one level up.

| File | What it is | Edited by |
|---|---|---|
| `CLAUDE.md` | this file — auto-loaded context | update when a locked decision or a "bites" item changes |
| `STATUS.md` | living progress tracker | **update whenever a chunk lands** |
| `WORKFLOW_PLAN.md` | the 4-sprint plan, ownership, GitHub bootstrap | rarely; it's the baseline |
| `rules.md` | collaboration rules | only by group agreement |
| `README.md` | setup + run instructions for a new developer | when setup steps change |
| `ARBITRATOR_BUNDLE.txt` | generated single-file copy of the whole tree, for handoff without git | never edit — regenerate with `python3 scripts/make-bundle.py` (git-ignored) |

## The team and who owns what

| Dev | Track | Owns |
|---|---|---|
| **Eshad** | Platform & Data | `server/{config,security,entity,repo}`, contest/problem/user services + controllers, Flyway `V1`–`V49`, admin panel |
| **Mahir** | Judge & Real-time | `server/{judge,realtime,leaderboard}`, submission + announcement services/controllers, `languages.yml`, `scripts/sandbox-run.sh`, Flyway `V50`–`V79` |
| **Zahin** | JavaFX Client | all of `arbitrator-client/**` including `arbitrator.css` |

**Rule 1 is not advisory.** Before editing, check who owns the file. Cross-track changes go through a `contract-change` issue, not a quiet edit.

## Locked decisions — do not reopen

| # | Decision |
|---|---|
| D1 | **MySQL 8** only. Not PostgreSQL (the SRS says Postgres in five places; that's a doc bug to fix, not a design choice). |
| D2 | **Linux only** for real deployment. macOS/Windows work for dev via a fallback path, but MLE, network isolation, and fork-bomb containment only hold on Linux. |
| D3 | Admin surface = **loopback filter AND ADMIN JWT**, both layers. |
| D4 | **HTTPS deferred** to post-v1.0. Plain HTTP/WS over the closed LAN. `server.scheme` stays configurable so enabling TLS later is one line. |

UI reference is **Codeforces-derived, information-dense** — now themed light AND dark (top-bar toggle or Ctrl+D), which supersedes both the SRS §4 "dark-themed" line and the earlier light-only note.

## Work vocabulary

Chunks are identified as `S<sprint>-<track><n>`, e.g. `S2-B3` = Sprint 2, Mahir's track, chunk 3.
They're listed in `WORKFLOW_PLAN.md` §6. Branches are `feat/<name>-<chunk-id>-<slug>`.

When starting work, say which chunk. When finishing, update `STATUS.md`.

## Build and run

```bash
mvn clean install                                    # all three modules
mvn -pl arbitrator-server spring-boot:run              # server (port 8080)
mvn -pl arbitrator-client exec:java -Dexec.mainClass=com.arbitrator.client.app.Launcher
#   ... add -Darbitrator.mock=true for canned data with no server
```

Demo credentials (seeded on first boot into an empty DB): `admin`/`admin123`, `alice`/`alice123`.

**Run the client via `com.arbitrator.client.app.Launcher`**, never `ArbitratorApp` and never
`mvn javafx:run`. In Eclipse: *Run As → Java Application* on `Launcher`, with **no VM arguments**.

Why: `javafx-web` declares `requires jdk.jsobject`, a module removed from the JDK in Java 11 and never
published to Maven Central, so JavaFX can never resolve on the **module path** from Maven deps alone
(`Module jdk.jsobject not found`). Launching from the **classpath** skips the module graph entirely.
`Launcher` also dodges "JavaFX runtime components are missing", which the JVM only raises when the main
class itself extends `Application`. Net effect: no `--module-path`, no machine-specific SDK paths.

## Things that bite (learned the hard way)

- **`languages.yml` is NOT auto-loaded.** It needs `spring.config.import: optional:classpath:languages.yml`
  in `application.yml`. Without it `JudgeProperties.languages` binds empty and every submission returns a
  bare RE with no explanation.
- **No `-static` in the C++ compile command.** macOS clang has no static libc (`ld: library 'crt0.o' not found`),
  and the sandbox's isolation doesn't depend on static linking anyway.
- **Enum columns need `@JdbcTypeCode(SqlTypes.VARCHAR)`.** Hibernate 6's MySQL dialect otherwise expects a native
  `ENUM(...)` column and fails schema validation against Flyway's `VARCHAR`.
- **Port 8080 conflicts**: a server left running in Eclipse blocks any terminal-launched restart. Check
  `lsof -i :8080 -sTCP:LISTEN` before assuming a fix didn't work.
- **Eclipse silently overwrites Maven's output with broken classes.** When anything is added to
  `arbitrator-common`, Eclipse's build path goes stale, its compiler emits *error-stub* `.class` files into
  `target/classes` (they throw `Unresolved compilation problems: X cannot be resolved` at runtime), and it
  overwrites whatever Maven just built. Symptom: `mvn clean install` says BUILD SUCCESS but the app dies with
  an **unqualified** `ClassNotFoundException`/`NoClassDefFoundError`.
  Fix: select all four projects → **Maven → Update Project (Alt+F5)** with "Force Update" ticked →
  **Project → Clean all**. Diagnose with:
  `javap -p -c arbitrator-server/target/classes/<Class>.class | grep "Unresolved compilation"`.
  **Always run `mvn clean install` after an Alt+F5**, and prefer Maven over Eclipse launchers when a change
  spans modules.
- **Untrusted-process containment has three layers; do not remove any of them.**
  (1) `ulimit` in a shell wrapper around BOTH compile and run — sandboxing only the run step let
  `#include </dev/urandom>` drive the *preprocessor* to eat all host RAM. (2) A **relative** `ulimit -u`
  (current user process count + 32) on the run phase only: `RLIMIT_NPROC` is **per-UID** on macOS/BSD, so an
  absolute value like 64 breaks even the compiler (`posix_spawn failed: Resource temporarily unavailable`),
  and no cap at all lets a fork bomb fill the process table until the whole machine stops forking.
  (3) A `pkill -f <workDir>` sweep in `cleanup()` and at startup — `ProcessHandle.descendants()` is only a
  *snapshot*, so a rapidly forking program outruns it and children survive as orphaned `prog` processes
  that no longer belong to the JVM and are never reaped.
  **Never test a fork bomb on a dev machine** — use a bounded fork loop whose children `_exit(0)`, or the
  Linux box where `prlimit --nproc` bounds the process tree properly.
- **`peakMemoryKb` is `-1` on macOS.** Expected — real peak RSS only comes from the Linux sandbox script.
- **Raw SQL touching times must use `UTC_TIMESTAMP()`, never `NOW()`.** `contests.start_time` is a zoneless
  `DATETIME` and the JDBC url sets `serverTimezone=UTC`, so Java reads stored values as UTC. `NOW()` writes
  machine-local time; on a +06 machine that puts the contest six hours in the future and **every submission
  returns 403** with no obvious cause. JPA writes (`Instant`) are already correct — this only bites hand-written
  SQL and DB tools.
- **Start testing from a clean slate**: `mysql -u root arbitrator < scripts/reset-dev-data.sql`. Stale solved
  state makes badge behaviour look broken — a solved problem shows its green tick and hides later failures,
  which is correct but confusing if the "solved" came from someone else's test run.
- **The client's WebSocket needs `tomcat-api` explicitly.** `tomcat-websocket` uses
  `org.apache.tomcat.InstanceManagerBindings` at runtime but doesn't declare it, so the client dies with
  `NoClassDefFoundError` the instant it opens a socket — verdicts and live standings silently never arrive
  while everything else keeps working. The server never sees this (tomcat-embed-core bundles the class), so
  **a passing server-side integration test does not prove the client can connect.** Verify from the client
  side when touching WebSocket wiring.
- **Don't use Mockito in tests.** Byte Buddy (pinned by the Spring Boot 3.2.5 BOM) supports Java 22 at most,
  and this dev machine runs JDK 26 — every `mock()` call dies with "Java 26 (70) is not supported".
  The project *targets* 17, so teammates on openjdk-17 won't see it, but tests must run everywhere.
  Use `java.lang.reflect.Proxy` fakes for repository interfaces and anonymous subclasses for concrete
  services — see `ProblemPackageServiceTest` for the pattern.

## Contest lifecycle

`DRAFT → LOBBY → ACTIVE ⇄ PAUSED/FROZEN → ENDED`

`LOBBY` is the holding room: students may enter and wait, but **problems are not
released and no clock runs** — releasing statements early would let people read
and plan before the timer starts. `ContestState` carries the rules
(`isJoinable`, `acceptsSubmissions`, `releasesProblems`, `hasStarted`); check
there rather than comparing states by hand.

A freshly booted server starts **nothing**, and this is enforced, not merely
seeded: `ContestBootReset` returns every LOBBY/ACTIVE/PAUSED/FROZEN contest to
DRAFT and clears its clock at startup. Contest state lives in MySQL, so without
this a contest left ACTIVE at shutdown came back ACTIVE with a clock that had
been running against the wall clock the whole time the server was down — the
first student to open the client walked into a contest nobody had started.
ENDED contests are left alone. Set `arbitrator.contest.reset-on-boot=false` only
if the server is meant to be bounced mid-contest and resumed.

Consequence for tests and scripts: **do not look up "the contest" by
`state != DRAFT`** — after a boot there isn't one. Take the contest by id, or
take the first row, then set the state you need.

## House style

Match the surrounding code. Comments explain *why* (usually citing an SRS requirement ID like FR-15, BR-01,
NFR-R04), not what. Keep requirement traceability in comments when adding features — it's how the SRS gets
verified at acceptance.
