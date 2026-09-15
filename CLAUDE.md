# Arbitrator — repository context

Arbitrator is an offline, LAN-first programming contest system for university labs. One instructor machine runs a Spring Boot server, MySQL, Docker-backed judging, and a loopback-only browser console. Students use a JavaFX desktop client over REST and STOMP/WebSocket.

Read these files before changing the repository:

1. `STATUS.md` — verified current state, known defects, and next priorities
2. `rules.md` — file ownership and collaboration rules; it is governance, not descriptive documentation
3. `WORKFLOW_PLAN.md` — current architecture, workflow, and roadmap
4. `README.md` — setup, run, packaging, and test commands

`ARBITRATOR_BUNDLE.txt` is a legacy generated snapshot and is not authoritative.

## Product boundary

The production-shaped application consists of three Maven modules:

| Module | Responsibility |
|---|---|
| `arbitrator-common` | Shared records, enums, API paths, and STOMP destinations |
| `arbitrator-server` | REST/auth, contest and content services, MySQL persistence, Docker judge, WebSockets, leaderboard, and static admin console |
| `arbitrator-client` | JavaFX participant UI, editor, statements, submissions, standings, and live updates |

`arbitrator-web` is a separate React 19/Vite 8/Tailwind 4 toolchain experiment. It is not included by the root Maven build, not served by Spring Boot, and not a replacement for the current admin console.

## Locked decisions

| # | Decision |
|---|---|
| D1 | MySQL 8 is the only application datastore. |
| D2 | Ubuntu 22.04 is the target lab deployment. Docker also makes judge development possible on macOS/Windows. |
| D3 | Instructor surfaces require a loopback request; admin API operations additionally require an `ADMIN` JWT. |
| D4 | HTTPS/WSS is deferred. v1 currently uses HTTP/WS on a controlled LAN. |
| D5 | One contest can be joinable/live at a time, although historical contests remain stored. |
| D6 | All participant compilation and execution, including custom checkers, runs in Docker; there is no host-process fallback. |

The UI is information-dense and Codeforces-inspired, with both light and dark themes.

## Current feature model

- Authentication: student self-registration, seeded instructor account, bcrypt cost 12, 12-hour JWT, explicit logout, in-memory single-active-session registry, and optional client MAC reporting
- Contest lifecycle: `DRAFT → LOBBY → ACTIVE`, then `PAUSED`, `FROZEN`, or `ENDED`; active contests can be paused/resumed, extended, frozen/unfrozen, scheduled, ended, or cloned
- Problems: ZIP import, HTML/TXT/Markdown/PDF statements, paired tests, test visibility, exact/custom checker selection, editing, PDF replacement, reordering, and deletion
- Judging: C++17, Java 17, Python 3.10; persist-before-queue; Docker compile/run; fail-fast tests; exact/custom checking; per-test output; AC/WA/TLE/MLE/CE/RE/OLE
- Live data: private verdict pushes plus contest-state, leaderboard, announcement, and presence updates over STOMP
- Participant tools: editor, syntax highlighting, drafts, file upload, custom run, submission history/source/tests, standings, announcements, materials, clarifications, themes, zoom, and shortcuts
- Instructor tools: contest controls, participant presence, submission feed/source/tests, standings, marks, manual penalties, verdict overrides, exports, notifications, announcements, materials, clarifications, and problem administration

Float tolerance and rejudge are not implemented. The repository also lacks a supported installer, automated UI/load/coverage suites, complete backup automation, and full localization.

## Build and run

```bash
bash scripts/docker/build-sandbox-image.sh
mvn clean install
mvn -pl arbitrator-server spring-boot:run
mvn -pl arbitrator-client exec:java
```

Use `com.arbitrator.client.app.Launcher` as the client entry point. Mock mode is:

```bash
mvn -pl arbitrator-client exec:java -Darbitrator.mock=true
```

JavaFX dependencies are platform-classified. The POM currently defaults to `mac-aarch64`; pass `-Djavafx.platform=linux` for the Ubuntu x86_64 client. The packaged name is `arbitrator-client-<platform>.jar`.

Demo data on an empty database: `admin` / `admin123`, `alice` / `alice123`, and draft contest `Lab Contest #1`.

## Runtime configuration

`application.yml` imports `languages.yml` explicitly. Do not remove that import: without it, the judge has no language commands.

Important environment variables:

| Variable | Purpose / default |
|---|---|
| `DB_USER` | MySQL user, default `arbitrator` |
| `DB_PASS` | MySQL password; committed default is development-only |
| `ARBITRATOR_JWT_SECRET` | JWT signing secret; must be overridden outside local development |
| `ARBITRATOR_DOCKER_BIN` | Docker executable, default `docker` with runtime discovery of common paths |
| `ARBITRATOR_DOCKER_IMAGE` | Judge image, default `arbitrator-judge:latest` |
| `ARBITRATOR_SUBMIT_COOLDOWN` | Submission cooldown, default 10 seconds |
| `ARBITRATOR_MATERIALS_ROOT` | Persistent material storage, default `./arbitrator-data/materials` |
| `ARBITRATOR_RESET_ON_BOOT` | Reset non-ended contests to draft, default `false` |
| `ARBITRATOR_ADMIN_AUTO_OPEN` | Open the admin console after startup, default `true` |

Multipart requests are globally capped at 64 MB. Material-service messages mention 200 MB, but the Spring limit currently wins.

The default `reset-on-boot=false` means a live contest survives a server restart and its wall-clock deadline continues to advance. Set it to `true` when every restart should return non-ended contests to `DRAFT` and clear their clocks.

## Judge invariants

- Docker must be usable before judging. If it is missing or the daemon is inaccessible, the server starts but judge work returns an internal-error-style `RE`.
- Every compile and run uses a throwaway container with `--network none`, read-only root, a non-root UID, dropped capabilities, `no-new-privileges`, PID/memory/CPU/file-descriptor limits, and tmpfs scratch space.
- `--memory-swap` must equal `--memory`; otherwise Docker can effectively double the intended memory allowance.
- The work root is mounted read-only at `/base`, while the immediate work directory is writable at `/sandbox`. Custom checker path translation relies on both mounts.
- Exit 124 is the reliable GNU `timeout` signal. Exit 137 may indicate an OOM kill and must not automatically become TLE.
- `peakMemoryKb == -1` is expected when a timed-out process is killed before GNU `time` writes its report.
- Never run fork-bomb fixtures directly on the host.
- Raw SQL timestamps must use `UTC_TIMESTAMP()`. The JDBC connection treats zoneless MySQL `DATETIME` values as UTC.

## Persistence and state

Flyway owns the schema; Hibernate uses `ddl-auto=validate`. Never edit an applied migration—add a new migration in the range assigned by `rules.md`.

`submission_results` and PDF statements use JDBC-backed side tables in addition to JPA entities. Uploaded materials are stored on disk with metadata in MySQL, so database-only backups are incomplete.

Contest deletion removes material rows in the same database transaction before deleting the contest. It removes the corresponding files only after commit; a rollback preserves them. Do not call the individual material-delete operation in a loop from contest deletion, because it deletes files before commit. Post-commit filesystem failures are logged for manual cleanup; this is not a durable cleanup queue.

The active-session registry, presence state, notification queue, judge executor, and scheduled broadcasts are in-memory and intentionally assume one server process.

## Tests and verification

```bash
mvn test -pl arbitrator-server
```

Latest result on 2026-09-15: 58 tests, 0 failures, 0 errors, 0 skipped with local MySQL and Docker available. This includes six material-deletion regression tests against `arbitrator_test`, covering commit, rollback, missing files, cleanup failures, empty contests, and missing transaction context. Environment-dependent tests can still skip when their prerequisites are unavailable. There are currently no JavaFX UI tests or React product tests.

Do not use Mockito in this repository while tests must run on JDKs newer than the Byte Buddy version managed by Spring Boot 3.2.5. Existing service tests use dynamic proxies and small fakes.

## Known high-priority defects

Before calling the system production-ready, address these findings from the full-codebase audit:

1. Contest-password checks do not create or enforce durable server-side contest membership; authenticated users can reach problem endpoints independently of the picker gate.
2. Student-controlled strings are placed in inline JavaScript handler attributes in the admin page. HTML escaping is not sufficient for that JavaScript context, creating a credible stored-XSS path.
3. The REST filter enforces active JWT session IDs, but the WebSocket handshake does not perform the equivalent active-session-registry check.
4. Duplicate-submission detection removes all whitespace, which changes Python semantics and can also alter strings/token boundaries in Java and C++.
5. ZIP entry-size enforcement does not robustly detect an unknown-size entry that exceeds the cap, and duplicate normalized paths are not rejected.

See `STATUS.md` for the broader prioritized backlog.

## Things that commonly bite

- A server left running in an IDE occupies port 8080. Check `lsof -i :8080 -sTCP:LISTEN` before debugging startup.
- Eclipse can overwrite Maven output with stale error-stub classes after `arbitrator-common` changes. Update all Maven projects, clean Eclipse projects, then run `mvn clean install`.
- The standalone STOMP client needs both `tomcat-websocket` and `tomcat-api`; server-side WebSocket tests alone do not verify the client runtime.
- A solved problem remains visually solved after later failed attempts. Reset development submissions when testing status badges.
- Source-ban regexes scan raw source rather than a parsed syntax tree and can match prohibited API names inside comments or strings.
- The global judge executor has a fixed worker pool but an unbounded FIFO queue. Per-user cooldown/in-flight checks reduce abuse but do not impose a global backlog cap.
- `scripts/prepare-zero-download-bundles.py` expects an obsolete client JAR filename. `scripts/make-bundle.py` also assumes all repository files are UTF-8 text despite binary assets. Neither script is currently a reliable release path.

## Change discipline

Follow `rules.md` for ownership, migration numbering, branches, and reviews. Newer material/clarification files are not fully represented in the old ownership table; coordinate ownership rather than guessing.

Match surrounding style. Comments should explain why and preserve requirement identifiers where they help acceptance traceability. Changes affecting shared DTOs or API constants must update server and client consumers together.
