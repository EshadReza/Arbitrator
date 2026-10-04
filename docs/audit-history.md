# Historical implementation and security audit record

Archived from the working copy of `STATUS.md` during the documentation consolidation on 2026-09-27. The record below preserves the original wording and order. Links, heading levels, paragraph breaks, and navigation have been adjusted for readability. It includes superseded proposals and dated test results; use [security.md](security.md) for current unresolved findings and final decisions, [README.md](../README.md) for current usage, and [AGENTS.md](../AGENTS.md) for development instructions.

Legacy plain-text references are historical: `CLAUDE.md` and `rules.md` were consolidated into `AGENTS.md`; `WORKFLOW_PLAN.md` was retired; `OPEN_SECURITY_ITEMS.md` became `docs/security.md`; `INCIDENT_RECOVERY.md` became `docs/recovery.md`. Repository paths in the record are relative to the repository root. The old “next actions” and v1.0 proposals are not implementation approval.

## Verification update — 2026-10-04

- Full Maven command: `mvn -B test -Djavafx.platform=linux`, run with JDK 17, finished 13:02:06 +06. Result: **332 server tests and one client source-policy test passed; zero failures, errors or skips**. Docker-dependent `SandboxExecutorTest` passed 40/40. Database integration tests used the isolated `arbitrator_test` schema.
- The preceding user-provided run used Java 27-ea and failed with four Mockito/Byte Buddy instrumentation errors in `JwtAuthFilterHealthTest` and `HealthChecksTest`; the client module was skipped after the server failed. The Byte Buddy version in that run reported support through Java 22, not Java 27. JDK 17 is the project's target and the full run above did not encounter those errors.
- The earlier fork/OOM reporting error was not reproduced in this run. No classifier or test fix was made, and one green run does not close the deferred intermittent defect. Neither this run nor the earlier live test certifies a clean target-machine deployment or public-production security.

## Live validation — 2026-09-27

<a id="live-validation-2026-09-27"></a>

**Result:** core contest workflows worked, but two live defects were found. No production source or tests were changed during this validation. The older records below retain their historical wording.

### Environment and evidence

- Fedora 44, OpenJDK 17.0.19, MySQL 8.4.10, Docker judge, Linux JavaFX 21.0.3 packaged client.
- Packaged server on local port `18080`, isolated `arbitrator_test` schema, temporary material/judge roots. The development application database was not used.
- Cleanup completed: the temporary JavaFX harness, QA server and browser tab were stopped/closed. The two main synthetic contests remain ended in the test schema for inspection; disposable clones and timestamp-reproduction contests were deleted.
- Full command: `mvn -o -B --fail-at-end test -Djavafx.platform=linux`. Finished **00:58:36 +06**, duration **1m39s**: **332 server + 1 client tests passed; zero failures, errors, or skips**.
- The temporary API matrix recorded **88 passing checks**, **one genuine failure** at the start-time boundary, and **one harness field-name error**, corrected and successfully rerun. Two additional targeted reproductions confirmed the start-time defect.
- Real JavaFX controls were exercised through a user-approved temporary harness because native desktop automation was unavailable. The harness and evidence are outside the repository at `/tmp/arbitrator-qa-20260927` and are temporary, not committed regression tests. That directory includes private test credentials/tokens; do not publish it wholesale.

### Confirmed defects

| Finding | Observed behavior and cause | Impact |
|---|---|---|
| Immediate-start submissions can be rejected | Starting a contest returned `ACTIVE` with start time `1790492898604`; reading it back returned `1790492899000`. A submission at `1790492898627` received HTTP 403, “The contest has not started yet.” A second controlled contest reproduced the same behavior. The database's whole-second `DATETIME` rounds the persisted start forward while admission compares precise instants. | A legitimate submission in the first fraction of a second can be rejected. Align persisted timestamp precision and admission handling; do not rewrite an applied migration. |
| Pausing floods the instructor action log | One pause generated “Contest is now PAUSED” at 13:03:07, :10, :13, :16 and :19. `pollContestState()` treats a changed end time as a state transition, while `Contest.endTime()` moves forward during an ongoing pause. | Repeated toast/log noise obscures actual actions. Distinguish a lifecycle change from the moving pause deadline. |

Both remain unfixed. The initial custom-checker acceptance attempt encountered the timestamp defect; retrying after the boundary correctly produced AC. Checker rejection and timeout also worked.

### Features exercised

| Area | Verified behavior |
|---|---|
| Build and launch | Linux package build; native libraries/manifest; real packaged server and client startup; liveness/readiness, operations and audit endpoints. |
| Accounts and access | Real GUI registration/login, replacement-session confirmation, completed logout and fresh login; API admin restrictions; wrong/right contest passwords; GUI password prompt and successful entry. |
| Contest lifecycle | Create, schedule, lobby, start, pause/resume, freeze/unfreeze, positive/negative time adjustment, end, clone, and disposable-contest deletion. Client pause/end states disabled scored submission. Schedule creation was checked; a timed automatic start was not awaited. |
| Problems | HTML, Markdown and PDF ZIP import; title/limit edit; reorder; original test data; PDF download; clone including PDF; disposable-problem deletion. HTML/PDF were displayed in the real client. |
| Judge | C++17, Java 17 and Python custom-input runs and AC submissions; WA, CE, RE and TLE; live custom checker accepting alternate output and rejecting invalid output. All compilation/execution used Docker. |
| Participant workflow | Submit, live verdict, solved badge, standings, history, own-source dialog, source draft restored after client restart, refresh, theme, zoom, problem-list toggle, statement/editor fullscreen, switch contest, and custom practice after ending. |
| Instructor grading | Participants, grouped submissions, standings, source retrieval, marks, penalty adjustment, verdict override and restoration. |
| Communication | Admin GUI announcement delivered as an acknowledgement popup; public/private API clarifications, answers and approval; real GUI question answered/approved in the browser; another student's private question remained hidden; disconnect/reconnect notification. |
| Materials | Upload and exact downloaded bytes through API; list visible in both UIs; individual deletion and contest-deletion cascade on disposable records. |
| Export | CSV and HTML export controls completed, including best-submission/source options. UI reported “Downloaded” and no browser console errors. Actual saved-file contents were not verified; the embedded browser exposed no download event/file. |
| End conditions | Ended contest rejected scored submissions, preserved standings/history, and allowed custom-input practice while still open in the client. |

### Warnings and limits

- FXML files declare JavaFX API version 26 while the packaged runtime is 21.0.3. Screens loaded, but the mismatch produces warnings and should be aligned.
- Markdown is intentionally wrapped as plain text, so headings such as `# Add two numbers` remain literal. Use HTML/PDF for formatted statements; this is a current limitation, not a new parser failure.
- After leaving an ended contest, the picker shows only live contests. Existing in-contest custom practice works, but the picker does not provide an archive for re-entry.
- The earlier fork/OOM reporting error did not recur in the full suite; it remains unresolved because no fix was applied.
- Native upload/save file dialogs, actual export files, keyboard-only accessibility, clean Ubuntu installation, physical LAN interruption, realistic concurrent-client load, service-failure recovery and backup/restore were not certified. Server restart/session recovery was not rehearsed; the participant client restart was.
- PDF parser warnings during the initial run came from an incorrect stream-length value in the synthetic PDF fixture; the statement still rendered. The harness also initially used the wrong test-case response field and timed out around a modal `showAndWait`; these were harness limitations, not product failures.
- Unsupported features such as rejudge, float tolerance and bulk-user/password-reset UI remain listed in README. A broad functional run is not exhaustive certification of every input or feature combination.

## How to use this archive

Start with [current security status](security.md) for decisions that still apply. Use this archive when you need the original test command, observed result, or reason for a decision. Entries remain in their original order, which is not always numerical.

- [Archived test results](#verification-snapshot)
- [Implemented features](#implemented)
- [Missing features](#not-implemented)
- [Early confirmed fixes](#confirmed-fixes)
- [Repository and release notes](#release-and-repository-hygiene)

<details>
<summary>Browse the numbered audit entries</summary>

- [item 22: password storage](#audit-entry-1)
- [item 23: account password policy](#audit-entry-2)
- [item 24: brute-force protection](#audit-entry-3)
- [items 25–26: MFA deferral and password reset](#audit-entry-4)
- [item 27: email verification](#audit-entry-5)
- [item 28: user enumeration](#audit-entry-6)
- [item 29: HTTP API rate limiting](#audit-entry-7)
- [item 30: submission queue abuse attack tests](#audit-entry-8)
- [items 31–32: scheduling deferral and output bombs](#audit-entry-9)
- [item 32 deferral and item 33 fork bombs](#audit-entry-10)
- [item 34 filesystem attack tests](#audit-entry-11)
- [item 34 declined; item 35 symlink review](#audit-entry-12)
- [item 35 dedicated symlink verification](#audit-entry-13)
- [item 35 approved symlink remediation](#audit-entry-14)
- [item 36 race-condition review](#audit-entry-15)
- [item 36 authorized concurrent attack tests](#audit-entry-16)
- [item 36 approved race remediation](#audit-entry-17)
- [item 37 contest timing review](#audit-entry-18)
- [item 37 authorized timing attack tests](#audit-entry-19)
- [item 37 approved admission-time remediation](#audit-entry-20)
- [item 38 direct contest access review](#audit-entry-21)
- [item 38 authorized live route matrix](#audit-entry-22)
- [item 38 deferral and item 39 score-manipulation review](#audit-entry-23)
- [item 39 authorized score-manipulation attack](#audit-entry-24)
- [item 40 rejudge security](#audit-entry-25)
- [item 41 custom-checker isolation review](#audit-entry-26)
- [item 41 authorized malicious-checker tests](#audit-entry-27)
- [item 41 approved runtime-limit remediation](#audit-entry-28)
- [item 42 test generators](#audit-entry-29)
- [item 43 special judges](#audit-entry-30)
- [item 44 file uploads review](#audit-entry-31)
- [item 44 approved upload remediation](#audit-entry-32)
- [item 45 ZIP uploads review](#audit-entry-33)
- [item 45 approved ZIP expansion remediation](#audit-entry-34)
- [item 46 filename security review](#audit-entry-35)
- [item 46 approved filename remediation](#audit-entry-36)
- [item 47 open redirects review](#audit-entry-37)
- [item 48 CORS and WebSocket Origin review](#audit-entry-38)
- [item 48 approved CORS/Origin remediation](#audit-entry-39)
- [item 49 security headers review](#audit-entry-40)
- [item 49 approved security-header remediation](#audit-entry-41)
- [item 50 HTTPS/WSS review](#audit-entry-42)
- [item 50 approved HTTPS/WSS remediation (historical; subsequently removed)](#audit-entry-43)
- [item 50 removal requested by user](#audit-entry-44)
- [item 67 supply-chain security review](#audit-entry-45)
- [item 74 API schema validation](#audit-entry-46)
- [item 75 integer validation review and approved first fix](#audit-entry-47)
- [item 75 contest-duration review (not approved)](#audit-entry-48)
- [item 75 manual-penalty arithmetic fix](#audit-entry-49)
- [item 75 problem-ID request validation](#audit-entry-50)
- [item 76 Unicode handling fix](#audit-entry-51)
- [item 77 username allowlist review](#audit-entry-52)
- [item 78 mass-assignment review](#audit-entry-53)
- [item 79 HTTP method restrictions](#audit-entry-54)
- [item 80 request-size limits](#audit-entry-55)
- [item 81 database backup security (user-deferred)](#audit-entry-56)
- [item 82 restore testing (user-deferred)](#audit-entry-57)
- [item 83 disaster recovery documentation](#audit-entry-58)
- [item 84 operational monitoring](#audit-entry-59)
- [item 85 security alerts (user-deferred)](#audit-entry-60)
- [item 86 health checks (implemented)](#audit-entry-61)
- [item 87 reverse proxy/WAF (user-deferred)](#audit-entry-62)
- [item 88 DDoS planning (user-deferred)](#audit-entry-63)
- [item 89 leaderboard integrity (user-deferred)](#audit-entry-64)
- [item 90 plagiarism/security distinction (user-deferred)](#audit-entry-65)
- [item 91 contest confidentiality (reviewed; user kept existing exception deferred)](#audit-entry-66)
- [item 92 cache security (implemented after approval)](#audit-entry-67)
- [item 93 CDN configuration (not applicable to current supported deployment)](#audit-entry-68)
- [item 94 source maps (reviewed; no current fix indicated)](#audit-entry-69)
- [item 95 Git/environment/config exposure (reviewed; dedicated probes passed)](#audit-entry-70)
- [item 96 cloud metadata (reviewed; isolation verified)](#audit-entry-71)
- [item 97 DNS isolation (reviewed; outbound DNS unavailable)](#audit-entry-72)
- [item 98 shared-host side channels (user retained documented limitation)](#audit-entry-73)
- [item 99 multi-language escape tests (approved; representative coverage implemented)](#audit-entry-74)
- [item 100 permanent hostile regressions (approved test coverage implemented; release gate unchanged)](#audit-entry-75)
- [item 101 security-bug regression tests (reviewed; existing representative coverage verified)](#audit-entry-76)
- [item 102 production configuration (source review complete; actual deployment unidentified)](#audit-entry-77)
- [item 103 exposed ports (source/local check; wildcard MySQL restriction user-deferred)](#audit-entry-78)
- [item 104 internal network boundaries (project boundary verified; host firewall unverified)](#audit-entry-79)
- [item 105 independent penetration testing (not established; external validation pending)](#audit-entry-80)
- [item 64 dependency locking review](#audit-entry-81)
- [item 65 software bill of materials review](#audit-entry-82)
- [item 66 container image security review](#audit-entry-83)
- [item 63 dependency vulnerabilities review](#audit-entry-84)
- [item 62 debug mode review](#audit-entry-85)
- [item 61 error handling — implemented](#audit-entry-86)
- [item 60 tamper-resistant logs review](#audit-entry-87)
- [item 59 secret-safe logging](#audit-entry-88)
- [item 58 durable audit logging](#audit-entry-89)
- [item 57 admin separation review](#audit-entry-90)
- [item 56 judge worker credentials](#audit-entry-91)
- [item 55 service separation review](#audit-entry-92)
- [item 54 database privileges review](#audit-entry-93)
- [item 53 secret rotation review](#audit-entry-94)
- [item 52 secrets review](#audit-entry-95)
- [item 51 TLS configuration](#audit-entry-96)

</details>

---

## Archived implementation status

**Last audited:** 2026-09-27
**Version:** `0.1.0-SNAPSHOT`
**Readiness:** feature-rich beta; suitable for controlled demonstrations and supervised lab trials, not yet production-ready

This file describes the source as it exists now. `WORKFLOW_PLAN.md` contains the current roadmap and the historical sprint mapping; `rules.md` remains the authority for team ownership and Git workflow.

**Current security handoff:** [OPEN_SECURITY_ITEMS.md](security.md) consolidates unresolved findings, skipped checks and final user decisions. For this audit, the user's explicit instruction overrides named part-ownership restrictions. Detailed follow-up sections below retain dated history: pre-approval proposals, old “next item” statements and earlier green runs are not current approval/status. The checklist reached item 105, but deferred risks and skipped items 68–73 remain.

## Verification snapshot

Latest full Maven attempt after the item-100 attack-test addition (2026-09-26):

| Result | Count |
|---|---:|
| Tests discovered | 332 |
| Passed | 331 |
| Failed | 0 |
| Errors | 1 |
| Skipped | 0 |

`mvn -B test -Djavafx.platform=linux` failed on 2026-09-26 at 18:04:20 +06:00: 332 server tests ran, 331 passed, zero assertion failures/skips, one error in the pre-existing `forkBombIsContained`; the client module did not run because the reactor stopped. All nine new item-100 cases passed within this run, including the final child timing bounds. An isolated fork-test rerun reproduced the error, and scoped Docker events confirmed a started container killed by OOM (exit 137), which the judge misclassified as a startup failure when timing metrics were missing. This is a reproduced reporting/reliability bug, not demonstrated escape; the user explicitly declined/deferred production remediation. See item 100 below. Do not claim a fully green current reactor run.

Most recent fully green reactor snapshot is historical item 99: 323 server + 1 client = 324, zero failures/errors/skips, at 15:38:28 +06 on 2026-09-26. The final item-99 test-only synthetic-secret permission/cleanup adjustment was separately verified with all nine affected cases at 15:38:33 +06.

The test profile uses only the isolated `arbitrator_test` schema; no application launch against the development schema was needed. These checks are not visual UI, multi-machine LAN, shared-cache replay, exhaustive escape or CVE exploit-reproduction claims.

Historical item-92 and item-86 full runs each passed 309 server + 1 client tests at 11:29:48 and 00:39:42 +06:00 respectively.

The previous fully green item-22 Maven run used local MySQL and Docker: all sandbox, worker, checker, STOMP, contest-access, IDOR, role-escalation, material-deletion, registration-policy, duplicate-policy, ZIP-import, HTML-sanitization, and admin-rendering tests passed without skips.

One additional client source-policy test passed (150 total across modules). The role-escalation suite covers registration-field injection, unsigned role modification, sid-less legacy tokens, weak/public signing secrets, and immediate revocation after database demotion.

This run does not replace a real LAN rehearsal.

The JavaFX client has no automated UI suite. The React experiment was not built during this audit because its `node_modules` directory was absent; it is outside the root Maven build.

Historical item-24 full verification: 176 server tests plus 1 client source-policy test passed (177 total), with zero failures/errors/skips. This includes all account-policy and login-throttle tests and the previously suite-dependent contest WebSocket assertion. Earlier item-23 failures are historical, not a current green-release claim for those runs.

## Implemented

### Platform, data, and security

- Spring Boot 3.2.5, Java 17, MySQL 8, Spring Data JPA, Flyway, Spring Security, and JJWT
- Student registration with a server-enforced 1–64 character student-ID policy (`A-Z`, `a-z`, `0-9`, `.`, `_`, `-`) plus seeded student/instructor accounts for development
- BCrypt cost 12 and 12-hour HS256 JWTs with an automatically generated private signing key (optional configured override) and live database-role authorization
- In-memory single-active-session registry with confirm-to-replace login behavior
- Explicit logout plus immediate REST-token and live-WebSocket invalidation
- Optional client MAC reporting and instructor notifications when a student's reported MAC changes
- Loopback filter for `/admin/**` and `/api/admin/**`, with `ADMIN` authorization on admin APIs
- ADMIN + loopback, process-local operations snapshot for host/JVM/disk/queue/DB-pool health and recent failure counters
- Loopback-only liveness and component readiness checks on the existing port, independent of admin login
- Flyway schema through V83, including contests, users, problems, tests, submissions, results, announcements, clarifications, PDF statements, MAC data, materials, persistent contest-access grants, and security audit events
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
- Dashboard operations card with read-only resource, queue-wait and recent-failure counters
- Announcements, materials, clarification answering/approval, notification feed, test visibility, clarification privacy, and server-address display

## Not implemented

- Float-tolerance judging (FR-13)
- Rejudge pipeline and instructor rejudge control (UC-14)
- Supported cross-platform installer or complete zero-download distribution
- Automated database-plus-material backup and restore workflow
- HTTPS/WSS (item 50 removed at user request to preserve existing build/run workflow)
- Bengali localization or a runtime locale selector; only the English resource scaffold exists
- TestFX client suite, JMeter/load suite, JaCoCo target, dependency-check automation, and a full dress rehearsal
- A production React web application; `arbitrator-web` is only a toolchain smoke test

## Confirmed fixes

### Contest-password authorization boundary — fixed 2026-09-15

`POST /api/contests/{id}/join` now verifies the password and records an opaque `(contest_id, user_id)` grant. All student contest-state, problem/PDF, custom-run/submission, announcement, material, clarification, leaderboard/attempt, and contest-topic STOMP paths enforce that grant; admins bypass the student gate. Problem detail/PDF and leaderboard problem codes remain hidden in `LOBBY` even after admission. V80 backfills grants from existing submissions and deletes grants automatically with their contest or user. The JavaFX client no longer stores the contest password or sends it in a query parameter.

The live replay used disposable contest 45 with problem 272 and material 17. The old `?password=` URL and direct contest, problem-list, problem-detail, and material-download requests all returned 403 before joining; a wrong join also returned 403.

A correct join returned 200, later refresh succeeded without a password, the lobby returned an empty problem list and no leaderboard problem codes, and direct problem detail remained 403 until the contest started. Active problem/material access returned 200 and submission returned 202.

Contest deletion returned 204 and left no fixture contest row. `ContestAccessIntegrationTest` adds four MySQL-backed HTTP/STOMP regression tests.

### Contest deletion with materials — fixed 2026-09-15

The live reproduction cloned Lab Contest #1 into contest 42, copied three text materials, ran a C++ submission (AC, 5/5), and ended the contest. Deletion initially returned HTTP 500 naming `fk_materials_contest`; the database transaction rolled back and all files remained.

`AdminContestController` now calls `MaterialService.deleteForContest()` inside its transaction. Material rows are deleted immediately before the contest's JDBC deletion. File cleanup runs only after commit, preserving downloads if later SQL fails and rolls back. Missing files are tolerated; cleanup failures are logged with the contest and path and do not prevent cleanup of other files. Filesystem cleanup is best-effort: an I/O error or process exit after commit can still leave orphan files requiring manual cleanup.

After the fix, deleting the same contest returned HTTP 204. Its problems, submission, material rows, and three files were removed; material downloads returned 404. All original contest summaries, material records, and remaining file checksums were unchanged. `ContestMaterialDeletionTest` verifies the transaction behavior against the separate `arbitrator_test` MySQL schema and temporary files, including a real FK failure after deletion to prove rollback safety.

### Instructor-console stored XSS — fixed 2026-09-15

Generated admin rows no longer interpolate usernames, display names, contest titles, or problem codes into inline JavaScript handlers. They carry numeric IDs/indices in `data-*` attributes and a delegated listener resolves the corresponding object from the last fetched model.

Participant and notification names are inserted with `textContent`, so markup-like display names remain literal text. Existing static handlers with fixed source literals remain, but no template expression is placed inside an inline `onclick`, `onchange`, or `onkeydown` attribute.

Registration trims the student ID before both uniqueness checking and storage, then requires `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`. Display names remain Unicode-friendly but are limited to 128 code points and reject control characters. The live browser replay registered `<img src=x onerror=alert(1)>` as a display name, triggered a MAC-change notification, and showed the payload literally with zero inserted `img` elements, zero payload-bearing handler attributes, no dialog, and a working delegated notification-history control.

A `<script>` student ID returned HTTP 400. The disposable notification and account were removed afterward.

### Forced-login WebSocket invalidation — fixed 2026-09-15

`JwtHandshakeInterceptor` now requires the JWT's `sid` to match `ActiveSessionRegistry` and stores both username and session ID in the WebSocket attributes. `AuthenticatedWebSocketSessions` tracks established transports by `sid`; replacement login and logout invalidation events immediately close every corresponding socket with policy-violation status. Registration performs a second active-session check after tracking the transport, closing the race between HTTP handshake validation and WebSocket establishment.

The MySQL-backed integration replay registered a disposable account, opened its first STOMP session, force-logged in again, and verified that the old socket received a transport close within five seconds. The old token then returned HTTP 401 and could not establish another WebSocket, while the replacement token connected successfully. The disposable account was deleted by test cleanup. Two focused registry tests also verify exact old-`sid` notification on replacement/logout and that transport-cleanup failure cannot undo authentication state.

### Duplicate-submission semantics — fixed 2026-09-15

Duplicate detection now compares the submitted source with prior active submissions for the same user and problem using exact Java `String` equality. Only an exact repeat is rejected; whitespace is no longer removed, so Python indentation, spaces inside strings, token boundaries, line endings, and trailing whitespace retain their program meaning. Reusing identical source for a different problem remains allowed.

Seven focused tests cover an exact repeat, five representative whitespace changes, and problem scoping. They remain included in the latest full build, which passes without skips.

### ZIP size and path enforcement — fixed 2026-09-15

The importer no longer trusts `ZipEntry.getSize()`, which is commonly unknown for streamed entries. It reads through the 32 MiB per-file cap and probes one additional byte, rejecting the whole package instead of accepting a truncated prefix. Existing 5,000-entry and 256 MiB aggregate-uncompressed limits remain enforced.

Entry names are canonicalized before validation and storage: backslashes become forward slashes, redundant separators and `.` segments are removed, and absolute paths, `..` traversal, NUL characters, empty paths, and duplicate canonical names are rejected.

This prevents later entries from replacing an earlier configuration, statement, or test through a path alias. Three tests cover the exact boundary, a streamed unknown-size 32 MiB + 1 byte entry, and a slash/backslash collision. The latest full Maven build includes these and the sandbox, network-isolation, compiler-hardening, command-execution-policy, IDOR, role-escalation, and resource-exhaustion regression suites, without skips.

### Role escalation — fixed 2026-09-16

The committed JWT signing key fallback was removed. Initially startup required `ARBITRATOR_JWT_SECRET`; that setup requirement was subsequently removed at the user's request (see the key-setup revision below). Explicitly configured short/placeholder keys are still rejected, and token creation requires an active session ID.

HTTP authentication no longer trusts the JWT's `role` claim: after signature and active-session validation, it loads the account's current role from MySQL. A demoted administrator therefore loses admin access on the next request rather than up to twelve hours later, and previously accepted sid-less tokens are rejected.

Seven focused tests cover public/weak signing secrets, missing session IDs, registration role-field injection, payload modification without resigning, sid-less signed tokens, and live admin demotion. The complete 118-test suite passed with MySQL and Docker available and no skips.

### Rich-HTML stored XSS — fixed 2026-09-16

Statements and announcements now use a shared jsoup allowlist on persistence and response generation, protecting both new content and existing database rows. Problem imports, clones, edits, detail responses, and announcement publication/history all apply the same policy. Basic formatting, tables, and math superscripts/subscripts survive; active content, event handlers, styles, links, images, forms, and embedded/remote content are removed. Content that sanitizes to blank is rejected on upload/publication. PDF raster rendering is unchanged.

The instructor announcement draft preview displays literal HTML source, the statement iframe has an empty sandbox, and all three JavaFX HTML viewers disable JavaScript before loading. Eight additional server checks cover sanitizer behavior, imported-content persistence/rejection, announcement publication/legacy reads, and safe admin rendering.

One client source-policy check verifies disabled JavaScript in the three display paths. The full Maven build passed with 126 server tests plus that client check and no failures/errors/skips. This change was verified by automated tests and compilation, not a new live JavaFX/browser replay.

### Reflected XSS — attack-tested 2026-09-16

Checklist item 13: no reflected-XSS vulnerability found in the reviewed paths or dedicated HTTP attack matrix. `ReflectedXssAttackIntegrationTest` starts the real server on a random loopback port against `arbitrator_test`. Three tests send 40 hostile requests: four script/image/SVG/script-breakout payloads across `search`, `error`, `message`, and `redirect` on the static admin page; rejected registration and missing routes with both JSON and HTML Accept headers; and authenticated admin numeric-parameter binding errors that genuinely echo the attack marker.

A separate baseline fetch confirms query payloads do not change the admin HTML. Tests validate JSON parsing/content types, `nosniff`, and absence of active elements/event-handler attributes in HTML error responses.

The focused run `mvn test -pl arbitrator-server -Dtest=ReflectedXssAttackIntegrationTest` passed all three tests with zero failures/errors/skips. An initial test assertion incorrectly expected the exact original payload in numeric conversion errors; Spring strips whitespace there, so the assertion now requires the attack marker while still checking response safety.

No production changes were needed. The temporary admin account and session are removed after the test. This is HTTP-response verification, not browser execution or proof against every possible payload. These three tests are now included in the latest full-suite snapshot above.

The subsequent separate DOM-XSS audit is recorded below.

### DOM XSS — browser attack-tested 2026-09-16

Checklist item 14: no exploitable DOM-XSS path found in the reviewed code or the dedicated Chromium rendering matrix. `scripts/security/dom-xss-attack.cjs` loads the actual instructor HTML/JavaScript with intercepted fixture responses, without a live server, real accounts, or database mutations.

Four image-error, SVG-load, script-breakout, and attribute-breakout payloads are exercised across nine paths: live notifications, notification list/history, clarifications, submission test output, announcement drafts, sanitized published announcements, sandboxed statements, and standalone exported HTML reports.

Published fixtures use the actual Java `RichTextSanitizer`; a raw statement payload separately challenges the iframe sandbox. Server-controlled enum/numeric DTO fields remain valid, rather than inventing attacker access to them.

Chromium 151.0.7922.34 passed the matrix with zero executions, dialogs, or page errors; checked parent DOMs and reports had zero active payload elements or injected handlers. Text remained literal and safe superscripts survived. A positive control successfully executed both a script and an image-error handler before the negative tests.

Playwright was already bundled but its browser executable was absent, so Chromium was downloaded into `/tmp/arbitrator-dom-xss-browser.zHnp0G`; no production dependency/configuration was changed. The reusable harness, Java fixture helper, prerequisites, rerun instructions, and limitations are documented in `scripts/security/README.md`.

No production fix was indicated. This is controlled browser rendering coverage, not every possible payload, JavaFX execution, API authorization, or an end-to-end publication workflow. Checklist item 15 (Markdown security) is next.

### Session fixation and privilege-change renewal — fixed 2026-09-16

Checklist item 19: login/registration already generated fresh random server-side session IDs, but a manually promoted database account could previously use its existing student session with admin permissions. The approved fix records the login-time role alongside the `sid` in `ActiveSessionRegistry`.

HTTP authentication, WebSocket handshakes, and incoming STOMP commands compare that server-held role with the current database role. Promotion, demotion, or account deletion revokes the matching session; HTTP returns 401 and requires fresh login.

Registry invalidation notifies existing WebSocket transports to close. Credential-verified login also retires an old-role session before creating its replacement, without requiring force-login. Authorization still uses the current database role, not the JWT role claim.

Role validation/removal is atomic against registry replacement: an old request cannot revoke a newer session with a different ID. Detection occurs on the next authenticated HTTP request, WebSocket handshake/incoming command, or credential-verified login—not at the instant of an out-of-band SQL change. An idle socket may remain connected until such detection; there is no database role-change event or polling mechanism. There is still no role-management API. Any future API should explicitly revoke sessions when changing roles.

The promotion/demotion HTTP integration tests confirm rejection of old tokens, successful re-login at the correct new privilege, distinct replacement IDs, and continued rejection of prior IDs. Registry tests cover role revocation, account deletion, exact transport notification, and protection of replacement sessions against stale requests.

The complete Maven run passed 131 server tests and 1 client policy test with zero failures/errors/skips and local MySQL/Docker available. No new live JavaFX replay was performed. Checklist item 20 (logout) is next.

### Recent audit choices and deferred coverage

- Item 15 (Markdown): reviewed as safe because `.md` is escaped literal text in a `pre` block, not parsed Markdown, then sanitized. User moved on without adding the proposed importer attack test.
- Item 16 (CSP): absence was initially deferred, then superseded by the approved enforcing CSP in item 49. Compatible script/style policy still permits `'unsafe-inline'`; admin inline scripts/handlers need refactoring before strict script CSP. Strict-policy hardening remains open, not absence of CSP.
- Item 17 (CSRF): code review found explicit bearer-header authentication, no authentication cookies, and no state-changing GET endpoints. No conventional CSRF path indicated; user moved on without the proposed dedicated cross-site tests.
- Item 18 (cookies): no application authentication-cookie creation found; admin token is memory-only, with theme preference alone in local storage. Cookie flags are not applicable to this design. No dedicated live cookie-header test was performed.

### Logout — fixed and attack-tested 2026-09-16

Checklist item 20: authenticated logout now revokes the exact verified request `sid`, not whichever session happens to belong to the username when logout executes. `JwtAuthFilter` supplies a server-side verified-session request attribute; `AuthController` requires it, and `ActiveSessionRegistry.clear(username, sid)` atomically removes only a matching session.

A superseded in-flight logout cannot revoke a replacement login or notify its transports. The unconditional registry cleanup overload remains for administrative/test cleanup only. Successful revocation closes sockets tracked under that ID and updates presence when there is no replacement session.

The admin console now has a listener-bound Sign out button that posts its bearer token to the logout endpoint. It drops local credentials and reloads the page to discard timers, pending polling state, and sensitive DOM content. HTTP 401 is treated as an already-invalid token; server/network errors display an explicit local-only logout warning.

JavaFX similarly warns after local sign-out when server logout is unconfirmed, rather than silently implying server revocation. Offline logout cannot guarantee remote invalidation: a copied token may remain valid until expiry or replacement login.

The new live HTTP/WebSocket integration replay registers a disposable account, logs out its active socket, confirms transport closure, rejects copied-token REST access and WebSocket reconnection, and successfully logs in without force under a fresh token.

A deterministic registry interleaving test confirms old logout cannot clear a replacement session and exact IDs are notified. `scripts/security/logout-browser.cjs` passed actual-admin-page Chromium checks for success, already-invalid token, server failure, and network failure with controlled responses and no real accounts.

The full Maven build passed 133 server tests plus 1 client policy test with no failures/errors/skips. JavaFX compiled but its warning was not exercised in a live GUI. Initial test compilation errors (missing assertion import and misnamed reused helper) were corrected before the passing full run.

Current next checklist item: 21 (session expiration).

### Signing-key setup requirement — removed at user request 2026-09-16

Admin sign-out presentation follow-up: the button uses the existing red danger treatment, spans the sidebar footer width, and has 16 px top separation plus increased internal padding. All four existing Chromium logout scenarios passed again after this visual change.

After local startup failed because `ARBITRATOR_JWT_SECRET` was unset, the user explicitly requested removal of mandatory signing-key setup. `application.yml` now defaults the optional setting to blank; `JwtService` generates a cryptographically random private HS256 key in memory when the setting is absent, null, or blank.

No setup script, persisted key file, public shared fallback, dependency, or manual environment step is added. An optional explicit key remains supported and validated for strength/placeholders. A fresh process cannot validate another process's default-key tokens.

Users must re-login after restart; the in-memory active-session registry already requires this even with an explicit key.

Setup examples, README, and AI handoff notes were updated. The focused signing-key and role-escalation suite passed nine tests with zero failures/errors/skips; the new test confirms unconfigured/blank startup behavior, same-instance token validation, and rejection by independent service instances. At that stage, the full-suite snapshot was the prior 133-server-test run. The later item-21 full run also verifies this revision. The security audit remains paused before checklist item 21 (session expiration).

### Session expiration — implemented 2026-09-16

Checklist item 21: approved absolute-expiry cleanup and admin idle timeout are implemented. The registry now stores fixed `expiresAt` and `lastActivity` timestamps for each role-bound session. Absolute lifetime comes from `arbitrator.jwt.expiry-hours` (12 hours by default) and is never extended by activity.

ID/role checks also enforce registry expiration, so existing WebSocket commands cannot bypass the limit. A scheduled sweep nominally every 15 seconds removes expired entries and notifies the socket tracker to close their connections even without incoming traffic.

Request checks expire entries immediately; idle socket closure can wait for the sweep and scheduler availability. Normal re-login replaces expired entries without requiring force-login.

Admin idle timeout defaults to 30 minutes. Authenticated mutations and explicit `/api/auth/activity` POSTs refresh idle time; read-only GET/HEAD/OPTIONS requests and WebSocket validation/heartbeats do not. The admin page sends activity on pointer/keyboard events, throttled to once per 30 seconds.

Common API 401 responses clear its token, explain the ended session, and reload the login gate. Activity requests require verified bearer authentication and cannot revive an expired session. Student idle expiration defaults to disabled to avoid interrupting long coding sessions; deployments may configure `arbitrator.session.student-idle-minutes`, with meaningful HTTP mutations/activity then required to keep that optional idle policy alive.

Student absolute expiry remains enabled.

`SessionExpirationTest` uses an injected clock to test exact idle/absolute boundaries, activity without absolute renewal, rejection of attempts to revive expired sessions, participant idle configuration, cleanup/re-login, and real socket-tracker notification with a proxy test socket. This is not a live timed WebSocket replay. Additional tests reject expired signed JWTs and require authentication for the activity endpoint. `scripts/security/session-activity-browser.cjs` passed actual-admin-page Chromium checks for polling/input throttling and expired-session UI cleanup. Existing DOM-XSS and logout Chromium matrices also passed after these changes. Final full-suite verification is recorded below. The security audit's next item is 22 (password storage).

Final item-21 verification: `mvn test` passed 140 server tests plus 1 client policy test with zero failures/errors/skips and local MySQL/Docker available. This full run also includes the earlier optional-signing-key revision. One intermediate run hit `HttpURLConnection`'s streaming-POST handling of the expected anonymous 401; the authentication assertion now uses the JDK HTTP client and passed in the final full run.

The expiry-triggered socket test is clock-driven with a proxy transport, complemented by existing live logout/replacement WebSocket integration tests. No live JavaFX expiration replay was performed. Current next concern: 22 (password storage).

<a id="audit-entry-1"></a>

## Security audit follow-up — item 22: password storage

Approved and implemented the bcrypt boundary fix. Production bcrypt remains cost 12 with random per-password salts. `PasswordLengthPolicy` rejects inputs above 72 UTF-8 bytes with a clear HTTP 400 before account registration/login and contest creation/password verification reach bcrypt.

Unicode is measured in bytes, not Java character count. No trimming, truncation, database migration, hash rewrite, or Argon2 migration was introduced. Null/blank contest creation remains optional within the limit; overlong supplied passwords are rejected even for an open contest.

Null account login returns generic invalid credentials.

Compatibility: existing hashes and ordinary passwords remain valid. A previously stored overlong password cannot be reconstructed from its hash; full overlong login input now fails validation. Operators must arrange a compliant replacement using their existing recovery/database administration process. This change does not introduce a password-reset endpoint, and cannot retrospectively recover or distinguish suffixes bcrypt already discarded.

Dedicated service-level attack tests cover exact 72-byte ASCII, two-byte Unicode, and four-byte emoji passwords, rejecting suffix-extended variants during creation and verification before persistence. A raw-bcrypt positive control reproduces the suffix collision and verifies randomized salts; service tests then demonstrate rejection of those attacks.

These are real bcrypt service tests, not live HTTP/browser password replays. Full `mvn test` verification passed 149 server tests plus 1 client source-policy test (150 total), with zero failures, errors, or skips and local MySQL/Docker available.

Nine new password-boundary tests passed. Next concern: 23 (password policy), requiring its own verdict and approval.

<a id="audit-entry-2"></a>

## Security audit follow-up — item 23: account password policy

User declined a 15-character minimum and approved rejecting spaces/common choices while retaining the existing 8-character minimum. New account registration shares `AccountPasswordPolicy` between server and JavaFX client: reject Unicode whitespace/space characters, control characters, a small case-insensitive offline common-password list, and single-code-point repetition.

Exact input is never trimmed or normalized; ordinary punctuation and Unicode remain allowed. The server's 72-byte UTF-8 bcrypt cap remains. Registration guidance was updated. The list is limited, not comprehensive breached-password screening or full modern password-policy compliance.

Existing logins are not subjected to these new registration rules: legacy weak/spaced passwords and hashes continue to work within the byte cap. Contest-password rules and demo credentials are unchanged; demo accounts remain unsuitable for production. No 15-character rule, mandatory character categories, reset endpoint, forced migration, or external password service was introduced.

Dedicated service tests cover leading/trailing/interior spaces, tabs, newlines, Unicode spaces, controls, mixed-case common choices, sequences/repetition, acceptance of an eight-character non-common password, and unchanged legacy authentication.

Byte-boundary tests now use non-repetitive ASCII/Unicode/emoji input. HTTP/WS integration fixtures use compliant registration passwords without weakening authorization assertions. No new live JavaFX UI replay is claimed. Both full runs executed 167 server tests: 166 passed and one existing contest WebSocket connected-state assertion failed, with zero errors or skips; the client module was skipped by reactor failure.

That four-test contest suite passed independently. The previous fully green snapshot remains 149 server plus 1 client test. Dedicated password/client verification is recorded below. Next concern: 24 (brute-force protection), requiring its verdict/proposal and approval.

Final item-23 targeted verification: the root build compiled all modules and passed 31 UserService tests, 5 contest password-boundary tests, and 1 client source-policy test, with zero failures/errors/skips. Eighteen new account-policy cases were added. The standalone four-test contest-access suite also passed. The two full-suite connected-state failures remain an unresolved suite-dependent verification caveat, not a claimed password-policy failure or a fully green release result.

<a id="audit-entry-3"></a>

## Security audit follow-up — item 24: brute-force protection

Approved and implemented `LoginThrottle` at the public login endpoint. Admission atomically reserves account and socket-peer IP budgets before bcrypt. Defaults: 20 attempts/account and 120 attempts/IP per 60-second fixed window, at most 2 simultaneous attempts/account and 16/IP.

After 5 account failures or 20 IP failures, apply a 5-second cooldown doubling with subsequent admitted failures, capped at 300 seconds. Denied retries do not extend penalties. All admitted attempts count toward fixed-window limits; successful credential verification (including the existing-session 409 flow) clears the account failure streak, not its attempt budget or shared IP history.

Server failures release reservations without adding credential failures. Existing signed-in sessions are not revoked by attacks.

Submitted account keys are trimmed/case-folded and SHA-256 hashed for bounded-size storage. After lookup, admission is bound to the DB's stored account identity before hashing, preventing MySQL collation-equivalent Unicode aliases from bypassing per-account limits.

Unknown usernames consume IP and submitted-account budgets. `force=true` does not bypass admission. HTTP 429 includes Retry-After and a user-facing retry interval; existing admin/client error rendering displays the message. Cooldowns use timestamps, not sleeping request threads.

Warn logs record cooldown scope/duration without submitted usernames, IP headers, passwords, or JWTs.

Configuration is under `arbitrator.auth.login` in application.yml, with positive-value validation. State is bounded to 10,000 keys; stale idle keys are cleaned periodically on requests without evicting active penalties/in-flight reservations.

Capacity exhaustion temporarily rejects new keys (availability tradeoff). State is process-local and resets on restart; replicas need a shared limiter. `server.forward-headers-strategy: none` deliberately trusts socket peers only. A reverse proxy would appear as one shared IP; proxy trust/network isolation and limits must be designed explicitly before deploying behind one.

Shared lab networks may need IP threshold tuning. Temporary per-account cooldowns can still briefly affect legitimate users under targeted attack; no permanent lockout is introduced. This scope protects login, not registration floods or repeated contest-password guessing.

Clock-driven tests cover exact recovery, escalating/capped delays, account/IP rotation, success reset rules, fixed-window budgets, atomic concurrent admission, IP concurrency, bounded-state cleanup, and stored-identity alias binding. Live HTTP attacks with real password verification test account/IP failure bursts, force-login, case/whitespace and accented aliases, spoofed Forwarded/X-Forwarded-For headers, Retry-After/message, and continued use of a victim's existing bearer session.

No live cooldown sleep or JavaFX GUI replay is claimed. Final full `mvn test` passed 176 server tests plus 1 client policy test (177 total), zero failures/errors/skips, with MySQL and Docker available. All nine new throttle tests passed, including the live HTTP matrix.

The earlier item-23 WebSocket assertion passed in both item-24 full runs; no separate WebSocket fix was made, so its prior suite-dependent failures remain historical test-stability observations. Next concern: 25 (MFA), requiring its own verdict/proposal and approval.

<a id="audit-entry-4"></a>

## Security audit follow-up — items 25–26: MFA deferral and password reset

Item 25 (MFA) was explicitly skipped by the user. No MFA implementation was added; ADMIN authentication remains password-only with the existing login throttle and admin-route restrictions. This is an accepted deferral, not a security pass.

Item 26 (password reset) source review found no forgot-password/change-password endpoint, client recovery UI, reset-token storage, or application recovery workflow. Account hash writes are limited to registration and demo seeding; contest hash creation is separate.

Reset-token randomness, single-use, expiration, enumeration, and session-revocation requirements are therefore not applicable to an implemented reset flow. No dedicated reset attack test or feature implementation is claimed. Missing recovery is an operational limitation, not evidence of an insecure reset endpoint.

Manual DB hash replacement does not itself revoke process-local sessions; operators must explicitly invalidate sessions or restart the server when replacing a compromised password. Do not introduce a recovery feature without separate approval and an identity-verification design.

Next checklist concern: 27 (email verification).

<a id="audit-entry-5"></a>

## Security audit follow-up — item 27: email verification

Source review found no email field in the user entity or registration/login DTO, no email identity/recovery flow, and no mail delivery or email-verification implementation in server/common/JavaFX production code. Authentication uses student ID/username rather than email.

Email ownership verification is therefore not applicable to the current system; no feature or dedicated attack test was added. This does not establish ownership of self-registered student IDs, which is a separate identity-integrity question.

Next concern: 28 (user enumeration).

<a id="audit-entry-6"></a>

## Security audit follow-up — item 28: user enumeration

Approved scoped login fix: UserService generates one dummy hash at construction using the injected PasswordEncoder (production bcrypt cost 12). For a non-null, byte-compliant password, both unknown-user and wrong-password paths execute one password match before returning the same 401 Invalid credentials.

Unknown users also pass through the pre-verification admission callback; endpoint IP/account throttling remains in front of bcrypt. A dummy match cannot authenticate a nonexistent account. No per-request dummy hash generation, sleep, fake session, or database hash migration was added.

Null/overlong password validation remains independent of account existence.

Dedicated service tests instrument real bcrypt to assert exactly one check at configured cost on both failure paths, dummy-hash reuse, rejection even when supplied input matches the dummy hash (force=true), and rejection by admission before password verification.

A live HTTP test at production cost alternates existing/unknown usernames, checks identical status/error/message/path fields (timestamps naturally differ), discards warm-up pairs, and compares six samples per path with a broad median-ratio bound to catch the old no-bcrypt fast path.

Test-only throttle thresholds allow measurements without hitting cooldown; the separate brute-force attack suite still tests limits. This reduces the obvious hash-work timing discrepancy, not a claim of constant-time DB/network responses or elimination of every account-existence signal.

Registration intentionally retains 409 Username already taken and success-session issuance as the approved usability tradeoff, so account existence is still discoverable there. Session-conflict 409 remains gated behind correct credentials. DB collation/cooldown history can still affect responses; no full anti-enumeration redesign was approved.

Final full `mvn test` passed 180 server plus 1 client policy test (181 total), zero failures/errors/skips, with MySQL and Docker available. The four new enumeration tests passed. Live measured medians were 241.4 ms for an existing account with a wrong password and 231.3 ms for an unknown account (ratio 0.96).

These local samples are a regression check, not a universal timing guarantee. Next concern: 29 (rate limiting), requiring its verdict/proposal and approval.

<a id="audit-entry-7"></a>

## Security audit follow-up — item 29: HTTP API rate limiting

Approved and implemented bounded, process-local `ApiRateLimiter` with configurable fixed windows under `arbitrator.api-rate-limit`. Socket-peer IP admission runs after the existing loopback restriction but before JSON body buffering/authentication/controller work.

Canonical authenticated-account admission runs after authorization. Limits aggregate across IDs, query strings, token replacements, and accounts sharing an IP; separate categories prevent run/join/clarification floods from consuming the user's read category directly.

Admission and total/category counters are atomic, including parallel calls. Rejected requests return JSON HTTP 429, Retry-After, and no-store; existing UI error handling displays the retry message. Login's specialized failure/concurrency throttle and all judge queue/container/memory/custom-run admission controls remain in place.

Default 60-second budgets: per-account total 1200, reads 600, generic writes 120, joins 10, custom runs 12, submissions 6, clarifications 5. Per-IP total/reads 12000, generic writes 2400, registration 30, login 600, joins/runs/submissions 240 each, clarifications 120.

IP thresholds provide shared-network headroom but must be tuned for actual lab size. Unknown write categories use the generic write budget. State is capped at 20,000 total/category keys; expired windows are cleaned on requests, and exhaustion rejects new keys rather than evicting live budgets.

Budgets count admitted attempts even if later validation fails. Fixed windows permit boundary bursts; limits are not a throughput guarantee or protection against all distributed/volumetric DoS. State resets on restart and replicas need shared counters.

Forwarded headers are not trusted; reverse-proxy topology needs explicit design before deployment. Static files and WebSocket/STOMP traffic are outside this HTTP API limiter. Reset/email/search-specific features do not currently exist; any future API endpoints still receive generic protection.

SubmissionService now uses 64 fixed lock stripes around each user's cooldown/backlog/duplicate/persistence/queue admission, so parallel calls cannot both pass the accepted-submission cooldown. Stripe collisions can briefly serialize different users but lock memory is bounded.

Failed submissions do not advance the accepted-submission timestamp. The tracked local configuration now keeps the default 10-second cooldown; the separate test profile deliberately overrides it to zero for rapid existing integration fixtures.

The new endpoint submission rate limit remains enabled in tests.

Dedicated clock/concurrency/filter tests cover atomic flood budgets, exact recovery without extending blocked windows, scopes/categories/aggregate budgets, bounded-state expiration, category ID/matrix handling, IP-header spoofing, token/IP rotation, and representative 20-student polling/signup capacity.

A direct service regression widens the old cooldown race and requires exactly one persistence/enqueue and one 429. Live HTTP tests use low isolated thresholds to flood run/submission/clarification/join categories with invalid or nonexistent-resource requests, change contest IDs and bearer sessions, verify account isolation, exercise 20 normal read requests then read exhaustion, block registration despite spoofed headers, and leave static admin HTML available.

They do not mass-create accounts, clarification rows, or judge executions. One initial test compile failed because the new fixture referenced an incorrect language enum; it was corrected before verification. Final full `mvn test` passed 191 server plus 1 client source-policy test (192 total), zero failures/errors/skips, with MySQL and Docker available.

All eleven new rate-limit/submission-race/HTTP-flood tests passed; existing login throttle, enumeration, authorization, and sandbox tests also passed. Next concern: 30 (submission queue abuse), requiring its verdict/proposal and approval.

<a id="audit-entry-8"></a>

## Security audit follow-up — item 30: submission queue abuse attack tests

User approved dedicated tests only; production queue policy/implementation is unchanged for this item. `JudgeQueueLimitTest` now subjects the real JudgeQueue semaphore and executor to 10,000 admission attempts from 32 concurrent caller threads, with four controlled workers held busy and capacity 500.

Exactly 500 are admitted and 9,500 rejected; observed outstanding depth never exceeds 500 and worker concurrency never exceeds four. After release, the test verifies full capacity recovery (without extra permits) and admission of a subsequent job.

Separate tests verify complete reservation/depth recovery after controlled worker exceptions and executor shutdown/rejection.

`SubmissionQueueAbuseTest` uses in-memory repository and queue-admission doubles to exercise the real SubmissionService: block at 20 unfinished jobs, stay blocked at 19 and five, resume below five, isolate another user, admit exactly one of 16 parallel requests starting at 19, reject global saturation before persistence/enqueue, and release reserved capacity after a persistence failure without consuming the accepted-submission cooldown. Together with the real queue tests, these validate service backpressure and queue capacity, but are not a live HTTP 10,000-request replay or a 10,000-container/real-MySQL load test. The earlier item-29 live HTTP flood matrix remains complementary coverage. Targeted run passed all eight tests (seven new, one existing), without failures/errors/skips. Final full `mvn test` passed 198 server plus 1 client source-policy test (199 total), zero failures/errors/skips, with local MySQL and Docker available. All eight dedicated queue/service tests passed in the full run.

Verdict: bounded global queue/worker concurrency, per-user backlog hysteresis, and account/IP rate controls withstand the tested admission attacks. There is no dedicated per-IP backlog quota or per-contest queue; the shared global queue remains the user's chosen design. Bounded capacity prevents queue growth, not worker monopolization or guaranteed fairness. Next concern: 31 (fair scheduling), requiring its own verdict/proposal and approval.

<a id="audit-entry-9"></a>

## Security audit follow-up — items 31–32: scheduling deferral and output bombs

Item 31 fair scheduling was explicitly declined by the user. FIFO/shared global queue policy is unchanged; no per-user active-judgment quota or round-robin scheduling was introduced. Capacity/rate controls do not guarantee fairness or prevent worker monopolization.

Item 32 source review confirms 1 MiB byte capture caps on each stdout/stderr stream, container kill on overflow, truncated stored/compiler output, and an existing live Docker stdout-flood test that passed in the latest full suite. OLE is supported, though time/memory classification takes precedence if those conditions also apply.

Sandbox container creation does not specify a logging driver, so daemon logging configuration is inherited. Docker's default json-file driver lacks rotation; captured output bounds do not themselves bound daemon-side log storage. Proposed fix (pending approval): disable per-container logging for sandbox executions while retaining attached output capture, then extend dedicated floods to stderr/combined streams and verify normal output and logging configuration.

No output-bomb fix is implemented on this review turn.

<a id="audit-entry-10"></a>

## Security audit follow-up — item 32 deferral and item 33 fork bombs

The user explicitly declined the item-32 Docker logging change and moved on. No log-driver override or extended output-flood tests were implemented; the inherited daemon-log disk-risk finding remains deferred. Earlier item-32 proposal text is historical, not pending authorization.

Item 33 review confirms Docker `--pids-limit` of 64 for executions and 200 for compilation, alongside CPU/memory/container concurrency limits and timeout/container cleanup. The existing malicious `while(true) fork()` Docker fixture passed in the latest full item-30 suite, with its test case reporting 2.471 seconds and no skip.

It asserts prompt termination and timeout/nonzero exit, not a measured exact maximum PID count or a dedicated thread-creation probe. Verdict: fork-bomb containment is implemented and has live Docker attack coverage; no additional production fix is indicated by this review.

No new attack run or implementation was performed on this turn. Next concern: 34 (filesystem attacks).

<a id="audit-entry-11"></a>

## Security audit follow-up — item 34 filesystem attack tests

Only the invocation's own work directory and its resource-measurement file are host bind mounts. Runtime workspace and container root are read-only; compilation has a writable private workspace and `/tmp` is a bounded private tmpfs. Host application/database files and sibling workspaces are not deliberately mounted.

Container `/etc/passwd` and namespace-local `/proc` are not equivalent to host files. Existing tests check absence of `/base` and runtime workspace write rejection, but the sibling test does not directly probe a sibling's secret path.

Review found `/run-metrics` is a writable host-backed file accessible to the same UID as submitted code; its contents are subsequently parsed for elapsed time and peak RSS. The user approved dedicated attack tests, not a production remediation.

Three new live Docker tests in `SandboxExecutorTest` now verify: (1) readable host/sibling sentinels cannot be opened via absolute, traversal, or `/proc/1/root` paths; Docker socket access and runtime/system writes are rejected, with host sentinels unchanged; (2) writable `/tmp` scratch does not survive a second execution container; (3) submitted C++ successfully overwrites and reads back an attacker marker at `/run-metrics`.

The third is explicitly a vulnerability-characterization test and must become a rejection/protection test when remediated. GNU time can overwrite data on exit; no final measurement forgery or verdict bypass has been demonstrated.

Targeted verification: `mvn test -pl arbitrator-server -am -Dtest=SandboxExecutorTest -Dsurefire.failIfNoSpecifiedTests=false` passed all 18 sandbox tests, zero failures/errors/skips, on 2026-09-16 at 23:19 +06:00. No full-suite rerun or production change on this turn.

Proposed remediation is a measurement supervisor inaccessible to the submission UID, plus bounded/validated host-side metrics reads; merely moving the file out of `/sandbox` or chmod while retaining the same UID is insufficient. Implementation design and production changes await approval.

Item 34 remains current; item 35 symlink attacks has not yet been reviewed.

<a id="audit-entry-12"></a>

## Security audit follow-up — item 34 declined; item 35 symlink review

The user declined the item-34 measurement-isolation fix and advanced. Writable `/run-metrics` remains an accepted deferral; the preceding attack tests were implemented, but no production remediation is authorized.

Item 35 review: unique work directories and read-only execution mounts prevent ordinary runtime submissions from planting links in the host-backed workspace. Compilation nevertheless has write access to that workspace. Post-compilation `compileWorkspaceViolation` walks without following directory links but uses `Files.isRegularFile` and `Files.size` with default link-following behavior and does not reject symbolic links.

Subsequent host `Files.writeString` calls for test input and checker output/expected output also follow links. Thus a link left in the compilation workspace could redirect host-side file operations outside the sandbox under the server OS account.

No exploit from ordinary submitted source, compiler compromise, or end-to-end host overwrite has been demonstrated; this is a missing defensive trust-boundary check, not proof arbitrary source can currently execute during compilation. Cleanup's `Files.walk` does not follow directory symlinks by default.

Proposed approved scope: reject symbolic links and non-regular artifacts after successful compilation, use non-link-following semantics for host staging reads/writes, and add harmless host-sentinel tests covering file/directory links and preservation of the outside target. No implementation or new tests for item 35 on this review turn. Awaiting user approval; next unreviewed concern is item 36 race conditions.

<a id="audit-entry-13"></a>

## Security audit follow-up — item 35 dedicated symlink verification

The user requested verification before considering remediation. Added three parameterized `JudgeWorkerTest` cases with explicit compiler-stage fault injection: compile ordinary C++ normally, then execute `ln -s` under the real writable compilation container's UID/mounts/limits, targeting a harmless outside host sentinel.

This test-only injected command is NOT part of configured production language commands and NOT an exploit derived from ordinary submitted source. The real post-compilation workspace validation accepted each link. Real JudgeWorker staging overwrote the sentinel via `__input.txt`, `__actual_output_1.txt`, and `__expected_output_1.txt`; each job still returned AC.

Repository/publisher/checker doubles isolate the test from production database and messaging. These are vulnerability-characterization tests, not passing security protections; convert their expectations after remediation.

Added `SandboxExecutorTest.ordinaryRuntimeSourceCannotPlantHostWorkspaceSymlink`: ordinary compiled C++ cannot create a new link in the read-only runtime workspace, while a link in private `/tmp` is allowed and not used by host staging. This distinguishes a demonstrated unsafe host response to compiler-stage artifacts from the still-unproven ability of ordinary submitted source to produce those artifacts.

No production code was changed, and no real sensitive host file was targeted. Each outside sentinel was deleted after its test; it was generated test data only. Remediation still awaits approval.

Targeted verification: `mvn test -pl arbitrator-server -am -Dtest=SandboxExecutorTest,JudgeWorkerTest -Dsurefire.failIfNoSpecifiedTests=false` passed 25 tests (19 sandbox + 6 judge-worker), zero failures/errors/skips, on 2026-09-16 at 23:22 +06:00. All four new symlink cases ran. No full-suite rerun is claimed. Passing characterization tests mean the harmful conditional behavior was reproduced, not fixed.

<a id="audit-entry-14"></a>

## Security audit follow-up — item 35 approved symlink remediation

The user approved the defensive fix after the dedicated verification. `SandboxExecutor.compileWorkspaceViolation` now inspects attributes with NOFOLLOW_LINKS and rejects every symbolic link and non-regular/non-directory artifact after successful compilation; symlinked directories are rejected, not traversed. Regular file byte accounting no longer follows links.

New `SafeSandboxFiles` is used for host-side source/input/checker-output staging in JudgeWorker, CustomRunService and CheckerRunner, and for private checker copies. Writes reject links/non-regular artifacts, unlink an existing regular staging file instead of truncating its inode (protecting outside hard-link targets), and open new files with CREATE_NEW/NOFOLLOW_LINKS.

Copies reject non-regular sources, open source channels without following links, and exclusively create fresh targets. Immediate staging parents must be real directories. The design relies on server-controlled ancestors and stopped compilation containers/read-only runtime workspaces; it is not a promise of safety against a malicious concurrent host administrator replacing ancestor directories.

The three compiler-stage injected-link tests now require CE, no checker call, and unchanged outside sentinels. `SafeSandboxFilesTest` additionally covers file links, immediate directory links, linked copy sources, hard-link-safe replacement, normal copying, and rejecting existing copy targets.

Earlier reproduction results are historical; these protection assertions supersede the item-35 characterization expectations. Ordinary-source compiler-stage exploitability remains unproven. The deferred item-34 `/run-metrics` exposure is deliberately unchanged.

Next concern: item 36 race conditions; not yet reviewed.

Item-35 full verification: 207 server + 1 client tests (208 total), no failures/errors/skips. All three compiler-stage symlink protection cases, two SafeSandboxFiles tests, and 19 live sandbox tests passed. After final import/field-name cleanup, recompilation and a focused SafeSandboxFilesTest/JudgeWorkerTest rerun passed all eight cases at 11:07 +06:00. No production deployment or LAN rehearsal is claimed.

<a id="audit-entry-15"></a>

## Security audit follow-up — item 36 race-condition review

Reviewed registration, submission admission, joins, cloning, and admin edit/delete paths. Database unique constraints prevent duplicate usernames, contest titles, problem codes within a contest, and test indices within a problem. Contest joining is transactional and uses the composite grant primary key with ON DUPLICATE KEY UPDATE, making duplicate grants idempotent.

Submission cooldown/backlog admission has process-local striped user locks and existing concurrent boundary tests. Contest cloning and destructive admin operations have transaction boundaries; transactions alone do not guarantee operation-level serialization or prevent lost updates.

Concrete code gap: registration uses existsByUsername then save, with no duplicate-key exception mapping. Two simultaneous requests can both pass the check, although the database rejects one insert; the loser can receive an unhandled server error instead of the intended 409.

Concurrent same-title cloning similarly relies on its database uniqueness constraint without graceful conflict mapping. No global DataIntegrityViolationException handler was found. Contest/problem entities and repositories have no optimistic version or explicit pessimistic locking, so overlapping admin edits/deletion deserve targeted tests before selecting a locking design.

No actual simultaneous HTTP attack or race reproduction was run on this review turn; do not claim corruption or authentication bypass. Rejudge is not implemented and is not a current race surface.

Proposed next authorized scope: dedicated concurrent registration, repeated join, same-title clone and edit/delete tests against the dedicated test schema; distinguish constraint-protected conflicts from genuine lost updates, then propose narrow remediation based on reproduced failures. No production changes or new tests are authorized yet. Item 36 is current; next unreviewed concern is item 37 contest timing attacks.

<a id="audit-entry-16"></a>

## Security audit follow-up — item 36 authorized concurrent attack tests

Added `RaceConditionAttackIntegrationTest` using the dedicated `test` profile/schema, unique disposable fixtures, fixture-scoped cleanup, real MySQL transactions and real server routes. Five tests ran successfully on 2026-09-17 at 11:10 +06:00 via `mvn test -pl arbitrator-server -am -Dtest=RaceConditionAttackIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`: zero failures/errors/skips.

These characterization tests reproduce defects rather than claim remediation; no production changes or full-suite rerun are included. An initial assertion mistakenly expected 200 instead of the registration API's 201; corrected and rerun successfully, without production changes.

Observed HTTP results: eight simultaneous same-username registrations yielded one 201 and seven 500 responses, exactly one stored account. Six same-title clones yielded one 200 and five 500 responses, exactly one cloned contest containing the source problem.

Six concurrent student joins all returned 200 and left exactly one access grant. Tokens for join/clone tests were issued for disposable database users via the actual JWT/session components; account login itself was not under test. Registration-specific IP allowance was increased only in this test context so rate limiting would not mask the race.

Two deterministic transaction interleavings exercise the real problem controller with real DB-managed entities, not concurrent HTTP scheduling: both editors load the same original snapshot, then one changes the title and another the time limit.

Both transactions succeed, but the final row retains the title and loses the new time limit (last-writer-wins full-row update). In the second interleaving, the editor loads a snapshot before a real committed repository deletion; its controller update fails with ObjectOptimisticLockingFailureException and the row remains deleted.

No row resurrection was observed. This exception's HTTP mapping was not tested. The read barriers live solely in test code; no production timing hooks or spy repositories were added.

Proposed remediation awaiting approval: map confirmed username/title unique-key races to a safe 409 response without masking unrelated database failures, add optimistic version checking to problem edits so simultaneous stale writes cannot silently discard successful independent changes, and map stale edit conflicts cleanly. Convert the corresponding characterization expectations into protection tests. No broad contest-locking design or changes to global scheduling are authorized. Item 36 remains current.

<a id="audit-entry-17"></a>

## Security audit follow-up — item 36 approved race remediation

The user approved fixes for the reproduced races. Added `PersistenceConflictAdvice` to return 409 only for MySQL duplicate-key error 1062/SQLSTATE 23000 naming the known username/title unique constraints. The SQL error values and query details are never included in responses. Other integrity failures remain generic 500 responses and are logged server-side, rather than falsely classified as duplicates. Optimistic-lock failures return 409 with a reload/retry message and no-store responses.

`Problem` now has a JPA @Version field; additive Flyway migration V81 creates `problems.version` with default zero for existing rows. The migration was exercised in the separate test schema; normal server startup will apply it to an existing deployment. Version checks reject stale concurrent entity writes at flush/commit, including edits after deletion. This protects overlapping server transactions, not arbitrary old browser forms submitted later without a client version token. Contest entities, global queue scheduling and unrelated locking designs are unchanged.

RaceConditionAttackIntegrationTest expectations now require exactly one registration 201/seven 409 responses, one clone 200/five 409 responses, and one successful editor/one optimistic conflict with only the successful editor's change persisted.

The targeted five-case run passed without skips at 11:13 +06:00; joins remain idempotent and stale edit/delete remains non-resurrecting. Added three PersistenceConflictAdviceTest cases for named duplicate constraints, unrelated integrity errors, secret-free/no-store responses and stale-edit 409 mapping.

HTTP registration/clone mapping is live-tested; the controlled edit transactions plus advice unit test establish stale conflict behavior without claiming a deterministic HTTP edit race.

Earlier item-36 defect reproduction and awaiting-approval statements are historical; approved fixes above supersede them. Deferred metrics/logging/fairness/MFA choices remain unchanged. Next concern: item 37 contest timing attacks, not yet reviewed.

Item-36 final full verification: 215 server + 1 client tests (216 total), zero failures/errors/skips, completed 2026-09-17 at 11:14 +06:00. Registration produced one 201/seven 409; cloning one 200/five 409; six joins all 200. Controlled edit transactions asserted exactly one success and one optimistic conflict, and the committed change matched the successful writer. All three advice mapping/sanitization tests passed. No production schema migration was applied by this test run; only the separate test schema was migrated.

<a id="audit-entry-18"></a>

## Security audit follow-up — item 37 contest timing review

Scored submission admission uses server Instant.now, rejects states other than ACTIVE/FROZEN, explicitly rejects PAUSED, requires a non-null start/end, refuses future starts, and refuses now >= endTime. SubmitRequest contains no client timestamp/score field.

Start, end, pause and freeze operations use server instants; pause duration extends the computed deadline. Scoring uses server queuedAt, not judge completion or client time; freeze filtering uses server queuedAt/judgedAt/frozenAt. Custom runs intentionally have released-access checks rather than scored submission deadline checks; no change to practice availability is proposed here.

Potential boundary inconsistency: assertAcceptingSubmissions runs before duplicate lookup/queue reservation and queuedAt is captured later with a separate Instant.now. A request admitted just before the deadline may be persisted with queuedAt after it; overlapping pause/end operations may also leave the previously loaded contest snapshot stale.

No boundary attack or delay-injection test was run on this review turn, and this does not prove requests arriving after the deadline are accepted. Need to distinguish admission-time semantics from persistence-time semantics before selecting a fix.

Penalty pause accounting is a separate scoring-policy question, not a browser-clock trust flaw.

Proposed next step awaiting approval: dedicated before-start, start, end, paused/frozen tests with controlled server time and a harmless delayed submission pipeline to verify the check/timestamp boundary. No production change or test additions yet. Current item 37; item 38 direct contest access remains unreviewed as its own checklist concern.

<a id="audit-entry-19"></a>

## Security audit follow-up — item 37 authorized timing attack tests

Added ContestTimingAttackTest with the real ContestService timing guard and real SubmissionService pipeline, using repository/access/queue doubles and disposable in-memory entities. Twelve cases passed without failures/errors/skips via `mvn test -pl arbitrator-server -am -Dtest=ContestTimingAttackTest -Dsurefire.failIfNoSpecifiedTests=false` on 2026-09-17 at 13:12 +06:00.

No Docker execution, HTTP race, database persistence, exact nanosecond clock mock, or full-suite rerun is claimed. An initial test-only compilation error attempted inaccessible JudgeQueue.shutdown; removed that call because the overridden fake queue never starts threads, then recompiled and ran successfully.

Production code is unchanged.

Verified all six states: only ACTIVE/FROZEN accept scored submissions. Future/missing starts, missing ends, elapsed deadlines, and a deadline captured at current time (boundary or later by guard execution) are rejected with 403 without requiring a scheduler state change. Completed pause time extends the server deadline; PAUSED still rejects submissions. These use real wall-clock instants with safe margins, not exact deterministic before/equal/after nanosecond assertions.

The bounded delay probe sets a fixed near-future deadline at the actual guard boundary, validates with the real guard, then holds the test queue reservation until that deadline passes. Observed admission 07:12:46.458007025Z, deadline 07:12:46.607990976Z, stored queuedAt 07:12:46.609074622Z: admitted before expiry, recorded afterwards.

A fresh guard call after expiry correctly rejects. A second probe switches the fixture to PAUSED after the guard but before reservation; the already-admitted request persists while subsequent admission is denied. This establishes check/timestamp and no-recheck behavior, not an ability to submit a fresh request after closing or to control server time.

The in-memory state mutation models an interleaving, not proof of a live database pause race.

Proposed narrow remediation awaiting approval: capture a single server admission instant, validate the contest against that instant, and persist that same instant as queuedAt. Preserve the normal policy that already-admitted submissions can finish persistence/judging after expiry or pause; do not silently turn timing into a persistence-deadline requirement.

This makes penalty/freeze timestamps agree with acceptance. Changing concurrent pause/end cancellation semantics would be a separate policy decision. Convert the delayed timestamp characterization assertion into a pre-deadline protection assertion if approved.

Current item 37; next is item 38 direct access bypass.

<a id="audit-entry-20"></a>

## Security audit follow-up — item 37 approved admission-time remediation

The user approved unifying timing validation and storage. SubmissionService now captures one server Instant immediately at its contest admission guard and passes it to ContestService.assertAcceptingSubmissions(contest, admittedAt). That overload compares the same instant against start and a once-computed end; the existing single-argument guard delegates with server now for other callers.

The accepted submission stores that exact captured instant as queuedAt, regardless of later duplicate-check/queue/persistence latency. No endpoint accepts a client admission timestamp. Cooldown tracking remains actual completion/admission bookkeeping as before, and already-admitted requests can finish after expiry/pause.

No new cancellation or global contest-lock policy was introduced.

Converted the delayed timing characterization into a protection regression requiring stored queuedAt == validated admission instant and queuedAt < deadline despite actual persistence after expiry. Added an exact fixed server-instant test: start-minus-one-nanosecond rejected, start allowed, end-minus-one-nanosecond allowed, end and end-plus-one-nanosecond rejected.

This tests the explicit trusted-instant guard, not a live network request at nanosecond precision. Existing state/pause/missing-time checks remain. Updated submission test doubles to intercept the new overload rather than bypass tests accidentally.

Targeted timing/cooldown/queue-service run passed 18 cases (13 timing + 1 cooldown concurrency + 4 queue-abuse), no failures/errors/skips, 2026-09-17 13:14 +06:00. Earlier twelve-case defect reproduction is historical and superseded by the protection assertions. No client changes were required for item 37. Next concern: item 38 direct contest access bypass, not yet reviewed separately.

Database precision follow-up: baseline queued_at used whole-second TIMESTAMP. Added V82 to retain TIMESTAMP(6) microseconds and capture/truncate the server admission instant to microseconds BEFORE both guard validation and storage, avoiding fractional database rounding disagreements.

Existing rows are preserved; only the test schema was migrated by tests, and deployments receive V82 at normal startup. Added a real repository/JDBC round-trip regression in RaceConditionAttackIntegrationTest using 59.999999 seconds, requiring exact microsecond preservation before the next second.

This covers persistence separately from the service-level delay model.

First item-37 full-suite attempt ran 228 server cases with one failure in the previously observed ContestAccessIntegrationTest contestTopicSubscriptionRequiresTheSameGrant allowed-connection assertion. All new timing cases passed; no unrelated WebSocket production change was made. Targeted rerun and final full verification are recorded below; do not interpret this initial attempt as a green run.

Item-37 precision/WebSocket targeted verification: ContestTimingAttackTest (13), RaceConditionAttackIntegrationTest (6, including timestamp round-trip), and ContestAccessIntegrationTest (4) all passed: 23 tests, zero failures/errors/skips, 2026-09-17 13:16 +06:00. The known allowed-WebSocket assertion passed without production WebSocket changes. The full-suite retry result is recorded below.

Item-37 final full retry: 229 server + 1 client tests (230 total), zero failures/errors/skips, completed 2026-09-17 at 13:18 +06:00. The previously failing WebSocket case passed without unrelated production changes. The earlier failed attempt remains recorded as a test stability observation. V82 was applied only to the test schema during verification; production receives it through normal Flyway startup. No deployment or LAN rehearsal is claimed.

<a id="audit-entry-21"></a>

## Security audit follow-up — item 38 direct contest access review

Reviewed direct REST and contest-topic subscription paths independently of client UI. Student problem detail/PDF, custom runs, scored submissions, participant/problem attempts, submission source/results and problem-linked clarifications use requireReleasedAccess; a joined LOBBY does not release them.

Problem-list and student leaderboard routes require a contest grant and deliberately return empty problem/row data before release. Scored submission adds the state/start/end admission guard fixed in item 37. Submission ownership checks remain in addition to contest access.

Contest STOMP subscriptions require a current role-bound session plus a grant for the destination contest.

Existing live ContestAccessIntegrationTest already exercises ungranted direct routes, lobby joining, direct problem detail, an empty lobby problem list/leaderboard, custom run, scored submission, attempt drill-down, and grant-bound contest-topic subscription. It proves UI hiding is not the only control, but it does not currently perform a complete joined-lobby matrix across every submission read route or a direct HTTP submission precisely after ENDED/deadline state.

Policy distinction: joining during LOBBY is intentional, and granted students may read contest state, announcements, materials, and general contest clarifications there. These are holding-room communications, not problem release. After ENDED, statements, own submissions, custom-input practice and final standings remain readable by design; only new scored submissions close.

Treating “after end” as total contest revocation would be a product-policy change, not the current security model. Admin grant/release bypass is intentional and still requires ADMIN authorization/loopback restrictions.

Verdict: no direct-access bypass found in code review. Proposed test-only follow-up awaiting approval: extend the live HTTP matrix for a password-granted LOBBY and ENDED contest, covering guessed problem/submission IDs, source/results, custom runs, attempts, clarifications, problem list/leaderboard, and scored submission rejection; assert the intentionally allowed holding-room/post-contest routes explicitly so future changes cannot blur the policy.

No item-38 production change or new test run on this review turn. Next concern: item 39 score manipulation.

<a id="audit-entry-22"></a>

## Security audit follow-up — item 38 authorized live route matrix

Extended ContestAccessIntegrationTest with two live HTTP matrices using the real Spring security chain, JWT/session registry, MySQL test schema and Docker custom-run sandbox. Targeted run passed all six class tests (four existing plus two new), zero failures/errors/skips, 2026-09-22 23:52 +06:00. No production code changed and no full-suite rerun is claimed. The logged STOMP send error belongs to the existing intentional denied-subscription attack; its assertions passed.

Joined-LOBBY matrix: after a valid password grant, direct problem detail/PDF, custom run, scored submission, participant/problem attempts, own submission source and test-result routes all returned 403. Problem list was empty and leaderboard contained no problem codes or rows.

Contest state, materials, announcements, general clarification list/privacy and a general lobby clarification were explicitly confirmed as allowed holding-room behavior. The test seeds one synthetic historical submission directly through the repository solely to probe read paths; normal production transitions cannot submit before release.

The seeded row exposed a narrow defense-in-depth gap: `/api/submissions/mine?contestId=...` returned 200 with problemCode `A` in LOBBY because scoped personal history uses requireAccess rather than requireReleasedAccess. It did not reveal source, tests, statement, another user's data, or permit a submission.

Exploitability through normal participant operations was not demonstrated because such a row cannot normally be created before release; imports/operator changes or future lifecycle changes could make it relevant. The broader `all=true` personal-history mode also needs explicit filtering semantics if this is fixed, so past released contests remain usable without leaking rows belonging to an unreleased contest.

ENDED matrix: new scored submission returned 403, while direct statement/problem list, own source/results/history, attempt summaries, final leaderboard and custom-input practice returned 200 as intended. The custom run executed through the real Docker sandbox. This locks in the stated post-contest review/practice policy rather than treating every post-end read as a bypass.

Verdict: score-sensitive direct access is enforced; one low-severity pre-release metadata gap was reproduced. Proposed remediation awaiting approval: for non-admin history, exclude submissions belonging to contests whose problems are not released (both contest-scoped and `all=true` views), preserving released historic results and admin behavior; convert the synthetic-row assertion to require no leaked problem code. No other item-38 production changes proposed. Next concern remains item 39 score manipulation after this decision.

<a id="audit-entry-23"></a>

## Security audit follow-up — item 38 deferral and item 39 score-manipulation review

The user declined the proposed item-38 personal-history metadata hardening and moved on. The authorized route-matrix tests remain; non-admin history can still expose a problem code for a synthetic pre-release own-submission row. This is an accepted low-severity deferral, not fixed behavior.

Item 39 review: student submission input is the fixed SubmitRequest record containing only problemId, language and sourceCode. No student DTO accepts verdict, score, marks, penalty, execution time, memory, failed-test index, judge time or submission time.

SubmissionService derives user/contest and captures server admission time. JudgeWorker alone records ordinary verdict/resource/test outcomes, and LeaderboardService recomputes cells/rank/penalty from active database submissions and server timestamps rather than accepting a client leaderboard row.

DTOs containing verdict/metrics are responses/events, not controller request bodies.

There are intentional instructor mutation paths for marks, verdict override and manual penalty adjustment. They are all under `/api/admin/**`, so SecurityConfig requires current ADMIN role; the existing loopback admin filter additionally restricts the management API.

Earlier role-escalation tests cover forged/stale role tokens. These privileged overrides are product features and trusted inputs, not student scoring fields. Rejudge is absent and remains item 40. Audit logging/bounds for instructor overrides would be an administrative-integrity enhancement, separate from student score manipulation.

Unknown JSON properties are normally ignored by Spring/Jackson, so adding `verdict`, `score`, `marks`, `penalty`, `queuedAt`, `userId` or `contestId` to a student submission request should not bind them to Submission. Code review finds no mass-assignment path, but there is no dedicated end-to-end forged-field test proving the stored row and leaderboard ignore them. Verdict: no score-manipulation flaw found; dedicated attack coverage is the remaining gap.

Proposed test-only follow-up awaiting approval: submit raw forged JSON with a deliberately non-AC program, inspect the persisted row after judging and leaderboard, attempt every admin scoring mutation with the student token, and assert the forged values never persist while admin routes return 403. No item-39 production change or new test run yet. Next concern: item 40 rejudge security.

<a id="audit-entry-24"></a>

## Security audit follow-up — item 39 authorized score-manipulation attack

Added ScoreManipulationAttackIntegrationTest using the live HTTP server/security chain, real JWT/session identity, MySQL test schema, real judge queue/compiler sandbox and derived student leaderboard. Targeted run passed one test, zero failures/errors/skips, 2026-09-23 11:31 +06:00. No production change and no full-suite rerun are claimed; the last full snapshot remains item 37.

The raw student JSON included legitimate problem/language/source plus forged userId, contestId, verdict=AC, score=100, marks=100, manualPenaltyDelta=-999999, execution/memory values, failed index, year-2000 queuedAt, status=DONE and active=false.

The endpoint returned 202, but the stored row retained the authenticated student, server-derived contest/problem, exact submitted source, server admission time, active state, null marks and zero manual adjustment. The real C++ compiler judged the deliberately invalid source CE with judge-derived -1 execution/memory values; none of the forged result fields persisted.

Using that valid student JWT over loopback with valid IDs, all four privileged mutations returned 403: per-submission marks, participant marks, verdict override and manual penalty adjustment. The database row remained CE/null-marks/zero-adjustment. The real student leaderboard contained the participant with zero solved and zero penalty. This complements rather than replaces existing forged/stale-role and object-authorization tests.

Verdict: item 39 has direct attack coverage and no student score-manipulation flaw was found. Unknown JSON properties are safely ignored in this path; strict unknown-field rejection is not required for integrity. Instructor override auditability/range hardening can be considered separately as an operational enhancement, but is not a student bypass. Item 39 is complete with tests only. Next concern: item 40 rejudge security.

<a id="audit-entry-25"></a>

## Security audit follow-up — item 40 rejudge security

Repository-wide route/service/client search found no rejudge-one, rejudge-problem or rejudge-contest API, UI action, DTO or service operation. JudgeWorker.judge is reached by the internal JudgeQueue only. Students can create a new submission through the normal admission/rate/backlog path, but cannot reset an existing submission to PENDING or enqueue an arbitrary ID through HTTP/STOMP. Therefore role restriction and rejudge audit logging are not currently applicable.

JudgeQueue recovery does enqueue database submissions whose status is not DONE at application startup. This is automatic crash recovery for interrupted PENDING/JUDGING work, not a remotely selectable rejudge operation; it preserves the same submission row. The deployment remains intentionally single-server/in-memory, as documented elsewhere. A multi-server judge-coordination design would require database claiming/leases to prevent duplicate workers, but adding that is outside the absent rejudge feature and current deployment model.

Admin verdict override is not a rejudge: it changes the stored verdict without recompiling/rerunning and is protected by `/api/admin/**` ADMIN authorization plus loopback restriction. Its behavior was directly included in item-39 student attack testing. It does not satisfy a future rejudge requirement, and the current admin console exposes no rejudge button.

Verdict: item 40 is not applicable to the current feature set; no fix or dedicated test is indicated. If rejudge is introduced later, it must be an explicit admin/loopback-only API, use bounded queue admission and atomic job claiming, define standings/history behavior, and create an immutable audit record containing actor, scope, reason, time and affected submission IDs. Next concern: item 41 custom checkers.

<a id="audit-entry-26"></a>

## Security audit follow-up — item 41 custom-checker isolation review

Custom checker source is accepted only through admin problem-package operations protected by `/api/admin/**` role and loopback gates. Validation/compilation uses SandboxExecutor.compile: Docker-only execution, no host fallback, no network/IPC sharing, fixed non-root UID, all capabilities dropped, no-new-privileges, read-only container root, bounded `/tmp`, CPU/PID/memory/file/output/time limits, safe environment, immutable image pinning and post-compilation symlink/non-regular-artifact rejection.

The persistent checker cache is under an owner-only parent; only its individual private compile directory is mounted during compilation.

Each check copies only the cached binary plus that invocation's input, bounded contestant output and expected output into a fresh private directory. SafeSandboxFiles refuses link/special-file copy sources and exclusive-creates destinations.

Execution mounts this directory read-only and uses the same runtime isolation as contestant code (network none, 64 PID limit, caps dropped, no-new-privileges, bounded tmpfs/output/files, timeout and container cleanup). It cannot see the contestant workspace, checker cache, sibling submissions, database/application files or host Docker socket.

Expected output is visible to the checker by design because it must decide correctness; checker stdout/stderr is not returned to contestants. Existing live Docker tests cover valid multi-output behavior, crashes, unmapped exits, timeout kill and per-invocation staging cleanup.

One gap was found: SandboxExecutor.run deliberately gives every runtime container 2x the requested memory so JudgeWorker can observe and classify contestant MLE. CheckerRunner passes checkerMemoryLimitKb into that API but never performs JudgeWorker's measured-peak comparison.

Therefore a checker can exceed its configured 256 MiB limit and still return AC if it stays below the approximately 512 MiB cgroup hard cap and exits zero. Host exposure remains bounded and aggregate-memory admission reserves the larger container amount, but the advertised checker memory limit is not actually enforced.

Writable `/run-metrics` and inherited Docker logging remain earlier user-deferred concerns and also apply to checker containers; they are not reopened here.

Proposed next step awaiting approval: live malicious-checker tests for configured-memory overrun, network access, host/sibling reads, runtime writes, fork pressure and output flood. If the memory attack reproduces, compare trustworthy peak RSS against checkerMemoryLimitKb and map over-limit execution to checker failure/RE while retaining cgroup headroom.

The trusted-metrics caveat must be explicit because item 34's measurement isolation was declined; do not claim cryptographically trustworthy self-reported checker RSS. No item-41 changes or new tests yet. Next concern: item 42 test generators.

<a id="audit-entry-27"></a>

## Security audit follow-up — item 41 authorized malicious-checker tests

Extended CheckerRunnerTest with five live Docker attacks. The final focused run passed all eight class tests, zero failures/errors/skips, via `mvn test -pl arbitrator-server -am -Dtest=CheckerRunnerTest -Dsurefire.failIfNoSpecifiedTests=false` on 2026-09-23 at 21:33 +06:00.

The tests confirmed that checker containers cannot connect to loopback MySQL, link-local metadata, or a public IP; cannot read host/sibling sentinels through absolute paths, `/proc/1/root`, or `/base`; cannot write the read-only runtime mount or `/etc`; hit the PID ceiling under fork pressure without harming the next checker; and are killed promptly when flooding output.

Existing valid-output, crash, and timeout checks also passed. This is targeted coverage, not a full suite or proof against every container escape.

The configured-memory gap was directly reproduced. With `checkerMemoryLimitKb=65536`, a checker allocated and page-touched 96 MiB, verified its own `ru_maxrss` exceeded 64 MiB, exited zero, and CheckerRunner returned AC. This succeeds because SandboxExecutor intentionally gives the process approximately 2x cgroup headroom and CheckerRunner does not apply the configured-limit comparison used for contestant programs.

The checker remains bounded by the larger cgroup and aggregate admission, so this is a resource-policy bypass rather than unbounded host memory access.

The first attempt had one test-fixture compilation error (`size_t` namespace), not a sandbox result; it was corrected to `std::size_t`. A disconnected retry left no Maven/Surefire/Docker test process and no fresh report. The final clean run supersedes both attempts. The output-flood test suppresses only its expected 1 MiB CheckerRunner error log inside the test to avoid overwhelming the runner; production logging is unchanged.

Proposed production remediation awaiting approval: after each checker run, treat measured peak RSS above `checkerMemoryLimitKb` as checker failure/RE before interpreting exit 0/1/2, while keeping the 2x cgroup headroom so the process can be measured rather than ambiguously OOM-killed.

Also make `outputLimitExceeded` an explicit RE condition rather than relying on the current pipe-close/nonzero-exit effect. The peak metric still uses the writable `/run-metrics` design explicitly deferred in item 34, so this improves normal policy enforcement but is not a tamper-proof boundary against a malicious checker.

No item-41 production code was changed. Next concern remains item 42 test generators after this decision.

<a id="audit-entry-28"></a>

## Security audit follow-up — item 41 approved runtime-limit remediation

The user confirmed this limit applies to the instructor-uploaded custom checker and approved reporting checker failure as submission `RE`, rather than incorrectly blaming the student's program with `MLE` or `OLE`. CheckerRunner now checks the execution result before interpreting checker exit codes: a positive measured peak above `checkerMemoryLimitKb` returns RE and logs measured/allowed KiB; an explicit sandbox output-limit signal also returns RE and logs only a fixed server message, not the captured attacker-controlled flood.

Timeout behavior remains RE. The approximately 2x container headroom remains so a normal overrun can finish far enough to be measured and classified.

Converted the memory attack from vulnerability characterization to a protection regression. With a 65,536 KiB configured limit, the checker allocated/page-touched 96 MiB; the focused post-fix run measured 100,548 KiB and returned RE. All eight CheckerRunnerTest cases passed with no failures/errors/skips at 22:03 +06:00.

The adjacent CheckerRunnerTest, JudgeWorkerTest and SandboxExecutorTest run then passed 33 tests (8 + 6 + 19), zero failures/errors/skips, at 22:04 +06:00. This verifies custom-checker classification plus existing contestant MLE/OLE, Docker isolation and resource behavior; it is not a new full-suite result.

The output-flood regression no longer suppresses CheckerRunner logging: the explicit output-limit branch emits only the fixed problem-ID message, demonstrating the 1 MiB payload is not copied into application logs through this path. A final unsuppressed eight-test rerun passed at 22:05 +06:00 and showed the bounded message.

Item 34's writable metrics path remains a separately accepted deferral, so measured checker-memory enforcement is not claimed as tamper-proof against a malicious checker that can manipulate its metrics file. Within that caveat, item 41 is implemented and verified.

Next concern: item 42 test generators.

<a id="audit-entry-29"></a>

## Security audit follow-up — item 42 test generators

Repository-wide backend/common/client search found no problem test-generator or validator feature, API, DTO, service, UI action, command template, or execution path. ProblemPackageService accepts pre-generated static `tests/*.in` and matching `tests/*.out` data.

ZIP entries such as `generator/generator.cpp`, scripts, binaries, or validator sources are read only as bounded archive bytes and then ignored: they are not compiled, executed, stored as executable artifacts, or passed to CheckerRunner/SandboxExecutor/a host process.

The only executable problem-package content recognized by the server is the explicitly named optional `checker/checker.cpp`, already reviewed and remediated under item 41.

Package upload is an `/api/admin/**` operation protected by ADMIN authorization and the existing loopback gate. Archive parsing stays in memory and retains its established traversal, duplicate canonical path, entry-count, per-file and total-uncompressed-size protections. Those file-upload controls are reviewed separately by later checklist items; they do not create a generator execution path.

Verdict: item 42 is not applicable to the current feature set, and no production change or dedicated runtime test is indicated. Instructors must generate tests outside Arbitrator and upload the resulting static pairs. If executable generators or validators are added later, they must use a distinct sandboxed workflow with no infrastructure credentials or host execution, immutable toolchains, network disabled, private mounts, bounded CPU/wall time/memory/PIDs/output/files, validated generated artifacts, and an audit record.

Next concern: item 43 special judges.

<a id="audit-entry-30"></a>

## Security audit follow-up — item 43 special judges

Repository-wide type and call-path review found one special-judge mechanism only: `CheckerType.CUSTOM`, implemented by CheckerRunner. There is no second special-judge executable, partial-score judge, generator, or validator path. Exact matching contains no uploaded executable code. The custom-checker upload, compilation, runtime limits, malicious isolation probes and approved memory/output remediation are therefore the complete applicable surface and were covered under item 41.

Contestant execution and special judging are sequential and isolated. JudgeWorker first runs the contestant in its own throwaway Docker container with only the submission workspace mounted read-only. After that container has exited, the server captures bounded contestant output and stages a new private checker directory containing only the checker binary, test input, contestant output and expected output.

CheckerRunner then launches a different throwaway container with that directory read-only and removes the staging directory afterwards. The contestant never receives the checker directory or expected output, and the checker never receives the contestant compilation workspace/cache.

Both use the same fixed unprivileged numeric container UID, but they do not share a container, mount namespace, visible writable directory, network, IPC namespace or infrastructure credentials; distinct numeric UIDs would not add a meaningful boundary within this existing no-shared-mount design.

Both paths retain non-root execution, dropped capabilities, no-new-privileges, read-only roots/runtime mounts, network disabled, private temporary storage, PID/CPU/memory/output/file/time limits, immutable image pinning and aggregate container/memory admission.

The latest item-41 related run already exercised CheckerRunner, JudgeWorker and SandboxExecutor together: 33 tests passed with no failures/errors/skips. Verdict: item 43 is satisfied by the item-41 implementation and verification; no additional production change or duplicate test is indicated.

The separately deferred writable metrics and inherited Docker log-driver risks remain unchanged rather than being reopened here. Next concern: item 44 file uploads.

<a id="audit-entry-31"></a>

## Security audit follow-up — item 44 file uploads review

Inventoried every multipart upload: problem-package ZIP, standalone replacement problem PDF and general contest material. Upload routes are under `/api/admin/**`, requiring a current ADMIN session and the loopback admin gate. Spring caps each multipart file/request at 64 MB.

Problem-package parsing ignores the request Content-Type and parses actual ZIP structure in memory; recognized statement extensions are allowlisted, HTML is sanitized, test/checker paths are fixed, and ZIP entry/file/expanded-total limits apply.

Standalone PDF replacement has a 16 MiB service limit and checks the `%PDF` signature. General materials are intentionally any-file opaque downloads, stored outside static web resources under random UUID-based names rather than supplied paths.

Student list/download routes require contest access, and downloads use `Content-Disposition: attachment`; Spring Security's `nosniff` header remains enabled. No avatar/image attachment or other upload endpoint was found.

Two gaps remain. First, a PDF statement inside a problem ZIP is selected only by its `.pdf` entry suffix and size; unlike standalone replacement, package import does not call `looksLikePdf`. Arbitrary bytes can therefore be stored and later sent as `application/pdf` into the JavaFX PDFBox parser.

Invalid bytes normally fail rendering rather than execute, and upload is admin/loopback-only, but this violates the actual-type validation policy and unnecessarily expands parser input. Second, MaterialService validates the syntax of the multipart-supplied Content-Type and stores it; MaterialController later reflects that type on download.

Attachment disposition plus `nosniff`, authenticated access and the intentional any-file policy substantially reduce exploitability, but an opaque-download feature should not trust or need an uploader-asserted MIME type. The existing 200 MB MaterialService message versus the effective 64 MB Spring multipart cap is a correctness/configuration mismatch already tracked, not a security limit bypass because the lower bound wins.

Proposed remediation awaiting approval: reject package PDF entries that fail the same `%PDF` byte-signature check already used by standalone replacement, and always serve general material downloads as `application/octet-stream` while retaining attachment disposition and the sanitized display filename.

Preserve the any-file material feature; do not introduce an extension allowlist that would block slides, archives or course assets. Add focused regressions proving a renamed non-PDF package entry is rejected, a valid PDF remains accepted, and a material recorded as `text/html` downloads as octet-stream/attachment with `nosniff`.

Filename length/control/path hardening is item 46 and detailed ZIP attacks are item 45, so they are not silently folded into this decision. No item-44 production or test change has been made yet. Next concern remains item 45 after this decision.

<a id="audit-entry-32"></a>

## Security audit follow-up — item 44 approved upload remediation

ProblemPackageService now applies its existing `%PDF` byte-signature check to PDF statements discovered inside problem ZIPs, before any problem/PDF row is persisted. The valid `%PDF-1.4` package fixture remains accepted; a new renamed-HTML `.pdf` fixture is rejected with no PDF insert. This is a lightweight type gate, not a promise that every structurally valid PDF is harmless; the participant client still parses accepted statements with PDFBox and parser dependencies must remain patched.

MaterialController now serves every general material as `application/octet-stream`, regardless of stored multipart Content-Type, while retaining authenticated contest access, byte length, sanitized original display filename and `Content-Disposition: attachment`. Any-file upload support and the metadata field remain unchanged; clients can still save `.pdf`, `.pptx`, images, archives and other assets under their intended filename. The fixed response avoids treating uploader assertions such as `text/html` as browser-renderable content.

Added FileUploadSecurityIntegrationTest using the live HTTP/security chain, admin login/loopback gate, MySQL test schema and a disposable material root. It uploads script-bearing bytes declared as `text/html`, confirms that informational metadata remains, and then verifies the authorized download is octet-stream, attachment filename `lesson.html`, `X-Content-Type-Options: nosniff`, and byte-for-byte unchanged.

Together with ProblemPackageServiceTest, the focused run passed 24 tests (23 package + 1 HTTP), zero failures/errors/skips, on 2026-09-23 at 22:24 +06:00. The first attempt had only incorrect test imports for Spring's DynamicProperty classes and stopped at test compilation; corrected imports produced the clean run.

No full-suite rerun is claimed. Item 44 is complete; next concern: item 45 ZIP uploads.

<a id="audit-entry-33"></a>

## Security audit follow-up — item 45 ZIP uploads review

Problem packages are parsed with ZipInputStream entirely in memory and are never extracted to the host filesystem. Each entry name is normalized to forward slashes; absolute paths, parent segments, NULs, empty names and canonical duplicates are rejected.

Directory entries carry no write effect. A symlink entry is only an in-memory byte entry because no ZIP metadata is materialized as a filesystem link. Nested ZIP bytes are not recursively opened or executed. Limits currently cap the multipart request at 64 MB compressed, entries at 5,000, each uncompressed entry at 32 MiB and aggregate uncompressed content at 256 MiB.

The per-entry reader does not trust ZipEntry.getSize: it reads through the actual stream up to the cap plus one byte, so unknown-size/data-descriptor entries and high-ratio single-entry bombs are rejected rather than truncated. Malformed/non-ZIP input and duplicate slash/backslash aliases already have focused tests.

One resource-exhaustion gap remains: the 256 MiB aggregate expansion allowance is four times the HTTP upload cap and `readZip` retains every accepted entry byte array in its map. Import then decodes statement/test/checker content into Java strings and later persists it, creating additional temporary/live memory.

A highly compressed admin/loopback upload can therefore make one request retain hundreds of MiB; concurrent requests are not governed by a dedicated decompression-memory admission control. The admin and loopback gates reduce remote exploitability, and the 256 MiB boundary prevents unlimited expansion, but this is too generous for a service whose own comment says real packages are only a few hundred KiB and may run on a lab machine with a modest heap.

Proposed remediation awaiting approval: lower aggregate uncompressed ZIP content from 256 MiB to 64 MiB while keeping the 32 MiB per-entry and 5,000-entry bounds. Add focused high-compression tests for aggregate overflow and the exact aggregate boundary, a 5,001-entry rejection, a nested-ZIP fixture proving no recursive interpretation, and a ZIP entry with link-like content/path proving it remains ordinary bytes and cannot create a filesystem object.

Retain the existing path/duplicate/unknown-size tests. This does not stream packages to disk or add a new extraction path. If legitimate packages later need more than 64 MiB uncompressed, raise the bound only alongside explicit heap sizing and upload-concurrency admission.

No item-45 production or test change has been made yet. Next concern remains item 46 filename security after this decision.

<a id="audit-entry-34"></a>

## Security audit follow-up — item 45 approved ZIP expansion remediation

Reduced `ProblemPackageService.MAX_TOTAL_UNCOMPRESSED` from 256 MiB to 64 MiB. The compressed HTTP request remains capped at 64 MB, each actual uncompressed entry at 32 MiB, and entry count at 5,000. Parsing is still memory-only and does not extract to disk. This lowers the maximum retained expansion and subsequent string-conversion pressure without changing the documented package format or normal few-hundred-KiB workflow.

Added five focused regressions to ProblemPackageServiceTest. Highly compressible padding produces a sub-1-MiB archive that expands through the real ZipInputStream: exactly 64 MiB is accepted and 64 MiB plus one byte is rejected. A 5,001-entry archive is rejected.

An embedded valid problem ZIP remains an opaque ignored entry and does not add its nested test case. A uniquely named link-target-like payload remains ordinary ignored bytes and creates no filesystem file. These complement the existing traversal, absolute path, malformed archive, cap-plus-one unknown-size entry and canonical-duplicate tests; the link-like fixture does not claim to preserve/inspect Unix symlink mode metadata, which is irrelevant because the importer never materializes any entry.

The complete focused ProblemPackageServiceTest suite passed 28 tests, zero failures/errors/skips, on 2026-09-23 at 22:30 +06:00. No database, HTTP multipart layer, concurrent-upload load or full-suite rerun is claimed by this targeted result. Item 45 is implemented and verified. If future legitimate packages exceed 64 MiB uncompressed, revisit the limit only with explicit server heap sizing and concurrent decompression admission. Next concern: item 46 filename security.

<a id="audit-entry-35"></a>

## Security audit follow-up — item 46 filename security review

Only general contest materials retain an uploaded filename. Problem ZIP entry names are never extracted and are separately canonicalized under item 45; standalone PDF replacement ignores its multipart filename. MaterialService does not save a material under the supplied name: it strips a host-recognized directory component, stores the result only as display/download metadata, and creates a UUID-prefixed disk name.

MaterialController builds attachment disposition through Spring's ContentDisposition API, and the JavaFX client only uses the server-returned name as a save-dialog suggestion; the user still chooses the final destination. Therefore classic `../../../app.py` or `../../templates/admin.html` upload traversal does not currently overwrite an application file.

The remaining policy is not fully portable or fail-safe. `Path.of(name).getFileName()` recognizes separators according to the server OS, so a Windows backslash path remains a display name on Linux. Sanitization replaces only CR, LF and quote, leaving other control/format characters, Windows-forbidden characters/device names and trailing dots/spaces that can break or spoof a participant's save dialog.

There is no filename length bound even though the database column is VARCHAR(255); a long name can cause a database failure after bytes have already been written, leaving an orphan. The disk name is UUID plus the complete substring from the last dot, so an attacker-controlled long/odd “extension” still reaches the server filesystem and can exceed component limits even though it cannot introduce a forward-slash traversal on Linux.

Finally, file lookup/deletion resolves database `stored_name` directly beneath the root without normalizing/confirming containment or rejecting symlinks/non-regular files. The API never lets a user set stored_name, so exploiting that last condition requires database or host-filesystem corruption, but it is an avoidable missing final boundary.

Proposed remediation awaiting approval: make new physical names pure UUIDs with no user-derived extension; sanitize display names platform-independently by stripping both slash styles, replacing control/format and portable-forbidden characters, handling blank/dot/Windows-device names, removing unsafe trailing dots/spaces, and limiting the result to 200 Unicode code points while preserving a short extension when practical.

Add one stored-path resolver used by download and all deletion paths that rejects absolute/multi-component/out-of-root names and requires a non-symlink regular file for downloads; continue accepting safe legacy UUID-with-extension rows. Add focused service/controller tests for Unix and Windows traversal names, NUL/control/header characters, very long names, reserved names, pure generated storage names, hostile database stored paths and symlinks, plus normal legacy download/delete behavior.

No item-46 production or test change has been made yet. Next concern: item 47 open redirects after this decision.

<a id="audit-entry-36"></a>

## Security audit follow-up — item 46 approved filename remediation

MaterialService now treats every uploader filename as display-only and stores new bytes under a pure random UUID with no user-derived extension. Portable sanitization handles both slash conventions independently of the server OS, NFC-normalizes Unicode, replaces control and format code points plus characters forbidden across common filesystems, removes unsafe trailing dots/spaces, supplies a safe fallback for blank/dot names, prefixes Windows device names, and limits the result to 200 Unicode code points while retaining a short extension where possible.

This keeps the catalog value within its database column and prevents malformed extensions from reaching the host filesystem. If saving the catalog row fails after the byte copy, the new physical file is removed; a failed copy also receives best-effort cleanup.

All material reads and deletion paths now use one normalized resolver. It accepts exactly one relative component beneath the configured root, explicitly rejects both separator styles, absolute paths, dot components and invalid syntax, and verifies the resolved parent.

Downloads additionally require a regular file without following symbolic links. Existing safe UUID-with-extension rows remain readable/deletable. A corrupt hostile row cannot reach an outside file and does not prevent deleting its catalog entry; contest deletion retains its existing after-commit, best-effort file cleanup behavior.

Added MaterialFilenameSecurityTest with five focused cases covering Unix/Windows traversal-style display names, NUL/control/Unicode-format/portable-forbidden characters, trailing dot/space handling, Windows device names, a 300-character filename with extension-preserving truncation, pure-UUID physical storage, traversal in a hostile database row, symbolic-link refusal, normal legacy lookup, safe row deletion, and physical-file cleanup after a simulated metadata failure.

Together with the live FileUploadSecurityIntegrationTest and all six ContestMaterialDeletionTest cases, the finalized focused run passed 12 tests with zero failures, errors or skips on 2026-09-23 at 22:55 +06:00. The first unit attempt compiled but four mock-backed cases stopped before exercising production code because this JDK cannot self-attach Mockito's inline agent; replacing Mockito with dependency-free test fakes produced the clean run.

One subsequent permission-review timeout occurred before a rerun process started; the immediate retry produced the finalized clean result. `git diff --check` also passed. No full-suite rerun is claimed. Item 46 is complete; next concern: item 47 open redirects.

<a id="audit-entry-37"></a>

## Security audit follow-up — item 47 open redirects review

Verdict: no exploitable open-redirect surface was found. The server has exactly two redirect registrations: `/admin` and `/admin/` both target the compile-time constant, context-relative `/admin/index.html`. They do not derive a destination from a request value.

Spring's RedirectView has query propagation disabled by default, and these registrations do not enable it or expose a request-derived model. A hostile Host header therefore does not become an absolute destination, while parameters such as `next=https://evil.example`, `redirect=//evil.example`, encoded slashes/backslashes or user-info syntax are never interpreted as redirect targets.

The broader navigation review found no alternate sink. Stateless Spring Security returns HTTP 401 for unauthenticated API requests instead of sending a login redirect. No controller, request DTO, entity or client response contains a next/return/callback/redirect URL.

Instructor-authored HTML is sanitized with an allowlist that excludes anchors, `href`, images, forms and remote resources; JavaFX statement/announcement WebViews also disable JavaScript. The automatic instructor-console launcher builds only `http://localhost:<integer port>/admin`; no request or database value reaches it.

The admin page's generated anchors download locally created blob URLs, and the JavaFX server-address field is an explicit local connection setting rather than an application redirect.

No production fix is indicated. Optional defense-in-depth is a focused live HTTP regression matrix that sends absolute URLs, scheme-relative URLs, encoded separator/backslash payloads and a hostile Host header to both fixed aliases and representative login/static/API routes, asserting that `/admin` can only emit a Location for `/admin/index.html` and that the other routes never produce a 3xx attacker-controlled Location.

This would lock in the current behavior; it would not close a reproduced vulnerability. No item-47 test or production change has been made pending approval. Next concern after this decision: item 48 CORS.

The user chose to move next rather than add the optional item-47 regression. The no-vulnerability verdict stands, but no dedicated open-redirect attack test was added.

<a id="audit-entry-38"></a>

## Security audit follow-up — item 48 CORS and WebSocket Origin review

Verdict: ordinary HTTP CORS is closed by default, but the raw WebSocket endpoint has an unnecessarily permissive Origin policy. Application code contains no `http.cors(...)`, explicit `CorsFilter`/`CorsConfiguration`, `@CrossOrigin`, HTTP allowed-origin list/pattern, or code writing `Access-Control-Allow-Origin`/`Access-Control-Allow-Credentials`.

Authentication is a stateless JWT supplied in an Authorization header, not an ambient browser cookie. Consequently a browser origin is not granted permission to read login, participant or private/admin API responses, and wildcard credentialed HTTP CORS is not present.

`WebSocketConfig`, however, registers `/ws` with `setAllowedOriginPatterns("*")`. Origin checking is not CORS proper, but it is the equivalent cross-site browser boundary for WebSocket handshakes. Immediate cross-site session-riding risk is limited because `JwtHandshakeInterceptor` requires `Authorization: Bearer ...` during the HTTP upgrade, the production JavaFX client can set that header, and browser WebSocket JavaScript cannot set arbitrary Authorization headers; there are no authentication cookies or query-token fallback. Nevertheless the wildcard is not needed by the non-browser client and silently removes a useful defense if authentication or clients change later. “Closed LAN” is not a sound reason to trust arbitrary web origins loaded in a participant's browser.

Proposed remediation awaiting approval: remove `.setAllowedOriginPatterns("*")` and rely on Spring's default same-origin WebSocket policy. Preserve the JavaFX Authorization-header handshake and do not add HTTP CORS. Add focused live coverage proving (1) a WebSocket upgrade with a hostile Origin is rejected even when it carries a valid token, (2) the normal JavaFX-style handshake with no cross-origin browser Origin still connects and receives a push, and (3) hostile HTTP Origin/preflight requests to representative public, authenticated and admin routes never receive permissive ACAO or ACAC headers.

This is a narrow hardening change; no origin allowlist configuration is needed unless a real browser frontend becomes a supported product. No item-48 production or test change has been made yet. Next concern after this decision: item 49 security headers.

<a id="audit-entry-39"></a>

## Security audit follow-up — item 48 approved CORS/Origin remediation

Removed the wildcard allowed-Origin pattern from the raw STOMP endpoint. Spring's default WebSocket policy now permits same-origin browser requests and non-browser handshakes without an Origin header, while rejecting an unrelated browser Origin before the socket opens. The JavaFX client protocol is unchanged: it continues sending the JWT in the Authorization upgrade header and does not need an HTTP CORS policy. HTTP remains closed to cross-origin browser reading; no wildcard, credentialed CORS or new allowlist was introduced.

Extended VerdictPushIntegrationTest with a valid-token attack carrying `Origin: https://evil.example`; the upgrade is rejected with HTTP 403. The existing suite simultaneously proves the JavaFX-style no-Origin handshake still connects, authentication/session replacement/logout behavior remains intact, and a real judged submission still receives its verdict over STOMP.

Added CorsOriginSecurityIntegrationTest, which sends hostile simple Origin requests to public admin static content, a protected participant API, public auth, and loopback-admin API, then sends preflights to public, participant and admin API paths.

Every response omits both `Access-Control-Allow-Origin` and `Access-Control-Allow-Credentials`.

The focused live run passed 7 tests with zero failures, errors or skips on 2026-09-23 at 23:10 +06:00, with local MySQL and Docker available. This is not a full-suite rerun. Item 48 is complete; next concern: item 49 security headers.

<a id="audit-entry-40"></a>

## Security audit follow-up — item 49 security headers review

Verdict: header protection is useful but incomplete and inconsistent across early rejection paths. Spring Security's default header writer supplies `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, cache prevention and the modern `X-XSS-Protection: 0` policy on responses that traverse its filter chain; existing live upload and reflected-XSS tests directly confirm `nosniff`.

Its default HSTS writer intentionally emits only for secure HTTPS requests. The application does not explicitly configure Content-Security-Policy, Referrer-Policy or Permissions-Policy.

Three application filters precede Spring Security: LoopbackAdminFilter, ApiIpRateLimitFilter and JsonBodyLimitFilter. Each can terminate a request with 403, 429 or 413 before Spring's HeaderWriterFilter runs, so framework defaults alone do not guarantee consistent headers on attacker-triggered errors. This matters most for `nosniff`, framing policy and referrer control; security headers should not disappear merely because a request was rejected early.

HSTS is intentionally not part of the item-49 fix while the project remains HTTP/WS by locked decision D4. Browsers only honor HSTS received over HTTPS, and enabling/advertising it before a stable HTTPS deployment is misleading and can create operational lockout once a real secure origin begins sending it. HTTPS redirect and HSTS activation belong to item 50; TLS configuration belongs to item 51.

The admin console currently contains one large inline script, inline CSS, many inline event handlers/style attributes, same-origin fonts/logo/fetches, a sandboxed statement `srcdoc` iframe, local blob downloads and clipboard write. A strict nonce/hash-only script CSP would break it and requires a deliberate UI refactor.

Proposed remediation awaiting approval: install a highest-precedence response-header filter so successful and early-error responses all receive `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and a Permissions-Policy denying unused sensitive capabilities such as camera, microphone, geolocation, payment and USB.

Add a compatible enforcing CSP limited to self-hosted content/connections, with `object-src 'none'`, `base-uri 'none'`, `form-action 'self'`, `frame-ancestors 'none'`, and temporary `'unsafe-inline'` allowances for script/style required by the current static console.

The CSP still prevents external resource/connection injection and supplies modern anti-framing, but `'unsafe-inline'` is a documented residual: full inline-script XSS mitigation requires extracting scripts/styles and replacing inline handlers.

Add a live response matrix for the admin document/assets, authenticated and anonymous APIs, 404/error responses, material downloads, oversized-JSON 413 and rate-limit 429, plus a direct filter test for non-loopback admin 403. Verify exact CSP/referrer/permissions/nosniff/frame headers and absence of HSTS over HTTP.

Run an actual admin-browser smoke under the enforcing CSP to confirm login, navigation, API fetches, statement preview, file download and clipboard behavior remain usable. This supersedes the earlier user deferral of standalone checklist item 18 only if approved now.

No item-49 production or test change has been made yet. Next concern after this decision: item 50 HTTPS.

<a id="audit-entry-41"></a>

## Security audit follow-up — item 49 approved security-header remediation

Added `SecurityHeadersFilter` at the highest servlet-filter precedence so successful responses and application-filter rejections receive the same baseline before any component can return early. It emits the enforcing CSP reviewed above, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and a Permissions-Policy denying camera, microphone, geolocation, payment and USB.

The loopback-admin, IP-rate-limit and JSON-size filters now follow it in their original relative order. HSTS is deliberately absent on HTTP; HTTPS redirect and HSTS activation remain item 50. The CSP's temporary `'unsafe-inline'` script/style allowances are a documented residual required by the current inline-heavy admin console; removing them requires extracting its script, styles and event handlers or adopting nonces/hashes.

Added direct filter coverage for an ordinary response, early non-loopback 403 and oversized-JSON 413. Added a live header matrix for the admin document and PNG, anonymous API, missing route and oversized JSON; extended live material-download and rate-limit attack tests to assert the exact policy on attachment responses and real 429 rejections. All 7 focused automated tests passed with zero failures, errors or skips on 2026-09-23 at 23:22 +06:00. Every tested HTTP response omits HSTS as intended.

A real browser loaded the admin console's inline-script/style login gate under the enforcing CSP with no console warnings, CSP violations or resource errors. Authenticated navigation, statement preview, download and clipboard checks were not completed: the existing development and isolated-test database admin rows no longer accept the documented demo password, and no credential was reset or account modified merely for this smoke test.

The first browser launch accidentally inherited the always-included `local` profile and therefore used the development `arbitrator` schema; normal startup applied its already-pending V81 and V82 Flyway migrations (schema 80 to 82). No rollback was attempted.

The repeat launch explicitly overrode the datasource to `arbitrator_test`, whose schema was already at 82. Both temporary servers were shut down cleanly. No full-suite rerun is claimed. Item 49 is complete; next concern: item 50 HTTPS.

<a id="audit-entry-42"></a>

## Security audit follow-up — item 50 HTTPS/WSS review

Verdict: genuine high-impact transport-security gap, currently accepted only by locked decision D4. The application listens on plaintext HTTP, participant push uses WS, the packaged client defaults to `http`, the server advertises a scheme-less LAN address, and the admin launcher/info endpoint hard-code `http://`.

Passwords, bearer JWTs, registration data, source code, contest content, clarifications, materials, verdicts and standings can therefore be observed or modified by an attacker who can sniff or actively interfere with the lab LAN. A stolen bearer token is replayable until it expires or its session is revoked.

Loopback-only admin routing reduces exposure of the instructor surface but does not protect participant traffic, and a controlled LAN is not itself an encryption or server-authentication boundary. Authentication uses headers rather than cookies, so the checklist's Secure-cookie clause is not applicable.

The Java client already maps an in-memory `https` scheme to HTTPS/WSS and relies on the JVM trust store, but this is incomplete. Its server-address UI displays only `host:port`; `updateHostPort` accepts an optional scheme but persists only `host:port`, so a manually entered `https://...` silently reverts to the packaged/default scheme after restart.

There is no server SSL/keystore configuration, trusted-certificate installation procedure, plaintext redirect/listener, downgrade guard, or live TLS regression. Spring Security would write HSTS on a secure request, but there currently is no supported secure request path and item 49 deliberately proves HSTS absent on HTTP.

Proposed remediation awaiting approval: supersede D4 and make deployed participant/admin transport HTTPS/WSS. Configure Spring Boot TLS from an external PKCS#12 keystore whose path/password come only from environment or ignored local configuration; do not commit a key.

Make the server's advertised LAN address and admin URL derive from the configured secure scheme. Change the client default and UI to use/persist a complete `https://host:port` origin, derive WSS from it, reject insecure non-loopback addresses by default, and never silently downgrade after a TLS failure.

Retain HTTP only behind an explicit development/test escape hatch for loopback, so a fresh source checkout can still run tests without possessing a deployment certificate while production fails closed if TLS material is missing.

For HTTP-to-HTTPS handling, use a separate plaintext connector only to redirect safe browser GET/HEAD requests to the configured HTTPS port; reject authentication/API/WebSocket and other unsafe plaintext traffic rather than relying on clients to follow a redirect after credentials may already have been sent.

Once the certificate/trust path is verified, emit HSTS on HTTPS only, without `includeSubDomains` or preload for a lab-local hostname. Add focused tests for full-scheme persistence, HTTPS-to-WSS mapping, non-loopback HTTP rejection, no downgrade, advertised secure URLs, HTTP redirect/rejection behavior, successful HTTPS login/API plus WSS push, certificate trust failure, and HSTS present only over HTTPS.

Item 51 will separately restrict TLS protocols/ciphers and define certificate issuance, trust distribution, renewal and expiry monitoring. No item-50 production or test change has been made pending approval.

<a id="audit-entry-43"></a>

## Security audit follow-up — item 50 approved HTTPS/WSS remediation (historical; subsequently removed)

Implemented the approved transport change and superseded D4. Normal startup requires direct TLS on the existing port 8080 with external ARBITRATOR_TLS_KEYSTORE/ARBITRATOR_TLS_PASSWORD PKCS#12 material. Admin URLs reflect the configured scheme, and the client address supports a configured certificate-covered HTTPS public origin.

Explicit dev-http/test mode forces a 127.0.0.1 bind and rejects remote plaintext peers. Direct socket-peer admin authorization/IP limits remain intact; forwarded-header trust is rejected. No item-50 database schema, API DTO, endpoint or STOMP destination changed.

The optional plaintext connector is disabled by default. When configured, it redirects only safe GET/HEAD requests to the small root/admin document allowlist, without auth/upgrade/body headers, to the fixed HTTPS public origin. Queries are dropped and Host is never trusted.

Plaintext auth/API/WS/unsafe requests receive 403 before application processing. HTTPS responses, including early rejections, carry HSTS max-age=31536000 without subdomains/preload; HTTP never carries it. Client addresses persist/display the full HTTPS origin and derive WSS.

Legacy scheme-less preferences and default-http external settings upgrade to HTTPS. Local HTTP needs the explicit client option; invalid input leaves the previous address intact and displays an error. Trust/hostname failures never trigger plaintext retries.

Focused verification passed 19 tests on 2026-09-23 at 23:42:36 +06:00. Final full `mvn test -Djavafx.platform=linux` passed 263 server + 5 client tests (268 total), zero failures/errors/skips, 2026-09-24 02:13:29 +06:00. The TLS integration test creates/removes a temporary test certificate and checks real HTTPS login/protected/admin APIs, advertised URLs, authenticated WSS verdict-event delivery, unknown-trust/wrong-host rejection and HTTP redirect/rejection/HSTS behavior.

The existing real judged-submission verdict test also passes in development transport. Only arbitrator_test was used by these item-50 integration tests; no deployment certificate or system trust was installed. No interactive JavaFX/browser acceptance pass is claimed.

Initial test failures and resolution: sandboxed temporary Java preference cleanup needed approved filesystem access; the first TLS harness used an incorrect admin route and an HTTP/2 upgrade request where its redirect assertion intended ordinary HTTP/1.1.

Correcting the harness made the focused run green. The first full run passed 262/263 server tests: the ended-contest fixture's global problem list selected another suite's still-active contest. That fixture now temporarily retires/restores earlier live fixture states; production contest-selection policy is unchanged.

The final full run also includes legacy external-config migration and passes all 268 tests.

README.md and docs/HTTPS_SETUP.md cover certificate-free local launch, per-installation keystore/browser/JVM trust, and optional redirect setup. Build/test needs no deployment key; LAN runtime requires certificate provisioning. Item 50 is complete; next concern: item 51 TLS protocol/cipher policy and certificate renewal/expiry monitoring. The earlier item-50 pending-approval section is historical.

<a id="audit-entry-44"></a>

## Security audit follow-up — item 50 removal requested by user

On 2026-09-24 the user explicitly requested removal of this feature and instructed: do not add anything requiring changes to how the project is built or run. Removed the TLS configuration/guard/redirect connector, dev-http profile, HTTPS client/default/address changes, custom HSTS writer, transport-specific tests and certificate setup guide.

Restored the original HTTP/WS launch commands, port 8080, LAN connectivity and host:port UI. Item-49 security headers and earlier security work remain; the independently justified ended-contest test isolation correction is retained. No deployment keys or system trust had been installed, so none require removal.

Historical item-50 implementation/test notes above describe the removed version, not current behavior. Verification follows after the clean regression run.

Post-removal verification: `mvn clean test -Djavafx.platform=linux` passed 257 server + 1 client tests (258 total), zero failures/errors/skips, at 2026-09-24 02:21:48 +06:00. Clean output contains no removed transport classes/profile. Client code, application/test configuration, launcher and server-info controller are restored to their original tracked versions; the retained header filter remains item 49. `git diff --check` passed.

## Open defects and risks

<a id="audit-entry-45"></a>

## Security audit follow-up — item 67 supply-chain security review

Verdict: the documented team workflow is stronger than the controls visible from this checkout/public GitHub view, but account and repository enforcement remain **unverified**. The live public [GitHub repository](https://github.com/EshadReza/Arbitrator) exposes `main` as default, one current public branch, no tags or published releases, and a public [Actions page](https://github.com/EshadReza/Arbitrator/actions) showing the setup screen rather than configured workflows/runs.

This checkout has no `.github/workflows`, GitLab/CircleCI/Jenkins/Azure pipeline file, or release-publishing job. `scripts/docker/build-sandbox-image.sh` builds a local judge image with `--load`, not a registry push; the current README likewise instructs local Maven/JAR/image builds.

No application artifact or Docker registry/deployment-token pipeline was found in the supported project workflow. This limits current CI/registry credential exposure but also means there is no automated build/release gate. The public GitHub copy predates the uncommitted security work in this local checkout; it is not evidence that those changes are released.

`rules.md` says `main` should be protected, feature work should merge through `dev` after another person's approval, and force pushes to `main`/`dev` are forbidden. That is a **written policy**, not proof that GitHub enforces it. The public [branches page](https://github.com/EshadReza/Arbitrator/branches) and [pull-request page](https://github.com/EshadReza/Arbitrator/pulls) show some historical PR use, but this signed-out browser session cannot view repository Settings, branch rulesets, collaborator permissions, account MFA, GitHub Actions token defaults, stored secrets, or any private deployment credentials. No claim is made that those protections are absent or enabled. GitHub supports enforced PR approvals/status checks/force-push restrictions through branch protection, and recommends least-privilege workflow tokens if Actions are used. [GitHub protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches), [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use).

Proposed limited next step, **pending user approval and authenticated repository-owner access**: inspect the actual GitHub ruleset/branch-protection and access settings; if `main` is not protected, enable a rule requiring a pull request and one *independent* approval, disallow force pushes/deletion, and decide explicitly whether administrators may bypass.

This changes the **Git collaboration workflow** (direct pushes to `main` may stop working) but not how the application is built or run. Confirm each collaborator uses MFA separately; the user/account owner must perform credential/MFA enrollment personally.

Since no CI or registry publishing exists now, do not introduce deployment secrets, registry accounts, release automation or a mandatory CI build merely to satisfy the checklist. If CI is later added, review workflow changes and default `GITHUB_TOKEN` to read-only, granting narrowly scoped permissions per job.

No remote setting, account, source, build, launch or deployment change was made in this review. Next checklist concern: item 68 secret scanning.

User disposition: move past this process-level concern and fix the next concrete application security issue. Item 67 remains unverified and unimplemented; items 68–73 are still open, not passed or silently cleared. Work proceeded to item 74 (API schema validation).

<a id="audit-entry-46"></a>

## Security audit follow-up — item 74 API schema validation

Confirmed gap: the server's `JacksonConfig` explicitly disabled `FAIL_ON_UNKNOWN_PROPERTIES` for every Spring JSON request DTO. A submission carrying `userId`, `score`, `verdict` and other unexpected fields previously returned 202; a registration or login carrying `isAdmin` was likewise parsed without error.

Existing service authorization and score-derivation checks ignored those fields, so this review does **not** establish privilege escalation or score manipulation. The security problem is that the API silently accepts client input outside its declared request schema, making misuse and future DTO changes harder to detect.

Approved by the user's request to fix the next actual application security concern: enable strict unknown-property rejection in the server's Spring Jackson mapper. Recognized fields and optional missing fields retain their previous behavior; the JavaFX client's separate mapper still tolerates new *response* fields from the server.

An old server will now reject a request field introduced by a newer client, so deploy any future server DTO change before the matching client. No API field, schema migration, build command, launch command, or client code changed.

Live HTTP regression coverage now asserts that a forged submission body returns 400 and creates no submission, while the same allowed `problemId`/`language`/`sourceCode` fields still produce a judged submission and preserve the existing score/admin-access checks.

It also asserts `isAdmin` on login/register returns 400, rejected registration creates no account, and an ordinary invalid-credentials login still reaches its prior 401 path. The older role-injection test now expects a 400 for an unexpected `role` field, confirms no account is created, then verifies normal registration still receives only STUDENT privileges.

Focused role/schema/score tests passed 8/8 with no skips using the existing isolated `arbitrator_test` schema and local Docker image.

Verification caveats: the first sandboxed focused run skipped all three tests because MySQL socket access was blocked; it is not counted as verification. The first full clean run exposed the older role-injection test's obsolete expectation that a forged `role` body would return 201; that assertion was corrected.

The next full run hit the existing shared-localhost registration rate limit (429) while creating a WebSocket fixture. The test profile now gives integration fixtures 300 registrations per IP per minute; **production remains at 30**, and the dedicated rate-limit attack test explicitly overrides the test threshold to 3 and still passes.

No production throttle was weakened. Final full run: 273 server + 1 client tests, zero failures/errors/skips, at 2026-09-24 23:27:39 +06. `git diff --check` passed.

<a id="audit-entry-47"></a>

## Security audit follow-up — item 75 integer validation review and approved first fix

Confirmed narrow input-validation gap in the instructor's problem-package ZIP import: `ProblemPackageService` reads `config.json` `timeLimitMs` and `memoryLimitKb` using Jackson `JsonNode.asInt(default)`, then applies the intended range checks only to the converted `int`.

Jackson 2.15.4's local source shows `NumericNode.asInt` calls `intValue`, `LongNode.intValue` casts `long` to `int`, and `DoubleNode.intValue` also casts to `int`. Therefore `timeLimitMs: 4294969296` (2^32 + 2000) becomes 2000 and passes, while `2000.9` becomes 2000; numeric strings such as `"2000"` are also coerced.

An explicit JSON `null` takes the default. This is a malformed-package acceptance/validation bypass, **not** evidence that a submitted program receives more than the enforced resource cap: the stored value is the converted, in-range result.

The route is ADMIN+loopback. Existing tests cover ordinary over-limit integers but not these representations.

The user approved this narrow first fix. An absent field still uses its documented default. A present field must now be an integral JSON number representable as a Java `int` and within the existing 100–30,000 ms or 1,024–1,048,576 KiB range. Negative/zero, fractional, huge, quoted, boolean and null values produce package-validation errors and cannot be persisted. No API field, resource bound, schema migration, build/run step or client behavior changed. Other item-75 numeric request fields and arithmetic were reviewed separately below.

Three new focused tests cover missing defaults and exact minimum/maximum values, plus invalid time and memory values across each conversion category. The full ZIP-import class passed 31/31 without skips. The first attempted full-suite command was delayed by the automatic permission review for local MySQL/Docker access; one retry ran normally.

Final full suite passed 276 server + 1 client tests, zero failures/errors/skips, at 2026-09-25 00:23:28 +06:00 using the isolated test schema and local judge image. `git diff --check` passed. This verifies package import and existing regression behavior, not every remaining item-75 numeric endpoint.

<a id="audit-entry-48"></a>

## Security audit follow-up — item 75 contest-duration review (not approved)

Confirmed admin-side integrity gap: `AdminContestController.create` accepts an `int durationMinutes` and passes it to `ContestService.create`, which persists it without a positive-value check. The browser's `newDur` number input also has no `min`. `Contest.endTime()` adds the signed duration to the start instant, so a zero-minute contest ends at its start and a negative-minute contest has a deadline before its start; `stateOf()`/the expiry sweep can then end it immediately, and submission admission rejects it.

The admin API is ADMIN+loopback, so this is not a student authorization bypass. The `int` type rejects values beyond its range, and `endTime()` multiplies by `60L`, avoiding that particular multiplication overflow. `extend` currently adds two `int`s before checking the total; positive overflow becomes negative and is rejected, but the arithmetic should be made explicit and bounded when restoring the positive-duration invariant.

Proposed narrow fix, **not approved**: reject initial contest duration values below 1 in `ContestService.create`, calculate `extend` totals in `long` and reject totals outside 1..`Integer.MAX_VALUE`, and add a browser `min="1" step="1"` hint.

Negative `extend` deltas would remain allowed for shortening a contest; only a resulting total below 1 minute would be rejected. The user declined because negative values are needed to reduce contest time, then explicitly said to skip this concern.

No contest-duration code, test, UI, database, build or run change was made. Do not implement this proposal without renewed approval. Scheduling and other item-75 numeric paths remain separate reviews.

<a id="audit-entry-49"></a>

## Security audit follow-up — item 75 manual-penalty arithmetic fix

The source review confirmed an overflow risk in an ADMIN+loopback-only scoring control. Before this fix, `AdminStandingsController.adjustPenalty` performed `latest.getManualPenaltyDelta() + delta` in signed 32-bit `int` and saved it without an overflow check.

A stored delta of `Integer.MAX_VALUE` followed by `delta=1` wrapped to `Integer.MIN_VALUE`, reversing the intended direction. Separately, `LeaderboardService.Tally.manualDelta` is still an `int` and adds each submission's valid stored delta with `+=`; two sufficiently large values for one problem can wrap during leaderboard aggregation even if each controller call fits.

The final per-user penalty is calculated in `long` but receives this already-overflowed tally and clamps negative results to zero, so standings can be wrong. The browser prompt's `parseInt` is not a security boundary. Negative adjustments are intentional and must remain supported.

This is a privileged grading-integrity problem, not evidence that a student can call the admin route.

The user approved the fix and owner coordination. The Eshad-owned controller now computes the stored result in `long`, returns HTTP 400 without saving if it falls outside the signed `int` persistence range, and retains ordinary positive and negative adjustments. `AdminPenaltyOverflowTest` checks positive and negative overflow, no save on rejection, and valid `+10`/`-5` corrections; focused 3/3 passed.

An additional live HTTP regression in `AuditLoggingIntegrationTest` verifies that `Integer.MAX_VALUE` is accepted, a subsequent `+1` is rejected with 400 without a stored change or committed-change audit entry, and `-5` is accepted. The final focused integration run passed 7/7 on 2026-09-25 at 02:06:58 +06.

Its first unsandboxed attempt could not access local MySQL; the first connected attempt found a missing required `statement_html` in the new fixture. Both were corrected before the passing focused run. The full post-change suite passed 280 server + 1 client tests, zero failures/errors/skips, at 2026-09-25 02:08:17 +06.

No schema, UI, build or run change was made.

The remaining leaderboard gap is now fixed. The user explicitly instructed us to ignore the named component-owner restrictions and complete the requested change directly; no GitHub owner-handoff issue was created. `LeaderboardService.Tally.manualDelta` now accumulates in `long`, so two individually valid `Integer.MAX_VALUE` adjustments cannot wrap before the row penalty is calculated. `LeaderboardServiceTest.manualAdjustmentsAccumulateWithoutOverflow` checks the >32-bit positive sum and then a negative correction.

Its focused class passed 11/11 at 2026-09-25 02:13:50 +06. The final full suite passed 281 server + 1 client tests, zero failures/errors/skips, at 02:15:15 +06. No schema, API, UI, build or run step changed. Other item-75 numeric paths remain separate reviews.

<a id="audit-entry-50"></a>

## Security audit follow-up — item 75 problem-ID request validation

Review of the remaining item-75 ID path found that `SubmitRequest.problemId` and `CustomRunRequest.problemId` are Java `long` fields and the server's Jackson mapper enables its default float-to-integer and numeric-string coercions. Thus a fractional or quoted JSON `problemId` can be bound to a real whole-number ID, even though the request is malformed.

Downstream problem lookup and contest-access checks still run; no authorization bypass was demonstrated. Negative/zero IDs do not match persisted positive IDs, and huge out-of-range integers fail binding. Verdict: low-severity input-shape integrity gap on the two student code-execution requests.

After approval, `SubmissionController` validates the raw JSON `problemId` before converting to the same shared request DTO: it must be an integral JSON number, fit a Java `long`, and be positive. Both submission and custom-run routes share this check.

Conversion still uses the configured server `ObjectMapper`, preserving strict unknown-field rejection and the other DTO behavior; invalid conversion maps to HTTP 400. The client continues sending ordinary JSON numeric IDs. Contest-time and other numeric API inputs, including negative duration adjustments, are unchanged.

No shared DTO, schema, API field, UI, build or run-step change was made.

The live `ScoreManipulationAttackIntegrationTest` now sends fractional and quoted *existing* IDs to both routes and zero, negative, huge and null IDs to custom run; all return 400 with no submission persisted. It also retains the valid submission and forged-score-field checks.

The first focused run hit the unchanged six-submissions-per-account API limit before the existing forged-field assertion; the test was reduced to two submission probes while keeping all six custom-run probes, with no production rate change.

Final focused score/contest-access/schema run passed 9/9, zero failures/errors/skips, at 2026-09-25 02:35:02 +06. The full suite passed 281 server + 1 client tests, zero failures/errors/skips, at 02:36:25 +06. `git diff --check` passed. Other ID-bearing request DTOs and path/query IDs have not been exhaustively audited; this closes only the approved submission/custom-run scope.

<a id="audit-entry-51"></a>

## Security audit follow-up — item 76 Unicode handling fix

Student usernames/login identifiers are already constrained server-side to ASCII letters, digits, dot, underscore and hyphen, so Unicode lookalikes cannot become alternate login IDs. Student-controlled display names remain Unicode-friendly and are limited to 128 code points.

Before this fix, `UserService.register` rejected only `Character.isISOControl`; Java returns false for bidirectional override U+202E and zero-width space U+200B, both Unicode format characters. These therefore passed the old display-name check.

The JavaFX registration screen repeated the same control-only check. Display names appear in instructor notifications and standings, often adjacent to the trusted ASCII username. Admin HTML inserts names as text/escapes markup, and JavaFX uses text controls, so this was not an established XSS or authorization bypass.

Verdict: a lower-severity visual-spoofing/operational-confusion gap for names chosen by students; instructor-controlled contest/problem text and full Unicode behavior remain separate questions.

The user approved the narrow fix. `UserService.register` now NFC-normalizes each *new* display name and rejects ISO controls, malformed surrogate code points, Unicode line/paragraph separators, explicit bidirectional formatting controls (Arabic letter mark, left/right marks, U+202A–U+202E, U+2066–U+206F), and selected invisible formatting characters (soft hyphen, zero-width space, word joiner/invisible operators and BOM).

It retains ordinary multilingual letters, combining marks, emoji and script-shaping ZWJ/ZWNJ. The JavaFX registration form mirrors the check for immediate feedback; its message was updated. The server remains authoritative. Existing stored names were not rewritten; usernames, contest/problem text, APIs, schemas, build and run steps were unchanged.

`UserServiceTest` now checks nine deceptive/malformed names are rejected before persistence and a decomposed multilingual/emoji/ZWNJ name is normalized and accepted. Live `RoleEscalationIntegrationTest` verifies a bidi-spoofing display name receives HTTP 400 without creating an account while an ordinary multilingual name receives 201 and is stored/returned in NFC. `LeaderboardServiceTest` verifies a normal Unicode name and trusted ASCII username both appear in standings. Focused classes passed 59/59, zero failures/errors/skips, at 2026-09-25 02:44:58 +06. The final full suite passed 293 server + 1 client tests, zero failures/errors/skips, at 02:46:46 +06. This targeted policy reduces common visual spoofing, but is not a universal detector of all visually confusable Unicode characters; the trusted ASCII username should remain visible beside display names.

<a id="audit-entry-52"></a>

## Security audit follow-up — item 77 username allowlist review

Verdict: already implemented; no new fix needed for this checklist item. `UserService.register` trims a proposed student ID, then requires the exact server-side pattern `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` before checking uniqueness or saving.

This excludes Unicode lookalikes, control/zero-width characters, spaces, markup punctuation, slashes, leading punctuation, and IDs longer than 64 characters. The database also has a unique username key. Login resolves the stored user and uses that canonical stored username for subsequent session/throttle identity, so a collation-equivalent submitted spelling is not made into a new principal.

Admin usernames are provisioned server-side, not through student registration. Display names remain separate presentation data and were treated under item 76. Existing `UserServiceTest` covers hostile IDs and trim-before-storage; the 2026-09-25 02:46:46 +06 full suite passed 293 server + 1 client tests with no failures/errors/skips.

No item-77 source, schema, UI, build or run change was made. Next checklist concern: item 78 mass assignment.

<a id="audit-entry-53"></a>

## Security audit follow-up — item 78 mass-assignment review

Verdict: no mass-assignment path found in the current request controllers. Student registration binds the fixed `LoginRequest` DTO, then explicitly sets username/display name/password hash and `Role.STUDENT` on a new `User`; client JSON cannot bind a `User` entity or role.

Student submission binds the fixed `SubmitRequest` after the item-75 raw-ID check and derives user, contest, verdict, resource metrics and timestamps server-side. Custom run has its own fixed DTO; clarification accepts only question, optional scope and intended public/private choice, then derives the asker from the authenticated principal and checks contest access.

Admin problem edit/clone/communication routes use narrow request records and are role/loopback protected. The server mapper rejects unknown JSON properties from item 74, and the item-39 live forged-score/role tests demonstrate 400/no side effects for representative unexpected privileged fields.

An intentional login-only `force` field in the shared login/register DTO is not used by registration to set privileges. This source review is not a claim that every future DTO is safe; new write APIs should keep explicit allowlists. No item-78 fix, new test, schema, UI, build or run change was made.

Next checklist concern: item 79 HTTP method restrictions.

<a id="audit-entry-54"></a>

## Security audit follow-up — item 79 HTTP method restrictions

Source review found specific GET/POST mappings and intentional ADMIN+loopback PUT (problem edit) and DELETE (contest/material/problem/notification deletion) routes, not a blanket controller method mapping. The user approved a dedicated live attack check before proposing any change. `HttpMethodRestrictionIntegrationTest` starts the real embedded server against the isolated `arbitrator_test` schema, uses disposable student/admin sessions and a disposable contest, and sends POST/PUT/PATCH/TRACE/TRACK to a DELETE-only admin contest route.

It also tries `X-HTTP-Method-Override: DELETE` and form `_method=DELETE` on a wrong-method POST, student DELETE on the admin contest, student PUT on admin problem edit, and GET/PUT/PATCH/DELETE/TRACE on the submission creation route. The test requires 4xx rejection, preserved contest state, role denial, and no bearer-token echo.

The focused test passed 1/1 with zero failures/errors/skips at 2026-09-25 02:56:02 +06. One subsequent full-suite attempt hit a Docker container-start error in an existing fork-bomb test; that test passed 1/1 on isolated rerun, and the complete suite then passed 294 server + 1 client tests with no failures/errors/skips at 03:00:00 +06.

No unexpected method or override bypass was reproduced, so no production method-blocking change is proposed. Intentional admin PUT/DELETE operations must remain available. This does not test every proxy or reverse-proxy method normalization behavior; the deployed proxy, if any, should be checked separately.

No production API, build or run step changed. Next checklist concern: item 80 request-size limits.

<a id="audit-entry-55"></a>

## Security audit follow-up — item 80 request-size limits

Verdict: important limits were already present, but the 1 MiB JSON gate had a real media-type bypass. `JsonBodyLimitFilter` previously applied only when Content-Type began with `application/json`; Spring's Jackson converter also accepts `application/*+json`.

A client could therefore send an oversized `application/problem+json` or vendor `+json` body past the early bounded read to Jackson. The pre-fix regression test reproduced this: oversized structured-JSON requests reached the downstream mock filter chain instead of returning 413, both with a declared and an unknown body length.

This was a memory/resource-exhaustion exposure, not an authorization or data-access bypass.

After user approval, the filter now parses the media type and applies the same 1 MiB cap to `application/json` and all `application/*+json` subtypes. It still rejects declared oversize bodies before reading and reads at most cap+1 bytes when length is absent; malformed/other media types continue through normal Spring handling.

Focused unit and live-server tests cover standard and structured JSON, vendor JSON, chunked/unknown-length bodies, exact-limit acceptance and cap+1 rejection. Fixed-length and chunked live requests both returned HTTP 413. The service-side general-material cap and its error message were aligned to the existing 64 MB Spring multipart file/request cap, without raising or lowering the effective upload allowance; a focused test verifies a cap+1 file is rejected before persistence.

The focused three-class run passed 14/14, zero failures/errors/skips, at 2026-09-25 03:10:06 +06. The full suite then passed 299 server + 1 client tests, zero failures/errors/skips, at 03:11:35 +06. This supersedes the material-cap mismatch noted in the older item-44 review.

No new dependency, schema, API format, build or run step was introduced. There is no reverse proxy in the shipped deployment to configure; if one is added, its limits must be set separately. Next checklist concern: item 81 database backup security.

<a id="audit-entry-56"></a>

## Security audit follow-up — item 81 database backup security (user-deferred)

Review verdict: no current database dump or backup file was found in the project or Spring's static web root, and the repository has no implemented database-plus-material backup workflow. This does not establish how any external/manual backup is stored or protected.

MySQL contains accounts, submissions, private tests and contest content; uploaded material bytes live separately under the configured material root, so a database-only copy is incomplete. The legacy `scripts/make-bundle.py` walks the project tree and excludes `.git`/`target` but not `arbitrator-data`, future backup directories or dump files; if used after such data were placed under the project, it could copy sensitive bytes into a shareable text bundle. `.gitignore` excludes `arbitrator-data` but not conventional dump filenames.

This is a latent accidental-disclosure path, not evidence that a backup has already leaked.

Proposed protections were to exclude runtime data/backups from bundling and Git, and document a restricted, encrypted database-plus-material backup procedure outside the web root without changing normal build/run commands. The user chose to **skip item 81 for now**.

None of those protections, encryption, backup automation or a restore operation was implemented or verified in this item; do not describe backups as secure. Next checklist concern: item 82 restore testing. Because there is no defined backup artifact/workflow yet, an end-to-end production-backup restore cannot be claimed until one exists.

<a id="audit-entry-57"></a>

## Security audit follow-up — item 82 restore testing (user-deferred)

The user chose to skip directly to item 83 after deferring item 81. There is still no defined database-plus-material backup artifact or restore procedure in this repository, so no backup was restored into a clean environment and no recovery integrity check was performed.

Existing isolated MySQL integration tests and startup migrations are not a substitute for a backup/restore rehearsal. Revisit this item when a secure backup workflow exists; do not claim recoverability from a backup until a clean restore has been verified.

No production code, backup data, database state, build/run command or test was changed for item 82.

<a id="audit-entry-58"></a>

## Security audit follow-up — item 83 disaster recovery documentation

Initial verdict: partial automatic restart recovery existed, but no complete incident runbook or data-loss recovery path was documented. The server persists submissions before queueing and `JudgeQueue.recoverUnfinished` requeues non-DONE submissions on application startup. `SandboxExecutor.verifyDockerReady` reports unavailable Docker/image as a judge error and sweeps stale sandbox containers/work files; the run path checks judge work-disk headroom before starting another container. `ContestBootReset` leaves live contests running by default across restart (or optionally resets them to DRAFT), ending one whose deadline passed offline; the default ephemeral JWT signing key and in-memory sessions require users to log in again.

These mechanisms do not restore a lost MySQL database or uploaded material files. The project has no Redis dependency; TLS certificates were explicitly removed from the supported deployment at the user's request, so those checklist examples are not currently applicable.

A bad application deployment also cannot safely be assumed reversible after a Flyway schema migration.

After user approval, added [INCIDENT_RECOVERY.md](recovery.md) and linked it from README.md. The current-host runbook covers safe first-response/read-only checks, MySQL outage versus loss, Docker/judge failure, disk exhaustion, failed deployment/Flyway rollback caution, restart validation, contest-clock fairness and already-completed infrastructure-error verdicts.

It explicitly warns that `init-db.sql` changes the example DB-account password, `reset-dev-data.sql` deletes submissions, an empty DB can be silently created/seeded with demo users, and database-only recovery omits material files. It does not automate backup, restore, rollback, monitoring, or any new build/start step.

No incident drill was run, so recovery is **documented but not validated**. True data-loss recovery still requires the user-deferred items 81–82 and an isolated rehearsal. Next checklist concern: item 84 monitoring.

<a id="audit-entry-59"></a>

## Security audit follow-up — item 84 operational monitoring

Initial verdict: the instructor's existing monitor showed participants, presence and submissions, not host/service health. `JudgeQueue.depth()` existed internally; Docker readiness and work-disk reserve failures were logged, while HTTP 5xx and login bursts appeared in logs/audit history. No consolidated CPU/RAM/disk, database-pool, judge-queue or recent-failure view existed. The repository has no Actuator/Prometheus service or external uptime/alerting dependency. Redis is not used.

After user approval, added a read-only `GET /api/admin/operations` endpoint under the existing ADMIN JWT and loopback gates plus a text-only operations card on the dashboard. The snapshot reports host CPU load (when available), host RAM, JVM heap, free/total space for judge-work and material filesystems, judge queue depth, and Hikari active/idle/waiting/total connections.

A fixed 60-second, process-local ring holds only totals for application HTTP 5xx, failed `/api/auth/login` responses (401/429), internal judge failures, and queue-wait average/max for submissions that started judging. It stores no usernames, tokens, source, request bodies, paths or per-submission IDs; rendering uses `textContent`.

A failed polling request clears stale values. No new port, external service, schema, build or start step is required.

Limits: these are local snapshots, not independent alerts or an uptime check; if this server or its database-backed admin authentication is unavailable, the dashboard may also be unavailable. CPU can be unreported on unsupported hosts. DB-pool occupancy is not a database reachability guarantee.

Queue wait is admission-to-worker-start, not end-to-end verdict latency. The judge-failure counter covers worker-recognized internal outcomes, not every contestant `RE` or every possible abrupt worker/process death. Network-traffic monitoring and external alert delivery remain outside this change and belong to later checklist items.

Focused 17/17 tests passed without failures/errors/skips on 2026-09-26 at 00:10:40 +06, including unauthorized/student denial, admin snapshot/no-token exposure, counter expiry, judge behavior and static text-only rendering. Inline admin JavaScript also passed a syntax parse.

The final-source full Maven rerun passed 303 server + 1 client tests, zero failures/errors/skips, at 00:17:30 +06. Next checklist concern: item 85 security alerts.

<a id="audit-entry-60"></a>

## Security audit follow-up — item 85 security alerts (user-deferred)

Verdict: partial detection and prevention exist, but no dedicated security-alert delivery. `LoginThrottle` rate-limits attempts and logs cooldown bursts without identifiers. Item 84's ADMIN + loopback panel shows one-minute HTTP 5xx, failed-login and internal-judge-failure totals plus disk space, but someone must be watching it. `SandboxExecutor` refuses new runs when the judge work disk is below its reserve.

The existing in-app notification feed covers participant disconnect/reconnect and reported MAC changes, not security events. Student registration always assigns STUDENT; the only application-created ADMIN account is the first-boot demo seed.

No application path was found for creating additional admins, but direct database changes or a compromised host are outside that guard. There is no submission-rate spike detector, dedicated high-5xx alert, low-disk alert, reliable sandbox-escape detector, or out-of-process delivery/uptime channel.

This review did not run an attack test, so it does not claim an exploit or a measured alert latency.

Deferred proposal: add a bounded, deduplicated security-alert feed to the existing ADMIN + loopback dashboard and WARN logs, using measured thresholds for repeated failed logins, sustained 5xx/judge failures, low judge/material disk space, and unusually high submission rate only after a minimum absolute count to avoid low-baseline false alarms.

Observe newly appearing ADMIN users after a startup baseline and flag them for manual verification; do not treat the intentional first-boot seed as suspicious. Keep counts and alert text free of passwords, tokens, source code and submitted identity strings, with tests for triggering, cooldown/deduplication, authorization, restart behavior and normal contest traffic.

This could use the current application and build/start workflow, but in-process alerts would reset on restart and could not notify anyone if the app is down or no instructor is watching. A true sandbox escape or host compromise needs independent host/container monitoring and an external alert destination; this proposal does not claim to detect those.

User decision: document and skip item 85 for now. No item-85 alert implementation, production code, schema, runtime dependency, launch change or tests were made. The missing alerting remains an open risk; item 84 monitoring does not close it. Next checklist concern: item 86 health checks.

<a id="audit-entry-61"></a>

## Security audit follow-up — item 86 health checks (implemented)

Verdict: missing separate health/readiness checks. No Actuator dependency or `/health`, `/ready`, or equivalent application route was found. A successful homepage response only proves HTTP serving. `SandboxExecutor.verifyDockerReady` checks Docker and the judge image on startup, but logs failure and allows the web server to continue; judging then returns judge-error RE rather than using an unsafe host fallback. `JudgeQueue.depth()` and the item-84 operations snapshot expose queue and pool observations, not a tested ready/not-ready decision.

The ADMIN + loopback operations API also depends on database-backed JWT role validation, so it is a poor independent DB-outage probe. This is a source review, not a live failure-injection test.

After user approval, added unauthenticated-but-**loopback-only** `GET /admin/health/live` and `GET /admin/health/ready` on the existing port. The direct-peer `LoopbackAdminFilter` denies remote access, including before the ready check can run.

The exact health routes bypass JWT role lookup so a database outage does not also break liveness. Responses use `Cache-Control: no-store` and expose only `UP`/`DOWN` component states; readiness returns HTTP 503 unless database, queue acceptance and judge are all up.

Database readiness opens a real connection and calls `isValid(1)` through a single bounded probe worker with a 2-second HTTP wait; stalled acquisition cannot pile up unbounded probe threads. Queue readiness checks executor availability and free backlog capacity.

Judge readiness checks the worker executor, the pinned Docker image/daemon with two read-only CLI calls each limited to 2 seconds, and the same 1 GiB work-disk reserve required before sandbox execution. No submission container is launched by a health request, and no new service, dependency, schema, port or build/start step is needed.

Limits: liveness says only this HTTP process responded. A readiness `UP` is a point-in-time connectivity/capacity check, not proof that a submitted program, checker or an individual long-running worker will complete. Full queue capacity can legitimately make readiness `NOT_READY` during load; Docker or database slowness may also give a transient 503.

The self-hosted routes cannot detect a stopped server or send alerts; independent polling and item-85 alert delivery remain deferred. Focused 28/28 tests passed after the worker refinement on 2026-09-26 at 00:37:28 +06; the final full `mvn -B test -Djavafx.platform=linux` run passed 309 server + 1 client tests, zero failures/errors/skips, at 00:39:42 +06.

Tests cover simulated component failure/recovery and DB timeouts, real local HTTP responses, simulated non-loopback denial, JWT bypass, queue capacity/shutdown, and Docker pin/readiness. Next checklist concern: item 87 reverse proxy/WAF.

<a id="audit-entry-62"></a>

## Security audit follow-up — item 87 reverse proxy/WAF (user-deferred)

Verdict: no reverse proxy or WAF is part of the supported direct HTTP/WS LAN deployment; no nginx/HAProxy/Caddy/compose/service configuration exists in the repository. Their absence alone is not a demonstrated application bypass. Existing app-layer controls include a 1 MiB JSON cap (including `+json`), a 64 MB multipart cap, bounded API/login rate limits, security headers, and untrusted-forwarding-header rejection (`server.forward-headers-strategy: none`).

They do **not** amount to upstream connection limits, network-level DDoS protection or TLS. The HTTP API limiter does not cover static admin assets or WebSocket/STOMP traffic; no reverse-proxy behavior or slow-connection stress test was performed here.

TLS was expressly removed by the user to preserve the current build/run workflow; item 85 external alerts are also deferred.

Adding a same-host proxy without redesign would be unsafe: `LoopbackAdminFilter` and IP rate limits trust the direct socket peer. With the proxy connecting to Spring on loopback, remote requests forwarded to `/admin/**` could pass the current local-only gate (the admin API would still require ADMIN JWT, but the static console and unauthenticated local health routes could become remote-visible), and every remote client would share the proxy's IP budget.

Trusting arbitrary `X-Forwarded-For` instead would permit spoofing. A correct proxy deployment would need edge-only routing rules that block admin/health from remote clients, a tightly defined trusted-proxy address/IP policy, TLS/certificate operations, WebSocket upgrade support, compatible request/body/timeout limits, and end-to-end tests; it would add infrastructure and alter the user's stated one-click deployment model.

User decision: move on without installing a proxy/WAF. Keep the direct-peer trust model and treat proxy/WAF as conditional/deferred unless the deployment becomes internet-facing or the user explicitly accepts a new proxy/TLS operational workflow. A WAF would be additional defense, not a substitute for the existing app controls. No item-87 production code, proxy config, service, build/run change or test was made. Next checklist concern: item 88 DDoS planning.

<a id="audit-entry-63"></a>

## Security audit follow-up — item 88 DDoS planning (user-deferred)

Verdict: the supported application is offline/LAN-first, not a documented internet-facing service, so the checklist's upstream internet DDoS/CDN requirement is conditional. The repository cannot establish whether an actual host firewall/router exposes port 8080 to the internet; that deployment boundary was not inspected.

Existing per-IP/account API and login budgets, submission cooldowns, bounded judge queue/container/memory admission, and item-86 health checks reduce some application-resource exhaustion but do not stop a volumetric network flood. The API limiter is process-local, resets on restart, and does not cover static resources or WebSocket/STOMP traffic; no explicit server connection/slow-client limits or sustained hostile-traffic test was found in project configuration.

This is a design/source review, not a claim that a specific DDoS attack succeeded.

Deferred proposal: document a no-public-port-forwarding deployment rule and a small LAN denial-of-service response checklist that uses the existing health/operations views and incident runbook; then run bounded, non-destructive local stress tests against static, login/API and authenticated WebSocket paths to see whether a realistic lab client can exhaust connections or workers.

Propose code-level connection/WebSocket limits only if the test demonstrates a gap, with normal multi-student traffic as a regression baseline. Do not add an upstream proxy/CDN or extra build/start step under the user's current constraint. If the service must later be internet-facing, require a separately approved upstream DDoS provider/firewall/proxy design and test the item-87 loopback/IP-identity consequences before exposure; an in-app limiter cannot replace that upstream protection.

User decision: declined the proposed tests/documentation and requested the next concern. No item-88 production code, load test, network change or deployment-plan addition was made; this review record is retained, and the untested availability boundaries remain open. Next checklist concern: item 89 leaderboard integrity.

<a id="audit-entry-64"></a>

## Security audit follow-up — item 89 leaderboard integrity (user-deferred)

Verdict: core scores are derived anew from active submission history, not a client-supplied or incrementally maintained score total. Each user/problem tallies only its first AC, later ACs do not add solves, CE does not add rejection penalty, and server admission time controls the stored scoring timestamp.

Existing item-39/74 score-forgery and item-35 timing tests cover representative student manipulation; item-75 checks protect penalty arithmetic while keeping negative instructor adjustments. Admin scoring routes remain ADMIN + loopback and application changes have item-58 audit history.

There is no rejudge feature; automatic recovery judges unfinished rows rather than creating extra scoring rows. Direct SQL/host tampering with authoritative history is not prevented or independently detected by the same-database audit and remains subject to the item-60 deferral.

Source-level integrity gap found: `JudgeWorker.judge` holds a Submission object across Docker execution and later saves it again. AdminStandingsController also reads/changes/saves Submission objects for marks, verdicts and penalties. Submission has neither an optimistic-lock version nor field-specific persistence in these paths, so an older worker/admin copy can overwrite another writer's marks, penalty or verdict; two simultaneous read/add/save penalty adjustments can lose an increment.

Verdict override also accepts a PENDING/JUDGING row, while leaderboard calculation checks non-null verdict rather than DONE status, so an instructor can temporarily score a still-running submission and the worker can subsequently replace that override.

These are instructor/judge concurrency and grading-integrity risks, not a proven student authorization bypass. No dedicated race reproduction exists yet; the previous full 309 server + 1 client run was green but did not test these interleavings.

Existing focused regressions were rerun: LeaderboardServiceTest, ContestTimingAttackTest, ScoreManipulationAttackIntegrationTest, AdminPenaltyOverflowTest and SubmissionCooldownConcurrencyTest passed 30/30, zero failures/errors/skips, on 2026-09-26 at 11:10:58 +06 against isolated `arbitrator_test`.

These prove the covered scoring/input/timing/cooldown behaviors, not the newly identified admin/judge write races. The initial permission review timed out without executing the command; the permitted single retry completed successfully. No full suite or new race test was run for this review.

Deferred proposal: first add controlled race reproductions with a paused judge and concurrent instructor adjustments, then fix the demonstrated lost-update paths with transactional, field-owned writes and atomic bounded penalty arithmetic. Avoid holding a database lock for the duration of Docker execution.

Allow manual verdict override only after the row is DONE, with a clear conflict response while judging; preserve normal completed overrides, marks, negative penalty corrections, audit records and verdict/leaderboard updates. Add repeatable-history and ordinary scoring regressions alongside the race tests.

No new build/start step or external service would be needed.

User decision: leave the behavior as-is and move on. No item-89 production fix or new test source was added. The source-level admin/judge lost-update and unfinished-override risks remain open, not disproved by the 30 existing green regressions. Next checklist concern: item 90 plagiarism/security distinction.

<a id="audit-entry-65"></a>

## Security audit follow-up — item 90 plagiarism/security distinction (user-deferred)

Verdict: no automated plagiarism verdict or automatic punishment based on code similarity, timing or reported device data was found. There is no cross-user source-similarity engine or suspicious-timing detector. The same-user/same-problem exact duplicate check only prevents redundant resubmission; it does not detect cross-user copying.

Single-active-session enforcement limits simultaneous sessions, not identity sharing. Optional client-reported MAC changes create instructor notifications and a participant-table indicator; they do not alter scores or disable accounts. Source API bans produce ordinary CE for the judge's execution policy and are not plagiarism determinations; their separately documented raw-regex false-positive risk is unchanged.

One small presentation issue: the current notification says "signed in from a different device (MAC changed)", although UserService stores a MAC supplied by the login client and cannot establish hardware identity from it. A changed or spoofed report is not proof of another device, account sharing or cheating.

Proposed optional, wording-only fix: label MAC data as client-reported and change the notification/indicator guidance to say the client reported a changed MAC, requiring instructor verification rather than asserting device identity. Retain the existing notification behavior and no automatic sanctions; do not add a plagiarism engine as a security fix.

User requested "Move to 91" instead of approving the wording change; item 90 is deferred. No item-90 production/UI change or new test was made. Next checklist concern: item 91 contest confidentiality.

<a id="audit-entry-66"></a>

## Security audit follow-up — item 91 contest confidentiality (reviewed; user kept existing exception deferred)

Verdict: normal pre-start problem access is protected at the server, not just hidden by the client. ContestAccessService requires a persisted user/contest grant and released contest state for student statement/detail/PDF, source/results, custom-run, scored-submit and problem-attempt access.

DRAFT/LOBBY do not release problems; the student problem list and leaderboard contain no problem codes/rows in LOBBY. Guessed IDs do not bypass these checks. Instructor test-case routes remain under ADMIN JWT + direct-loopback protection; student ProblemDetailDto has no checker source or test data.

No separate published model-solution feature was found. Problem statements/tests/checker source are stored in the database, not frontend static assets; the static resource inventory contains only the admin UI/logo/fonts, with no contest sitemap or embedded contest package.

Material files are stored outside the static resource tree and served through grant-gated downloads, not a public storage URL. These are current repository/default-deployment findings, not a check of an independently configured web server, CDN or host filesystem.

Announcements have no saved-draft state: the instructor publish action saves and broadcasts them immediately. Announcement/material/general-clarification visibility in LOBBY is intentional holding-room policy already recorded under item 38, not a new leak to fix silently.

Do not place confidential statements/solutions in a published announcement or uploaded lobby material. Clarification list filtering excludes other students' private and unapproved public messages; broadcasts carry a refresh signal rather than private question/answer content.

Contest-state pushes contain lifecycle/clock data, and leaderboard pushes use the same pre-release empty student view. A full broadcast/content matrix for every topic was not newly exercised.

Known remaining exception: item 38's user-deferred own-history metadata gap is still present. A synthetic historical submission inserted before release exposes its problem code through `/api/submissions/mine?contestId=...`; normal submission admission cannot create that row in LOBBY.

The `all=true` history path would also need release-aware filtering if this is revisited. No statement, source, tests or another user's history is exposed by that reproduction. Do not count this as a new issue or silently override its prior deferral.

Optional limited fix, only if approved: omit unreleased-contest rows from both student history modes while preserving released/ended history and legitimate admin access, with dedicated regressions. No new build/run step is required. Announcement drafts would be a separately requested feature rather than a required repair of an existing draft mechanism.

Verification: reran existing ContestAccessIntegrationTest and RoleEscalationIntegrationTest against isolated `arbitrator_test` on 2026-09-26, finished 11:19:00 +06: 12/12 passed, zero failures/errors/skips. The direct HTTP LOBBY matrix verifies denial of statement/PDF/run/submit/attempts/source/results and empty list/board; the existing WebSocket test verifies contest-grant subscription enforcement.

The history assertion deliberately characterizes the known exception, so a green result does not mean that gap is closed. Role tests verify the covered forged-role/session-change boundaries, not every admin data route. No new test source or production code was added.

Cache policy and externally configured shared caches remain the next dedicated review, item 92; this review does not certify cache/CDN behavior or absence of all possible leaks.

User decision: "move to 92" rather than approve the own-history fix. Keep the existing item-38 exception deferred and leave lobby communication policy unchanged.

<a id="audit-entry-67"></a>

## Security audit follow-up — item 92 cache security (implemented after approval)

Verdict: no demonstrated public caching of private data in the supported direct-server deployment. SecurityConfig retains Spring Security's default cache prevention. The locally installed 6.2.4 HeadersConfigurer/CacheControlHeadersWriter sources confirm the default `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache`, `Expires: 0` writer; it yields to already supplied cache headers.

Repository search found no private controller emitting public caching, HTTP response-cache service, service worker, CDN/proxy cache configuration, or custom resource mapping of private storage. Explicit rate-limit/health/conflict responses use no-store.

Admin credentials remain in memory; only the theme is in localStorage, and sign-out reloads to discard sensitive DOM and pending polls. The JavaFX HTTP adapter uses Java HttpClient without an added response cache. This does not erase intentionally downloaded files, prevent malicious caches from ignoring policy, or certify arbitrary external hosting/CDN configurations (item 93).

Verification-only changes: added no-store/no-public assertions to existing ContestAccessIntegrationTest status checks and SecurityHeadersIntegrationTest's ordinary response policy checks; login/registration helpers now retain ResponseEntity so identity/token response headers are checked too.

Live checks cover successful student problem detail, own source/results/history and lobby communications, admin-readable pre-start detail, login/registration, rejected release/permission routes, admin static HTML/logo, unauthenticated API and ordinary missing-resource responses.

Not every endpoint/status was individually checked, nor was an actual shared-cache replay attack simulated. Initial normal-response run passed 9/9 at 11:22:34 +06 on 2026-09-26 against isolated `arbitrator_test`.

Small reproducible consistency gap: the oversized JSON test received HTTP 413 without a no-store Cache-Control header because JsonBodyLimitFilter rejects before Spring Security's header writer. A temporary no-store assertion failed exactly there at 11:23:14 +06 (9 tests: one failure, zero errors/skips); private-response checks remained green.

The generic early rejection contains no submission source, identity/token response or problem statement, so this is not evidence of a private-data leak or permission bypass. Other early rejection paths were source-inspected but not all independently reproduced.

Pre-approval proposal: have the existing earliest SecurityHeadersFilter establish the cache-prevention policy as well, so early errors receive the same no-store guarantee as normal responses; add regressions for early rejects while retaining successful private/static response checks.

The experimental failing assertion was removed pending approval, rather than leaving the project's normal tests broken or silently fixing production. Retained pre-approval verification tests passed 9/9, zero failures/errors/skips, at 11:23:53 +06 on 2026-09-26; that run did not close the early-error gap.

After user approval, SecurityHeadersFilter now establishes `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache` and `Expires: 0` before invoking downstream filters. This matches the existing Spring Security default rather than changing private response bodies, authentication or resource routes.

Existing explicit no-store policies remain compatible. Added exact header checks to the existing filter unit tests (normal, non-loopback admin 403 and oversized JSON 413) and live oversized ordinary/structured-JSON tests, including chunked requests without Content-Length.

Retained item-92 ordinary private/static/login/registration header checks remain in place. The previously failing live 413 no-store case now passes. Focused SecurityHeadersFilterTest, SecurityHeadersIntegrationTest, ContestAccessIntegrationTest and ApiRateLimitAttackIntegrationTest passed 14/14, zero failures/errors/skips, finished 2026-09-26 11:26:09 +06.

Final full `mvn -B test -Djavafx.platform=linux` passed 309 server + 1 client tests, zero failures/errors/skips, at 11:29:48 +06 on 2026-09-26. No dependency, port, schema, frontend feature or build/start step changed. This does not force a misconfigured external cache to honor headers or erase saved downloads.

Next checklist concern: item 93 CDN configuration.

The initial post-fix full run finished 2026-09-26 11:27:31 +06 with one health-test assertion failure: it required the exact literal `no-store`, while the live response carried the equivalent stronger complete cache policy. The health test now checks an actual no-store directive and absence of public caching for both liveness and readiness. Health response status/body and production health code were not changed. The final full run above verifies this final test source; the initial full run remains a historical failed run, not a green claim.

<a id="audit-entry-68"></a>

## Security audit follow-up — item 93 CDN configuration (not applicable to current supported deployment)

Verdict: no CDN or shared HTTP caching intermediary is configured in the repository's supported deployment. README starts Spring Boot directly and connects the JavaFX client to the server's LAN address; the admin panel fetches same-origin APIs.

Searches of tracked/source configuration, scripts, the separate React experiment and deployment-file names found no Cloudflare/CloudFront/Fastly/Akamai/CDN configuration, proxy cache, cache-key rules, `s-maxage`, surrogate-cache policy, nginx/Caddy/Varnish or hosted-site deployment configuration.

Authorization uses Bearer headers, not authentication cookies. There is therefore no existing CDN cache-key implementation to repair or CDN replay target to exercise. This is a repository/default-deployment review, not verification of an external provider account, DNS/router state or an independently installed host proxy.

Item 92's early cache-prevention policy and final 309 server + 1 client full run remain the supporting origin-response evidence; they do not prove that an external cache honors those headers. If a CDN is introduced later, private APIs/auth/health/admin/material downloads should bypass shared caching rather than relying only on adding an identity to the cache key.

Such a deployment needs separately approved end-to-end User-A/User-B replay and logout checks, and review of item 87's direct-peer loopback/IP trust risks. Do not add a CDN, forwarded-header trust, service or new build/run step as part of this currently inapplicable concern.

No item-93 production change, new test, external service operation or test rerun was performed. Next checklist concern: item 94 source maps.

<a id="audit-entry-69"></a>

## Security audit follow-up — item 94 source maps (reviewed; no current fix indicated)

Verdict: no `.map` files or embedded `sourceMappingURL`/`sourceURL`/`sourcesContent` references were found in the current instructor static resources or their compiled `target/classes/static` copy. The shipped instructor console is a hand-authored HTML file with embedded, readable JavaScript, not a minified frontend bundle with separately published original-source maps.

The root Maven reactor builds Java server/common/client modules and has no frontend source-map generation step; distribution assembly copies the server/client JARs and optional JRE, not the separate React directory. Readable browser code is not itself a security defect: ADMIN JWT and direct-loopback API gates must remain server-enforced regardless of whether users can inspect frontend logic.

Obfuscation/map removal cannot replace those checks.

The separate `arbitrator-web` React/Vite directory is explicitly a toolchain experiment, not served by Spring or included in Maven. Its project configuration contains no source-map enabling option/reference; neither `node_modules` nor generated `dist` exists here, so no fresh React production build/output was inspected.

No current server JAR was available under server target for a packaged-artifact inspection. The review covers source resources, the existing compiled static copy and packaging configuration, not an independently built release or external web-host directory.

No dependency installation, extra build, live guessed-map URL test or new test source was necessary. The item-92 full 309 server + 1 client run remains the latest regression snapshot, not a dedicated source-map test. No item-94 production/build/run change is indicated.

If the React experiment is later integrated, separately inspect its actual release artifacts and deliberately choose whether any maps are published; never put secrets in browser code. Next checklist concern: item 95 `.git` exposure.

<a id="audit-entry-70"></a>

## Security audit follow-up — item 95 Git/environment/config exposure (reviewed; dedicated probes passed)

Verdict: no private-file HTTP exposure reproduced in the supported Spring deployment. Static source and compiled-resource inventory contains only the admin HTML/logo/fonts; no `.git`, `.env`, backup or private config is inside that web resource tree, and no custom resource handler/project-root/filesystem web mount was found.

Spring configuration and SQL migrations outside the static tree are not downloadable just because they are classpath resources. The existing Maven exclusion of `application-local.yml` remains unchanged; this review does not build/inspect a fresh release JAR or certify independently installed web servers/reverse proxies.

Added dedicated verification-only PrivateFileExposureIntegrationTest against random-port HTTP and isolated `arbitrator_test`: 22 ordinary paths include the checklist's `/.git/`, `/.env`, `/config`, `/.svn/`, `/backup`, `/debug`, plus Git HEAD/config/index, local env/config names, SQL migration, source-path config, backup ZIP and admin-side variants.

Six encoded/double-encoded/traversal paths test attempts to escape the static tree. All 28 private-file probes return 400/403/404, never downloadable success or redirect. Two positive controls verify the server really serves local `/admin/index.html` (200) and requires authentication for `/api/contests` (401).

No authentication/login is needed for these probes. Application-level missing/denied responses must also carry no-store headers.

Initial run at 2026-09-26 14:25:35 +06 had one overly broad test-header assertion failure, not a file leak: Tomcat rejected an encoded traversal URL with generic 400 before application filters, so the application cache header was absent. The test now accepts that non-downloadable connector rejection and only requires application no-store headers for 403/404.

This distinguishes malformed-URL handling from item 92's application-filter early errors; no production header/filter change was made. Final two tests, covering 30 HTTP requests including controls, passed 2/2, zero failures/errors/skips, finished 2026-09-26 14:26:13 +06.

The earlier item-92 full 309 server + 1 client run predates these two new tests; no full suite was rerun for item 95.

No production fix indicated or implemented; only the dedicated regression tests and audit docs changed. If a separate web server is ever configured to serve the repository root, that would need its own review; the current app's lack of exposure does not guarantee external hosting policy. Build/run workflow, dependencies, schema and routes remain unchanged. Next checklist concern: item 96 cloud metadata access from judge containers.

<a id="audit-entry-71"></a>

## Security audit follow-up — item 96 cloud metadata (reviewed; isolation verified)

Verdict: submitted code cannot route to the checklist metadata address through the current sandbox network. SandboxExecutor's common Docker invocation always sets `--network none`, drops all capabilities and uses no-new-privileges with a non-root user; no host network, extra network attachment, metadata proxy or host socket mount exists in this path.

Both compiler and runtime call this same implementation. JudgeWorker, CustomRunService and custom-checker compilation/execution also delegate to SandboxExecutor, so this source-level boundary covers those entry points rather than only scored submissions.

Isolated loopback inside the container still exists; it is not the host's loopback network. No cloud-provider deployment/account configuration was inspected.

The existing broad network probe included `169.254.169.254` but used port 1 and only asserted connection failure, which could also reflect an absent service. Added verification-only `cloudMetadataHttpPortIsUnroutableDuringCompileAndExecution` to SandboxExecutorTest.

Its Python probe uses an internal loopback listener/connection as a positive socket control, verifies there are no non-loopback IPv4 routes, and attempts a bounded TCP connection to `169.254.169.254:80`. It requires ENETUNREACH/EHOSTUNREACH, not mere refusal or timeout.

The exact success marker must be returned from both compiler-mode (writable workspace) and runtime-mode (read-only workspace) invocations. No metadata HTTP request is sent and no credentials are fetched. Checker entry points were source-traced, not separately given this payload, and alternate provider-specific/IPv6 endpoints were not individually exercised.

The full Docker-backed SandboxExecutorTest class passed 20/20, zero failures/errors/skips, finished 2026-09-26 14:54:44 +06 using the existing local judge image; this includes the new two-phase metadata probe and existing network, environment, filesystem, resource and ordinary-language regressions.

No production fix is indicated or made. Only one diagnostic test and audit docs changed; no Dockerfile, dependency, schema, cloud setting, build or launch step changed. The earlier item-92 full 309 server + 1 client run predates item-95's two tests and this new test; no full reactor rerun was performed for item 96.

Host/cloud administrator compromise, altered sandbox flags/images or a successful container escape would require separate assessment and are not disproved by this network test. Next checklist concern: item 97 DNS isolation.

<a id="audit-entry-72"></a>

## Security audit follow-up — item 97 DNS isolation (reviewed; outbound DNS unavailable)

Verdict: no DNS egress channel reproduced in the current compiler/runtime sandbox. The common Docker invocation's fixed `--network none` policy has no configured external interface/route or host/bridge attachment. Source review found no DNS override, host-gateway attachment or added resolver proxy; checker/custom-run paths retain the same SandboxExecutor delegation documented in item 96.

Container-local `localhost`/hosts-file resolution is permitted and does not by itself indicate external DNS access; don't break that normal behavior to satisfy a literal "no names can resolve" interpretation.

Added verification-only `externalDnsIsUnavailableDuringCompileAndExecution` to SandboxExecutorTest. In both compiler and runtime containers, it confirms localhost resolution, verifies no non-loopback IPv4 route, and probes UDP and TCP port 53 for two public resolver IPs, Docker's usual loopback resolver address and every nameserver actually listed in that container's `/etc/resolv.conf`.

Non-loopback attempts require unreachable/no-address/no-protocol errors rather than mere timeout/refusal. Loopback resolver probes must not receive a UDP reply or establish a TCP connection. A bounded system lookup of a fixed `.invalid` test name must also fail; this negative lookup alone would not prove isolation, so it is supplemental to routing/port checks.

Payload is a fixed non-sensitive DNS query with no submission source, secrets or host data. Resolver configuration is only read, not modified; timeout options apply only inside the diagnostic process. Configured IPv6 nameserver entries are handled if present, but a comprehensive provider/IPv6 resolver matrix was not exercised.

The entire Docker-backed SandboxExecutorTest passed 21/21, zero failures/errors/skips, finished 2026-09-26 14:57:38 +06 using the existing local judge image. This run includes both the DNS probe and prior metadata/ordinary-language/resource/filesystem tests.

No production fix indicated or implemented. Only one new diagnostic test and audit docs changed; no DNS/Dockerfile/server configuration, dependency, schema or build/start change. No full reactor rerun was performed; the historical item-92 309 server + 1 client full run predates four new item-95/96/97 tests.

Existing current-host results do not certify a differently configured Docker/cloud host or successful sandbox escape. Next checklist concern: item 98 side channels/shared-host isolation.

<a id="audit-entry-73"></a>

## Security audit follow-up — item 98 shared-host side channels (user retained documented limitation)

Verdict: current sandbox provides ordinary namespace/filesystem/network/resource isolation, not dedicated-hardware or side-channel-proof isolation. There is no `--pid host`, host IPC/network, shared judge-root mount or Docker socket mount. Existing filesystem/environment/network tests protect their synthetic secret boundaries.

Nevertheless, up to ten containers share the underlying kernel/hardware with each other and the application/database in the supported single-host setup. `--cpus 1` sets CPU quota, not an exclusive CPU/core assignment; `--cpu-shares 256` is scheduling weight, and neither is a confidentiality guarantee.

No exclusive CPU partition, dedicated judge machine, VM/microVM or alternative runtime is configured. [Docker CPU constraints](https://docs.docker.com/engine/containers/resource_constraints/), [Docker namespace/cgroup security](https://docs.docker.com/engine/security/).

Bounded verification-only `procSystemMetadataVisibilityIsCharacterizedWithoutDumpingValues` runs a Python read-only probe in the runtime sandbox. It confirmed `/proc/cpuinfo`, `/proc/meminfo`, `/proc/uptime`, `/proc/stat` and `/proc/loadavg` are readable on this host; only readable/blocked labels are logged, never raw values or process data.

This is system-metadata visibility, not a demonstrated ability to read another submission's code, JVM/DB secrets or another process's memory. Exact host-vs-container metric scope was not compared. The test deliberately characterizes both readable and blocked configurations; green does not mean the visibility or hardware side-channel risk was removed.

The entire SandboxExecutorTest class passed 22/22, zero failures/errors/skips, finished 2026-09-26 15:22:30 +06. No concurrent cache/timing secret-recovery attack, noisy workload or CPU-contention stress test was run; a failed sample attack would not prove absence of this attack class.

Proposed disposition: explicitly retain this as a residual limitation for the intended supervised LAN/lab use under the user's unchanged-build/run requirement. Do not silently claim it is fully fixed, blanket-hide `/proc`, disable normal timers or serialize/repin the judge: those changes need compatibility/performance/limit-accounting tests and would not eliminate hardware side channels.

If a stronger hostile multi-tenant threat model is later required, separately design host/kernel mitigations, sensitive-workload separation and potentially VM/microVM or alternative-runtime isolation, with approval for operational changes and complete C++/Java/Python/compiler/checker regressions.

Such runtimes may strengthen some boundaries but cannot be promised to eliminate shared-hardware leakage; gVisor explicitly leaves hardware-side-channel defenses to the host/platform. [gVisor security model](https://gvisor.dev/docs/architecture_guide/security/).

User decision: "yes, next" in response to retaining the current sandbox and documenting the limitation. Keep the current architecture; no stronger runtime/host-isolation work is approved, and the residual side-channel risk remains open rather than fixed.

Only one diagnostic characterization test and audit docs changed; Docker image/config, schema, dependencies and build/start remain unchanged. The latest full reactor snapshot remains the historical item-92 309 server + 1 client run, before five new item-95–98 tests; no full reactor rerun was performed here.

Next checklist concern: item 99 multi-language escape tests (review only the shipped supported toolchains unless adding languages is separately requested).

<a id="audit-entry-74"></a>

## Security audit follow-up — item 99 multi-language escape tests (approved; representative coverage implemented)

Verdict: supported runtimes all share the fixed Docker isolation path, but representative hostile tests are not independently balanced across languages. Language enum, languages.yml and LanguageCommandPolicy configure/accept only C++17, Java17 and Python3.10; C, JavaScript, Rust and Go are not separate supported submission runtimes and must not be added just to fulfill the generic checklist.

Java/C++ compile within Docker and Python runs there without a separate compiler. Command templates are validated/rendered as arguments without a host shell. Existing source-text threading/process bans in scored judging are a policy supplement, not the security boundary; do not broaden Python filtering or mistake a CE before execution for a passed OS-isolation attack test.

Custom runs still use the same sandbox.

Coverage review: C++ has the primary AC/WA/CE/RE/TLE/MLE/fork/output and filesystem/host-sibling-secret/environment/symlink fixtures. Python has normal execution plus the recent compiler-mode/runtime metadata and DNS probes and runtime `/proc` characterization; those diagnostic compiler-mode Python invocations verify the common compiler sandbox flags, not a new Python compilation feature.

Java's dedicated live SandboxExecutor coverage is primarily normal compilation/execution in the read-only workspace, not hostile filesystem/network/environment/process/resource probes. JudgeWorkerTest's six discovered cases are C++ pipeline tests, including three symlink fault-injection parameters, not three-language pipeline coverage.

Current tests therefore do not establish equivalent escape-regression coverage for Java/Python merely because their ordinary programs run.

Read-only verification: existing Java/Python smoke method plus JudgeWorkerTest and LanguageCommandPolicyTest passed 12/12, zero failures/errors/skips, on 2026-09-26 at 15:29:10 +06 with actual local Docker available. The previous whole SandboxExecutorTest 22/22 run remains supporting baseline; no new hostile test source or production code was added for item 99 and no full reactor run was performed. No language-specific escape has been reproduced by this review; the finding is missing independent attack coverage, not a proven broken sandbox.

User approved the tests-only coverage fix ("Yes"). Implemented three parameterized methods in SandboxExecutorTest, nine discovered cases across C++/Java/Python, using three matching `fixtures/isolation.*` programs. Each language exercises real SandboxExecutor, not JudgeWorker source bans: the configured compiler/run argument templates are rendered by LanguageCommandPolicy, including production Java `-Xss256m`; Python correctly has no compile phase. Existing C++ fixtures and compiler-mode Python diagnostics remain unchanged.

The isolation matrix requires a readable mounted sentinel, non-root UID, successful private-tmp read/write and container-local loopback sockets as positive controls. It rejects absolute/relative host/sibling fake-secret reads (including `/proc/1/root` variants), requires both Docker daemon socket paths absent (ordinary file-open failure alone would not establish this), and rejects non-truncating opens for writes to the fake host/sibling files, system files, proc/sys and the read-only workspace.

Fake sentinels and their parent directories are made world-readable/searchable so host filesystem permissions alone do not explain denial; their host contents are checked unchanged and the fake files/sibling directory are cleaned in finally.

It checks fixed HOME/PATH, cleared injection variables and absence of representative secret variables without printing their values; this is not an exhaustive environment-leak certification or a host-environment injection test. Metadata TCP port 80 and public resolver TCP port 53 must be unreachable with no non-loopback IPv4 routes.

C++/Python additionally require unreachable errno; Java uses connect failure plus route-table evidence. No HTTP/DNS payload or real credential is retrieved. All three runtimes also undergo a bounded infinite-loop timeout and independent finite 2-MiB stdout/stderr floods; normal execution succeeds before limit tests, and each retained stream is capped at 1 MiB.

Verification: full SandboxExecutorTest passed 31/31 at 15:36:24 +06. Full reactor `mvn -B test -Djavafx.platform=linux` passed 323 server + 1 client = 324 tests at 15:38:28 +06, zero failures/errors/skips, including the final Docker-socket absence checks.

After strengthening fake-secret parent permissions and finally cleanup, the final nine-case matrix rerun passed 9/9 at 15:38:33 +06 using `mvn -B test -pl arbitrator-server -am '-Dtest=SandboxExecutorTest#supportedLanguagesCannotAccessHostSecretsOrEscapeRuntimeIsolation+supportedLanguagesHaveRuntimeTimeBounds+supportedLanguagesHaveIndependentStdoutAndStderrBounds' -Dsurefire.failIfNoSpecifiedTests=false`.

No new production flaw was demonstrated by these probes; no production, Docker image, dependency, schema or build/start change was made.

Scope limits: these are representative runtime isolation regressions, not arbitrary compiler exploits, kernel/daemon escape testing, cross-language JudgeWorker pipeline coverage, exhaustive IPv6/provider/DNS targets, per-language memory/fork/thread/disk-bomb tests or a proof of confidentiality against side channels.

The user-retained item-98 proc visibility and previously deferred writable-metrics exposure remain unchanged; green characterization tests do not fix them. Item 100 separately reviews permanent hostile-suite breadth and automation. Next checklist concern: item 100 malicious submission regressions.

<a id="audit-entry-75"></a>

## Security audit follow-up — item 100 permanent hostile regressions (approved test coverage implemented; release gate unchanged)

Verdict: a permanent malicious-submission suite already exists and normally runs with Maven, but coverage and mandatory deployment enforcement are incomplete. Item 99 added representative three-language runtime isolation/time/output tests; do not recreate them.

Existing SandboxExecutorTest covers infinite loops, touched memory exhaustion, fork pressure, independent stdout/stderr caps, host/sibling path isolation, environment isolation, network/metadata/DNS, read-only workspace and runtime symlinks.

SafeSandboxFilesTest and JudgeWorkerTest cover safe host staging and compiler-stage symlink fault injection; CheckerRunnerTest adds checker timeout/memory/PID-recovery/output/network/filesystem coverage. Accepted proc-stat visibility and the deferred writable-metrics exposure are characterization cases, not attacks whose success must suddenly fail the suite.

At review, missing dedicated live assertions included thread exhaustion, bounded tmpfs/file-size exhaustion, oversized aggregate compiler output, privilege/privileged-syscall denial, signal/timeout evasion, detached child cleanup, and a controlled host-loopback listener test.

The existing loopback connection-refusal probes at port 1 do not prove host-service isolation by themselves; item-99 positive controls demonstrate legitimate container-local sockets, which must remain allowed. The older fork test observes timely failure/timeout but does not independently enumerate surviving processes/containers after completion.

Source bans, configured Docker limits and comments are not substitutes for attack assertions. The finding was missing coverage, not a newly demonstrated production escape.

Automation review: there is no repository `.github` CI/release workflow. The live Docker test classes use assumptions to skip when Docker/image is unavailable. Ordinary Maven test/package/install runs fail for executed failing tests, but can be green while Docker tests are skipped, and `-DskipTests` bypasses them deliberately. `scripts/prepare-zero-download-bundles.py` copies existing JARs without running/validating tests and suggests `mvn clean package -DskipTests` when artifacts are absent.

Therefore neither every build nor every deployment is currently certified by a mandatory live hostile-suite gate. Enforcing such a gate would alter accepted build/release behavior and is not authorized under the user's standing instruction to keep build/run unchanged.

Read-only verification: existing SandboxExecutorTest, SafeSandboxFilesTest and LanguageCommandPolicyTest passed 38/38 with actual local Docker, zero failures/errors/skips, on 2026-09-26 at 17:57:06 +06 via `mvn -B test -pl arbitrator-server -am '-Dtest=SandboxExecutorTest,SafeSandboxFilesTest,LanguageCommandPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false`. No new test or production source was added for item 100; only audit documentation changed. Full reactor baseline remains the item-99 323 server + 1 client run.

User approved ("Yes") the tests/docs-only first step, with build/start unchanged and any production fix requiring separate approval. Implemented nine cases in SandboxExecutorTest using one reusable test-only `fixtures/hostile_bounds.py`, without duplicating item-99 language fixtures:

- Four parameterized resource/privilege cases: at most 128 thread starts with small stacks must stop below 64 while more than one thread succeeds, then all threads join and a new thread works; at most 80 MiB of physical writes in private tmpfs must hit ENOSPC by 64 MiB, then files are removed and a fresh write succeeds; a sparse 65-MiB resize must fail EFBIG under the 64-MiB file-size cap; non-root execution, no-new-privileges and zero capability sets are asserted alongside denied setuid(0) and three harmless user/mount/network namespace unshare attempts. These are selected privileged-syscall checks, not every syscall or a kernel exploit.
- A synthetic compiler-mode Python diagnostic creates three sparse 48-MiB output files, requires post-compile rejection of the 144-MiB logical total under the 128-MiB workspace cap, checks sizes and removes the exact outputs in finally. This does not allocate 144 MiB of host storage or demonstrate a real compiler/large-executable exploit. The runtime file-size probe exercises the shared per-file limit separately.
- Three signal/child cases: ignored SIGTERM, an ignored-SIGTERM child detached with setsid while the parent times out, and a detached child surviving ordinary parent exit. A pipe handshake confirms child startup before the parent emits its ready marker. Host elapsed bounds (8 seconds for timeout probes, 5 seconds for ordinary parent exit) precede the child's private 10-second fallback, preventing natural child exit from looking like successful cleanup. Each run must emit its own Docker hostname; read-only inspection requires that exact container identity to disappear, and daemon/permission failures are not accepted as proof. No broad process/container kill command is added. This demonstrates container cleanup, not independently mapped host-PID enumeration or arbitrary kernel escapes.
- A controlled host IPv4 loopback listener is first reached from the host as a positive control, stays listening during the submission probe, must not be reached by the container, and must receive no unexpected accepted connection. No payload or real service request is sent; both listener/control sockets close automatically. Item-99 container-local socket controls remain allowed.

Normal sandbox execution/private-tmp read-write controls run before and after resource, privilege, child and loopback probes, and after rejected compiler output. Tests bypass source-text bans intentionally to exercise actual OS isolation. Selected new probes are Python/Linux-in-container diagnostics; no new submission language or Python compilation feature was added, and they do not establish every-language coverage for every pressure mode. Accepted proc visibility and deferred writable metrics remain unchanged.

Initial nine-case attack run passed 9/9 with no failures/errors/skips at 2026-09-26 18:01:58 +06 via `mvn -B test -pl arbitrator-server -am '-Dtest=SandboxExecutorTest#boundedResourceAndPrivilegeAttacksAreContainedAndRecover+oversizedSyntheticCompilerWorkspaceIsRejectedWithoutFillingHostDisk+signalAndDetachedChildAttacksLeaveNoContainerBehind+runningHostLoopbackServiceIsNotReachableFromSubmission' -Dsurefire.failIfNoSpecifiedTests=false`.

The final child timing assertions were subsequently tightened below the private fallback and are included in the full verification recorded below.

Full verification with final child bounds: `mvn -B test -Djavafx.platform=linux` ran 332 server tests at 18:04:20 +06: 331 passed, one error, zero failures/skips; client module did not run. SandboxExecutorTest ran 40 cases with one error in the unchanged pre-existing fork-bomb test; all nine new cases passed. An isolated `SandboxExecutorTest#forkBombIsContained` rerun reproduced the same IOException at 18:04:47 +06. Do not weaken that test, hide the failure, or mark the current full reactor green.

Final focused rerun of all nine approved new cases, with the tightened child timing bounds, passed 9/9 at 18:06:17 +06 using the initial attack-run command above, zero failures/errors/skips. This verifies the completed test additions but does not erase the full-run or isolated fork errors.

Newly reproduced existing reporting bug: Docker events scoped to the judge image and isolated-recheck interval show `arbitrator-sbx-sub-0-17875937903361749-1` (compile) exiting 0, then `arbitrator-sbx-sub-0-17875937903361749-2` (runtime) receiving an OOM event and exiting 137.

Thus this runtime container started and memory containment acted. SandboxExecutor's `metricsMissing && exit != 0 && exit != 124` guard instead throws `docker run failed before the sandbox container started` because the killed timing process left no report.

JudgeWorker catches this as judge-side RE with a misleading infrastructure message. This is a reliability/verdict-reporting flaw under hostile resource pressure, not evidence that the attacker escaped or survived cleanup. Read-only confirmation command: `docker events --since '2026-09-26T18:04:45+06:00' --until '2026-09-26T18:04:48+06:00' --filter type=container --filter image=arbitrator-judge:latest --filter event=oom --filter event=die --format '{{.Actor.Attributes.name}} {{.Action}} exit={{.Actor.Attributes.exitCode}}'`.

Proposed separate production fix: distinguish verified sandbox resource termination from genuine Docker startup failure when timing metrics are absent, preserve fail-closed startup checks and do not blindly call every exit 137 TLE (OOM uses the same signal). Add a focused regression for the confirmed distinction and retain the existing fork attack test. This fix is NOT implemented or approved by the item-100 tests-only approval. No production, image, dependency, schema or build/start change was made.

Mandatory no-skip release enforcement also remains unimplemented/unapproved: tests still may skip without Docker/image, deliberate -DskipTests remains available, and bundle assembly still does not gate deployment. Item 100's approved coverage work must not be described as a complete every-deployment security certification.

User decision: "No, move on to the next" — decline/defer the separately proposed fork/OOM reporting fix and proceed to item 101. The reporting bug remains open; do not change the production classifier, weaken/disable the fork test, or describe the full suite as green.

<a id="audit-entry-76"></a>

## Security audit follow-up — item 101 security-bug regression tests (reviewed; existing representative coverage verified)

Verdict: the requested regression-testing practice is already present for the major fixes examined, including the checklist's authorization and XSS examples. No new implementation gap requiring a production change was demonstrated by this review. This is a representative fix-to-test audit, not proof that every historical change/CVE/payload has a complete independent exploit reproduction.

AuthorizationIdorIntegrationTest exercises real HTTP denial when a different student requests another student's source/test results, requires no secret bytes in denied responses, and verifies owner/admin access still works. Its contest-grant case requires 403 for changed contest/problem/material IDs; private clarification and STOMP topic cases cover additional boundaries.

RoleEscalationIntegrationTest covers injected registration/login privileges, modified JWT role claims, weak/legacy tokens and database role-change revocation. These tests check behavior rather than only the existence of security annotations.

XSS coverage is layered: RichTextSanitizerTest asserts active content/dangerous links are removed while legitimate formatting survives; ReflectedXssAttackIntegrationTest exercises live HTTP query/error payloads and content types; AdminRenderingSafetyTest guards textContent/delegated-handler/sandboxed-preview source patterns.

The reusable `scripts/security/dom-xss-attack.cjs` separately executes script/image/SVG/attribute-breakout payloads in actual admin DOM rendering, with a working script-execution positive control, execution counters, dialog/page-error assertions and literal-text/formatting controls.

That browser harness is not part of Maven and was inspected, not rerun or installed here; its earlier execution is historical evidence only. The JavaFX WebView test is a source-policy guard, not a real JavaFX execution suite. Do not describe source assertions or HTTP markup checks as proof of browser non-execution.

Additional existing tests inspected cover ZIP traversal/size/duplicate-path limits, strict unknown-request-field rejection and cache/security-header behavior. Earlier scoring/race/session/rate-limit/sandbox fixes also retain dedicated tests; skipped/unfixed concerns remain documented rather than being silently converted into passing protection tests.

The user-deferred fork/OOM reporting bug is intentionally left exposed by its existing failing regression. No new CI gate, browser dependency, mandatory build/run step or test suppression is proposed under this item.

Verification: `mvn -B test -pl arbitrator-server -am '-Dtest=AuthorizationIdorIntegrationTest,RoleEscalationIntegrationTest,ReflectedXssAttackIntegrationTest,RichTextSanitizerTest,AdminRenderingSafetyTest,ApiSchemaValidationIntegrationTest,SecurityHeadersIntegrationTest,SecurityHeadersFilterTest' -Dsurefire.failIfNoSpecifiedTests=false` passed 29/29, zero failures/errors/skips, on 2026-09-26 at 23:52:15 +06 using the isolated test database.

Only documentation changed; no new test or production source was added. This focused run does not replace the failed item-100 full reactor snapshot (332 server tests, one fork-test error, client not run). Continue pairing future approved fixes with a regression and normal-behavior controls.

Next checklist concern: item 102 production configuration review.

<a id="audit-entry-77"></a>

## Security audit follow-up — item 102 production configuration (source review complete; actual deployment unidentified)

Verdict: source defaults and previously tested protections can be reviewed, but this checklist explicitly requires the actual production environment. The user has not identified a deployed lab/server machine, running artifact, effective overrides or network boundary. Do not assume this development computer, its MySQL test schema or an integration-test listener is production. No production sign-off, effective-secret audit, firewall assessment or externally reachable-port verification is claimed.

| Concern | Source/default evidence | Deployment status |
|---|---|---|
| Debug/errors/logs | INFO application logging, no configured debug/trace, stacktrace/exception/binding fields disabled; SafeErrorAttributes sanitizes server errors; SQL diagnostic logger disabled | Actual log/profile/CLI overrides and log access/retention unverified |
| TLS/redirect | HTTP/WS on 8080; no TLS/HTTPS redirect after the user's explicit removal | Known accepted transport limitation, not a production pass; do not reinstall certificates/profiles |
| Cookies/CSRF | Stateless explicit bearer header, no application authentication-cookie issuance; CSRF disabled for that design | Cookie flags not applicable to current auth; any deployment-added cookie auth would require a new review |
| CORS/WebSocket Origin | No permissive HTTP CORS rule; WebSocket default same-origin plus authenticated handshake | Source protections present; actual browser/deployment responses not checked here |
| CSP/headers/cache | Earliest filter sets enforcing same-origin CSP, anti-framing/nosniff/referrer/permissions/no-store policy, including early application rejections | Inline script/style allowances remain a documented compatibility limitation; no newly strict CSP proposed |
| Rate limits | API/login/account/IP/concurrency limits and judge/source/backlog admission configured; forwarded headers ignored | Effective overrides, shared-IP capacity and real load unverified |
| DB permissions | Example account is localhost-scoped with schema ALL; historical item-54 live grants were schema-only, not global superuser | Reduction previously not applied; current production account/grants unknown; startup Flyway needs schema rights |
| Secrets/admin accounts | Private random per-start JWT key unless explicit secret override; validation rejects weak fixed signing keys; packaging excludes classpath application-local.yml. Empty-user-table seeder still creates known demo admin/student credentials; datasource/setup examples also retain development credentials | No real secrets or account hashes queried/disclosed; deployed overrides/account state unknown. Demo credentials unsuitable for real production |
| Backups/recovery | Recovery runbook exists, but backup workflow and clean restore validation remain user-deferred | No recoverability certification; do not silently implement deferred backup steps |
| Judge/host/network | Non-root, no capabilities/no-new-privileges, network-none, read-only runtime and bounded resource flags; representative real-Docker tests exist | Actual image/kernel/daemon settings unknown. Retained proc/metrics/side-channel/log-driver caveats and user-deferred fork/OOM reporting bug remain open; firewall/open ports unverified |

No additional implementation was authorized or made in this review: only STATUS.md/CLAUDE.md changed. No tests were rerun, no application/database was launched or modified, no real credential/environment value was read, no grants/firewall rule changed, and no host/network port scan was performed. Item-101's 29 passing targeted tests are historical source/test evidence, not this item's deployment validation; the latest full reactor still has the item-100 fork-test error.

User disposition: "next problem" — move past item 102 without supplying a deployed target. Actual-production verification remains pending, not passed; no production change approved. If revisited, identify the real lab server and perform relevant read-only effective-setting/account/grant/header/log checks without displaying secrets. Any confirmed fix must be proposed and approved individually, respecting the standing unchanged build/run constraint and earlier deferred decisions.

<a id="audit-entry-78"></a>

## Security audit follow-up — item 103 exposed ports (source/local check; wildcard MySQL restriction user-deferred)

Verdict: project source does not configure an extra judge TCP listener or published container port. The shared REST/admin/WebSocket server defaults to TCP 8080; loopback admin filtering and authenticated ADMIN APIs are route controls on that same listener, not a separate private port.

Participant LAN access is intentional, so do not bind the web server to loopback or replace the retained HTTP workflow with the checklist's generic 80/443 example. SandboxExecutor uses network-none and no Docker `-p`/publish flags. The datasource's localhost:3306 URL and example SQL account's @localhost restriction do not themselves prove MySQL binds only to loopback.

Read-only local observation on 2026-09-26 at approximately 23:57 +06: `ss -lnt '( sport = :8080 or sport = :3306 or sport = :2375 or sport = :2376 )'` returned one listener, `*:3306`. No listeners on 8080 or the two conventional Docker TCP API ports were shown at that moment.

The first sandboxed attempt could not open a netlink socket; the approved read-only host check succeeded. This is a selected TCP-port inventory on the current development computer, not a complete port scan, external reachability test, production-machine identification, UDP/SSH assessment or firewall certification.

No application was started to produce a listener. Do not call a quiet web port evidence of a secure running deployment.

Concrete concern: this local MySQL listener is not restricted to loopback. Firewall rules, address family/host routing and MySQL authentication still determine actual remote access; no LAN/internet connection or database exploit was attempted, and no leaked data/public DB access is demonstrated. Other applications may depend on this MySQL installation, and the user has not identified it as production.

User disposition: "Nah, next" — decline/defer investigation and the proposed MySQL restriction; leave the host database listener/configuration unchanged. The wildcard-listener concern and outside-in verification remain open, not fixed or certified. The historical proposed follow-up was to confirm other applications' remote DB needs and the actual deployment before a separately approved host MySQL loopback-bind change and local connectivity check; do not silently apply it under the next checklist item.

Only audit documentation changed. No production/project source, test, MySQL configuration/grant, firewall rule, dependency or build/start step was changed; no external scan was performed. The checklist's outside-in verification remains pending until an authorized target and outside vantage point are supplied. Next item after disposition: item 104 internal network review.

<a id="audit-entry-79"></a>

## Security audit follow-up — item 104 internal network boundaries (project boundary verified; host firewall unverified)

Verdict: the project has a working network boundary for untrusted execution in the representative tests, with no new project-code fix indicated here. It does not establish a complete deployed-host firewall policy. Source tracing confirms ordinary submissions, custom runs and checker compilation/execution all delegate to SandboxExecutor, which uses network-none, no published ports, no shared IPC, no DB credential injection or Docker-socket mount.

Only per-run workspace/metrics mounts are passed. Both writable compiler and read-only runtime flag sets share the network restriction; compiler-mode Python diagnostics exercise those flags, not a new supported Python compilation feature.

Architecture distinctions: the web API intentionally connects to local MySQL. JudgeQueue is a bounded in-process ThreadPoolExecutor, not a separate Redis/queue network service; no Redis/RabbitMQ/Kafka dependency/configuration was found in the reviewed Maven/configuration files. Trusted judge orchestration remains in the server JVM and shares its datasource, as documented in item 55. It must not be confused with untrusted code inside the disposable containers; separate trusted service/account isolation was not implemented or silently approved.

Existing seven-case network matrix rerun passed 7/7, zero failures/errors/skips, on 2026-09-27 at 00:01:55 +06 using real local Docker: `mvn -B test -pl arbitrator-server -am '-Dtest=SandboxExecutorTest#networkIsolationBlocksLoopbackPrivateMetadataAndInternet+cloudMetadataHttpPortIsUnroutableDuringCompileAndExecution+externalDnsIsUnavailableDuringCompileAndExecution+supportedLanguagesCannotAccessHostSecretsOrEscapeRuntimeIsolation+runningHostLoopbackServiceIsNotReachableFromSubmission' -Dsurefire.failIfNoSpecifiedTests=false`.

It includes legacy local/private/metadata/public connection probes, no external IPv4 routes and routing-specific metadata failure in compiler/runtime modes, external DNS unavailability, three-language filesystem/environment/network isolation, and an actual controlled host IPv4 loopback listener with a host-side successful connection.

The more rigorous route/error/positive controls supplement the legacy unused-port refusal checks; failure to reach an unused port alone is not network-isolation proof. Legitimate container-local sockets remain allowed.

Limits: no actual production target/external vantage or host firewall rules were supplied/verified. This run does not freshly connect to the real MySQL listener, certify every IPv6/network path, separately execute checker entry points, prove trusted-JVM compromise containment or defeat kernel/daemon escapes.

The localhost datasource URL is not a host firewall. Item-103 wildcard MySQL listening and external reachability remain explicitly user-deferred; do not revisit or alter MySQL/firewall configuration implicitly. Previously retained proc/metrics/side-channel limitations and fork/OOM reporting bug remain open; this focused pass does not erase the failed full reactor.

Only documentation changed. No new test/production source, service, credential, grant, firewall rule, image, dependency or build/start step was added/changed, and no external network scan was performed. If deployed network hardening is revisited, require an identified authorized server/topology and individually approved changes that preserve intended participant LAN access. Next checklist concern: item 105 independent penetration testing.

<a id="audit-entry-80"></a>

## Security audit follow-up — item 105 independent penetration testing (not established; external validation pending)

Verdict: no independent penetration-test report or documented external-review result was identified in the examined repository documentation. This does not establish whether someone performed a review outside this workspace; that evidence has not been supplied.

Existing developer/assistant code reviews, dependency/image scans, live attack probes and regression tests provide useful evidence but are not a substitute for the checklist's separate tester. Another self-run suite or assistant pass must not be labeled independent production certification.

Recommended scope for a separately authorized reviewer: use an isolated, representative deployment with fake users/contest data and a recorded source revision, artifact/image identifiers and redacted effective settings. Cover authentication/session/privilege changes, owner/contest confidentiality boundaries, injection/XSS/upload handling, scoring/timing/races and request/queue abuse, plus sandbox/resource/network isolation.

Record concrete reproduction steps, expected/actual results, evidence and remaining uncertainty, and recheck any subsequently approved fixes. Agree targets/accounts/allowed actions and pressure limits first; no destructive host/kernel attack, real data access or external scan is implicitly authorized by this checklist.

Production itself requires its own authorized deployment/network review, still pending under items 102–104.

The reviewer should receive STATUS.md/CLAUDE.md and the original checklist dispositions, including—not conceal—the existing full-reactor fork/OOM error, user-deferred writable metrics, retained proc/side-channel exposure, HTTP-only workflow, incomplete backup/restore validation and host/network uncertainties. Existing fixtures and ordinary-behavior controls can support their work but should not dictate the only paths tested. A handoff or an unexecuted checklist is preparation, not a completed pentest.

No independent tester was contacted/delegated/hired, no new test or external scan was performed and no app/host configuration, dependency, build/run command or production source changed; only audit docs changed. This final numbered item remains pending independent evidence, not fixed/passed.

The checklist traversal has reached item 105; skipped/unreviewed items (including the earlier 68–73 gap), explicitly deferred risks and deployment validation remain open. The project remains described as a supervised-lab beta, not security-certified or ready for an unrestricted public online-judge release.

Any further work must be selected and approved within the user's existing constraints rather than silently implementing the generic architecture diagram or release-blocker list after item 105.

<a id="audit-entry-81"></a>

## Security audit follow-up — item 64 dependency locking review

Verdict: application dependencies are mostly locked, but judge-image *build inputs* are not fully reproducible. The root Maven POM fixes the Spring Boot parent and explicit versions (including Tomcat 10.1.60); Boot dependency management supplies concrete transitive versions.

An offline full dependency tree found no external `SNAPSHOT`, `LATEST` or `RELEASE` dependencies. `0.1.0-SNAPSHOT` names only this project's own reactor modules. The separate, non-shipped React/Vite experiment has a committed npm v3 `package-lock.json` with 425 package entries, all external package entries carrying registry resolution and integrity values.

Its `package.json` uses compatible-version ranges, but the current lockfile records exact packages; the README's `npm install` uses those exact versions while the files remain in sync. This is not proof of byte-for-byte reproducible binaries across JDK/OS/tool versions. [Maven reproducible-build guidance](https://maven.apache.org/guides/mini/guide-reproducible-builds.html), [npm install lockfile behavior](https://docs.npmjs.com/cli/install/).

The judge Dockerfile uses mutable `FROM ubuntu:22.04` and installs unversioned `g++`, `openjdk-17-jdk-headless`, `python3`, `time` and `ca-certificates` through apt. The existing `arbitrator-judge:latest` is a *locally built* tag, not an automatic Docker Hub pull.

Different rebuilds or machines can nevertheless get different base-image/package contents, and Docker cache may reuse older layers. `SandboxExecutor` resolves the local tag to an immutable image ID once per server startup, so retagging during that server run cannot silently switch submission containers; it does not make separate builds identical.

Docker documents both mutable tags/digest pinning and the need to deliberately refresh pinned bases for security fixes. [Docker build guidance](https://docs.docker.com/build/building/best-practices/).

User decision: leave item 64 as-is for now and move on. The limited option would be to pin the Ubuntu base to a verified digest, but it was **not** approved or implemented. A fully reproducible release image would also need a tested apt snapshot or an immutable distributed image and an explicit security-update cadence; that broader release-process choice could make new-device builds brittle or leave packages stale. No production code, Dockerfile, lockfile or build command was changed in this review. Next checklist concern: item 65 SBOM.

<a id="audit-entry-82"></a>

## Security audit follow-up — item 65 software bill of materials review

Verdict: no machine-readable SBOM is generated or included with a release. A repository and current-output search found no CycloneDX, SPDX, or SBOM file or generator. Maven POMs and a dependency tree identify Java dependencies, and the separate React experiment has an npm lockfile, but neither is a saved inventory of the exact distributed server/client build.

The existing bundle-assembly script copies JARs and an optional JRE, not a dependency inventory. The locally built Ubuntu judge image also has no SBOM. This is an incident-response/traceability gap, not evidence of a currently exploitable flaw. [OWASP SBOM requirements](https://scvs.owasp.org/scvs/v2-software-bill-of-materials/).

User decision: skip item 65 and move on. The first-step option was a pinned CycloneDX Maven plugin to create separate inventories automatically during existing `mvn package`/`mvn install` commands and bundle them beside the server/client JARs; it was **not** approved or implemented.

That option would not cover the judge image or optional JRE. No item-65 build or production change was made. [CycloneDX Maven plugin](https://cyclonedx.github.io/cyclonedx-maven-plugin/index.html), [Docker build attestations](https://docs.docker.com/build/metadata/attestations/).

<a id="audit-entry-83"></a>

## Security audit follow-up — item 66 container image security review

Initial review verdict (before the approved scans/rebuild): partially hardened, with image freshness and vulnerability assessment unverified. The judge Dockerfile uses `ubuntu:22.04` (not `latest`, but still a mutable tag) and installs `g++`, `openjdk-17-jdk-headless`, `python3`, GNU `time` and `ca-certificates` without apt recommendations, then removes apt lists and switches to an unprivileged `sandbox` user.

The named C++, Java and Python toolchains and GNU `time` correspond to the configured language commands/judge metrics; do not remove one solely to make the image smaller. The then-current local `arbitrator-judge:latest` image was read-only inspected: image ID `sha256:449d728f92c2322203acb7e3f0bf5b2357ecf130cfc75336e4815d3df6b10ade`, built 2026-08-12, linux/amd64, user `sandbox`.

No Trivy, Grype or Docker Scout CLI was installed and no image-scanning workflow was found at that point; the approved one-time scans and local refresh are recorded below. The separate base-digest pin was offered under item 64 and explicitly deferred by the user, so do not silently implement it here.

Docker documents mutable base tags and image rebuilding for security updates. [Docker build guidance](https://docs.docker.com/build/building/best-practices/).

The user approved a **one-time scan only**. On 2026-09-24, the exact local image index `sha256:449d728f92c2322203acb7e3f0bf5b2357ecf130cfc75336e4815d3df6b10ade` was exported to a temporary archive; its exported linux/amd64 image configuration ID was `sha256:13db741aee33834ec0961c909e989ade535f64efc4b01fea52deca7e0c45feaa` (the index and config IDs are different Docker objects, not an image mismatch).

Official Trivy 0.74.0 scanner image digest `sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969` scanned that archive for **OS-package vulnerabilities only** using a fresh vulnerability DB updated 2026-09-24 09:10 UTC. The scanner received no Docker socket, only the read-only archive, and the actual scan ran without network after the DB download.

Its initial attempt to download an unnecessary Java-artifact DB was stopped; the final scan used `--scanners vuln --pkg-types os --skip-db-update --skip-java-db-update`. Trivy detected Ubuntu 22.04 and 178 OS packages. [Trivy archive scanning](https://www.trivy.dev/docs/v0.68/guide/target/container_image/), [Trivy DB behavior](https://trivy.dev/docs/latest/configuration/db/).

The raw report has 4,117 package/CVE matches: 6 Critical, 200 High, 3,565 Medium and 346 Low. **Do not interpret this as 206 demonstrated critical/high sandbox exploits.** Of those matches, 3,869 (including *all* Critical and High) are attached to `linux-libc-dev`, which Ubuntu describes as kernel **development headers**; containers use the host kernel, not a kernel from that package.

This scanner/source-package association needs separate host-kernel and reachability review, not blanket dismissal or proof the host is affected. Excluding that header package leaves 248 matches (150 Medium, 98 Low) across OS packages; 124 have a fixed Ubuntu package version shown.

The image's OpenJDK 17 JDK/JRE packages are 17.0.19, whereas Ubuntu's 2026-08-25 security notice provides 17.0.20 for Jammy; 8 unique JDK CVEs are reported against both JDK and JRE packages. Other fixable outdated packages include glibc, OpenSSL, Expat and Python 3.10.

These are affected-version findings, not exploit reproductions, and the OS-only scan does not assess Java libraries, the Docker daemon/host kernel, or future image builds. [Ubuntu linux-libc-dev package](https://packages.ubuntu.com/jammy/linux-libc-dev), [Ubuntu OpenJDK security notice](https://ubuntu.com/security/notices/USN-8676-1).

The user then approved the limited local rebuild/rescan/test mitigation. On 2026-09-24, the **unchanged** Dockerfile was rebuilt for linux/amd64 with a freshly pulled `ubuntu:22.04` base (`sha256:b8b6ee6aa931ecd9d0d952abc34dc0e5f7c6a30c6bb71b079fe399fde0329c02`) and `--no-cache` apt resolution under a temporary tag.

The refreshed image index is `sha256:0e7cec05579043c858260112af0053cc38745bbfb622315cf8e99ce515e748ac`, still running as `sandbox`; Ubuntu now supplies OpenJDK 17.0.20.1, libc6 2.35-0ubuntu3.15 and linux-libc-dev 5.15.0-194.204. Ubuntu's 2026-09-21 notice confirms that Java 17.0.20.1 is its Jammy security update.

No Dockerfile, script, source, user-facing build command or launch command was changed. This updates **this machine's local image only**; it is not a portable image artifact or a permanent guarantee of fresh packages on other devices. The base-digest pin remains user-deferred under item 64. [Ubuntu OpenJDK notice](https://ubuntu.com/security/notices/USN-8795-1).

The refreshed image was rescanned with Trivy 0.74.0 and an updated 2026-09-24 13:23 UTC vulnerability DB, again OS packages only and without passing Docker socket access to the scanner. It still has 178 OS packages. Raw matches fell from 4,117 to 3,996, but the DB changed between scans, so treat the comparison as indicative rather than an exact controlled before/after.

Excluding `linux-libc-dev` development-header matches, the count fell from 248 to 131 (47 Medium, 84 Low). Seven Medium package/CVE matches still have fixed versions available: five attached to inherited `libc-bin` 2.35-0ubuntu3.14 (fixed in 2.35-0ubuntu3.15) and two to inherited `perl-base` 5.34.0-3ubuntu1.8 (fixed in 5.34.0-3ubuntu1.9). `dpkg-query` in the rebuilt container independently confirmed those exact installed versions; installing requested packages did not upgrade every package already in the base.

The remaining raw 6 Critical/198 High matches are still all attached to kernel development headers and are not proof of a vulnerable running host kernel. No exploit or reachability test was performed for the residual package matches. Eliminating the two stale inherited packages reliably would require a separately reviewed image-build change (and Mahir's ownership coordination), not silently mutating this one-off image outside the Dockerfile.

After the rescan, the original image was retained as local rollback tag `arbitrator-judge:item66-previous` (`sha256:449d728f92c2322203acb7e3f0bf5b2357ecf130cfc75336e4815d3df6b10ade`) and `arbitrator-judge:latest` was switched to the refreshed index.

The Docker-backed `SandboxExecutorTest`, `CheckerRunnerTest` and `JudgeWorkerTest` passed 34/34 with no skips, including actual C++, Java and Python judging. Full `mvn -B clean test -Djavafx.platform=linux` passed 271 server + 1 client tests, zero failures/errors/skips, at 2026-09-24 22:19:29 +06.

The tests logged the refreshed image ID, verifying they did not silently test the old tag. Temporary scan archives/reports/cache, the extra refreshed alias and scanner image were removed; the old rollback tag deliberately remains. No source-control changes were needed for this local refresh beyond these audit notes.

Item 66 is improved but **not fully closed** due to remaining package findings and lack of a repeatable image-scanning/rebuild policy. Next checklist item: 67 supply-chain security (item 72 separately covers repeated container scanning).

<a id="audit-entry-84"></a>

## Security audit follow-up — item 63 dependency vulnerabilities review

Verdict: confirmed outdated, advisory-affected dependency and no continuous dependency/image vulnerability scan in the repository. An offline resolved Maven dependency tree on 2026-09-24 shows Spring Boot 3.2.5, Spring Framework 6.1.6, Spring Security 6.2.4, embedded Tomcat and JavaFX-client Tomcat artifacts 10.1.20, Jackson Databind 2.15.4, and PDFBox 3.0.3.

Apache lists embedded Tomcat 10.1.8–10.1.59 as affected by WebSocket-close busy-wait DoS CVE-2026-77791, fixed in 10.1.60; this server exposes an authenticated STOMP WebSocket endpoint. This is an affected-version and relevant-feature match, not a reproduced exploit or proof an unauthenticated client can trigger it.

Spring announced end of open-source support for the 3.2.x Boot line at 3.2.12 in November 2024; the project is still on 3.2.5. [Tomcat advisory](https://tomcat.apache.org/security-10), [Spring Boot 3.2 support notice](https://spring.io/blog/2024/11/21/spring-boot-3-2-12-available-now/).

This was not a complete CVE inventory. Maven dependency resolution was checked offline, but no full Maven/npm/OS-package vulnerability scanner is installed or configured. The separate `arbitrator-web` React/Vite experiment has a lockfile but is not in the root Maven build or served by the current server; its npm dependencies were not audited.

The judge Dockerfile starts from floating `ubuntu:22.04` and installs apt packages at image-build time; source alone cannot establish the packages in the actually built image or whether they have available security updates. A PDFBox advisory currently lists 3.0.3 in the affected range for an examples-module path traversal, explicitly not the core library used here; version-only scanner hits require reachability review.

Spring Security's affected BCrypt 6.2.x range is also not by itself an exploitable finding here because registration and login reject passwords over 72 UTF-8 bytes before `BCryptPasswordEncoder.matches`; this defense should still be retested during upgrades. [PDFBox security page](https://pdfbox.apache.org/security.html), [Spring BCrypt advisory](https://spring.io/security/cve-2025-22228/).

Approved targeted mitigation: the user chose to update Tomcat instead of continuing a live CVE reproduction. The root Maven `tomcat.version` property is now 10.1.60. Resolved dependency trees show 10.1.60 for embedded server core/WebSocket/EL and the JavaFX client's Tomcat WebSocket/API artifacts, with no 10.1.20 Tomcat artifacts.

Direct bytecode inspection of the actual old/new WebSocket JARs confirmed the vendor's close-wait change from `Thread.yield()` to `Thread.sleep(50)`. Full `mvn clean test -Djavafx.platform=linux` passed 271 server + 1 client tests, zero failures/errors/skips, at 2026-09-24 14:11:59 +06:00; the five authenticated WebSocket integration cases passed.

This verifies dependency-level remediation and compatibility, not that the attack was reproduced against the old app. No new build or run command, service or credential was introduced. Spring Boot support migration and complete Maven/npm/judge-image vulnerability assessment remain open under item 63; do not describe the whole dependency concern as solved.

A network-dependent scanner should not be added to the ordinary offline build without a separate user decision. Next checklist concern after item 63: item 64 dependency locking.

<a id="audit-entry-85"></a>

## Security audit follow-up — item 62 debug mode review

Verdict: no enabled debug mode or diagnostic endpoint was found in the current server source/configuration. The committed `application.yml` does not set `debug` or `trace`, sets `server.error.include-stacktrace: never` and `include-exception: false`, and keeps application logging at INFO.

The server POM declares neither Spring Boot DevTools nor Actuator; no `/debug`, `/actuator`, environment or heap-dump route was found in the application. Item-61 real-HTTP tests already confirmed that unexpected 500 responses do not render internal details in JSON or HTML.

The separate React/Vite experiment is not served by the Spring Boot application. This is a source/test-environment assessment, not a deployed-production pass.

The checklist specifically asks for verification of the *deployed* environment. No server was listening on local port 8080 during this review (`lsof -nP -iTCP:8080 -sTCP:LISTEN` returned no process), and no deployment manifest or remote host was supplied, so effective deployment overrides could not be checked.

Spring properties can be overridden outside the committed file; an external `application-local.yml` may also be loaded by the included `local` profile, although the POM excludes that file from packaged JAR resources. No production code, test, configuration or build/run command was changed for this review.

Proposed disposition, pending user direction: no fix is justified for the current defaults. When the ordinary deployment is running, inspect its effective debug/trace/error and logging settings without exposing secret values, and verify that ordinary error responses show no framework diagnostics.

If an unsafe override is actually found, remove that override in its existing configuration; do not add an actuator/debug endpoint or a new deployment step. An automatic startup guard could be considered separately, but would deliberately reject developer-supplied debug settings and is not proposed as necessary for the current finding.

Next checklist concern: item 63 dependency vulnerabilities.

<a id="audit-entry-86"></a>

## Security audit follow-up — item 61 error handling — implemented

Confirmed gap: `CustomRunService` previously put arbitrary sandbox exception text into an HTTP 500 reason. That text could include filesystem paths or Docker stderr. The global `server.error.include-message: always` preserved useful 4xx guidance but lacked a 5xx-specific sanitizer.

After user approval, `SafeErrorAttributes` now replaces framework-rendered 5xx messages with `Unable to complete this request`, adds a server-generated request ID, and removes path, trace, exception and binding-error fields. `SecurityHeadersFilter` places the same ID in `X-Request-ID` on responses, including errors; client-supplied IDs are ignored.

Explicit Boot settings disable response stack traces, exception classes and binding errors. `CustomRunService` no longer incorporates the caught exception message into a client-facing 500. `PersistenceConflictAdvice` uses the same fixed message/ID for its custom unexpected 500.

Existing 4xx statuses, guidance and response shape remain unchanged. The standard handler logs request ID, status and exception class; the custom advice logs ID and exception class, without raw exception payloads, consistent with item 59. The ID assists correlation but is not an independently stored incident record.

Dedicated real-HTTP tests injected internal path/SQL sentinels into an unexpected exception and verified sanitized JSON and HTML 500 responses plus matching UUID header/body; 400/401/403/409/429 guidance remained intact. A custom-run unit test injected a sandbox failure sentinel and verified a safe 500 and released run slot.

Focused 14-test run passed. Full `mvn clean test -Djavafx.platform=linux` passed 271 server + 1 client tests, zero failures/errors/skips, at 2026-09-24 13:29:36 +06:00. No new service, credential, build flag or launch step was added. This covers the application's standard Spring error path and reviewed custom 500 handler; it is not a claim about arbitrary future handlers that construct their own 500 bodies.

Next concern: item 62 debug mode. [OWASP Error Handling Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html) recommends generic responses for unexpected server failures.

<a id="audit-entry-87"></a>

## Security audit follow-up — item 60 tamper-resistant logs review

Verdict: the item-58 audit table is durable but not tamper-resistant. The loopback ADMIN endpoint only reads it and provides no delete/update route. Nevertheless the normal application datasource account has schema-local ALL PRIVILEGES (confirmed in the earlier item-54 live grant check and `scripts/init-db.sql`), so a database operator using that account or a compromised trusted server process can UPDATE, DELETE, TRUNCATE or DROP `audit_events`.

The same account runs Flyway and the normal audit-retention DELETE. Audit rows have no chained digest, external checkpoint, separate credential or independent destination. A missing record can be legitimate retention or hostile deletion; the application cannot distinguish them.

This is an integrity/investigation gap, not an additional API authorization bypass.

The checklist's stronger goal requires an independent trust boundary: for example, a separate collector or append-only destination with credentials that permit the application to submit events but not rewrite/delete them, plus delivery-failure monitoring and a way to compare history against the MySQL view.

Such a destination needs provisioning, access control, credentials/transport and backup or retention operations. Those are deployment changes contrary to the user's standing requirement not to change how the project is built and run. A second local file or a hash chain whose anchor/key the same server process can rewrite would offer limited recovery against database-only edits, but would not prevent a host operator or compromised server from silently rewriting both copies.

Do not label that as a complete fix.

Disposition: the user explicitly skipped item 60 under the unchanged build/run constraint. Revisit it when a separately controlled log destination is acceptable. If that constraint changes, design and test a write-only external audit sink with bounded delivery failure behavior, tamper/deletion detection, secure transport, retention and restoration before calling item 60 complete.

No code, database grants, log destination, credentials or tests were changed. Next checklist concern: item 61 error handling. This review is consistent with the [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html), which calls for protection against alteration/deletion and detection of missing or modified events.

<a id="audit-entry-88"></a>

## Security audit follow-up — item 59 secret-safe logging

Verdict and approved fix: the item-58 audit table already uses an explicit scalar field allowlist. Ordinary logs still contained raw checker compiler diagnostics and runtime output, session IDs on cleanup failure, full database exception objects, full contest titles at boot, and exception messages on the client WebSocket path. Compiler diagnostics can quote checker source and custom checker output can contain hidden test data; these were concrete disclosure paths. The earlier review did not establish an actual password or JWT leak.

`CheckerRunner` now logs problem IDs, fixed reasons and bounded numeric facts without compiler output/stdout/stderr/throwables. JudgeWorker logs the submission ID and exception class while retaining its prior outcome handling. Session invalidation warnings omit the SID, username and exception payload. `PersistenceConflictAdvice` logs a fixed error plus exception class; Spring's Hibernate `SqlExceptionHelper` logger is OFF by default because it printed duplicate user-supplied values even for expected 409 conflicts. The application still returns its existing 409/500 responses. Boot reset logs contest IDs/state instead of titles. Broadcast, material and connection warnings no longer dump exception payloads; material orphan-cleanup warnings retain only generated UUID-shaped stored names so operators can still locate files. Docker readiness logs no longer include raw CLI output, configured image names or executable paths, and the client WebSocket stderr prints only exception classes. Instructor-visible checker validation feedback and judge verdict behavior remain available.

Focused tests injected sentinel text into checker source diagnostics, checker output made from a hidden expected file, session IDs and SQL exception messages. The tests captured application log events and verified those sentinels and throwable proxies were absent while expected RE/session replacement/HTTP responses remained.

The focused 18-test run passed. The full 266-server + 1-client suite also passed without skips. Its duplicate-registration/clone tests ran; unlike the prior item-58 log, the item-59 log contained no `Duplicate entry` values. This verifies the default application configuration, not all possible operator logging overrides or third-party diagnostic settings.

No new service, credential, build flag or launch step was added. Next checklist item: 60 (tamper-resistant logs).

<a id="audit-entry-89"></a>

## Security audit follow-up — item 58 durable audit logging

Confirmed gap: important administrative actions lack a durable actor/action trail. AdminStandingsController updates marks, verdicts and penalties directly without preserving the previous value or acting administrator. AdminContestController creates/changes/deletes contests without an audit record.

Operational entities describe present state, not attributable change history. NotificationService is an in-memory, dismissible event feed, and authentication lacks a comprehensive persisted login-success/failure trail. No audit entity/table or configured persistent application audit sink was found.

This limits investigation of disputed changes and misuse; it does not itself bypass authorization.

Implemented after user approval. V83 creates audit_events automatically through existing Flyway startup; no new service, credential, launch flag, manual setup or changed build/run command. Administrative ORM inserts/updates/deletes are recorded by AuditEntityListener using the current authenticated HTTP action context.

Direct SQL contest deletion and existing statement-PDF replacement have explicit transactional records. Committed database history includes actor, controller action, entity/target ID, timestamp (exposed as UTC Instant), real socket-peer IP, and allowlisted before/after metadata (marks, verdict, penalty, contest state/settings, problem limits, roles, visibility and IDs).

No foreign keys tie history lifetime to deleted objects.

COMMITTED_CHANGE rows join the existing datasource transaction: rollback removes both business change and audit row, and inability to insert the audit row rolls back that transaction. Existing transaction boundaries are preserved, not widened around controllers: one multi-entity HTTP action can still partially commit across its existing separate transactions.

Each committed entity change remains attributable even if a later part of the request fails. Cascading direct-SQL child deletions are represented by the parent deletion event, not an individual row per child. Content-only edits still identify the action/target but intentionally omit content diffs.

Filesystem effects, in-memory notifications and direct DBA SQL are not independently audited as transactional entity changes; internal startup/judge operations are outside the administrative HTTP scope.

Separate REQUEST_COMPLETED/REQUEST_FAILED records cover admin mutation requests and login/register/logout, including early filter rejection. These are HTTP outcomes, not proof of commit or semantic acceptance (for example an import may return validation errors within a successful HTTP response).

Login failures record only a syntax-bounded claimedSubject, not a verified identity. Malformed requests may have no subject, and pre-auth failures may have no actor. Request logging is best-effort so logging failure cannot turn an already-established login into a misleading client failure; constant warnings omit exception values.

No password/hash, bearer token, key, raw body, query string, upload bytes, source, test input/output, title or message body is serialized. Entity snapshots use explicit field and scalar-type allowlists.

GET /api/admin/audit requires existing ADMIN and loopback gates. It returns newest-first pages (default 50, maximum 100); use the previous page's last id as exclusive before cursor. There is no delete/edit API or new UI. All failed tracked requests share a process-local limit of 200 detailed rows/minute; overflow receives a suppression-count summary when the window advances.

This intentionally loses per-attempt details during floods; pending counters can be lost on restart or logging failure. Minute sweeps retain the newest 100,000 ID slots and at most 90 days, with temporary excess between sweeps and potentially fewer retained rows due to rolled-back ID gaps.

This is not tamper-resistant against the same DB account/DBA (item 60).

AuditLoggingIntegrationTest adds six tests: live creation/deletion, actor/target/IP attribution and cursor/access checks; rollback and forced audit-insert failure; login success/failure redaction; clock-controlled failure flood summary and age retention; live marks/verdict/penalty changes and existing PDF replacement without source/upload capture; non-loopback denial despite forged forwarding headers.

The first four-test focused run passed. Final six-test/full-suite verification is recorded in the verification snapshot. Initial test fixture compilation mismatches were corrected; no application configuration or production DB was changed during testing.

Next checklist concern: item 59.

<a id="audit-entry-90"></a>

## Security audit follow-up — item 57 admin separation review

Admin pages and /api/admin operations are restricted by LoopbackAdminFilter to the request's loopback socket peer. Admin APIs additionally require an authenticated ADMIN role in Spring Security, and JwtAuthFilter checks the current database role/session binding.

Student tokens therefore do not gain admin powers merely by originating locally. server.forward-headers-strategy remains none, so forwarding-header claims do not establish loopback access in the supported direct deployment. The static login page is locally readable without a token; privileged data/actions are independently API-protected.

Administrative mutations reviewed use the admin namespace.

Admin sessions have a 30-minute meaningful-activity idle limit in addition to the shared absolute JWT lifetime; logout/replacement/role invalidation remain enforced. Existing tests cover representative non-loopback rejection, local student attempts against admin mutations and stale-role revocation.

No new live attack test or exhaustive path-normalization/proxy claim is made for this item. No separation bypass was found in the reviewed code. MFA remains explicitly user-deferred, sensitive-operation reauthentication is not implemented, and durable audit logging is reviewed separately under item 58.

No extra listener, service, VPN, build flag or launch step is proposed. Next concern: item 58 audit log.

<a id="audit-entry-91"></a>

## Security audit follow-up — item 56 judge worker credentials

Reviewed JudgeWorker's dependencies, SandboxExecutor's Docker command/environment/mount construction and the sandbox Dockerfile. No path forwarding application DB passwords/JWT secrets into participant, compiler or checker containers was found.

Container environment entries are explicit non-secret toolchain settings; mounts are limited to per-run work and metrics, networking is disabled, and the Dockerfile copies no application/configuration/credential files. Host Docker CLI processes inherit the server environment, but Docker does not automatically pass that environment into execution containers; no --env-file or bare secret-variable forwarding is configured here.

The trusted JudgeWorker is a Spring component with repositories and JdbcTemplate and therefore retains application datasource privileges. It is not a separately isolated worker with a narrowly scoped credential, so the checklist's stronger worker-privilege recommendation remains part of item 55's architectural limitation.

No change requiring a separate service/account/startup step is introduced. Existing SandboxExecutorTest report has 19 passing tests without skips, with environment, host-file, Docker-socket and network probes; this review did not add a dedicated sentinel-credential injection test or rerun the suite.

Verdict: no sandbox credential leak found in reviewed code; trusted worker credential separation is not implemented. Next concern: item 57 admin separation.

<a id="audit-entry-92"></a>

## Security audit follow-up — item 55 service separation review

Untrusted submissions, compilation and custom checkers execute in disposable Docker containers, not the web-server JVM or unrestricted host processes. SandboxExecutor disables networking/IPC sharing and uses an unprivileged UID, dropped capabilities, no-new-privileges, read-only root, resource bounds and narrow per-run mounts. It does not pass DB credentials or mount the Docker socket into these containers. This is source review backed by existing sandbox tests, not a new attack test or proof against all kernel/runtime escapes.

Trusted JudgeWorker/queue orchestration remains inside Spring and shares its datasource; the server and containers share a host, and the server can invoke Docker. A compromised trusted server process is therefore not isolated from database/daemon access.

Separate services/hosts would strengthen that boundary but require deployment changes contrary to the user's build/run constraint. No new service, dependency or configuration is introduced. Verdict: direct execution of untrusted code in the web process is not present; stronger trusted-service/host separation remains an acknowledged limitation.

Next concern: item 56 judge worker credentials.

<a id="audit-entry-93"></a>

## Security audit follow-up — item 54 database privileges review

Disposition: the user moved next; the proposed privilege reduction was not applied. Grants remain unchanged.

Read-only live verification on 2026-09-24 returned CURRENT_USER arbitrator@localhost, CURRENT_ROLE NONE, global USAGE only and ALL PRIVILEGES scoped to arbitrator.* and arbitrator_test.*. No GRANT OPTION was present. The application account is not root/a database superuser; these grants do not confer global FILE, SUPER or account-administration privileges. scripts/init-db.sql likewise grants schema-local ALL rather than global ALL. No account or grant was modified.

Remaining least-privilege gap: schema ALL includes capabilities such as routine/event/trigger management that current application code and migrations do not use. Flyway runs at ordinary server startup with the same datasource, so simply removing all schema-change permissions would break existing startup/migration behavior.

Proposed hardening, pending approval, is an explicit schema allowlist covering SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX and REFERENCES, verified against a fresh migration and existing integration flows before reducing the current account's grants.

Keep the existing setup command, credentials, build and launch workflow; do not require separate migration credentials or manual runtime provisioning. Retaining DROP/ALTER means this does not isolate application compromise from destructive schema changes; that is the compatibility tradeoff.

The trusted judge orchestration code shares the server datasource, while participant/compiler/checker Docker processes receive neither datasource credentials nor network access. Splitting trusted services is item 55 and is not implemented under this review. No new tests or production/config/grant changes were made. Next concern after the item-54 decision: item 55 service separation.

<a id="audit-entry-94"></a>

## Security audit follow-up — item 53 secret rotation review

Verdict: the current single-server design permits credential replacement without a rebuild; no mandatory production fix is indicated. Default JwtService creates a fresh private signing key per process, and ActiveSessionRegistry is process-local.

Restart therefore replaces the default key and invalidates sessions; users log in again. If an operator deliberately configures a fixed signing key, it can be changed through runtime configuration then restart. Database password replacement requires coordinating the MySQL account change with the application's external configuration and restart.

Spring's standard SPRING_DATASOURCE_PASSWORD can override a literal classpath application-local.yml password; changing only the DB_PASS placeholder input may not override that local value. Do not claim automatic DB rotation, hot reload, zero downtime or persistence of active sessions across restart.

No SMTP/cloud/provider-API credential integration was found in the application sources. Existing JwtServiceSecurityTest's six-case report passed without failures/errors/skips, including independently generated keys rejecting each other's tokens, weak configured-secret rejection and expired-token rejection.

This turn inspected that prior report and source; it did not rerun tests, rotate credentials, change runtime configuration or implement new features. Item-52 repository exposure remains separately deferred by the user. Current build/run workflow is unchanged.

Next concern: item 54 database privileges.

<a id="audit-entry-95"></a>

## Security audit follow-up — item 52 secrets review

Disposition: the user chose to leave this unchanged ("that's okay for now"). Item 52 is deferred; the proposed cleanup was not implemented. Next concern: item 53 secret rotation.

Confirmed: arbitrator-server/src/main/resources/application-local.yml is tracked at HEAD despite its git-ignored/local-only comment. Its literal datasource password matches the public development default in application.yml; this is not evidence of a separate production credential.

Five local-history revisions of the file contain literal passwords. .gitignore only excludes build/data directories, so later personal credential edits can be committed accidentally. The bundle generator excludes the exact local YAML and .env names but not other secret-file variants; the legacy bundle does not contain the current local password literal.

A targeted scan of 329 tracked files found no private-key blocks or common GitHub/AWS/Stripe token patterns. This is not an exhaustive entropy, all-history or all-provider scan. Production JwtService generates a private per-process signing key unless an override is supplied; test-profile signing values are test fixtures. Bootstrap database/demo credentials are public defaults, not confidential values protected by ignore rules.

Proposed fix awaiting approval: untrack the local config while retaining its working copy/runtime values; add scoped ignore rules for local configs, environment secrets and private keystores, preserving example templates; apply corresponding exclusions to the text-bundle generator.

Preserve current build/run commands, local behavior and new-checkout defaults. This prevents future accidental publication but does not erase history or make public development credentials private. No rotation, history rewrite, production/config/index changes or tests were performed for this review.

<a id="audit-entry-96"></a>

## Security audit follow-up — item 51 TLS configuration

Reviewed the checklist's obsolete-protocol/weak-cipher, certificate-renewal and expiry-monitoring requirements after the user-requested item-50 removal. The supported application deployment currently uses HTTP/WS and has no configured TLS listener or certificate lifecycle to harden.

Item 51 is therefore deferred with item 50, not marked secure or passed. Enabling it would reintroduce the certificate/runtime setup the user explicitly removed. No code, configuration or tests were added. Preserve the user's requirement that future fixes must not change the build/run workflow.

Next concern: item 52 secrets in Git/configuration.

### Priority 1 — correctness and security hardening

The earlier full-codebase audit's original Priority 1 findings and item-36 registration/cloning error mapping/problem-edit lost updates were fixed as documented above. This is a historical scope statement, not closure of the subsequent security checklist: the fork/OOM reporting failure and other unresolved risks are listed in OPEN_SECURITY_ITEMS.md.

### Priority 2 — operational and quality risks

7. The judge backlog is globally bounded and every Docker execution shares active-container and aggregate-memory admission; tune those limits for the deployment host before a large contest.
8. Source-ban regexes scan raw text and can match prohibited API names in comments or string literals.
9. HTTP/WS traffic remains exposed to LAN interception. Mandatory TLS/certificate setup was explicitly removed by the user; preserving the existing build/run workflow is required.
10. Uploaded materials are outside MySQL and require coordinated filesystem backup and restore.
11. Active sessions, presence, notifications, the judge executor, and broadcast schedules are in-memory and assume one server process.
12. Sandbox output capture has a byte-level cap and an explicit output-limit flag; decoded Java string length is not the sole enforcement boundary. Do not carry forward the earlier claim that non-ASCII output bypasses the capture limit.
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

Use [OPEN_SECURITY_ITEMS.md](security.md) to choose the next unresolved concern with the user; explain and obtain fresh approval before changing a deferred item. The latest full reactor failed as recorded above; the security backlog is not fully closed.

Separate release work remains: real multi-client LAN/visual acceptance, packaging hygiene and verified backup/restore. These are not approved by this roadmap. Rejudge and float tolerance are optional feature work, not prerequisites granted by a supposedly green security backlog. Do not change build/run steps or reopen declined host/TLS fixes without explicit direction.

## Definition of v1.0-ready

Do not call the project v1.0-ready until:

- Priority 0 and Priority 1 defects have tests and verified fixes.
- The full suite runs without environmental skips on the target deployment machine.
- Student and instructor flows pass on the real LAN with at least 20 concurrent clients for a two-hour rehearsal.
- Docker, MySQL, material storage, restart behavior, and backup/restore have an operator-tested runbook.
- Ubuntu client packaging and installation are repeatable on a clean participant machine.
- Documentation and generated release artifacts are produced from the same tagged commit.
