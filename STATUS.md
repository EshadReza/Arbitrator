# Arbitrator — implementation status

**Last audited:** 2026-09-15
**Version:** `0.1.0-SNAPSHOT`
**Readiness:** feature-rich beta; suitable for controlled demonstrations and supervised lab trials, not yet production-ready

This file describes the source as it exists now. `WORKFLOW_PLAN.md` contains the current roadmap and the historical sprint mapping; `rules.md` remains the authority for team ownership and Git workflow.

## Verification snapshot

The full Maven server suite was run during the 2026-09-15 repository audit:

| Result | Count |
|---|---:|
| Tests discovered | 86 |
| Passed | 86 |
| Failed | 0 |
| Errors | 0 |
| Skipped | 0 |

The latest run used local MySQL and Docker: all sandbox, worker, checker, STOMP, contest-access, material-deletion, registration-policy, duplicate-policy, ZIP-import, and admin-rendering tests passed without skips. Six tests cover material cleanup and rollback behavior; four cover the contest entry boundary; eleven cover registration input policy and the admin XSS boundary; three cover active-session replacement, including a live HTTP/WebSocket integration scenario; seven cover exact duplicate semantics; and three new importer tests cover exact-cap reading, unknown-size overflow, and normalized-path collisions. This run does not replace a real LAN rehearsal.

The JavaFX client has no automated UI suite. The React experiment was not built during this audit because its `node_modules` directory was absent; it is outside the root Maven build.

## Implemented

### Platform, data, and security

- Spring Boot 3.2.5, Java 17, MySQL 8, Spring Data JPA, Flyway, Spring Security, and JJWT
- Student registration with a server-enforced 1–64 character student-ID policy (`A-Z`, `a-z`, `0-9`, `.`, `_`, `-`) plus seeded student/instructor accounts for development
- BCrypt cost 12 and 12-hour HS256 JWTs
- In-memory single-active-session registry with confirm-to-replace login behavior
- Explicit logout plus immediate REST-token and live-WebSocket invalidation
- Optional client MAC reporting and instructor notifications when a student's reported MAC changes
- Loopback filter for `/admin/**` and `/api/admin/**`, with `ADMIN` authorization on admin APIs
- Flyway schema through V80, including contests, users, problems, tests, submissions, results, announcements, clarifications, PDF statements, MAC data, materials, and persistent contest-access grants
- Persistent uploaded materials on disk with metadata in MySQL

### Contest and problem administration

- Contest lifecycle: `DRAFT`, `LOBBY`, `ACTIVE`, `PAUSED`, `FROZEN`, `ENDED`
- One joinable/live contest at a time
- Open lobby, start, pause/resume, schedule, extend/remove time, freeze/unfreeze, end, clone, and delete operations
- Authoritative server clock and pause-aware deadline adjustment
- Optional contest password field and one-time client prompt; successful entry stores a server-side per-user grant rather than retaining or retransmitting plaintext
- ZIP problem import with aggregate validation and atomic persistence
- HTML, HTM, plain-text, Markdown, and PDF statements
- Paired input/output tests, hidden-test control, exact/custom checker configuration, statement replacement, problem editing, reordering, and deletion

### Judge and submissions

- C++17, Java 17, and Python 3.10
- Persist-before-queue submission flow and startup recovery of unfinished submissions
- Fixed-size judge worker pool, queue-position acknowledgement, per-user cooldown, and one-in-flight-submission guard
- Docker-backed compilation, execution, and custom checkers
- Network-disabled, non-root, read-only-root containers with memory/swap, PID, CPU, file, process, and output controls
- Stale-container cleanup, Docker binary discovery, image/daemon readiness diagnostics, and disk-headroom check
- Exact matching, custom checkers, fail-fast tests, compiler output, failed-test index, per-test timing/memory/output, and test-case visibility
- Verdicts: `AC`, `WA`, `TLE`, `MLE`, `CE`, `RE`, and `OLE`
- Custom input/output runs that are judged in the sandbox but not stored as contest submissions
- Submission history, source privacy, participant/admin test detail, manual verdict overrides, marks, and penalty adjustments

### Real-time and scoring

- JWT-authenticated STOMP/WebSocket handshake
- Private verdict events
- Contest state, standings, and announcements broadcasts
- Presence tracking with disconnect debounce and admin participant monitoring
- ICPC-style scoring: accepted time plus 20 minutes per prior rejected attempt, with CE excluded
- First-solve tracking, solved/penalty/last-accept ordering, frozen standings, and participant attempt drill-down

### Participant client

- Login, registration, contest picker, sign-out, switch-contest, and manual refresh
- RichTextFX editor with language-specific highlighting, line numbers, auto-indent, bracket/quote completion, block indent/dedent, file upload, and per-problem local drafts
- HTML and PDF statement rendering; PDF pages are rasterized for display with a 30-page client cap
- Custom runs, submit flow, verdict banner, submission history/source/tests, leaderboard, announcements, materials, and clarifications
- Light/dark themes, full-screen statement/editor/window modes, zoom, collapsible problem list, shortcuts, and reconnect watchdog

### Instructor console

- Browser login gate and loopback-restricted serving
- Delegated event binding for generated controls; student-controlled names are assigned with `textContent` rather than embedded in executable HTML attributes
- Dashboard, live server/contest clock, contest controls, problem management, participant presence, submission feed, source/test inspection, standings, marks, penalty/verdict controls, and exports
- Announcements, materials, clarification answering/approval, notification feed, test visibility, clarification privacy, and server-address display

## Not implemented

- Float-tolerance judging (FR-13)
- Rejudge pipeline and instructor rejudge control (UC-14)
- Supported cross-platform installer or complete zero-download distribution
- Automated database-plus-material backup and restore workflow
- HTTPS/WSS
- Bengali localization or a runtime locale selector; only the English resource scaffold exists
- TestFX client suite, JMeter/load suite, JaCoCo target, dependency-check automation, and a full dress rehearsal
- A production React web application; `arbitrator-web` is only a toolchain smoke test

## Confirmed fixes

### Contest-password authorization boundary — fixed 2026-09-15

`POST /api/contests/{id}/join` now verifies the password and records an opaque `(contest_id, user_id)` grant. All student contest-state, problem/PDF, custom-run/submission, announcement, material, clarification, leaderboard/attempt, and contest-topic STOMP paths enforce that grant; admins bypass the student gate. Problem detail/PDF and leaderboard problem codes remain hidden in `LOBBY` even after admission. V80 backfills grants from existing submissions and deletes grants automatically with their contest or user. The JavaFX client no longer stores the contest password or sends it in a query parameter.

The live replay used disposable contest 45 with problem 272 and material 17. The old `?password=` URL and direct contest, problem-list, problem-detail, and material-download requests all returned 403 before joining; a wrong join also returned 403. A correct join returned 200, later refresh succeeded without a password, the lobby returned an empty problem list and no leaderboard problem codes, and direct problem detail remained 403 until the contest started. Active problem/material access returned 200 and submission returned 202. Contest deletion returned 204 and left no fixture contest row. `ContestAccessIntegrationTest` adds four MySQL-backed HTTP/STOMP regression tests.

### Contest deletion with materials — fixed 2026-09-15

The live reproduction cloned Lab Contest #1 into contest 42, copied three text materials, ran a C++ submission (AC, 5/5), and ended the contest. Deletion initially returned HTTP 500 naming `fk_materials_contest`; the database transaction rolled back and all files remained.

`AdminContestController` now calls `MaterialService.deleteForContest()` inside its transaction. Material rows are deleted immediately before the contest's JDBC deletion. File cleanup runs only after commit, preserving downloads if later SQL fails and rolls back. Missing files are tolerated; cleanup failures are logged with the contest and path and do not prevent cleanup of other files. Filesystem cleanup is best-effort: an I/O error or process exit after commit can still leave orphan files requiring manual cleanup.

After the fix, deleting the same contest returned HTTP 204. Its problems, submission, material rows, and three files were removed; material downloads returned 404. All original contest summaries, material records, and remaining file checksums were unchanged. `ContestMaterialDeletionTest` verifies the transaction behavior against the separate `arbitrator_test` MySQL schema and temporary files, including a real FK failure after deletion to prove rollback safety.

### Instructor-console stored XSS — fixed 2026-09-15

Generated admin rows no longer interpolate usernames, display names, contest titles, or problem codes into inline JavaScript handlers. They carry numeric IDs/indices in `data-*` attributes and a delegated listener resolves the corresponding object from the last fetched model. Participant and notification names are inserted with `textContent`, so markup-like display names remain literal text. Existing static handlers with fixed source literals remain, but no template expression is placed inside an inline `onclick`, `onchange`, or `onkeydown` attribute.

Registration trims the student ID before both uniqueness checking and storage, then requires `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`. Display names remain Unicode-friendly but are limited to 128 code points and reject control characters. The live browser replay registered `<img src=x onerror=alert(1)>` as a display name, triggered a MAC-change notification, and showed the payload literally with zero inserted `img` elements, zero payload-bearing handler attributes, no dialog, and a working delegated notification-history control. A `<script>` student ID returned HTTP 400. The disposable notification and account were removed afterward.

### Forced-login WebSocket invalidation — fixed 2026-09-15

`JwtHandshakeInterceptor` now requires the JWT's `sid` to match `ActiveSessionRegistry` and stores both username and session ID in the WebSocket attributes. `AuthenticatedWebSocketSessions` tracks established transports by `sid`; replacement login and logout invalidation events immediately close every corresponding socket with policy-violation status. Registration performs a second active-session check after tracking the transport, closing the race between HTTP handshake validation and WebSocket establishment.

The MySQL-backed integration replay registered a disposable account, opened its first STOMP session, force-logged in again, and verified that the old socket received a transport close within five seconds. The old token then returned HTTP 401 and could not establish another WebSocket, while the replacement token connected successfully. The disposable account was deleted by test cleanup. Two focused registry tests also verify exact old-`sid` notification on replacement/logout and that transport-cleanup failure cannot undo authentication state.

### Duplicate-submission semantics — fixed 2026-09-15

Duplicate detection now compares the submitted source with prior active submissions for the same user and problem using exact Java `String` equality. Only an exact repeat is rejected; whitespace is no longer removed, so Python indentation, spaces inside strings, token boundaries, line endings, and trailing whitespace retain their program meaning. Reusing identical source for a different problem remains allowed.

Seven focused tests cover an exact repeat, five representative whitespace changes, and problem scoping. They are included in the current 86-test full build, which passes without skips.

### ZIP size and path enforcement — fixed 2026-09-15

The importer no longer trusts `ZipEntry.getSize()`, which is commonly unknown for streamed entries. It reads through the 32 MiB per-file cap and probes one additional byte, rejecting the whole package instead of accepting a truncated prefix. Existing 5,000-entry and 256 MiB aggregate-uncompressed limits remain enforced.

Entry names are canonicalized before validation and storage: backslashes become forward slashes, redundant separators and `.` segments are removed, and absolute paths, `..` traversal, NUL characters, empty paths, and duplicate canonical names are rejected. This prevents later entries from replacing an earlier configuration, statement, or test through a path alias. Three new tests cover the exact boundary, a streamed unknown-size 32 MiB + 1 byte entry, and a slash/backslash collision. The full Maven build passes with 86 tests and no skips.

## Open defects and risks

### Priority 1 — correctness and security hardening

No Priority 1 findings from the audit remain open.

### Priority 2 — operational and quality risks

7. Multipart requests are capped at 64 MB in `application.yml`, while `MaterialService` advertises a 200 MB material limit. The effective limit is currently 64 MB.
8. The global judge executor uses a bounded worker count over an unbounded FIFO queue. Per-user limits reduce abuse but do not cap total backlog.
9. Source-ban regexes scan raw text and can match prohibited API names in comments or string literals.
10. HTTP/WS exposes passwords, JWTs, source code, and contest traffic to LAN interception. This is an accepted v1 deferral, not a secure default.
11. Uploaded materials are outside MySQL and require coordinated filesystem backup and restore.
12. Active sessions, presence, notifications, the judge executor, and broadcast schedules are in-memory and assume one server process.
13. Output-limit classification operates on captured Java string length near the limit, which may differ from byte-level behavior for non-ASCII output.
14. Client reconnect behavior has not been proven under real cable loss or sustained packet loss.
15. Java JVM overhead can make low per-problem memory limits less comparable across languages.

## Release and repository hygiene

- `scripts/prepare-zero-download-bundles.py` expects `arbitrator-client-0.1.0-SNAPSHOT.jar`, but the client POM produces `arbitrator-client-<javafx.platform>.jar`.
- The client POM defaults to `mac-aarch64`; an Ubuntu build must explicitly pass `-Djavafx.platform=linux`.
- `scripts/make-bundle.py` opens every included file as UTF-8 even though the repository now contains PDF, PNG, and TTF assets. Its unpack instructions also reference the removed `scripts/sandbox-run.sh`.
- `ARBITRATOR_BUNDLE.txt` is an old Bundle 1 snapshot and is not an accurate copy of the current tree.
- The current `.gitignore` only excludes `target/`, `dist/`, and `arbitrator-data/`. IDE metadata, `application-local.yml`, `.DS_Store`, and the generated bundle are already tracked despite `rules.md` saying they must not be.
- The root POM description still says “visual programming labs”; the product is for programming labs generally.
- Some source comments still describe an older multi-contest or host-sandbox design.

## Next actions

Recommended order:

1. Repair `.gitignore`/tracked local artifacts and the packaging scripts without committing real credentials.
2. Extend controller/security integration coverage beyond the now-tested session replacement, contest-access, deletion-transaction, registration-policy, admin-rendering, duplicate-policy, and ZIP-import paths.
3. Run a multi-client LAN soak test and visual acceptance pass on Ubuntu 22.04; the complete Docker/MySQL suite is currently green without skips.
4. Implement rejudge and float tolerance now that the correctness/security audit backlog is green.
5. Produce a repeatable server/client distribution, backup/restore procedure, and operator checklist before tagging v1.0.

## Definition of v1.0-ready

Do not call the project v1.0-ready until:

- Priority 0 and Priority 1 defects have tests and verified fixes.
- The full suite runs without environmental skips on the target deployment machine.
- Student and instructor flows pass on the real LAN with at least 20 concurrent clients for a two-hour rehearsal.
- Docker, MySQL, material storage, restart behavior, and backup/restore have an operator-tested runbook.
- Ubuntu client packaging and installation are repeatable on a clean participant machine.
- Documentation and generated release artifacts are produced from the same tagged commit.
