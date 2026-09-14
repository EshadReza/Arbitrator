# Arbitrator — implementation status

**Last audited:** 2026-09-15
**Version:** `0.1.0-SNAPSHOT`
**Readiness:** feature-rich beta; suitable for controlled demonstrations and supervised lab trials, not yet production-ready

This file describes the source as it exists now. `WORKFLOW_PLAN.md` contains the current roadmap and the historical sprint mapping; `rules.md` remains the authority for team ownership and Git workflow.

## Verification snapshot

The full Maven server suite was run during the 2026-09-15 repository audit:

| Result | Count |
|---|---:|
| Tests discovered | 52 |
| Passed | 38 |
| Failed | 0 |
| Errors | 0 |
| Skipped | 14 |

The skipped cases are environment-dependent Docker sandbox, worker, checker, and full STOMP integration tests. The build is green, but this run does not replace a Docker-enabled integration run or a real LAN rehearsal.

The JavaFX client has no automated UI suite. The React experiment was not built during this audit because its `node_modules` directory was absent; it is outside the root Maven build.

## Implemented

### Platform, data, and security

- Spring Boot 3.2.5, Java 17, MySQL 8, Spring Data JPA, Flyway, Spring Security, and JJWT
- Student registration plus seeded student/instructor accounts for development
- BCrypt cost 12 and 12-hour HS256 JWTs
- In-memory single-active-session registry with confirm-to-replace login behavior
- Explicit logout and REST-token invalidation
- Optional client MAC reporting and instructor notifications when a student's reported MAC changes
- Loopback filter for `/admin/**` and `/api/admin/**`, with `ADMIN` authorization on admin APIs
- Flyway schema through V65, including contests, users, problems, tests, submissions, results, announcements, clarifications, PDF statements, MAC data, and materials
- Persistent uploaded materials on disk with metadata in MySQL

### Contest and problem administration

- Contest lifecycle: `DRAFT`, `LOBBY`, `ACTIVE`, `PAUSED`, `FROZEN`, `ENDED`
- One joinable/live contest at a time
- Open lobby, start, pause/resume, schedule, extend/remove time, freeze/unfreeze, end, clone, and delete operations
- Authoritative server clock and pause-aware deadline adjustment
- Optional contest password field and client password prompt
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

## Open defects and risks

### Priority 0 — fix before an untrusted contest

1. **Contest access is not a server-side authorization boundary.** `ContestController.byId()` checks a submitted password, but no durable membership/grant is created. Problem list/detail/PDF and related contest resources do not consistently verify that the student passed the password gate. Direct problem-ID access can also bypass intended release behavior.
2. **Credible stored XSS in the instructor console.** HTML-escaped student-controlled strings are interpolated into single-quoted inline JavaScript handler attributes. Browser entity decoding occurs before handler execution, so HTML escaping alone does not make these values safe in JavaScript context. Registration does not enforce a restrictive server-side student-ID character policy.

### Priority 1 — correctness and security hardening

3. **Forced-login invalidation is incomplete for WebSockets.** `JwtAuthFilter` checks the JWT `sid` against `ActiveSessionRegistry`; `JwtHandshakeInterceptor` validates the token but does not perform the equivalent active-session check.
4. **Duplicate-source normalization is semantically unsafe.** `SubmissionService` removes all whitespace before comparison. This can collapse distinct Python programs and can alter string/token meaning in Java or C++.
5. **Contest deletion omits materials.** `AdminContestController.delete()` does not remove material rows or disk files. The V65 materials foreign key has no delete cascade, so a contest containing materials can block deletion.
6. **ZIP size enforcement has edge cases.** Unknown-size entries are read exactly to the cap without an extra-byte probe, so an oversized entry can be silently truncated. Duplicate normalized ZIP paths can overwrite earlier entries.

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

1. Close both Priority 0 findings and add regression tests.
2. Fix WebSocket session invalidation, duplicate detection, material-aware contest deletion, and ZIP validation.
3. Repair `.gitignore`/tracked local artifacts and the packaging scripts without committing real credentials.
4. Add controller/security integration tests for contest access, session replacement, materials, deletion, and admin rendering.
5. Run the complete Docker-enabled suite, a multi-client LAN soak test, and a visual acceptance pass on Ubuntu 22.04.
6. Implement rejudge and float tolerance only after the correctness/security backlog is green.
7. Produce a repeatable server/client distribution, backup/restore procedure, and operator checklist before tagging v1.0.

## Definition of v1.0-ready

Do not call the project v1.0-ready until:

- Priority 0 and Priority 1 defects have tests and verified fixes.
- The full suite runs without environmental skips on the target deployment machine.
- Student and instructor flows pass on the real LAN with at least 20 concurrent clients for a two-hour rehearsal.
- Docker, MySQL, material storage, restart behavior, and backup/restore have an operator-tested runbook.
- Ubuntu client packaging and installation are repeatable on a clean participant machine.
- Documentation and generated release artifacts are produced from the same tagged commit.
