# Arbitrator

Arbitrator is an offline, LAN-first programming contest platform for university labs. An instructor runs one Spring Boot server with MySQL and Docker; students connect through a JavaFX desktop client. The system supports contest administration, problem distribution, sandboxed judging, live standings, announcements, materials, and clarifications without depending on an internet connection.

The repository currently represents a feature-rich beta rather than a production-hardened v1.0. See [`STATUS.md`](STATUS.md) for verified capabilities, open risks, and the release-readiness backlog.

Latest targeted security check: 18 live Docker sandbox tests passed without skips, including three new filesystem attack tests. Host/sibling isolation and forbidden writes passed; submitted code's writable access to `/run-metrics` was confirmed and remains pending remediation approval. This targeted run does not replace the documented earlier full-suite result.

Subsequent symlink verification passed 25 targeted sandbox/judge-worker tests without skips. Compiler-stage fault injection reproduced three outside temporary-file overwrites by real host staging, despite AC verdicts; ordinary runtime workspace link creation was blocked. Source-level exploitability remains unproven and no production remediation has been implemented. See STATUS.md for the precise test boundary.

Update: the approved symlink remediation is now implemented. The same three probes require Compilation Error and unchanged outside files; host staging also rejects links and avoids hard-link truncation. Full verification passed 207 server + 1 client tests without failures/errors/skips on 2026-09-17. The preceding reproduction paragraph is historical; the item-34 measurement-file fix remains declined/deferred.

## Architecture

Race-condition remediation is implemented: duplicate username/title races return clean 409 responses, and optimistic problem version checks reject conflicting edits rather than silently losing a successful change. The five attack cases now require these protections; migration V81 preserves existing rows. Precise HTTP versus controlled-transaction test scope is documented in STATUS.md.

Contest timing remediation is implemented: scored admission validation and stored submission time share one server instant, preserving correct timestamps despite persistence delays. Thirteen timing regressions verify state/deadline protections and start-inclusive/end-exclusive boundaries; no client timestamp is accepted. Already-admitted submissions can finish normally after closing or a pause. See STATUS.md for scope and verification.

| Component | Purpose |
|---|---|
| `arbitrator-common` | Shared DTOs, enums, REST paths, and STOMP destinations |
| `arbitrator-server` | Spring Boot REST API, security, contest services, judge queue, Docker sandbox, Flyway migrations, and WebSocket publishing |
| `arbitrator-client` | JavaFX participant application with RichTextFX editor, PDF/HTML statements, and live contest views |
| `arbitrator-server/src/main/resources/static/admin` | Loopback-only instructor console served by the Spring Boot application |
| `arbitrator-web` | Standalone React/Vite toolchain experiment; not integrated with the running product or root Maven build |

The root Maven reactor contains three modules: `arbitrator-common`, `arbitrator-server`, and `arbitrator-client`.

## Implemented features

- Student registration with server-enforced student-ID/display-name policy, login/logout, bcrypt passwords, 12-hour JWTs, automatic private signing keys, database-backed current-role authorization, single-active-session enforcement across REST and live WebSockets, and optional MAC-address change notifications
- One-time contest-password entry backed by persistent per-user access grants; contest REST resources and contest-topic subscriptions enforce the grant server-side, while lobby problem statements and codes remain hidden
- New accounts retain the 8-character minimum but reject whitespace/control characters, a small offline common-password list, and single-character repetition. Server and JavaFX registration share these checks. This is not comprehensive breached-password screening; existing logins, contest-password rules, and demo credentials are unchanged. Item-23's suite-dependent WebSocket failure and later verification are recorded in STATUS.md.
- Login has configurable account/IP attempt and concurrency limits plus escalating temporary failure cooldowns (HTTP 429 with Retry-After). Defaults are 20/account and 120/IP per minute; 5 account or 20 IP failures start a 5-second cooldown, doubling up to 300 seconds. It trusts socket-peer IPs, not forwarded headers. Limits are process-local, reset on restart, and require tuning/shared storage for proxy or multi-server deployment; see STATUS.md for scope and attack tests.
- Unknown-account login performs a dummy bcrypt check at the configured cost and returns the same invalid-credentials response as a wrong password. This reduces the obvious timing leak, not every enumeration signal; registration intentionally retains its duplicate-ID response.
- HTTP APIs now have atomic, configurable account/IP total and category limits with 429/retry timing. Defaults per account/minute: reads 600, writes 120, joins 10, runs 12, submissions 6, clarifications 5; registration allows 30/IP/minute. Shared-IP budgets are more generous. Submission cooldown/backlog admission is serialized per user and local configuration keeps the 10-second cooldown. Counters are bounded and process-local; restarts, proxies, replicas, boundary bursts, and WebSocket exclusions are documented in STATUS.md.
- Controlled queue-abuse tests exercise 10,000 concurrent admission attempts (500 accepted, 9,500 rejected), bounded workers, recovery after failures, and per-user backlog blocking/recovery. These are real queue-executor and service tests with controlled workers/repository doubles, not a mass-container or live-network load test; fairness is a separate concern.
- Account and contest passwords are limited server-side to 72 UTF-8 bytes on creation and verification; longer inputs return HTTP 400 instead of bcrypt silently ignoring their suffix. Passwords are not trimmed or truncated. Existing hashes remain unchanged; users of legacy overlong passwords must replace them with a compliant password through an operator-supported recovery process (no new recovery flow is implemented here).
- Contest states `DRAFT`, `LOBBY`, `ACTIVE`, `PAUSED`, `FROZEN`, and `ENDED`, including scheduling, pause/resume, time adjustment, freeze/unfreeze, cloning, and ending
- Hardened ZIP problem packages with per-entry, aggregate-size, entry-count, path-traversal, and duplicate-path guards; HTML, text, Markdown, or PDF statements; paired test files; exact or custom checkers; editing and reordering
- C++17, Java 17, and Python 3.10 compilation and execution
- Docker isolation for compilation, execution, and custom checkers: no network, read-only root filesystem, non-root user, capability drop, PID/CPU/memory/output limits, and temporary workspaces
- Persist-before-queue submissions, crash recovery for pending work, per-test results, source viewing, custom runs, and verdicts `AC`, `WA`, `TLE`, `MLE`, `CE`, `RE`, and `OLE`
- ICPC-style leaderboard, first-solve tracking, freeze-aware standings, manual marks, verdict overrides, and penalty adjustments
- STOMP/WebSocket delivery for verdicts, contest state, standings, announcements, and participant presence
- Participant announcements, downloadable materials, public/private clarifications, and instructor approval for public answers
- Instructor monitoring, exports, notification feed, participant drill-downs, and test-case visibility controls
- Injection-safe generated admin controls using delegated event binding and text-only rendering for student-controlled names
- JavaFX drafts, file upload, syntax highlighting, automatic indentation/brackets, custom input, submission history, themes, fullscreen views, zoom, shortcuts, and reconnect watchdog

Not yet implemented: float-tolerance judging, rejudge, a supported installer, Bengali localization, automated JavaFX UI tests, load tests, and a complete backup/deployment workflow.

## Prerequisites

Target deployment is Ubuntu 22.04 with:

- JDK 17
- Maven 3.8+
- MySQL 8
- Docker Engine with access to the daemon for the account running the server

macOS and Windows with Docker Desktop are supported for development and judge testing, but the planned lab deployment remains Linux.

The host does not need `g++`, `javac`, or Python for judging. Those toolchains live in the Docker image.

## First-time setup

1. Create the local database and development account:

   ```bash
   sudo mysql < scripts/init-db.sql
   ```

2. Prepare local configuration:

   ```bash
   cp arbitrator-server/src/main/resources/application-local.yml.example \
      arbitrator-server/src/main/resources/application-local.yml
   ```

   Edit the copied file or set environment variables. No JWT key setup is required. The server generates a private in-memory signing key on each startup. `ARBITRATOR_JWT_SECRET` is an optional private, non-placeholder override of at least 32 UTF-8 bytes. Sessions require re-login after server restart. Important production overrides also include `DB_USER`, `DB_PASS`, `ARBITRATOR_MATERIALS_ROOT`, and optionally `ARBITRATOR_RESET_ON_BOOT`.

   `application-local.yml` is intended to remain local and must not contain shared credentials. If Git already tracks it in an older checkout, remove it from the index before adding real secrets.

3. Build the judge image:

   ```bash
   bash scripts/docker/build-sandbox-image.sh
   ```

4. Build and test the Maven modules:

   ```bash
   mvn clean install
   ```

If the server is launched by an IDE that cannot find Docker, set `arbitrator.judge.docker-binary` in `application-local.yml` to the absolute path returned by `which docker`.

## Run locally

Start the server:

```bash
mvn -pl arbitrator-server spring-boot:run
```

The default address is `http://localhost:8080`. On an empty database, the development seeder creates:

- Instructor: `admin` / `admin123`
- Student: `alice` / `alice123`
- Draft contest: `Lab Contest #1`

The instructor console is available at [http://localhost:8080/admin](http://localhost:8080/admin). Both `/admin/**` and `/api/admin/**` are restricted to loopback requests, and admin API operations also require an `ADMIN` JWT.

Run the participant client from another terminal:

```bash
mvn -pl arbitrator-client exec:java
```

Run it against canned data without a server:

```bash
mvn -pl arbitrator-client exec:java -Darbitrator.mock=true
```

The supported entry point is `com.arbitrator.client.app.Launcher`. The client reads `server.properties` from its classpath and accepts a file with the same name beside the packaged JAR as an external override.

## Client packaging

JavaFX artifacts contain platform-specific native libraries. The current default classifier is `mac-aarch64`; override it when building for the target lab platform:

```bash
mvn -pl arbitrator-client -am package -DskipTests -Djavafx.platform=linux
```

The resulting client artifact is named `arbitrator-client-<platform>.jar`. The existing `scripts/prepare-zero-download-bundles.py` still expects the older generic filename and must be corrected before it is used as a release process.

## Problem-package format

Upload a ZIP from the instructor console. A minimal package is:

```text
config.json                 {"code":"B","title":"Max of Three",
                             "timeLimitMs":1000,"memoryLimitKb":131072}
statement/statement.html    HTML, HTM, TXT, MD, or PDF
tests/01.in
tests/01.out
tests/02.in
tests/02.out
```

A single wrapping directory is removed automatically. Packages are validated before persistence and are accepted or rejected as a unit. `config.json` can also select a custom checker, in which case the package must include its checker source.

HTML statements and announcements retain basic formatting, tables, and math superscripts/subscripts. The server removes scripts, event handlers, styles, links, images, forms, and embedded/remote content on write and read, including existing database rows. JavaFX HTML viewers also disable JavaScript. PDF rendering is unchanged.

## Tests

Sessions are bound to their login-time role. A detected database role change invalidates the session and requires fresh login; old student tokens cannot inherit a promotion. Manual SQL changes are detected on the next authenticated request, incoming WebSocket command, or login, rather than proactively on idle connections.

Sessions also expire absolutely after the configured JWT lifetime (12 hours by default), including already-open WebSockets. Expired entries are removed automatically, allowing normal re-login. Admin sessions expire after 30 minutes without meaningful activity; automatic polling/heartbeats do not reset that timer. Student idle expiry is disabled by default to preserve long coding sessions. These settings are under `arbitrator.session` (`admin-idle-minutes`, `student-idle-minutes`, and `sweep-millis`). Socket cleanup runs nominally every 15 seconds, while authenticated request validation checks expiry immediately.

Admin and participant sign-out revoke the requesting session and close its tracked sockets. Both clients warn when server logout cannot be confirmed; local-only offline sign-out cannot guarantee revocation of copied tokens.

Run the server suite with:

```bash
mvn test -pl arbitrator-server
```

The latest fully green full Maven run (item 37) on 2026-09-17 completed with 229 server tests and 1 client source-policy test, 0 failures, 0 errors, and 0 skips, with MySQL and Docker available. It includes adversarial sandbox- and network-isolation, compiler command/environment/image-pinning, a command-execution policy guard, a two-user HTTP/STOMP IDOR matrix, role-injection/forged-token/stale-role checks, stored-HTML sanitization, output-flood, cross-language read-only-workspace, memory-admission, queue-capacity, custom-run concurrency, source-size, and JSON-body-limit coverage alongside the existing integration suites. Database integration tests use the separate `arbitrator_test` schema. Tests can skip when external prerequisites are unavailable; a green run with skips is not equivalent to a full deployment verification.

The React experiment can be checked separately and is not part of Maven:

```bash
cd arbitrator-web
npm install
npm run build
npm run lint
```

## Documentation

- [`STATUS.md`](STATUS.md) — current implementation and remaining work
- [`CLAUDE.md`](CLAUDE.md) — concise repository context for coding agents and contributors
- [`WORKFLOW_PLAN.md`](WORKFLOW_PLAN.md) — current architecture, ownership, workflow, and roadmap
- [`rules.md`](rules.md) — team collaboration and ownership rules; change only by team agreement

Generated directories (`target/`, `dist/`, and `arbitrator-data/`) are not source. `ARBITRATOR_BUNDLE.txt` is a legacy snapshot and should not be treated as authoritative.
