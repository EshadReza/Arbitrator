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

## Current security audit update

FINAL item 37 verification: full `mvn test -Djavafx.platform=linux` retry passed 229 server + 1 client cases (230 total), no failures/errors/skips, 2026-09-17 13:18 +06:00. All 13 timing cases and real TIMESTAMP(6) round-trip passed. Known WebSocket assertion passed; initial failed attempt is documented, not erased. Only test schema was migrated; production V82 applies at normal startup. Next concern remains item 38 direct access bypass.

Item 37 DB precision addition: V82 upgrades submissions.queued_at to TIMESTAMP(6); the single captured admission Instant is truncated to MICROS before guard/storage. A real DB round-trip test checks exact pre-deadline microseconds. V82 is part of the approved timing fix; no client schema/API change. Initial full run hit the known ContestAccessIntegrationTest WebSocket assertion; no unrelated production fix was applied. See STATUS.md for reruns/final result; do not assume the initial run was green.

CURRENT item 37 remediation is approved/implemented: one server Instant at the scored admission guard is validated through the new ContestService overload and persisted as queuedAt. Existing guard delegates using server now; no client timestamp field, persistence-deadline policy, or pause cancellation added. Delay regression now requires equality with validated pre-deadline time. Exact start-inclusive/end-exclusive trusted-instant assertions added; 18 timing/cooldown/queue-service tests passed without skips. Final full result is in STATUS.md. Earlier item-37 pending/no-fix statements are historical. Next is item 38 direct access bypass.

LATEST item 37 verification: 12 ContestTimingAttackTest cases passed without skips, 2026-09-17 13:12 +06:00. Real guard rejects fresh requests before start/after expiry/during pause, permits ACTIVE/FROZEN. A delayed real SubmissionService pipeline with repository/access/queue doubles records queuedAt after deadline despite pre-deadline admission; post-guard fixture pause similarly does not cancel an admitted request. No HTTP/DB race or fresh late-request bypass proven. Proposed same validated/persisted server admission instant awaits approval; do not change persistence-deadline or pause-cancellation policy without a separate decision. No production changes/full-suite rerun for item 37. Full item-36 snapshot excludes these 12 additions. Details in STATUS.md.

CURRENT REVIEW item 37 contest timing: scored admission and stored timestamps use server time, with start/end/state/paused checks; no client submission timestamp exists. Potential check-to-queuedAt deadline inconsistency and stale contest snapshot need boundary/delay tests before claiming a bypass. Proposed dedicated timing tests await approval; no changes/tests for item 37. Custom-run practice availability is separate from scored submission policy. See STATUS.md; item 38 is next unreviewed concern.

Latest full verification for completed item 36: `mvn test -Djavafx.platform=linux` passed 215 server + 1 client tests (216 total), no failures/errors/skips, 2026-09-17 11:14 +06:00. V81 was applied to the separate test schema; production migration remains the normal server-startup step. Five race protection cases and three advice tests passed. Next concern is item 37; no implementation on deferred concerns.

CURRENT item 36: user approved remediation. PersistenceConflictAdvice maps ONLY named username/title MySQL duplicate keys to 409; unrelated integrity failures remain sanitized 500, optimistic write conflicts return reload/retry 409. Problem @Version plus additive V81 migration protects overlapping entity transactions; no client version token or broad contest locking was added. Converted five race tests now pass with clean HTTP registration/clone conflicts and one rejected stale editor rather than a silent lost successful change. Three advice tests cover mapping/sanitization. See STATUS.md for final full verification. Older no-fix/awaiting-approval paragraphs are historical. Next concern: item 37 contest timing attacks; deferred concerns remain unchanged.

LATEST item 36: five authorized RaceConditionAttackIntegrationTest cases passed without skips, reproducing registration (one 201/seven 500) and clone (one 200/five 500) conflicts, with uniqueness intact. Six HTTP joins all succeed with one grant. Controlled real-DB/direct-controller transaction interleavings demonstrate a lost disjoint problem edit; a stale edit after repository deletion raises ObjectOptimisticLockingFailureException without resurrection. The latter tests are not HTTP scheduling/mapping tests. No production fix is approved; proposed targeted unique-key 409 mapping and optimistic problem-edit conflict detection await user decision. Full-suite item-35 snapshot remains historical and does not cover the five new cases. Details and exact command in STATUS.md.

CURRENT REVIEW: item 36 race conditions. Existing unique constraints, idempotent join upsert, transactions and submission stripe locks provide partial protection. Registration and same-title cloning lack duplicate-key-to-409 mapping; overlapping admin operations have no version/explicit lock and need reproduction, not assumed corruption. Proposed dedicated concurrent tests await approval; no new tests or production changes for item 36. See STATUS.md. Item 35 remediation remains completed/verified.

CURRENT: item 35 symlink remediation is approved and implemented. Compilation rejects links/special artifacts using NOFOLLOW_LINKS; SafeSandboxFiles protects judge/custom-run/checker host staging and copies. Existing staging files are replaced rather than truncated to avoid hard-link writes. Three injected compiler-link tests now require CE and unchanged outside targets; two helper tests cover link rejection and safe normal/hard-link operations. Earlier item-35 no-fix/awaiting-approval statements below are historical. Keep item-34 metrics exposure deferred. Final verification is recorded in STATUS.md. Next concern is item 36 race conditions.

Item 35 verification update: user authorized tests only. Three real JudgeWorker staging cases with test-only compiler-stage `ln -s` fault injection reproduced outside temporary-host-sentinel overwrites through input, actual-output, and expected-output paths, with AC verdicts. An ordinary-runtime source probe checks workspace link creation is blocked. The injected `ln` is not a production compiler command or a demonstrated source-level exploit. No production fix is approved/implemented; see STATUS.md for final targeted verification and limitations. Convert characterization assertions into protection assertions if remediation is later approved.

Latest decision: user declined the item-34 measurement-isolation remediation and moved to item 35. Keep `/run-metrics` exposure deferred. Item 35 review found missing symlink rejection after writable compilation and link-following host staging operations; ordinary runtime workspaces are read-only and no source-to-host exploit is proven. Proposed symlink rejection/no-follow staging/sentinel tests await approval. Do not implement this proposal without approval. Details are in STATUS.md; this update supersedes earlier current-item statements.

Item 34 dedicated filesystem attacks are implemented with user approval; all 18 live Docker `SandboxExecutorTest` cases passed with no skips on 2026-09-16. Host/sibling traversal, forbidden runtime/system writes, and per-container temporary storage isolation passed. A characterization test confirmed submitted code can overwrite/read back `/run-metrics`; this is an open measurement trust-boundary exposure, not a proven verdict bypass. Production remediation has NOT been approved or implemented. See STATUS.md for scope and proposed trusted-supervisor/bounded-parser fix. Item 34 remains current; convert the characterization test to a protection test after remediation. The earlier full-suite result remains historical, not a rerun covering these three additions.

## Current feature model

- Authentication: student self-registration with server-enforced student IDs matching `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, seeded instructor account, bcrypt cost 12, 12-hour JWT, automatic private per-startup signing key (optional configured override), database-backed current-role authorization, explicit logout, in-memory single-active-session registry enforced across REST and live WebSockets, optional client MAC reporting, and persistent per-user contest-access grants established by one-time password entry
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

Multipart requests are globally capped at 64 MB. Material-service messages mention 200 MB, but the Spring limit currently wins.

The default `reset-on-boot=false` means a live contest survives a server restart and its wall-clock deadline continues to advance. Set it to `true` when every restart should return non-ended contests to `DRAFT` and clear their clocks.

## Judge invariants

- Docker must be usable before judging. If it is missing or the daemon is inaccessible, the server starts but judge work returns an internal-error-style `RE`.
- Every compile and run uses a throwaway container with `--network none`, read-only root, a non-root UID, dropped capabilities, `no-new-privileges`, PID/memory/CPU/file-descriptor limits, and tmpfs scratch space.
- The configured image tag is resolved to an immutable image ID at startup. Language commands are startup-validated against the built-in executable/option policy, rendered as argument arrays without a shell, and run with loader/compiler/runtime injection variables cleared.
- Production backend code must not invoke a command shell, `Runtime.exec`, or `ProcessBuilder.startPipeline`. `CommandExecutionPolicyTest` inventories `ProcessBuilder` call sites; adding one requires explicit review and allowlisting.
- `--memory-swap` must equal `--memory`; otherwise Docker can effectively double the intended memory allowance.
- Only the immediate execution directory is mounted at `/sandbox`; the shared work root is never mounted. Custom checkers stage their binary, input, actual output, and expected output in a private per-invocation directory.
- Exit 124 is the reliable GNU `timeout` signal. Exit 137 may indicate an OOM kill and must not automatically become TLE.
- `peakMemoryKb == -1` is expected when a timed-out process is killed before GNU `time` writes its report.
- Never run fork-bomb fixtures directly on the host.
- Raw SQL timestamps must use `UTC_TIMESTAMP()`. The JDBC connection treats zoneless MySQL `DATETIME` values as UTC.

## Persistence and state

Flyway owns the schema; Hibernate uses `ddl-auto=validate`. Never edit an applied migration—add a new migration in the range assigned by `rules.md`.

`submission_results` and PDF statements use JDBC-backed side tables in addition to JPA entities. Uploaded materials are stored on disk with metadata in MySQL, so database-only backups are incomplete.

Contest deletion removes material rows in the same database transaction before deleting the contest. It removes the corresponding files only after commit; a rollback preserves them. Do not call the individual material-delete operation in a loop from contest deletion, because it deletes files before commit. Post-commit filesystem failures are logged for manual cleanup; this is not a durable cleanup queue.

Contest passwords are accepted only by `POST /api/contests/{id}/join`. A successful join stores an opaque `(contest_id, user_id)` grant in `contest_access_grants`; the JavaFX client does not retain or resend the plaintext. Student-facing problem/PDF, custom-run/submission, announcement, material, clarification, leaderboard, contest-state, and contest-topic STOMP paths enforce that grant. Admins bypass student grants, and LOBBY still hides problem statements and leaderboard problem codes. V80 backfills grants from existing submissions and cascades them on contest/user deletion.

The active-session registry, presence state, notification queue, judge executor, and scheduled broadcasts are in-memory and intentionally assume one server process.

`ActiveSessionRegistry.register` requires the database login-time role as well as `sid`. HTTP authentication, WebSocket handshake, and incoming STOMP commands must call the role validation policy before granting access. A role mismatch or deleted account revokes only the matching session, closes its tracked sockets, and requires fresh login. Never authorize newly promoted privileges through an old session. Credential-verified login retires stale-role sessions; new session IDs are always random. Out-of-band SQL changes are detected on the next authentication/message/login, not proactively on idle sockets. Future role-management APIs must explicitly revoke sessions at the role change.

WebSocket handshakes require the JWT `sid` to still match `ActiveSessionRegistry`. `AuthenticatedWebSocketSessions` tracks raw transports by `sid`; replacement login and logout close the invalidated session's existing sockets immediately. Preserve the post-registration active check because it closes the handshake/replacement race.

Generated admin-console controls use delegated `data-action` event binding. Never put fetched/user-controlled values into inline JavaScript handler attributes. Participant and notification names are hydrated with `textContent`; keep that context separation when adding rows. Display names may contain ordinary Unicode and markup characters, but registration rejects control characters and names longer than 128 code points.

`RichTextSanitizer` is the shared allowlist for statement and announcement HTML. Sanitize both persistence inputs and every HTML-bearing response (including legacy rows and title-only edits). Preserve basic formatting/tables/math, but never allow scripts, event handlers, styles, navigation, images, forms, or embedded/remote content. Announcement draft preview uses `textContent`; the admin statement iframe is sandboxed, and all three JavaFX HTML display paths disable JavaScript before loading content.

## Tests and verification

Session-expiration policy (item 21): registry entries carry fixed absolute expiry and last meaningful activity. Session ID/role checks must also reject expired entries. The scheduled sweep revokes expired IDs and closes tracked sockets even without traffic; request checks expire entries immediately, so cleanup scheduling cannot extend request authorization. Never extend absolute expiry when touching activity. Only authenticated mutating requests or the explicit `/api/auth/activity` POST refresh idle time; GET/HEAD/OPTIONS polling and STOMP validation/heartbeats do not. Admin UI pointer/keyboard events send throttled activity updates, and common API 401 responses clear its local session. Admin idle default is 30 minutes; student idle default is zero (disabled for long coding), configurable if a deployment wants request-based participant inactivity limits. Default absolute lifetime remains 12 hours. See `SessionExpirationTest` and `scripts/security/session-activity-browser.cjs` for deterministic timeout and browser activity coverage. Do not describe proxy-socket clock tests as a live timed WebSocket replay.

User-requested setup revision: JWT environment configuration is optional. Missing/blank `arbitrator.jwt.secret` generates a private random in-memory HS256 key per process; never restore a public shared default. No setup script or persisted key file was added. Explicit keys are still strength/placeholder-validated. The latest revision passed nine focused key/role tests; this revision is now included in the latest full-suite snapshot below. The in-memory session registry and default ephemeral key both require fresh login after restart.

```bash
mvn test -pl arbitrator-server
```

Latest fully green full Maven result (item 30) on 2026-09-16: 198 server tests and 1 client source-policy test, 0 failures, 0 errors, 0 skipped with local MySQL and Docker available. This includes adversarial sandbox- and network-isolation, compiler-policy/image-pinning/environment checks, the command-execution guard, a two-user HTTP/STOMP IDOR matrix, registration-role injection, forged/sid-less JWT rejection, immediate database-role demotion, stored-HTML sanitization, cross-language runtime-hardening, and resource-exhaustion coverage plus the existing database, authorization, ZIP-import, and rendering suites. Environment-dependent tests can still skip when their prerequisites are unavailable. The client policy test checks source configuration, not a live JavaFX UI; there are no automated JavaFX UI or React product tests. The earlier student-name XSS fix has a recorded live browser replay in `STATUS.md`.

Do not use Mockito in this repository while tests must run on JDKs newer than the Byte Buddy version managed by Spring Boot 3.2.5. Existing service tests use dynamic proxies and small fakes.

Reflected-XSS checklist item 13 additionally passed three focused live-HTTP tests (40 hostile requests) in `ReflectedXssAttackIntegrationTest` on 2026-09-16, included in the latest full-suite snapshot above. The test covers query parameters, registration/missing-route errors, genuinely reflected authenticated binding errors, JSON/HTML content negotiation, and `nosniff`; it does not execute a browser. No production fix was indicated. Preserve escaped/text-only error rendering. See `STATUS.md` for coverage and limitations.

DOM-XSS item 14 subsequently passed the standalone Chromium harness `scripts/security/dom-xss-attack.cjs`: four payloads across nine rendering paths, with zero payload execution/dialogs/page errors and a working positive execution control. It uses the real admin page, mocked DTO responses, the production Java sanitizer for published HTML, and a sandbox challenge. See `scripts/security/README.md` for prerequisites and limitations. It is separate from Maven and does not exercise JavaFX or real publication. No production fix was needed; the next audit item is 15 (Markdown security).

Subsequent items 15–18 were reviewed; proposed Markdown/CSRF regression tests were skipped by user progression, cookies are not used for authentication, and the absent CSP was explicitly deferred. Item 19's approved role-bound-session fix is complete and included in the 131-server-test full run. See `STATUS.md` for detailed choices, detection limitations, and verification. Current next item: 20 (logout). Do not interpret deferred CSP or skipped attack coverage as implemented/tested.

Item 20's approved logout fix is complete: require the filter's verified `sid` request attribute and use `clear(username, sid)` for user logout. Never clear by username alone from a user request; concurrent replacement login must survive a stale logout. Admin sign-out posts the token then reloads to clear credentials/UI; server failures warn local-only logout. JavaFX also reports unconfirmed remote logout. Offline logout cannot promise token revocation. The item-20 full suite passed 133 server tests plus 1 client policy test; live logout HTTP/WebSocket coverage and the standalone `scripts/security/logout-browser.cjs` checks passed. The JavaFX warning has compilation coverage, not a live GUI replay. At that stage the next item was 21 (session expiration).

## Known high-priority defects

Current audit handoff: item 30 approved queue-abuse tests are implemented; production queue policy was not changed. The real queue/executor passed a controlled 10,000-attempt concurrent flood (500 admitted, 9,500 rejected; at most four workers), capacity recovery, worker failure, and executor rejection tests. Real SubmissionService with repository/admission doubles passed per-user 20/below-five hysteresis, isolation, concurrent boundary, pre-persistence saturation rejection, and persistence-failure reservation release tests. No 10,000-container, live-HTTP, or real-DB load test is claimed. Final full verification is in STATUS.md. Shared global queue remains the user-selected policy; no per-IP backlog quota or fairness guarantee. Item 31 fair scheduling was explicitly declined; retain FIFO shared global queue without per-user concurrency quotas. Item 32 Docker logging fix was explicitly declined: inherited logging disk risk remains deferred, with no implementation. Item 33 fork-bomb review confirms run/compile PID limits of 64/200 and an existing live Docker fork-bomb test passing in the latest full suite (no new test run on review turn). The test checks prompt termination, not an exact observed PID ceiling. Next concern: 34 (filesystem attacks). MFA remains deferred; no reset or email features were introduced.

No Priority 0 or Priority 1 findings from the full-codebase audit remain open. This does not make the system production-ready; operational, packaging, backup, UI-test, and load-test work remains.

See `STATUS.md` for the broader prioritized backlog.

## Things that commonly bite

- A server left running in an IDE occupies port 8080. Check `lsof -i :8080 -sTCP:LISTEN` before debugging startup.
- Eclipse can overwrite Maven output with stale error-stub classes after `arbitrator-common` changes. Update all Maven projects, clean Eclipse projects, then run `mvn clean install`.
- The standalone STOMP client needs both `tomcat-websocket` and `tomcat-api`; server-side WebSocket tests alone do not verify the client runtime.
- A solved problem remains visually solved after later failed attempts. Reset development submissions when testing status badges.
- Source-ban regexes scan raw source rather than a parsed syntax tree and can match prohibited API names inside comments or strings.
- The judge has a bounded global backlog, per-user submission controls, per-user/global custom-run limits, and shared active-container/aggregate-memory admission.
- `scripts/prepare-zero-download-bundles.py` expects an obsolete client JAR filename. `scripts/make-bundle.py` also assumes all repository files are UTF-8 text despite binary assets. Neither script is currently a reliable release path.

## Change discipline

Follow `rules.md` for ownership, migration numbering, branches, and reviews. Newer material/clarification files are not fully represented in the old ownership table; coordinate ownership rather than guessing.

Match surrounding style. Comments should explain why and preserve requirement identifiers where they help acceptance traceability. Changes affecting shared DTOs or API constants must update server and client consumers together.
