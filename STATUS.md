# Arbitrator — implementation status

**Last audited:** 2026-09-17
**Version:** `0.1.0-SNAPSHOT`
**Readiness:** feature-rich beta; suitable for controlled demonstrations and supervised lab trials, not yet production-ready

This file describes the source as it exists now. `WORKFLOW_PLAN.md` contains the current roadmap and the historical sprint mapping; `rules.md` remains the authority for team ownership and Git workflow.

## Verification snapshot

Latest full Maven server run for item 37 on 2026-09-17:

| Result | Count |
|---|---:|
| Tests discovered | 229 |
| Passed | 229 |
| Failed | 0 |
| Errors | 0 |
| Skipped | 0 |

One client source-policy test also passed: 230 total across modules. Full command `mvn test -Djavafx.platform=linux` finished successfully at 13:18 +06:00 with local Docker/MySQL available. All thirteen timing cases and the real microsecond timestamp round-trip passed alongside race, conflict-advice, symlink, sandbox and WebSocket protections. This replaces the item-36 full-run snapshot, while historical test counts below are preserved.

The previous fully green item-22 Maven run used local MySQL and Docker: all sandbox, worker, checker, STOMP, contest-access, IDOR, role-escalation, material-deletion, registration-policy, duplicate-policy, ZIP-import, HTML-sanitization, and admin-rendering tests passed without skips. One additional client source-policy test passed (150 total across modules). The role-escalation suite covers registration-field injection, unsigned role modification, sid-less legacy tokens, weak/public signing secrets, and immediate revocation after database demotion. This run does not replace a real LAN rehearsal.

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

Seven focused tests cover an exact repeat, five representative whitespace changes, and problem scoping. They remain included in the latest full build, which passes without skips.

### ZIP size and path enforcement — fixed 2026-09-15

The importer no longer trusts `ZipEntry.getSize()`, which is commonly unknown for streamed entries. It reads through the 32 MiB per-file cap and probes one additional byte, rejecting the whole package instead of accepting a truncated prefix. Existing 5,000-entry and 256 MiB aggregate-uncompressed limits remain enforced.

Entry names are canonicalized before validation and storage: backslashes become forward slashes, redundant separators and `.` segments are removed, and absolute paths, `..` traversal, NUL characters, empty paths, and duplicate canonical names are rejected. This prevents later entries from replacing an earlier configuration, statement, or test through a path alias. Three tests cover the exact boundary, a streamed unknown-size 32 MiB + 1 byte entry, and a slash/backslash collision. The latest full Maven build includes these and the sandbox, network-isolation, compiler-hardening, command-execution-policy, IDOR, role-escalation, and resource-exhaustion regression suites, without skips.

### Role escalation — fixed 2026-09-16

The committed JWT signing key fallback was removed. Initially startup required `ARBITRATOR_JWT_SECRET`; that setup requirement was subsequently removed at the user's request (see the key-setup revision below). Explicitly configured short/placeholder keys are still rejected, and token creation requires an active session ID. HTTP authentication no longer trusts the JWT's `role` claim: after signature and active-session validation, it loads the account's current role from MySQL. A demoted administrator therefore loses admin access on the next request rather than up to twelve hours later, and previously accepted sid-less tokens are rejected.

Seven focused tests cover public/weak signing secrets, missing session IDs, registration role-field injection, payload modification without resigning, sid-less signed tokens, and live admin demotion. The complete 118-test suite passed with MySQL and Docker available and no skips.

### Rich-HTML stored XSS — fixed 2026-09-16

Statements and announcements now use a shared jsoup allowlist on persistence and response generation, protecting both new content and existing database rows. Problem imports, clones, edits, detail responses, and announcement publication/history all apply the same policy. Basic formatting, tables, and math superscripts/subscripts survive; active content, event handlers, styles, links, images, forms, and embedded/remote content are removed. Content that sanitizes to blank is rejected on upload/publication. PDF raster rendering is unchanged.

The instructor announcement draft preview displays literal HTML source, the statement iframe has an empty sandbox, and all three JavaFX HTML viewers disable JavaScript before loading. Eight additional server checks cover sanitizer behavior, imported-content persistence/rejection, announcement publication/legacy reads, and safe admin rendering. One client source-policy check verifies disabled JavaScript in the three display paths. The full Maven build passed with 126 server tests plus that client check and no failures/errors/skips. This change was verified by automated tests and compilation, not a new live JavaFX/browser replay.

### Reflected XSS — attack-tested 2026-09-16

Checklist item 13: no reflected-XSS vulnerability found in the reviewed paths or dedicated HTTP attack matrix. `ReflectedXssAttackIntegrationTest` starts the real server on a random loopback port against `arbitrator_test`. Three tests send 40 hostile requests: four script/image/SVG/script-breakout payloads across `search`, `error`, `message`, and `redirect` on the static admin page; rejected registration and missing routes with both JSON and HTML Accept headers; and authenticated admin numeric-parameter binding errors that genuinely echo the attack marker. A separate baseline fetch confirms query payloads do not change the admin HTML. Tests validate JSON parsing/content types, `nosniff`, and absence of active elements/event-handler attributes in HTML error responses.

The focused run `mvn test -pl arbitrator-server -Dtest=ReflectedXssAttackIntegrationTest` passed all three tests with zero failures/errors/skips. An initial test assertion incorrectly expected the exact original payload in numeric conversion errors; Spring strips whitespace there, so the assertion now requires the attack marker while still checking response safety. No production changes were needed. The temporary admin account and session are removed after the test. This is HTTP-response verification, not browser execution or proof against every possible payload. These three tests are now included in the latest full-suite snapshot above. The subsequent separate DOM-XSS audit is recorded below.

### DOM XSS — browser attack-tested 2026-09-16

Checklist item 14: no exploitable DOM-XSS path found in the reviewed code or the dedicated Chromium rendering matrix. `scripts/security/dom-xss-attack.cjs` loads the actual instructor HTML/JavaScript with intercepted fixture responses, without a live server, real accounts, or database mutations. Four image-error, SVG-load, script-breakout, and attribute-breakout payloads are exercised across nine paths: live notifications, notification list/history, clarifications, submission test output, announcement drafts, sanitized published announcements, sandboxed statements, and standalone exported HTML reports. Published fixtures use the actual Java `RichTextSanitizer`; a raw statement payload separately challenges the iframe sandbox. Server-controlled enum/numeric DTO fields remain valid, rather than inventing attacker access to them.

Chromium 151.0.7922.34 passed the matrix with zero executions, dialogs, or page errors; checked parent DOMs and reports had zero active payload elements or injected handlers. Text remained literal and safe superscripts survived. A positive control successfully executed both a script and an image-error handler before the negative tests. Playwright was already bundled but its browser executable was absent, so Chromium was downloaded into `/tmp/arbitrator-dom-xss-browser.zHnp0G`; no production dependency/configuration was changed. The reusable harness, Java fixture helper, prerequisites, rerun instructions, and limitations are documented in `scripts/security/README.md`. No production fix was indicated. This is controlled browser rendering coverage, not every possible payload, JavaFX execution, API authorization, or an end-to-end publication workflow. Checklist item 15 (Markdown security) is next.

### Session fixation and privilege-change renewal — fixed 2026-09-16

Checklist item 19: login/registration already generated fresh random server-side session IDs, but a manually promoted database account could previously use its existing student session with admin permissions. The approved fix records the login-time role alongside the `sid` in `ActiveSessionRegistry`. HTTP authentication, WebSocket handshakes, and incoming STOMP commands compare that server-held role with the current database role. Promotion, demotion, or account deletion revokes the matching session; HTTP returns 401 and requires fresh login. Registry invalidation notifies existing WebSocket transports to close. Credential-verified login also retires an old-role session before creating its replacement, without requiring force-login. Authorization still uses the current database role, not the JWT role claim.

Role validation/removal is atomic against registry replacement: an old request cannot revoke a newer session with a different ID. Detection occurs on the next authenticated HTTP request, WebSocket handshake/incoming command, or credential-verified login—not at the instant of an out-of-band SQL change. An idle socket may remain connected until such detection; there is no database role-change event or polling mechanism. There is still no role-management API. Any future API should explicitly revoke sessions when changing roles.

The promotion/demotion HTTP integration tests confirm rejection of old tokens, successful re-login at the correct new privilege, distinct replacement IDs, and continued rejection of prior IDs. Registry tests cover role revocation, account deletion, exact transport notification, and protection of replacement sessions against stale requests. The complete Maven run passed 131 server tests and 1 client policy test with zero failures/errors/skips and local MySQL/Docker available. No new live JavaFX replay was performed. Checklist item 20 (logout) is next.

### Recent audit choices and deferred coverage

- Item 15 (Markdown): reviewed as safe because `.md` is escaped literal text in a `pre` block, not parsed Markdown, then sanitized. User moved on without adding the proposed importer attack test.
- Item 16 (CSP): no CSP configured; user deferred this defense-in-depth fix. Inline admin scripts/handlers still need refactoring before strict script CSP can be enabled. This gap remains open despite passing XSS attack tests.
- Item 17 (CSRF): code review found explicit bearer-header authentication, no authentication cookies, and no state-changing GET endpoints. No conventional CSRF path indicated; user moved on without the proposed dedicated cross-site tests.
- Item 18 (cookies): no application authentication-cookie creation found; admin token is memory-only, with theme preference alone in local storage. Cookie flags are not applicable to this design. No dedicated live cookie-header test was performed.

### Logout — fixed and attack-tested 2026-09-16

Checklist item 20: authenticated logout now revokes the exact verified request `sid`, not whichever session happens to belong to the username when logout executes. `JwtAuthFilter` supplies a server-side verified-session request attribute; `AuthController` requires it, and `ActiveSessionRegistry.clear(username, sid)` atomically removes only a matching session. A superseded in-flight logout cannot revoke a replacement login or notify its transports. The unconditional registry cleanup overload remains for administrative/test cleanup only. Successful revocation closes sockets tracked under that ID and updates presence when there is no replacement session.

The admin console now has a listener-bound Sign out button that posts its bearer token to the logout endpoint. It drops local credentials and reloads the page to discard timers, pending polling state, and sensitive DOM content. HTTP 401 is treated as an already-invalid token; server/network errors display an explicit local-only logout warning. JavaFX similarly warns after local sign-out when server logout is unconfirmed, rather than silently implying server revocation. Offline logout cannot guarantee remote invalidation: a copied token may remain valid until expiry or replacement login.

The new live HTTP/WebSocket integration replay registers a disposable account, logs out its active socket, confirms transport closure, rejects copied-token REST access and WebSocket reconnection, and successfully logs in without force under a fresh token. A deterministic registry interleaving test confirms old logout cannot clear a replacement session and exact IDs are notified. `scripts/security/logout-browser.cjs` passed actual-admin-page Chromium checks for success, already-invalid token, server failure, and network failure with controlled responses and no real accounts. The full Maven build passed 133 server tests plus 1 client policy test with no failures/errors/skips. JavaFX compiled but its warning was not exercised in a live GUI. Initial test compilation errors (missing assertion import and misnamed reused helper) were corrected before the passing full run. Current next checklist item: 21 (session expiration).

### Signing-key setup requirement — removed at user request 2026-09-16

Admin sign-out presentation follow-up: the button uses the existing red danger treatment, spans the sidebar footer width, and has 16 px top separation plus increased internal padding. All four existing Chromium logout scenarios passed again after this visual change.

After local startup failed because `ARBITRATOR_JWT_SECRET` was unset, the user explicitly requested removal of mandatory signing-key setup. `application.yml` now defaults the optional setting to blank; `JwtService` generates a cryptographically random private HS256 key in memory when the setting is absent, null, or blank. No setup script, persisted key file, public shared fallback, dependency, or manual environment step is added. An optional explicit key remains supported and validated for strength/placeholders. A fresh process cannot validate another process's default-key tokens. Users must re-login after restart; the in-memory active-session registry already requires this even with an explicit key.

Setup examples, README, and AI handoff notes were updated. The focused signing-key and role-escalation suite passed nine tests with zero failures/errors/skips; the new test confirms unconfigured/blank startup behavior, same-instance token validation, and rejection by independent service instances. At that stage, the full-suite snapshot was the prior 133-server-test run. The later item-21 full run also verifies this revision. The security audit remains paused before checklist item 21 (session expiration).

### Session expiration — implemented 2026-09-16

Checklist item 21: approved absolute-expiry cleanup and admin idle timeout are implemented. The registry now stores fixed `expiresAt` and `lastActivity` timestamps for each role-bound session. Absolute lifetime comes from `arbitrator.jwt.expiry-hours` (12 hours by default) and is never extended by activity. ID/role checks also enforce registry expiration, so existing WebSocket commands cannot bypass the limit. A scheduled sweep nominally every 15 seconds removes expired entries and notifies the socket tracker to close their connections even without incoming traffic. Request checks expire entries immediately; idle socket closure can wait for the sweep and scheduler availability. Normal re-login replaces expired entries without requiring force-login.

Admin idle timeout defaults to 30 minutes. Authenticated mutations and explicit `/api/auth/activity` POSTs refresh idle time; read-only GET/HEAD/OPTIONS requests and WebSocket validation/heartbeats do not. The admin page sends activity on pointer/keyboard events, throttled to once per 30 seconds. Common API 401 responses clear its token, explain the ended session, and reload the login gate. Activity requests require verified bearer authentication and cannot revive an expired session. Student idle expiration defaults to disabled to avoid interrupting long coding sessions; deployments may configure `arbitrator.session.student-idle-minutes`, with meaningful HTTP mutations/activity then required to keep that optional idle policy alive. Student absolute expiry remains enabled.

`SessionExpirationTest` uses an injected clock to test exact idle/absolute boundaries, activity without absolute renewal, rejection of attempts to revive expired sessions, participant idle configuration, cleanup/re-login, and real socket-tracker notification with a proxy test socket. This is not a live timed WebSocket replay. Additional tests reject expired signed JWTs and require authentication for the activity endpoint. `scripts/security/session-activity-browser.cjs` passed actual-admin-page Chromium checks for polling/input throttling and expired-session UI cleanup. Existing DOM-XSS and logout Chromium matrices also passed after these changes. Final full-suite verification is recorded below. The security audit's next item is 22 (password storage).

Final item-21 verification: `mvn test` passed 140 server tests plus 1 client policy test with zero failures/errors/skips and local MySQL/Docker available. This full run also includes the earlier optional-signing-key revision. One intermediate run hit `HttpURLConnection`'s streaming-POST handling of the expected anonymous 401; the authentication assertion now uses the JDK HTTP client and passed in the final full run. The expiry-triggered socket test is clock-driven with a proxy transport, complemented by existing live logout/replacement WebSocket integration tests. No live JavaFX expiration replay was performed. Current next concern: 22 (password storage).

## Security audit follow-up — item 22: password storage

Approved and implemented the bcrypt boundary fix. Production bcrypt remains cost 12 with random per-password salts. `PasswordLengthPolicy` rejects inputs above 72 UTF-8 bytes with a clear HTTP 400 before account registration/login and contest creation/password verification reach bcrypt. Unicode is measured in bytes, not Java character count. No trimming, truncation, database migration, hash rewrite, or Argon2 migration was introduced. Null/blank contest creation remains optional within the limit; overlong supplied passwords are rejected even for an open contest. Null account login returns generic invalid credentials.

Compatibility: existing hashes and ordinary passwords remain valid. A previously stored overlong password cannot be reconstructed from its hash; full overlong login input now fails validation. Operators must arrange a compliant replacement using their existing recovery/database administration process. This change does not introduce a password-reset endpoint, and cannot retrospectively recover or distinguish suffixes bcrypt already discarded.

Dedicated service-level attack tests cover exact 72-byte ASCII, two-byte Unicode, and four-byte emoji passwords, rejecting suffix-extended variants during creation and verification before persistence. A raw-bcrypt positive control reproduces the suffix collision and verifies randomized salts; service tests then demonstrate rejection of those attacks. These are real bcrypt service tests, not live HTTP/browser password replays. Full `mvn test` verification passed 149 server tests plus 1 client source-policy test (150 total), with zero failures, errors, or skips and local MySQL/Docker available. Nine new password-boundary tests passed. Next concern: 23 (password policy), requiring its own verdict and approval.

## Security audit follow-up — item 23: account password policy

User declined a 15-character minimum and approved rejecting spaces/common choices while retaining the existing 8-character minimum. New account registration shares `AccountPasswordPolicy` between server and JavaFX client: reject Unicode whitespace/space characters, control characters, a small case-insensitive offline common-password list, and single-code-point repetition. Exact input is never trimmed or normalized; ordinary punctuation and Unicode remain allowed. The server's 72-byte UTF-8 bcrypt cap remains. Registration guidance was updated. The list is limited, not comprehensive breached-password screening or full modern password-policy compliance.

Existing logins are not subjected to these new registration rules: legacy weak/spaced passwords and hashes continue to work within the byte cap. Contest-password rules and demo credentials are unchanged; demo accounts remain unsuitable for production. No 15-character rule, mandatory character categories, reset endpoint, forced migration, or external password service was introduced.

Dedicated service tests cover leading/trailing/interior spaces, tabs, newlines, Unicode spaces, controls, mixed-case common choices, sequences/repetition, acceptance of an eight-character non-common password, and unchanged legacy authentication. Byte-boundary tests now use non-repetitive ASCII/Unicode/emoji input. HTTP/WS integration fixtures use compliant registration passwords without weakening authorization assertions. No new live JavaFX UI replay is claimed. Both full runs executed 167 server tests: 166 passed and one existing contest WebSocket connected-state assertion failed, with zero errors or skips; the client module was skipped by reactor failure. That four-test contest suite passed independently. The previous fully green snapshot remains 149 server plus 1 client test. Dedicated password/client verification is recorded below. Next concern: 24 (brute-force protection), requiring its verdict/proposal and approval.

Final item-23 targeted verification: the root build compiled all modules and passed 31 UserService tests, 5 contest password-boundary tests, and 1 client source-policy test, with zero failures/errors/skips. Eighteen new account-policy cases were added. The standalone four-test contest-access suite also passed. The two full-suite connected-state failures remain an unresolved suite-dependent verification caveat, not a claimed password-policy failure or a fully green release result.

## Security audit follow-up — item 24: brute-force protection

Approved and implemented `LoginThrottle` at the public login endpoint. Admission atomically reserves account and socket-peer IP budgets before bcrypt. Defaults: 20 attempts/account and 120 attempts/IP per 60-second fixed window, at most 2 simultaneous attempts/account and 16/IP. After 5 account failures or 20 IP failures, apply a 5-second cooldown doubling with subsequent admitted failures, capped at 300 seconds. Denied retries do not extend penalties. All admitted attempts count toward fixed-window limits; successful credential verification (including the existing-session 409 flow) clears the account failure streak, not its attempt budget or shared IP history. Server failures release reservations without adding credential failures. Existing signed-in sessions are not revoked by attacks.

Submitted account keys are trimmed/case-folded and SHA-256 hashed for bounded-size storage. After lookup, admission is bound to the DB's stored account identity before hashing, preventing MySQL collation-equivalent Unicode aliases from bypassing per-account limits. Unknown usernames consume IP and submitted-account budgets. `force=true` does not bypass admission. HTTP 429 includes Retry-After and a user-facing retry interval; existing admin/client error rendering displays the message. Cooldowns use timestamps, not sleeping request threads. Warn logs record cooldown scope/duration without submitted usernames, IP headers, passwords, or JWTs.

Configuration is under `arbitrator.auth.login` in application.yml, with positive-value validation. State is bounded to 10,000 keys; stale idle keys are cleaned periodically on requests without evicting active penalties/in-flight reservations. Capacity exhaustion temporarily rejects new keys (availability tradeoff). State is process-local and resets on restart; replicas need a shared limiter. `server.forward-headers-strategy: none` deliberately trusts socket peers only. A reverse proxy would appear as one shared IP; proxy trust/network isolation and limits must be designed explicitly before deploying behind one. Shared lab networks may need IP threshold tuning. Temporary per-account cooldowns can still briefly affect legitimate users under targeted attack; no permanent lockout is introduced. This scope protects login, not registration floods or repeated contest-password guessing.

Clock-driven tests cover exact recovery, escalating/capped delays, account/IP rotation, success reset rules, fixed-window budgets, atomic concurrent admission, IP concurrency, bounded-state cleanup, and stored-identity alias binding. Live HTTP attacks with real password verification test account/IP failure bursts, force-login, case/whitespace and accented aliases, spoofed Forwarded/X-Forwarded-For headers, Retry-After/message, and continued use of a victim's existing bearer session. No live cooldown sleep or JavaFX GUI replay is claimed. Final full `mvn test` passed 176 server tests plus 1 client policy test (177 total), zero failures/errors/skips, with MySQL and Docker available. All nine new throttle tests passed, including the live HTTP matrix. The earlier item-23 WebSocket assertion passed in both item-24 full runs; no separate WebSocket fix was made, so its prior suite-dependent failures remain historical test-stability observations. Next concern: 25 (MFA), requiring its own verdict/proposal and approval.

## Security audit follow-up — items 25–26: MFA deferral and password reset

Item 25 (MFA) was explicitly skipped by the user. No MFA implementation was added; ADMIN authentication remains password-only with the existing login throttle and admin-route restrictions. This is an accepted deferral, not a security pass.

Item 26 (password reset) source review found no forgot-password/change-password endpoint, client recovery UI, reset-token storage, or application recovery workflow. Account hash writes are limited to registration and demo seeding; contest hash creation is separate. Reset-token randomness, single-use, expiration, enumeration, and session-revocation requirements are therefore not applicable to an implemented reset flow. No dedicated reset attack test or feature implementation is claimed. Missing recovery is an operational limitation, not evidence of an insecure reset endpoint. Manual DB hash replacement does not itself revoke process-local sessions; operators must explicitly invalidate sessions or restart the server when replacing a compromised password. Do not introduce a recovery feature without separate approval and an identity-verification design. Next checklist concern: 27 (email verification).

## Security audit follow-up — item 27: email verification

Source review found no email field in the user entity or registration/login DTO, no email identity/recovery flow, and no mail delivery or email-verification implementation in server/common/JavaFX production code. Authentication uses student ID/username rather than email. Email ownership verification is therefore not applicable to the current system; no feature or dedicated attack test was added. This does not establish ownership of self-registered student IDs, which is a separate identity-integrity question. Next concern: 28 (user enumeration).

## Security audit follow-up — item 28: user enumeration

Approved scoped login fix: UserService generates one dummy hash at construction using the injected PasswordEncoder (production bcrypt cost 12). For a non-null, byte-compliant password, both unknown-user and wrong-password paths execute one password match before returning the same 401 Invalid credentials. Unknown users also pass through the pre-verification admission callback; endpoint IP/account throttling remains in front of bcrypt. A dummy match cannot authenticate a nonexistent account. No per-request dummy hash generation, sleep, fake session, or database hash migration was added. Null/overlong password validation remains independent of account existence.

Dedicated service tests instrument real bcrypt to assert exactly one check at configured cost on both failure paths, dummy-hash reuse, rejection even when supplied input matches the dummy hash (force=true), and rejection by admission before password verification. A live HTTP test at production cost alternates existing/unknown usernames, checks identical status/error/message/path fields (timestamps naturally differ), discards warm-up pairs, and compares six samples per path with a broad median-ratio bound to catch the old no-bcrypt fast path. Test-only throttle thresholds allow measurements without hitting cooldown; the separate brute-force attack suite still tests limits. This reduces the obvious hash-work timing discrepancy, not a claim of constant-time DB/network responses or elimination of every account-existence signal.

Registration intentionally retains 409 Username already taken and success-session issuance as the approved usability tradeoff, so account existence is still discoverable there. Session-conflict 409 remains gated behind correct credentials. DB collation/cooldown history can still affect responses; no full anti-enumeration redesign was approved. Final full `mvn test` passed 180 server plus 1 client policy test (181 total), zero failures/errors/skips, with MySQL and Docker available. The four new enumeration tests passed. Live measured medians were 241.4 ms for an existing account with a wrong password and 231.3 ms for an unknown account (ratio 0.96). These local samples are a regression check, not a universal timing guarantee. Next concern: 29 (rate limiting), requiring its verdict/proposal and approval.

## Security audit follow-up — item 29: HTTP API rate limiting

Approved and implemented bounded, process-local `ApiRateLimiter` with configurable fixed windows under `arbitrator.api-rate-limit`. Socket-peer IP admission runs after the existing loopback restriction but before JSON body buffering/authentication/controller work. Canonical authenticated-account admission runs after authorization. Limits aggregate across IDs, query strings, token replacements, and accounts sharing an IP; separate categories prevent run/join/clarification floods from consuming the user's read category directly. Admission and total/category counters are atomic, including parallel calls. Rejected requests return JSON HTTP 429, Retry-After, and no-store; existing UI error handling displays the retry message. Login's specialized failure/concurrency throttle and all judge queue/container/memory/custom-run admission controls remain in place.

Default 60-second budgets: per-account total 1200, reads 600, generic writes 120, joins 10, custom runs 12, submissions 6, clarifications 5. Per-IP total/reads 12000, generic writes 2400, registration 30, login 600, joins/runs/submissions 240 each, clarifications 120. IP thresholds provide shared-network headroom but must be tuned for actual lab size. Unknown write categories use the generic write budget. State is capped at 20,000 total/category keys; expired windows are cleaned on requests, and exhaustion rejects new keys rather than evicting live budgets. Budgets count admitted attempts even if later validation fails. Fixed windows permit boundary bursts; limits are not a throughput guarantee or protection against all distributed/volumetric DoS. State resets on restart and replicas need shared counters. Forwarded headers are not trusted; reverse-proxy topology needs explicit design before deployment. Static files and WebSocket/STOMP traffic are outside this HTTP API limiter. Reset/email/search-specific features do not currently exist; any future API endpoints still receive generic protection.

SubmissionService now uses 64 fixed lock stripes around each user's cooldown/backlog/duplicate/persistence/queue admission, so parallel calls cannot both pass the accepted-submission cooldown. Stripe collisions can briefly serialize different users but lock memory is bounded. Failed submissions do not advance the accepted-submission timestamp. The tracked local configuration now keeps the default 10-second cooldown; the separate test profile deliberately overrides it to zero for rapid existing integration fixtures. The new endpoint submission rate limit remains enabled in tests.

Dedicated clock/concurrency/filter tests cover atomic flood budgets, exact recovery without extending blocked windows, scopes/categories/aggregate budgets, bounded-state expiration, category ID/matrix handling, IP-header spoofing, token/IP rotation, and representative 20-student polling/signup capacity. A direct service regression widens the old cooldown race and requires exactly one persistence/enqueue and one 429. Live HTTP tests use low isolated thresholds to flood run/submission/clarification/join categories with invalid or nonexistent-resource requests, change contest IDs and bearer sessions, verify account isolation, exercise 20 normal read requests then read exhaustion, block registration despite spoofed headers, and leave static admin HTML available. They do not mass-create accounts, clarification rows, or judge executions. One initial test compile failed because the new fixture referenced an incorrect language enum; it was corrected before verification. Final full `mvn test` passed 191 server plus 1 client source-policy test (192 total), zero failures/errors/skips, with MySQL and Docker available. All eleven new rate-limit/submission-race/HTTP-flood tests passed; existing login throttle, enumeration, authorization, and sandbox tests also passed. Next concern: 30 (submission queue abuse), requiring its verdict/proposal and approval.

## Security audit follow-up — item 30: submission queue abuse attack tests

User approved dedicated tests only; production queue policy/implementation is unchanged for this item. `JudgeQueueLimitTest` now subjects the real JudgeQueue semaphore and executor to 10,000 admission attempts from 32 concurrent caller threads, with four controlled workers held busy and capacity 500. Exactly 500 are admitted and 9,500 rejected; observed outstanding depth never exceeds 500 and worker concurrency never exceeds four. After release, the test verifies full capacity recovery (without extra permits) and admission of a subsequent job. Separate tests verify complete reservation/depth recovery after controlled worker exceptions and executor shutdown/rejection.

`SubmissionQueueAbuseTest` uses in-memory repository and queue-admission doubles to exercise the real SubmissionService: block at 20 unfinished jobs, stay blocked at 19 and five, resume below five, isolate another user, admit exactly one of 16 parallel requests starting at 19, reject global saturation before persistence/enqueue, and release reserved capacity after a persistence failure without consuming the accepted-submission cooldown. Together with the real queue tests, these validate service backpressure and queue capacity, but are not a live HTTP 10,000-request replay or a 10,000-container/real-MySQL load test. The earlier item-29 live HTTP flood matrix remains complementary coverage. Targeted run passed all eight tests (seven new, one existing), without failures/errors/skips. Final full `mvn test` passed 198 server plus 1 client source-policy test (199 total), zero failures/errors/skips, with local MySQL and Docker available. All eight dedicated queue/service tests passed in the full run.

Verdict: bounded global queue/worker concurrency, per-user backlog hysteresis, and account/IP rate controls withstand the tested admission attacks. There is no dedicated per-IP backlog quota or per-contest queue; the shared global queue remains the user's chosen design. Bounded capacity prevents queue growth, not worker monopolization or guaranteed fairness. Next concern: 31 (fair scheduling), requiring its own verdict/proposal and approval.

## Security audit follow-up — items 31–32: scheduling deferral and output bombs

Item 31 fair scheduling was explicitly declined by the user. FIFO/shared global queue policy is unchanged; no per-user active-judgment quota or round-robin scheduling was introduced. Capacity/rate controls do not guarantee fairness or prevent worker monopolization.

Item 32 source review confirms 1 MiB byte capture caps on each stdout/stderr stream, container kill on overflow, truncated stored/compiler output, and an existing live Docker stdout-flood test that passed in the latest full suite. OLE is supported, though time/memory classification takes precedence if those conditions also apply. Sandbox container creation does not specify a logging driver, so daemon logging configuration is inherited. Docker's default json-file driver lacks rotation; captured output bounds do not themselves bound daemon-side log storage. Proposed fix (pending approval): disable per-container logging for sandbox executions while retaining attached output capture, then extend dedicated floods to stderr/combined streams and verify normal output and logging configuration. No output-bomb fix is implemented on this review turn.

## Security audit follow-up — item 32 deferral and item 33 fork bombs

The user explicitly declined the item-32 Docker logging change and moved on. No log-driver override or extended output-flood tests were implemented; the inherited daemon-log disk-risk finding remains deferred. Earlier item-32 proposal text is historical, not pending authorization.

Item 33 review confirms Docker `--pids-limit` of 64 for executions and 200 for compilation, alongside CPU/memory/container concurrency limits and timeout/container cleanup. The existing malicious `while(true) fork()` Docker fixture passed in the latest full item-30 suite, with its test case reporting 2.471 seconds and no skip. It asserts prompt termination and timeout/nonzero exit, not a measured exact maximum PID count or a dedicated thread-creation probe. Verdict: fork-bomb containment is implemented and has live Docker attack coverage; no additional production fix is indicated by this review. No new attack run or implementation was performed on this turn. Next concern: 34 (filesystem attacks).

## Security audit follow-up — item 34 filesystem attack tests

Only the invocation's own work directory and its resource-measurement file are host bind mounts. Runtime workspace and container root are read-only; compilation has a writable private workspace and `/tmp` is a bounded private tmpfs. Host application/database files and sibling workspaces are not deliberately mounted. Container `/etc/passwd` and namespace-local `/proc` are not equivalent to host files. Existing tests check absence of `/base` and runtime workspace write rejection, but the sibling test does not directly probe a sibling's secret path.

Review found `/run-metrics` is a writable host-backed file accessible to the same UID as submitted code; its contents are subsequently parsed for elapsed time and peak RSS. The user approved dedicated attack tests, not a production remediation. Three new live Docker tests in `SandboxExecutorTest` now verify: (1) readable host/sibling sentinels cannot be opened via absolute, traversal, or `/proc/1/root` paths; Docker socket access and runtime/system writes are rejected, with host sentinels unchanged; (2) writable `/tmp` scratch does not survive a second execution container; (3) submitted C++ successfully overwrites and reads back an attacker marker at `/run-metrics`. The third is explicitly a vulnerability-characterization test and must become a rejection/protection test when remediated. GNU time can overwrite data on exit; no final measurement forgery or verdict bypass has been demonstrated.

Targeted verification: `mvn test -pl arbitrator-server -am -Dtest=SandboxExecutorTest -Dsurefire.failIfNoSpecifiedTests=false` passed all 18 sandbox tests, zero failures/errors/skips, on 2026-09-16 at 23:19 +06:00. No full-suite rerun or production change on this turn. Proposed remediation is a measurement supervisor inaccessible to the submission UID, plus bounded/validated host-side metrics reads; merely moving the file out of `/sandbox` or chmod while retaining the same UID is insufficient. Implementation design and production changes await approval. Item 34 remains current; item 35 symlink attacks has not yet been reviewed.

## Security audit follow-up — item 34 declined; item 35 symlink review

The user declined the item-34 measurement-isolation fix and advanced. Writable `/run-metrics` remains an accepted deferral; the preceding attack tests were implemented, but no production remediation is authorized.

Item 35 review: unique work directories and read-only execution mounts prevent ordinary runtime submissions from planting links in the host-backed workspace. Compilation nevertheless has write access to that workspace. Post-compilation `compileWorkspaceViolation` walks without following directory links but uses `Files.isRegularFile` and `Files.size` with default link-following behavior and does not reject symbolic links. Subsequent host `Files.writeString` calls for test input and checker output/expected output also follow links. Thus a link left in the compilation workspace could redirect host-side file operations outside the sandbox under the server OS account. No exploit from ordinary submitted source, compiler compromise, or end-to-end host overwrite has been demonstrated; this is a missing defensive trust-boundary check, not proof arbitrary source can currently execute during compilation. Cleanup's `Files.walk` does not follow directory symlinks by default.

Proposed approved scope: reject symbolic links and non-regular artifacts after successful compilation, use non-link-following semantics for host staging reads/writes, and add harmless host-sentinel tests covering file/directory links and preservation of the outside target. No implementation or new tests for item 35 on this review turn. Awaiting user approval; next unreviewed concern is item 36 race conditions.

## Security audit follow-up — item 35 dedicated symlink verification

The user requested verification before considering remediation. Added three parameterized `JudgeWorkerTest` cases with explicit compiler-stage fault injection: compile ordinary C++ normally, then execute `ln -s` under the real writable compilation container's UID/mounts/limits, targeting a harmless outside host sentinel. This test-only injected command is NOT part of configured production language commands and NOT an exploit derived from ordinary submitted source. The real post-compilation workspace validation accepted each link. Real JudgeWorker staging overwrote the sentinel via `__input.txt`, `__actual_output_1.txt`, and `__expected_output_1.txt`; each job still returned AC. Repository/publisher/checker doubles isolate the test from production database and messaging. These are vulnerability-characterization tests, not passing security protections; convert their expectations after remediation.

Added `SandboxExecutorTest.ordinaryRuntimeSourceCannotPlantHostWorkspaceSymlink`: ordinary compiled C++ cannot create a new link in the read-only runtime workspace, while a link in private `/tmp` is allowed and not used by host staging. This distinguishes a demonstrated unsafe host response to compiler-stage artifacts from the still-unproven ability of ordinary submitted source to produce those artifacts. No production code was changed, and no real sensitive host file was targeted. Each outside sentinel was deleted after its test; it was generated test data only. Remediation still awaits approval.

Targeted verification: `mvn test -pl arbitrator-server -am -Dtest=SandboxExecutorTest,JudgeWorkerTest -Dsurefire.failIfNoSpecifiedTests=false` passed 25 tests (19 sandbox + 6 judge-worker), zero failures/errors/skips, on 2026-09-16 at 23:22 +06:00. All four new symlink cases ran. No full-suite rerun is claimed. Passing characterization tests mean the harmful conditional behavior was reproduced, not fixed.

## Security audit follow-up — item 35 approved symlink remediation

The user approved the defensive fix after the dedicated verification. `SandboxExecutor.compileWorkspaceViolation` now inspects attributes with NOFOLLOW_LINKS and rejects every symbolic link and non-regular/non-directory artifact after successful compilation; symlinked directories are rejected, not traversed. Regular file byte accounting no longer follows links.

New `SafeSandboxFiles` is used for host-side source/input/checker-output staging in JudgeWorker, CustomRunService and CheckerRunner, and for private checker copies. Writes reject links/non-regular artifacts, unlink an existing regular staging file instead of truncating its inode (protecting outside hard-link targets), and open new files with CREATE_NEW/NOFOLLOW_LINKS. Copies reject non-regular sources, open source channels without following links, and exclusively create fresh targets. Immediate staging parents must be real directories. The design relies on server-controlled ancestors and stopped compilation containers/read-only runtime workspaces; it is not a promise of safety against a malicious concurrent host administrator replacing ancestor directories.

The three compiler-stage injected-link tests now require CE, no checker call, and unchanged outside sentinels. `SafeSandboxFilesTest` additionally covers file links, immediate directory links, linked copy sources, hard-link-safe replacement, normal copying, and rejecting existing copy targets. Earlier reproduction results are historical; these protection assertions supersede the item-35 characterization expectations. Ordinary-source compiler-stage exploitability remains unproven. The deferred item-34 `/run-metrics` exposure is deliberately unchanged. Next concern: item 36 race conditions; not yet reviewed.

Item-35 full verification: 207 server + 1 client tests (208 total), no failures/errors/skips. All three compiler-stage symlink protection cases, two SafeSandboxFiles tests, and 19 live sandbox tests passed. After final import/field-name cleanup, recompilation and a focused SafeSandboxFilesTest/JudgeWorkerTest rerun passed all eight cases at 11:07 +06:00. No production deployment or LAN rehearsal is claimed.

## Security audit follow-up — item 36 race-condition review

Reviewed registration, submission admission, joins, cloning, and admin edit/delete paths. Database unique constraints prevent duplicate usernames, contest titles, problem codes within a contest, and test indices within a problem. Contest joining is transactional and uses the composite grant primary key with ON DUPLICATE KEY UPDATE, making duplicate grants idempotent. Submission cooldown/backlog admission has process-local striped user locks and existing concurrent boundary tests. Contest cloning and destructive admin operations have transaction boundaries; transactions alone do not guarantee operation-level serialization or prevent lost updates.

Concrete code gap: registration uses existsByUsername then save, with no duplicate-key exception mapping. Two simultaneous requests can both pass the check, although the database rejects one insert; the loser can receive an unhandled server error instead of the intended 409. Concurrent same-title cloning similarly relies on its database uniqueness constraint without graceful conflict mapping. No global DataIntegrityViolationException handler was found. Contest/problem entities and repositories have no optimistic version or explicit pessimistic locking, so overlapping admin edits/deletion deserve targeted tests before selecting a locking design. No actual simultaneous HTTP attack or race reproduction was run on this review turn; do not claim corruption or authentication bypass. Rejudge is not implemented and is not a current race surface.

Proposed next authorized scope: dedicated concurrent registration, repeated join, same-title clone and edit/delete tests against the dedicated test schema; distinguish constraint-protected conflicts from genuine lost updates, then propose narrow remediation based on reproduced failures. No production changes or new tests are authorized yet. Item 36 is current; next unreviewed concern is item 37 contest timing attacks.

## Security audit follow-up — item 36 authorized concurrent attack tests

Added `RaceConditionAttackIntegrationTest` using the dedicated `test` profile/schema, unique disposable fixtures, fixture-scoped cleanup, real MySQL transactions and real server routes. Five tests ran successfully on 2026-09-17 at 11:10 +06:00 via `mvn test -pl arbitrator-server -am -Dtest=RaceConditionAttackIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`: zero failures/errors/skips. These characterization tests reproduce defects rather than claim remediation; no production changes or full-suite rerun are included. An initial assertion mistakenly expected 200 instead of the registration API's 201; corrected and rerun successfully, without production changes.

Observed HTTP results: eight simultaneous same-username registrations yielded one 201 and seven 500 responses, exactly one stored account. Six same-title clones yielded one 200 and five 500 responses, exactly one cloned contest containing the source problem. Six concurrent student joins all returned 200 and left exactly one access grant. Tokens for join/clone tests were issued for disposable database users via the actual JWT/session components; account login itself was not under test. Registration-specific IP allowance was increased only in this test context so rate limiting would not mask the race.

Two deterministic transaction interleavings exercise the real problem controller with real DB-managed entities, not concurrent HTTP scheduling: both editors load the same original snapshot, then one changes the title and another the time limit. Both transactions succeed, but the final row retains the title and loses the new time limit (last-writer-wins full-row update). In the second interleaving, the editor loads a snapshot before a real committed repository deletion; its controller update fails with ObjectOptimisticLockingFailureException and the row remains deleted. No row resurrection was observed. This exception's HTTP mapping was not tested. The read barriers live solely in test code; no production timing hooks or spy repositories were added.

Proposed remediation awaiting approval: map confirmed username/title unique-key races to a safe 409 response without masking unrelated database failures, add optimistic version checking to problem edits so simultaneous stale writes cannot silently discard successful independent changes, and map stale edit conflicts cleanly. Convert the corresponding characterization expectations into protection tests. No broad contest-locking design or changes to global scheduling are authorized. Item 36 remains current.

## Security audit follow-up — item 36 approved race remediation

The user approved fixes for the reproduced races. Added `PersistenceConflictAdvice` to return 409 only for MySQL duplicate-key error 1062/SQLSTATE 23000 naming the known username/title unique constraints. The SQL error values and query details are never included in responses. Other integrity failures remain generic 500 responses and are logged server-side, rather than falsely classified as duplicates. Optimistic-lock failures return 409 with a reload/retry message and no-store responses.

`Problem` now has a JPA @Version field; additive Flyway migration V81 creates `problems.version` with default zero for existing rows. The migration was exercised in the separate test schema; normal server startup will apply it to an existing deployment. Version checks reject stale concurrent entity writes at flush/commit, including edits after deletion. This protects overlapping server transactions, not arbitrary old browser forms submitted later without a client version token. Contest entities, global queue scheduling and unrelated locking designs are unchanged.

RaceConditionAttackIntegrationTest expectations now require exactly one registration 201/seven 409 responses, one clone 200/five 409 responses, and one successful editor/one optimistic conflict with only the successful editor's change persisted. The targeted five-case run passed without skips at 11:13 +06:00; joins remain idempotent and stale edit/delete remains non-resurrecting. Added three PersistenceConflictAdviceTest cases for named duplicate constraints, unrelated integrity errors, secret-free/no-store responses and stale-edit 409 mapping. HTTP registration/clone mapping is live-tested; the controlled edit transactions plus advice unit test establish stale conflict behavior without claiming a deterministic HTTP edit race.

Earlier item-36 defect reproduction and awaiting-approval statements are historical; approved fixes above supersede them. Deferred metrics/logging/fairness/MFA choices remain unchanged. Next concern: item 37 contest timing attacks, not yet reviewed.

Item-36 final full verification: 215 server + 1 client tests (216 total), zero failures/errors/skips, completed 2026-09-17 at 11:14 +06:00. Registration produced one 201/seven 409; cloning one 200/five 409; six joins all 200. Controlled edit transactions asserted exactly one success and one optimistic conflict, and the committed change matched the successful writer. All three advice mapping/sanitization tests passed. No production schema migration was applied by this test run; only the separate test schema was migrated.

## Security audit follow-up — item 37 contest timing review

Scored submission admission uses server Instant.now, rejects states other than ACTIVE/FROZEN, explicitly rejects PAUSED, requires a non-null start/end, refuses future starts, and refuses now >= endTime. SubmitRequest contains no client timestamp/score field. Start, end, pause and freeze operations use server instants; pause duration extends the computed deadline. Scoring uses server queuedAt, not judge completion or client time; freeze filtering uses server queuedAt/judgedAt/frozenAt. Custom runs intentionally have released-access checks rather than scored submission deadline checks; no change to practice availability is proposed here.

Potential boundary inconsistency: assertAcceptingSubmissions runs before duplicate lookup/queue reservation and queuedAt is captured later with a separate Instant.now. A request admitted just before the deadline may be persisted with queuedAt after it; overlapping pause/end operations may also leave the previously loaded contest snapshot stale. No boundary attack or delay-injection test was run on this review turn, and this does not prove requests arriving after the deadline are accepted. Need to distinguish admission-time semantics from persistence-time semantics before selecting a fix. Penalty pause accounting is a separate scoring-policy question, not a browser-clock trust flaw.

Proposed next step awaiting approval: dedicated before-start, start, end, paused/frozen tests with controlled server time and a harmless delayed submission pipeline to verify the check/timestamp boundary. No production change or test additions yet. Current item 37; item 38 direct contest access remains unreviewed as its own checklist concern.

## Security audit follow-up — item 37 authorized timing attack tests

Added ContestTimingAttackTest with the real ContestService timing guard and real SubmissionService pipeline, using repository/access/queue doubles and disposable in-memory entities. Twelve cases passed without failures/errors/skips via `mvn test -pl arbitrator-server -am -Dtest=ContestTimingAttackTest -Dsurefire.failIfNoSpecifiedTests=false` on 2026-09-17 at 13:12 +06:00. No Docker execution, HTTP race, database persistence, exact nanosecond clock mock, or full-suite rerun is claimed. An initial test-only compilation error attempted inaccessible JudgeQueue.shutdown; removed that call because the overridden fake queue never starts threads, then recompiled and ran successfully. Production code is unchanged.

Verified all six states: only ACTIVE/FROZEN accept scored submissions. Future/missing starts, missing ends, elapsed deadlines, and a deadline captured at current time (boundary or later by guard execution) are rejected with 403 without requiring a scheduler state change. Completed pause time extends the server deadline; PAUSED still rejects submissions. These use real wall-clock instants with safe margins, not exact deterministic before/equal/after nanosecond assertions.

The bounded delay probe sets a fixed near-future deadline at the actual guard boundary, validates with the real guard, then holds the test queue reservation until that deadline passes. Observed admission 07:12:46.458007025Z, deadline 07:12:46.607990976Z, stored queuedAt 07:12:46.609074622Z: admitted before expiry, recorded afterwards. A fresh guard call after expiry correctly rejects. A second probe switches the fixture to PAUSED after the guard but before reservation; the already-admitted request persists while subsequent admission is denied. This establishes check/timestamp and no-recheck behavior, not an ability to submit a fresh request after closing or to control server time. The in-memory state mutation models an interleaving, not proof of a live database pause race.

Proposed narrow remediation awaiting approval: capture a single server admission instant, validate the contest against that instant, and persist that same instant as queuedAt. Preserve the normal policy that already-admitted submissions can finish persistence/judging after expiry or pause; do not silently turn timing into a persistence-deadline requirement. This makes penalty/freeze timestamps agree with acceptance. Changing concurrent pause/end cancellation semantics would be a separate policy decision. Convert the delayed timestamp characterization assertion into a pre-deadline protection assertion if approved. Current item 37; next is item 38 direct access bypass.

## Security audit follow-up — item 37 approved admission-time remediation

The user approved unifying timing validation and storage. SubmissionService now captures one server Instant immediately at its contest admission guard and passes it to ContestService.assertAcceptingSubmissions(contest, admittedAt). That overload compares the same instant against start and a once-computed end; the existing single-argument guard delegates with server now for other callers. The accepted submission stores that exact captured instant as queuedAt, regardless of later duplicate-check/queue/persistence latency. No endpoint accepts a client admission timestamp. Cooldown tracking remains actual completion/admission bookkeeping as before, and already-admitted requests can finish after expiry/pause. No new cancellation or global contest-lock policy was introduced.

Converted the delayed timing characterization into a protection regression requiring stored queuedAt == validated admission instant and queuedAt < deadline despite actual persistence after expiry. Added an exact fixed server-instant test: start-minus-one-nanosecond rejected, start allowed, end-minus-one-nanosecond allowed, end and end-plus-one-nanosecond rejected. This tests the explicit trusted-instant guard, not a live network request at nanosecond precision. Existing state/pause/missing-time checks remain. Updated submission test doubles to intercept the new overload rather than bypass tests accidentally.

Targeted timing/cooldown/queue-service run passed 18 cases (13 timing + 1 cooldown concurrency + 4 queue-abuse), no failures/errors/skips, 2026-09-17 13:14 +06:00. Earlier twelve-case defect reproduction is historical and superseded by the protection assertions. No client changes were required for item 37. Next concern: item 38 direct contest access bypass, not yet reviewed separately.

Database precision follow-up: baseline queued_at used whole-second TIMESTAMP. Added V82 to retain TIMESTAMP(6) microseconds and capture/truncate the server admission instant to microseconds BEFORE both guard validation and storage, avoiding fractional database rounding disagreements. Existing rows are preserved; only the test schema was migrated by tests, and deployments receive V82 at normal startup. Added a real repository/JDBC round-trip regression in RaceConditionAttackIntegrationTest using 59.999999 seconds, requiring exact microsecond preservation before the next second. This covers persistence separately from the service-level delay model.

First item-37 full-suite attempt ran 228 server cases with one failure in the previously observed ContestAccessIntegrationTest contestTopicSubscriptionRequiresTheSameGrant allowed-connection assertion. All new timing cases passed; no unrelated WebSocket production change was made. Targeted rerun and final full verification are recorded below; do not interpret this initial attempt as a green run.

Item-37 precision/WebSocket targeted verification: ContestTimingAttackTest (13), RaceConditionAttackIntegrationTest (6, including timestamp round-trip), and ContestAccessIntegrationTest (4) all passed: 23 tests, zero failures/errors/skips, 2026-09-17 13:16 +06:00. The known allowed-WebSocket assertion passed without production WebSocket changes. The full-suite retry result is recorded below.

Item-37 final full retry: 229 server + 1 client tests (230 total), zero failures/errors/skips, completed 2026-09-17 at 13:18 +06:00. The previously failing WebSocket case passed without unrelated production changes. The earlier failed attempt remains recorded as a test stability observation. V82 was applied only to the test schema during verification; production receives it through normal Flyway startup. No deployment or LAN rehearsal is claimed.

## Open defects and risks

### Priority 1 — correctness and security hardening

The earlier audit's Priority 1 findings are closed; reproduced item-36 registration/cloning error mapping and concurrent problem-edit lost-update findings have approved remediation as documented above.

### Priority 2 — operational and quality risks

7. Multipart requests are capped at 64 MB in `application.yml`, while `MaterialService` advertises a 200 MB material limit. The effective limit is currently 64 MB.
8. The judge backlog is globally bounded and every Docker execution shares active-container and aggregate-memory admission; tune those limits for the deployment host before a large contest.
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
