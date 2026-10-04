# Arbitrator

Arbitrator is an offline, LAN-first programming contest platform for university labs. An instructor runs one Spring Boot server with MySQL and Docker; students connect through a JavaFX desktop client.

[Project presentation video](https://drive.google.com/file/d/1u2Lf1WeJ11qpMlNYV6Lrm9vIo1zdsCJr/view?usp=drive_link)

**Status:** feature-rich supervised-lab beta. The security checklist reached item 105, but deferred findings and skipped checks remain. The October 4 full Maven run under JDK 17 passed all 333 tests; earlier live testing found two correctness/UI defects and the intermittent fork/OOM reporting issue remains unresolved. See [live validation results](docs/audit-history.md#live-validation-2026-09-27) and [current security status](docs/security.md).

## On this page

- [Setup](#first-time-setup)
- [Run the application](#run-locally)
- [Package the client](#client-packaging)
- [Create a problem ZIP](#problem-package-format)
- [Configuration](#runtime-configuration)
- [Run tests](#testing)
- [Operations and recovery](#operations)
- [Frontend experiment](#frontend-experiment)
- [Remaining work](#remaining-work)
- [Other documentation](#documentation)

## Architecture and features

| Component | Purpose |
|---|---|
| `arbitrator-common` | Shared DTOs, enums, REST paths, and STOMP destinations |
| `arbitrator-server` | Spring Boot API, MySQL/Flyway persistence, Docker judge, WebSockets, and leaderboard |
| `arbitrator-client` | JavaFX participant application with RichTextFX editor and statement viewers |
| `arbitrator-server/src/main/resources/static/admin` | Loopback-only instructor console |
| `arbitrator-web` | Independent React/Vite toolchain experiment, outside the root Maven reactor |

- Registration, bcrypt passwords, JWT login/logout, single active sessions, live role validation, idle/absolute expiration, and contest access grants.
- Contest lobby/start/pause/resume/freeze/end/clone, scheduling and time adjustments; one joinable/live contest at a time.
- ZIP problem import, HTML/PDF statements and plain-text display of text/Markdown, exact/custom checkers, and C++17/Java 17/Python 3.10 judging.
- Network-disabled Docker compilation/execution, bounded queues/resources, persist-before-queue submissions, unfinished-work recovery, custom runs, source/history, and per-test results.
- ICPC standings, first solves, freeze-aware results, manual marks/penalties/verdict overrides, and live STOMP updates.
- Announcements, materials, clarifications, instructor monitoring/exports, audit records, and local health checks.
- JavaFX drafts, syntax highlighting, editor assistance, themes, fullscreen/zoom, and reconnect handling.

MySQL stores relational data and PDF statement blobs. Uploaded material bodies live on disk; backups must include both. Sessions, presence, notifications, and the judge queue are in memory and assume a single server process.

## Prerequisites

Target deployment is Ubuntu 22.04 with:

- JDK 17
- Maven 3.8+
- MySQL 8
- Docker Engine, the Docker Buildx plugin, and daemon access for the account running the server

macOS and Windows with Docker Desktop are supported for development and judge testing, but the planned lab deployment remains Linux.

The commands below assume Ubuntu x86_64, a graphical desktop for the JavaFX client, and a terminal opened at the repository root. Initial Maven dependency downloads and the Docker image build require internet access unless their inputs are already cached. The configured application can then run on the LAN without internet access.

The judge uses the compilers and interpreters inside Docker. A host JDK is still required to build and run the application; no separate host C++ or Python installation is needed for judging.

## First-time setup

### 1. Create the development database

For a fresh local development installation:

```bash
sudo mysql < scripts/init-db.sql
```

This script creates the `arbitrator` database and the MySQL account `arbitrator` at `localhost`. It sets that account's password to the example value `changeme`, including when the account already exists. Do not rerun it against an existing contest installation as a repair step.

### 2. Match the database credentials

For the fresh setup above, set these in each terminal that runs the server or database tests:

```bash
export DB_USER=arbitrator
export DB_PASS=changeme
```

These are local development examples. For an existing installation, use that database's actual credentials. The application's fallback password is different from the setup script's value, so leaving credentials unspecified does not reproduce the fresh setup correctly.

No JWT key setup is required. The server generates a private in-memory signing key on startup; sessions require login again after a restart. `ARBITRATOR_JWT_SECRET` is an optional private override of at least 32 UTF-8 bytes.

**About local YAML configuration:** Maven deliberately excludes `arbitrator-server/src/main/resources/application-local.yml` from the server's copied resources and packaged JAR. Merely copying the example there does not make ordinary Maven launches load it. The example also contains a placeholder `labjudge` username, not the account created by the SQL script. Use the environment variables above for the documented Maven workflow. If an IDE or an explicitly configured external Spring configuration file supplies overrides, ensure its credentials match the database. Do not overwrite an existing working configuration.

### 3. Prepare the test database

Before running the full build with tests, create the separate test schema and grant access to the fresh development account:

```bash
sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS arbitrator_test CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON arbitrator_test.* TO 'arbitrator'@'localhost';
SQL
```

If you use another account, adapt the grant. Integration tests clean or modify this test schema; it must not contain contest data. The ordinary setup script does not create or grant access to it.

### 4. Build the judge image

```bash
bash scripts/docker/build-sandbox-image.sh
```

The script uses `docker buildx build --load` and defaults to `linux/amd64`. `ARBITRATOR_DOCKER_PLATFORM` can select another Docker target when needed; this is separate from the JavaFX platform setting.

### 5. Build and install the Maven modules

For Ubuntu x86_64:

```bash
mvn clean install -Djavafx.platform=linux
```

`install` makes the parent and shared module available to the separate server/client launch commands below. A successful `package` alone does not install them into Maven's local repository.

The October 4 full test run passed under JDK 17, but an earlier fork/OOM reporting error remains unresolved. A failed run must still be investigated. If you deliberately need local artifacts without running tests, the existing Maven option is:

```bash
mvn clean install -DskipTests -Djavafx.platform=linux
```

This skips test execution; it does not verify the application or resolve the intermittent failure. See [verification status](docs/security.md#current-verification-including-the-failure).

The client POM defaults to Apple Silicon macOS (`mac-aarch64`). Keep the correct `-Djavafx.platform` on both build and client-launch commands: `linux` for Linux x86_64, `linux-aarch64` for Linux ARM64, `mac` for Intel macOS, `mac-aarch64` for Apple Silicon, or `win` for Windows x86_64. These select JavaFX dependencies; they do not make the Unix shell/MySQL setup commands portable to Windows.

If an IDE cannot find Docker, set `ARBITRATOR_DOCKER_BIN` to the absolute path reported by `which docker` in that IDE's launch environment, or supply the equivalent `arbitrator.judge.docker-binary` through configuration the IDE actually loads.

## Run locally

### Start the server

After the install step, run from the repository root in a terminal with the matching database environment variables:

```bash
mvn -pl arbitrator-server spring-boot:run
```

The default address is `http://localhost:8080`. On an empty database, the development seeder creates:

- Instructor: `admin` / `admin123`
- Student: `alice` / `alice123`
- Draft contest: `Lab Contest #1`

The instructor console is available at [http://localhost:8080/admin](http://localhost:8080/admin). Both `/admin/**` and `/api/admin/**` are restricted to loopback requests, and admin API operations also require an `ADMIN` JWT.

### Start the participant client

Run from another terminal at the repository root:

```bash
mvn -pl arbitrator-client -Djavafx.platform=linux exec:java
```

### Try the client without a server

Mock mode uses sample data:

```bash
mvn -pl arbitrator-client -Djavafx.platform=linux exec:java -Darbitrator.mock=true
```

The supported entry point is `com.arbitrator.client.app.Launcher`. The client first looks for `server.properties` in its **current working directory**, then falls back to the classpath copy. It does not automatically look beside the JAR. A previously saved server address in Java Preferences takes precedence over the file's host and port; use the login screen to change it.

For a student on another machine, enter the instructor machine's LAN address and port 8080. `localhost` refers to the student's own machine. The instructor console remains accessible only from the server machine.

## Client packaging

JavaFX artifacts contain platform-specific native libraries. The default platform is `mac-aarch64`. For Ubuntu x86_64, select `linux`:

```bash
mvn -pl arbitrator-client -am package -DskipTests -Djavafx.platform=linux
```

The Ubuntu artifact is `arbitrator-client/target/arbitrator-client-linux.jar`. Run it with Java 17 on a graphical Linux x86_64 machine:

```bash
java -jar arbitrator-client/target/arbitrator-client-linux.jar
```

To use a `server.properties` file beside a copied JAR, first change into that directory, then launch the JAR there. A Java runtime is still required; this is not a bundled-JRE installer.

Other platforms produce `arbitrator-client-<platform>.jar`. The existing `scripts/prepare-zero-download-bundles.py` still expects the older generic filename and must be corrected before it is used as a release process.

## Problem-package format

### ZIP layout

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

### Upload limits and content handling

Problem ZIPs are never extracted to the host filesystem. Uploads are limited to 5,000 entries, 32 MiB per uncompressed entry, and 64 MiB total uncompressed content; path traversal, absolute paths and canonical duplicates are rejected. Nested archives are treated as opaque unused bytes rather than opened recursively.

HTML statements and announcements retain basic formatting, tables, and math superscripts/subscripts. The server removes scripts, event handlers, styles, links, images, forms, and embedded/remote content on write and read, including existing database rows. JavaFX HTML viewers also disable JavaScript. PDF rendering is unchanged.

Uploaded PDF statements must carry a `%PDF` signature whether they arrive inside a problem package or through standalone replacement. General contest materials intentionally allow any file type, but downloads are always served as `application/octet-stream` with attachment disposition and a portable sanitized display filename capped at 200 Unicode code points; uploader-provided MIME types are not trusted at the download boundary.

Material bytes use pure UUID physical names outside the web root, and guarded lookup rejects traversal, absolute paths, and symbolic links while remaining compatible with older UUID-with-extension rows.

## Runtime configuration

`application.yml` imports `languages.yml` explicitly. Do not remove that import: without it, the judge has no language commands.

Important environment variables:

| Variable | Purpose / default |
|---|---|
| `DB_USER` | MySQL user, default `arbitrator` |
| `DB_PASS` | MySQL password; committed default is development-only |
| `ARBITRATOR_JWT_SECRET` | Optional non-placeholder signing-key override of at least 32 UTF-8 bytes; otherwise a random private key is generated per startup |
| `ARBITRATOR_DOCKER_BIN` | Docker executable, default `docker` with runtime discovery of common paths |
| `ARBITRATOR_DOCKER_IMAGE` | Judge image, default `arbitrator-judge:latest` |
| `ARBITRATOR_JUDGE_MAX_BACKLOG` | Global running-plus-queued submission cap, default 500 |
| `ARBITRATOR_JUDGE_MAX_CONTAINERS` | Maximum simultaneous sandbox containers, default 10 |
| `ARBITRATOR_JUDGE_MEMORY_BUDGET_MB` | Aggregate sandbox memory budget; default 0 derives 70% of RAM |
| `ARBITRATOR_MAX_CUSTOM_RUNS` | Maximum simultaneous custom runs, default 2 |
| `ARBITRATOR_MAX_SOURCE_BYTES` | Submission/custom-run source cap, default 262144 UTF-8 bytes |
| `ARBITRATOR_SUBMIT_COOLDOWN` | Submission cooldown, default 10 seconds |
| `ARBITRATOR_MATERIALS_ROOT` | Persistent material storage, default `./arbitrator-data/materials` |
| `ARBITRATOR_RESET_ON_BOOT` | Reset non-ended contests to draft, default `false` |
| `ARBITRATOR_ADMIN_AUTO_OPEN` | Open the admin console after startup, default `true` |

### Request limits

Multipart files/requests are capped at 64 MB, and the material-service cap/message matches. JSON bodies using `application/json` or `application/*+json` are capped at 1 MiB by an early filter; submission/custom-run source remains capped at 256 KiB by the service.

### Contest behavior after restart

The default `reset-on-boot=false` means a live contest survives a server restart and its wall-clock deadline continues to advance. Set it to `true` when every restart should return non-ended contests to `DRAFT` and clear their clocks.

## Testing

Run the server tests from the repository root:

```bash
mvn test -pl arbitrator-server
```

Run tests across all Maven modules on Ubuntu x86_64:

```bash
mvn -B test -Djavafx.platform=linux
```

For server-only tests, first complete the install step so Maven can resolve `arbitrator-common`. Use the database environment variables and separate test schema from [first-time setup](#first-time-setup). Integration tests use the isolated `arbitrator_test` schema. MySQL and the built Docker judge image are needed for the corresponding tests; missing prerequisites can cause skips. Browser tests below are separate from Maven. JavaFX currently has source-policy checks, not an automated UI suite.

The [security index](docs/security.md#current-verification-including-the-failure) records the latest full-suite pass and the unresolved earlier failure. [Live validation results](docs/audit-history.md#live-validation-2026-09-27) cover the real instructor console, JavaFX client, and contest APIs, including defects and untested boundaries.

### Browser security checks

#### DOM-XSS attack test

`scripts/security/dom-xss-attack.cjs` runs the actual instructor-console HTML and JavaScript in
headless Chromium. Playwright intercepts requests with controlled fixtures;
there is no live application server, database mutation, login, or external API
access. The loopback URL is a synthetic browser origin, not a listening service.

Prerequisites:

- Java 17 or later, the shared module installed by the setup build, and compiled server classes: `mvn compile -pl arbitrator-server`.
- jsoup 1.23.2.
- Playwright with Chromium installed. Make Playwright available through Node's normal module path or `NODE_PATH`.

Run from the repository root:

```sh
JSOUP_JAR=/absolute/path/to/jsoup-1.23.2.jar node scripts/security/dom-xss-attack.cjs
```

Optional `CHROMIUM_PATH` selects a browser executable. `PLAYWRIGHT_BROWSERS_PATH`
selects the installed Playwright browser directory. No extra package dependency
is added to the product or Maven build.

Four image-error, SVG-load, script-breakout, and attribute-breakout payloads are tested through live notifications, notification list/history, clarifications, submission test input/expected/actual output, announcement drafts, published announcements, statement sandboxing, and standalone exported HTML reports.

Published announcement fixtures are generated by the production `RichTextSanitizer`, invoked through `scripts/security/RichTextFixture.java`, not a duplicate JS sanitizer. Raw malicious statements deliberately exercise the iframe sandbox. Ordinary text must remain literal, and safe superscript formatting must survive.

A positive control first verifies that script and image-error execution works.
The harness then asserts zero payload execution, dialogs, page errors, active
payload elements, and injected handler attributes in the tested parent DOMs;
exported reports are loaded in a separate browser page. Statements are isolated
in their sandbox and may contain the deliberately injected image there.

This is a browser rendering regression test with trusted DTO shapes: enum and
numeric fields remain valid, while attacker-controlled text fields carry
payloads. It does not prove every possible payload safe, test API authorization,
replace end-to-end publication tests, or exercise JavaFX WebViews. The dedicated
HTTP reflected-XSS and Java sanitizer/service tests cover complementary layers.

#### Admin logout browser check

Run with the same Playwright/Chromium environment; jsoup is not required:

```bash
node scripts/security/logout-browser.cjs
```

It clicks the actual admin
sign-out control with success, already-invalid-token (401), server-error, and
network-failure fixtures. It asserts a POST carrying the bearer token, a reloaded
login gate with cleared in-memory token, appropriate failure warnings, and zero
page errors. No real accounts or listening server are used. Live server token
revocation/socket closure is covered separately by `VerdictPushIntegrationTest`.

#### Admin session activity check

Run with the same browser environment:

```bash
node scripts/security/session-activity-browser.cjs
```

It loads the actual admin page with controlled responses and checks that background GET polling sends no activity update, pointer/keyboard input sends an authenticated POST throttled to once per 30 seconds, and HTTP 401 clears the token and returns to login.

It does not simulate 30 minutes passing in a live server. `SessionExpirationTest` uses an injected clock for exact timeout boundaries and invokes the real socket-tracking component with a test socket to verify expiry closure. The production sweep runs nominally every 15 seconds; request validation rejects expired sessions immediately, while no-traffic socket closure may wait for the sweep and scheduler availability.

## Operations

For outages and restarts, follow [the recovery guide](docs/recovery.md). It covers MySQL/Docker/disk failures and deployment recovery, but does not certify backup/restore or rollback.

The loopback-only `/admin/health/live` checks HTTP responsiveness. `/admin/health/ready` checks database, queue acceptance, and Docker judge readiness, returning 503 when a required component is unavailable. These use port 8080 without login; they are not external outage alerts. The authenticated local instructor dashboard also shows host/JVM/disk/queue/database-pool metrics and recent failure counts.

### Administrative audit records

Audit history is stored in MySQL automatically using the normal Flyway startup migration; build and run commands are unchanged. An authenticated administrator on the server machine can read `GET /api/admin/audit?limit=50`. Pages are newest-first; pass the last returned `id` as `before` for the next page (maximum 100 rows).

`COMMITTED_CHANGE` records identify administrative database changes with safe before/after metadata. Separate request records describe HTTP outcomes for admin mutations and login/register/logout, not proof of commit. Passwords, tokens, source, upload content and raw requests are excluded. Failed-request details are capped at 200/minute with overflow summaries. A minute sweep retains at most 90 days/the newest 100,000 ID slots; temporary excess is possible. This is bounded investigation history, not a tamper-proof archive. See [audit history](docs/audit-history.md), item 58, for coverage and limitations.

Operational logs record checker failure reasons and IDs without checker output or hidden test content, and omit session IDs and raw database diagnostics. The instructor's checker validation response still includes useful compiler feedback. Default Hibernate SQL exception logging is disabled because duplicate-value diagnostics can echo submitted identifiers. See [audit history](docs/audit-history.md), item 59, for verification and limits.

## Frontend experiment

`arbitrator-web` is a standalone React 19 / TypeScript 6 / Vite 8 / Tailwind 4 smoke test using Base UI, shadcn-style components, Motion, and Oxlint. It has no product routes or API/auth integration, is not served by Spring Boot, and does not replace either current client.

Start the experiment:

```bash
cd arbitrator-web
npm install
npm run dev
```

To verify it, run these separately from `arbitrator-web`:

```bash
npm run build
npm run lint
```

Before product integration, decide its purpose and design routing, REST/STOMP clients, admin authentication/loopback behavior, loading/error/reconnect states, production packaging, and browser/security tests. The experiment's build does not verify the production application.

## Remaining work

The current [security index](docs/security.md) and preserved [audit findings](docs/audit-history.md) supersede the retired workflow plan. Remaining product and release work includes:

- Rejudge and float-tolerance judging; bulk user import/password-reset UI remain unsupported.
- Supported installers and repeatable Ubuntu packaging; the existing bundle scripts have obsolete artifact/path assumptions and are not a reliable release process.
- Coordinated database/material backup and a verified restore/rollback drill.
- Fix the immediate-start submission rejection and repeated paused-state log messages found during [live validation](docs/audit-history.md#live-validation-2026-09-27).
- Broader JavaFX visual acceptance, native file-dialog checks, realistic LAN load/reconnect tests, and a multi-client rehearsal.
- Bengali localization, coverage tooling, and the skipped automated security checks listed in the security index.

Source-ban regex false positives, Java memory-overhead differences, and process-local state remain limitations. HTTP/WS, global FIFO, negative time adjustments, and deferred security decisions are intentional retained policies; this list is not approval to change them. Release readiness requires verified target-host tests, repeatable installation, recovery rehearsal, matching documentation/artifacts, and instructor acceptance.

## What was verified

On 2026-09-27, `mvn -o -B package -DskipTests -Djavafx.platform=linux` completed successfully using cached dependencies. The client JAR contains Linux native libraries and the Launcher manifest entry; the server JAR includes `languages.yml` and excludes `application-local.yml`. The Docker build script also passed a shell syntax check.

The subsequent live check started the packaged server and real JavaFX client against isolated `arbitrator_test`, ran a contest through completion, and passed all 333 Maven tests. It found two defects: immediate-start submission rejection and repeated paused-state log messages. See [the full validation record](docs/audit-history.md#live-validation-2026-09-27) for coverage and limitations. This was Fedora with OpenJDK 17.0.19 and MySQL 8.4.10, not a clean Ubuntu installation; the earlier intermittent fork/OOM issue remains open.

## Documentation

- [AGENTS.md](AGENTS.md) — development invariants and team collaboration rules.
- [docs/security.md](docs/security.md) — current unresolved risks, decisions, and verification status.
- [docs/recovery.md](docs/recovery.md) — operator incident procedures.
- [docs/audit-history.md](docs/audit-history.md) — preserved dated audit and implementation evidence.

Generated `target/`, `dist/`, and `arbitrator-data/` directories are not source. `ARBITRATOR_BUNDLE.txt` is a stale legacy snapshot, not authoritative documentation. Historical filenames in the audit record and applied migration comments refer to documents consolidated here.
