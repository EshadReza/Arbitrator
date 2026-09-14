# Arbitrator

Arbitrator is an offline, LAN-first programming contest platform for university labs. An instructor runs one Spring Boot server with MySQL and Docker; students connect through a JavaFX desktop client. The system supports contest administration, problem distribution, sandboxed judging, live standings, announcements, materials, and clarifications without depending on an internet connection.

The repository currently represents a feature-rich beta rather than a production-hardened v1.0. See [`STATUS.md`](STATUS.md) for verified capabilities, open risks, and the release-readiness backlog.

## Architecture

| Component | Purpose |
|---|---|
| `arbitrator-common` | Shared DTOs, enums, REST paths, and STOMP destinations |
| `arbitrator-server` | Spring Boot REST API, security, contest services, judge queue, Docker sandbox, Flyway migrations, and WebSocket publishing |
| `arbitrator-client` | JavaFX participant application with RichTextFX editor, PDF/HTML statements, and live contest views |
| `arbitrator-server/src/main/resources/static/admin` | Loopback-only instructor console served by the Spring Boot application |
| `arbitrator-web` | Standalone React/Vite toolchain experiment; not integrated with the running product or root Maven build |

The root Maven reactor contains three modules: `arbitrator-common`, `arbitrator-server`, and `arbitrator-client`.

## Implemented features

- Student registration, login/logout, bcrypt passwords, 12-hour JWTs, single-active-session handling, and optional MAC-address change notifications
- Contest states `DRAFT`, `LOBBY`, `ACTIVE`, `PAUSED`, `FROZEN`, and `ENDED`, including scheduling, pause/resume, time adjustment, freeze/unfreeze, cloning, and ending
- ZIP problem packages with HTML, text, Markdown, or PDF statements; paired test files; exact or custom checkers; editing and reordering
- C++17, Java 17, and Python 3.10 compilation and execution
- Docker isolation for compilation, execution, and custom checkers: no network, read-only root filesystem, non-root user, capability drop, PID/CPU/memory/output limits, and temporary workspaces
- Persist-before-queue submissions, crash recovery for pending work, per-test results, source viewing, custom runs, and verdicts `AC`, `WA`, `TLE`, `MLE`, `CE`, `RE`, and `OLE`
- ICPC-style leaderboard, first-solve tracking, freeze-aware standings, manual marks, verdict overrides, and penalty adjustments
- STOMP/WebSocket delivery for verdicts, contest state, standings, announcements, and participant presence
- Participant announcements, downloadable materials, public/private clarifications, and instructor approval for public answers
- Instructor monitoring, exports, notification feed, participant drill-downs, and test-case visibility controls
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

   Edit the copied file or set environment variables. Important production overrides include `DB_USER`, `DB_PASS`, `ARBITRATOR_JWT_SECRET`, `ARBITRATOR_MATERIALS_ROOT`, and optionally `ARBITRATOR_RESET_ON_BOOT`.

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

## Tests

Run the server suite with:

```bash
mvn test -pl arbitrator-server
```

The latest repository audit on 2026-09-15 completed with 52 tests, 0 failures, 0 errors, and 14 environment-dependent skips. Docker-backed sandbox, worker, checker, and full STOMP integration tests skip when their external prerequisites are unavailable; a green run with skips is not equivalent to a full deployment verification.

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
