# Arbitrator — development instructions

Arbitrator is an offline, LAN-first programming contest system: Java 17, Spring Boot, MySQL 8, Docker judging, and a JavaFX participant client. The instructor console is static HTML/JavaScript served by Spring Boot. `arbitrator-web` is a separate React toolchain experiment, outside the Maven reactor and running product.

## On this page

- [Documentation and scope](#documentation-and-scope)
- [Product decisions](#locked-decisions)
- [Team rules](#collaboration-rules)
- [Docker judge](#judge-invariants)
- [Database and contest state](#persistence-and-state)
- [Authentication and HTTP](#authentication-and-http-invariants)
- [HTML rendering](#safe-html-rendering)
- [Verification](#verification-and-maintenance)
- [Common problems](#common-development-problems)

## Documentation and scope

- [README.md](README.md): setup, run, configuration, packaging, tests, and remaining product work.
- [docs/security.md](docs/security.md): current unresolved findings, final user decisions, and verification status. Read before security work.
- [docs/audit-history.md](docs/audit-history.md): dated findings, attack boundaries, fixes, and test evidence retained from STATUS.md. Read the relevant item before revisiting it; old proposals and “next item” statements are historical.
- [docs/recovery.md](docs/recovery.md): operator recovery procedures and limitations.

These instructions consolidate the current CLAUDE.md guidance and applicable team rules. The outdated workflow plan is retired; its sprint schedule and unchecked tasks are not a current backlog. Preserve existing uncommitted changes. The security checklist reached item 105, but skipped/deferred items and the earlier intermittent full-suite error remain open. The October 4 full suite passed under JDK 17; earlier live validation still found two defects. Report each result with its date and scope, and do not infer production certification from a green run.

The user's audit instruction overrode named part-ownership restrictions for that audit only. Respect later explicit user instructions. Revisit deferred security fixes one concern at a time: verify the gap, explain the proposed change, and obtain fresh approval before implementing it.

Ordinary authorized work does not require a new approval merely because this file exists. Do not introduce a new build/run dependency, certificate requirement, service, or provisioning step without explicit direction. Preserve global FIFO and intentional negative contest-time adjustments.

## Locked decisions

| # | Decision |
|---|---|
| D1 | MySQL 8 is the only application datastore. |
| D2 | Ubuntu 22.04 is the target lab deployment. Docker also makes judge development possible on macOS/Windows. |
| D3 | Instructor surfaces require a loopback request; admin API operations additionally require an `ADMIN` JWT. |
| D4 | HTTPS/WSS is deferred. HTTP/WS and the existing build/run commands are preserved. |
| D5 | One contest can be joinable/live at a time, although historical contests remain stored. |
| D6 | All participant compilation and execution, including custom checkers, runs in Docker; there is no host-process fallback. |

The UI is information-dense and Codeforces-inspired, with both light and dark themes.

## Collaboration rules

The rule numbers below preserve references in source comments and historical migrations. Team: Eshad (platform/data/admin), Mahir (judge/realtime), Zahin (JavaFX client).

### Rule 1 — ownership

| Owner | Domain |
|---|---|
| Eshad | Server configuration, security, entities/repositories, platform services/controllers, auth/admin/contest/problem/user flows, migrations, static admin console, root build/docs |
| Mahir | Judge, realtime, leaderboard, submission/announcement flows, language configuration, Docker scripts |
| Zahin | `arbitrator-client/**`, including resources and packaging |
| Shared | `arbitrator-common/**` |

Outside an explicit scope override, coordinate cross-owner changes through a `[contract-change]` issue and the owner. Material/clarification ownership is not fully covered by the old table; coordinate rather than guessing. Do not contact others unless the user authorizes it.

### Rule 2 — shared contracts

Changes to existing DTO fields, enums, or API paths need team agreement and coordinated server/client updates; the original process requires all three developers' approval and Eshad landing a `contract: ...` commit. Additive DTOs/constants are allowed without that ceremony. Never casually rename shared contracts on an isolated feature branch. Preserve requirement identifiers where useful.

### Rule 3 — local and generated files

Do not commit credentials, local configuration, IDE metadata, build outputs, logs, runtime uploads, or generated bundles. Preserve example configuration. Existing tracked local/generated files are a known deferred cleanup; do not silently delete or untrack working configuration under an unrelated task. See [security item 52](docs/security.md#item-52--local-configuration).

### Rule 4 — formatting and Eclipse

Match surrounding style. Use `config/eclipse-formatter.xml` and `config/eclipse.importorder` for Eclipse format-on-save/import organization. After pulling shared-contract changes, update all Maven projects (Alt+F5), clean Eclipse projects, then run `mvn clean install -Djavafx.platform=linux`; stale Eclipse output can overwrite Maven classes. The shown platform flag is for Ubuntu x86_64; use the matching platform from README on other systems. Keep JavaFX on the classpath and use the Launcher entry point in README.

### Rule 5 — migrations

Eshad: V1–V49; Mahir: V50–V79; integration/hotfix: V80–V99. Never rewrite an applied or already-pushed Flyway migration, including its comments; add a new version and coordinate numbering.

### Rule 6 — database configuration

Each developer uses local MySQL 8 and private credentials. Committed configuration uses placeholders/development examples; real values belong in environment variables or local configuration explicitly loaded by the launcher. Maven excludes the source-tree `application-local.yml`; follow README for matching database credentials and test-schema setup. Use the isolated `arbitrator_test` schema for integration tests, not an application launch against the development database.

### Rule 7 — branches and reviews

The team's integration branch is `dev`; `main` is intended to remain demoable. Human feature branches use `feat/<name>-<issue>-<slug>`; Codex branches default to `codex/<slug>` unless directed otherwise. Keep one behavior per branch, merge current `dev` into the feature branch, run relevant tests and `mvn clean install -Djavafx.platform=linux` before a PR, and obtain one teammate's review. Actual branch protection is unverified. Never force-push `main` or `dev`.

### Rules 8–9 — synchronization and conflicts

Keep branches small; the team's sync cadence is Wednesday/Saturday. Resolve integration conflicts on the feature branch. Inspect both sides instead of blindly accepting one version; coordinate shared-contract/ownership conflicts, preserve both required dependencies in POM merges, and resolve migration numbering without rewriting applied files. Do not discard unrelated work or rewrite Git history without authorization.

## Judge invariants

These rules protect the host and keep verdicts consistent. `RE` means runtime error; `TLE` means time limit exceeded. An OOM kill means the process was terminated for using too much memory.

- Docker must be usable before judging. If it is missing or the daemon is inaccessible, the server starts but judge work returns an internal-error-style `RE`.
- Every compile and run uses a throwaway container with `--network none`, read-only root, a non-root UID, dropped capabilities, `no-new-privileges`, PID/memory/CPU/file-descriptor limits, and tmpfs scratch space.
- The configured image tag is resolved to an immutable image ID at startup. Language commands are startup-validated against the built-in executable/option policy, rendered as argument arrays without a shell, and run with loader/compiler/runtime injection variables cleared.
- Production backend code must not invoke a command shell, `Runtime.exec`, or `ProcessBuilder.startPipeline`. `CommandExecutionPolicyTest` inventories `ProcessBuilder` call sites; adding one requires explicit review and allowlisting.
- `--memory-swap` must equal `--memory`; otherwise Docker can effectively double the intended memory allowance.
- Only the immediate execution directory is mounted at `/sandbox`; the shared work root is never mounted. Custom checkers stage their binary, input, actual output, and expected output in a private per-invocation directory.
- Exit 124 is the reliable GNU `timeout` signal. Exit 137 may indicate an OOM kill and must not automatically become TLE.
- `peakMemoryKb == -1` is expected when a timed-out process is killed before GNU `time` writes its report.
- Never run fork-bomb fixtures directly on the host.

## Persistence and state

### Database and material files

Flyway owns the schema; Hibernate uses `ddl-auto=validate`. Never edit an applied migration—add a new migration in the range assigned by the collaboration rules above.

Raw SQL timestamps must use `UTC_TIMESTAMP()`. The JDBC connection treats MySQL `DATETIME` values without a time zone as UTC.

`submission_results` and PDF statements use JDBC-backed side tables in addition to JPA entities. Uploaded materials are stored on disk with metadata in MySQL, so database-only backups are incomplete.

Contest deletion removes material rows in the same database transaction before deleting the contest. It removes the corresponding files only after commit; a rollback preserves them. Do not call the individual material-delete operation in a loop from contest deletion, because it deletes files before commit. Post-commit filesystem failures are logged for manual cleanup; this is not a durable cleanup queue.

### Contest access

Contest passwords are accepted only by `POST /api/contests/{id}/join`. A successful join stores an opaque `(contest_id, user_id)` grant in `contest_access_grants`; the JavaFX client does not retain or resend the plaintext. Student-facing problem/PDF, custom-run/submission, announcement, material, clarification, leaderboard, contest-state, and contest-topic STOMP paths enforce that grant.

Admins bypass student grants, and LOBBY still hides problem statements and leaderboard problem codes. V80 backfills grants from existing submissions and cascades them on contest/user deletion.

### In-memory state

The active-session registry, presence state, notification queue, judge executor, and scheduled broadcasts are in-memory and intentionally assume one server process.

## Authentication and HTTP invariants

### Signing keys and session identity

`ActiveSessionRegistry.register` requires the database login-time role as well as `sid`. HTTP authentication, WebSocket handshake, and incoming STOMP commands must call the role validation policy before granting access. A role mismatch or deleted account revokes only the matching session, closes its tracked sockets, and requires fresh login. Never authorize newly promoted privileges through an old session. Credential-verified login retires stale-role sessions; new session IDs are always random. Out-of-band SQL changes are detected on the next authentication/message/login, not proactively on idle sockets. Future role-management APIs must explicitly revoke sessions at the role change.

WebSocket handshakes require the JWT `sid` to still match `ActiveSessionRegistry`. `AuthenticatedWebSocketSessions` tracks raw transports by `sid`; replacement login and logout close the invalidated session's existing sockets immediately. Preserve the post-registration active check because it closes the handshake/replacement race.

JWT configuration remains optional: missing/blank secrets generate a private random in-memory key at startup; never restore a public shared signing default. Explicit overrides remain strength/placeholder-validated. Sessions require fresh login after restart.

### Session expiration

Every session has a fixed absolute expiry and a last-activity time. Session ID and role checks must also reject expired sessions. Request checks expire sessions immediately; the scheduled sweep also revokes expired IDs and closes sockets when there is no traffic.

| Setting | Default |
|---|---|
| Absolute lifetime | 12 hours |
| Admin idle timeout | 30 minutes |
| Student idle timeout | Disabled (`0`), to allow long coding sessions |

Never extend the absolute expiry when updating activity. Only authenticated requests that change state, or `POST /api/auth/activity`, refresh idle time. GET/HEAD/OPTIONS polling and STOMP validation or heartbeats do not count as activity.

The admin UI sends throttled activity updates for pointer and keyboard events. Common API 401 responses clear its local session. Student inactivity limits remain configurable.

`SessionExpirationTest` covers exact timeout boundaries. `scripts/security/session-activity-browser.cjs` covers browser activity. Tests using a controlled clock and test socket are not a live timed WebSocket replay.

### Logout

Logout must use the filter-verified `sid` and `clear(username, sid)`, never username-only clearing from a user request. A stale logout must not invalidate a replacement login. Clients must distinguish local-only logout from confirmed server revocation.

### HTTP responses and browser origins

Keep HTTP CORS and WebSocket same-origin restrictions; do not add wildcard origins. Preserve early response security/cache headers, generic unexpected 5xx messages with request IDs, readable expected 4xx messages, and text-only/escaped error rendering. Compatible CSP is implemented; its inline allowance remains an explicit limitation.

## Safe HTML rendering

Generated admin-console controls use delegated `data-action` event binding. Never put fetched/user-controlled values into inline JavaScript handler attributes. Participant and notification names are hydrated with `textContent`; keep that context separation when adding rows. Display names may contain ordinary Unicode and markup characters, but registration rejects control characters and names longer than 128 code points.

`RichTextSanitizer` is the shared allowlist for statement and announcement HTML. Sanitize both persistence inputs and every HTML-bearing response (including legacy rows and title-only edits). Preserve basic formatting/tables/math, but never allow scripts, event handlers, styles, navigation, images, forms, or embedded/remote content. Announcement draft preview uses `textContent`; the admin statement iframe is sandboxed, and all three JavaFX HTML display paths disable JavaScript before loading content.

## Verification and maintenance

Use README's build/test commands. Run the smallest relevant checks while iterating and required broader checks before delivery. Docker-dependent tests can skip without prerequisites; report failures, errors, and skips accurately. Never weaken tests to hide the retained fork/OOM reporting failure. Browser fixtures are separate from Maven, and client source-policy checks are not JavaFX UI execution. Use bounded fake data and authorize external/deployed targets before probing them.

Do not use Mockito while tests must run on JDKs newer than the Byte Buddy version managed by Spring Boot 3.2.5; use the existing dynamic proxies and small fakes.

## Common development problems

- A server left running in an IDE occupies port 8080. Check `lsof -i :8080 -sTCP:LISTEN` before debugging startup.
- Eclipse can overwrite Maven output with stale error-stub classes after `arbitrator-common` changes. Update all Maven projects, clean Eclipse projects, then run `mvn clean install -Djavafx.platform=linux`.
- The standalone STOMP client needs both `tomcat-websocket` and `tomcat-api`; server-side WebSocket tests alone do not verify the client runtime.
- A solved problem remains visually solved after later failed attempts. Reset development submissions when testing status badges.
- Source-ban regexes scan raw source rather than a parsed syntax tree and can match prohibited API names inside comments or strings.
- The judge has a bounded global backlog, per-user submission controls, per-user/global custom-run limits, and shared active-container/aggregate-memory admission.
- `scripts/prepare-zero-download-bundles.py` expects an obsolete client JAR filename. `scripts/make-bundle.py` also assumes all repository files are UTF-8 text despite binary assets. Neither script is currently a reliable release path.

Update current user-facing behavior in README, unresolved security decisions in docs/security.md, and dated verification evidence in docs/audit-history.md. Keep historical audit chronology out of README and AGENTS. `ARBITRATOR_BUNDLE.txt` is a stale generated snapshot and is not authoritative.
