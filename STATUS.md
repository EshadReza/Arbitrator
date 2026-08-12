# STATUS

**Last updated:** 2026-08-13
**Product name:** Arbitrator — rename COMPLETE: packages `com.arbitrator.*`, modules `arbitrator-*`, classes `ArbitratorApp` / `ArbitratorServerApplication`, config prefix `arbitrator.*`, MySQL schema `arbitrator`, project folder `Arbitrator/arbitrator/`.
**Current phase:** Sprint 2 complete, checkpoint **I2 closed**. Leaderboard end-to-end (engine + client view).
Next: announcements (S3-B5), freeze controls (S3-B4), submission history UI (S3-C4).

> Update this file whenever a chunk lands. It is the first thing read at the start of a session.

---

## Where we are

Bundle 1 — the runnable vertical slice — is **built, tested, and confirmed working end-to-end on macOS dev**:
login → open problem → submit C++ → sandbox compile+run → verdict persisted → returned to client.

Roughly **Sprint 1 + half of Sprint 2** of the 4-sprint plan in `WORKFLOW_PLAN.md` §6.

## Done

| Chunk | What | Verified |
|---|---|---|
| S1-X | `arbitrator-common` frozen contract: 4 enums, 9 DTOs, path/topic constants | compiles, both modules depend on it |
| S1-A1 | Repo, parent pom, 3 modules, `.gitignore`, Eclipse formatter config | `mvn clean install` green |
| S1-A2 | MySQL 8 Flyway baseline `V1__baseline.sql` — 6 tables, FKs, soft-delete | migrates cleanly on real MySQL 8.4 |
| S1-A3 | JPA entities + 5 repositories | schema validation passes |
| S1-A4 | Auth: register/login, bcrypt(12), 12h JWT, `SecurityConfig` path rules, `LoopbackAdminFilter` | login returns JWT; verified live |
| S1-B1 | `SandboxExecutor` + `sandbox-run.sh` (`unshare -Urn` + `prlimit` + 2× kill) | **Linux path unverified** — see Known issues |
| S1-B2 | `languages.yml` compile/run config for C++/Java/Python | C++ path verified live |
| S1-B3 | JUnit fixture suite: AC/WA/TLE/CE/RE + MLE + fork bomb | 5 pass on macOS, 2 skip (Linux-only) |
| S1-C1..C5 | JavaFX shell, `codeforces.css`, login screen, 3-panel layout, `FakeJudgeApi` | app launches and renders |
| S2-B1 | `POST /api/submissions` — persist-before-queue, 202 + queue position | verified live |
| S2-B2 | Judge queue, bounded pool, crash-recovery requeue on startup | requeue path not yet exercised |
| S2-B3 | `VerdictEvaluator` exact match + fail-fast | 8/8 unit tests pass |
| S2-B4 | STOMP config + JWT handshake + `/user/queue/verdicts` | server-side wired; **client banner not yet visually confirmed** |
| S2-B5 | 30s submit rate limit (BR-01) | implemented, not load-tested |
| S2-C3..C5 | Code editor (RichTextFX), submit flow, toast, verdict banner | rendering confirmed; live banner pending |
| **S2-A2** | **Problem package ZIP upload (FR-05)** — validate, reject-whole, zip-slip + zip-bomb guards, persist test cases | **16 unit tests + live end-to-end: uploaded a package, student solved it, AC** |
| **S2-A4** | **Admin panel Problems page** — list, upload, delete with confirm (UIF-22) | served at `/admin`, loopback+ADMIN verified (student token → 403) |
| **S2-B4** | **STOMP verdict push proven end-to-end** | `VerdictPushIntegrationTest`: real handshake → subscribe → submit → verdict frame; plus unauthenticated handshake refused |
| **S3-B3** | **Leaderboard + penalty engine** (FR-17/18, BR-03/04/06) + 30 s broadcast | 10 unit tests incl. **Gherkin scenario 5 verbatim**; live board matches real submissions |
| **NFR-P03** | **Standings pushed on every verdict**, not just the 30 s tick | measured **898 ms** from submit to updated board (budget 5 s); 2-client broadcast verified at ~1.15 s |
| **FR-09** | **All three languages judged** — C++17, Java 17, Python | each produced **AC** live; previously only C++ had ever run |
| **S2-A1** | Single-live-contest enforcement + clearer cross-contest error | starting a contest ends any other live one |
| **FR-01** | **Registration screen** in the client | 201/400/409 verified; always creates STUDENT |
| **FR-06** | **Contest state pushed to clients** — the countdown now obeys pause/extend/end | verified: server-side pause reached a connected client (`state=PAUSED`) |
| **FR-16** | **Submissions tab** (UIF-12) — history table + detail pane, refreshes on every verdict | third tab beside Problems and Standings |
| DOC-7 | **Live updates fixed** — clients refresh on *every* contest-state push, not only on state changes; problem upload/delete now pushes too | probe confirmed pushes arriving; a new problem no longer needs sign-out to appear |
| DOC-8 | **Manual refresh rewritten** — fetches contest state first, then problems (order matters: state decides whether problems are released at all) | previously it could apply a stale list |
| DOC-9 | **Restarting a contest archives the previous run's submissions** — problems no longer start pre-solved and standings begin empty | verified: `solved=[True]` → `solved=[False]`, standings 0 rows |
| DOC-10 | **Instructor console rebuilt** — sidebar navigation, five sections, CSS grid/flexbox, dark mode, verdict colours matching the client | all endpoints verified 200 |
| DOC-11 | **Uploaded statements no longer render white-on-white in dark mode** — an uploaded package is a whole HTML document carrying its own `<style>`, which appeared *after* the theme block and won on source order; the theme now reclaims background/border with `!important` | verified in WebKit against the real DB statement: `code`/`pre` computed `rgb(35,42,54)` on `rgb(230,234,240)` text (was `#f3f4f6` on white); light mode still `#f5f7fa` on `#1c2430` |
| DOC-12 | **`ContestBootReset`** — every live contest returns to DRAFT at startup, so a restart never leaves a contest running with a clock that ticked while the server was down | verified: PAUSED + ACTIVE → DRAFT, clocks cleared, `/api/contests` returns `[]` |
| DOC-13 | **Contest picker is live** — polls every 3 s and has a Refresh button, so a lobby opened by the instructor appears without signing out and back in; selection is held by contest id across refreshes | verified end to end: student `[]` → `LOBBY` → `ACTIVE` with no re-login |
| DOC-14 | **Admin "Adjust time" is now one input + Add/Remove** instead of fixed ±5/15/30 buttons | verified: +7 → 127 min, −7 → 120 min |
| DOC-4 | **Two-phase start** — new `LOBBY` state: doors open, no problems, no clock; then Start releases problems and begins the timer | verified: DRAFT→LOBBY→ACTIVE, problems hidden in lobby, submit refused with a clear reason |
| DOC-5 | **Server no longer auto-starts a contest** — seeded as DRAFT; students see "No contest is running" until the instructor opens it | verified on a fresh database |
| DOC-6 | **Dark-mode text areas fixed** (`-fx-control-inner-background`) and **per-verdict colours** (AC green, WA red, TLE/MLE/RE/OLE orange, CE purple) | the custom I/O boxes stayed white because a TextArea paints its own inner background |
| DOC-1 | **Custom input / output** — run code against your own input, never judged or stored, same sandbox limits | verified: `7 35` → `42`; compile errors clean of server paths; creates no submission |
| DOC-2 | **Editor ergonomics** — Tab = 4 spaces, auto-indent (deeper after `{`/`:`), auto-closing brackets/quotes, Ctrl +/− and Ctrl+scroll zoom, Ctrl+0 reset | compiles; needs a visual pass |
| DOC-3 | **Ctrl+R refresh**, white caret in dark mode, verdict banner click → Submissions tab with that run selected | compiles; needs a visual pass |
| SEC | **Sandbox hardening** — compile is now limited too, relative per-UID process cap, process-tree kill, work-dir `pkill` sweep on cleanup and at startup, output cap → new **OLE** verdict | verified live: AC / OLE / TLE correct, zero stray processes; **fork-bomb containment still UNVERIFIED — see Known issue 9** |
| UIF-12 | **Submitted code in submission details** — lazy-loaded per row; LRR-02 enforced (another student gets 403) | verified live |
| UIF-21 | **Live monitoring in the console** — who's online, rank/solved/penalty/submission count, full submission feed, view any code | presence tracked from STOMP subscriptions |
| UI | **Sign out**, **switch contest**, **manual refresh** (⟳) in the client | refresh re-pulls contest state, problems, standings and history |
| UI | **Dark mode** (top-bar toggle / Ctrl+D), fullscreen statement (F11) and editor, collapsible problem list (Ctrl+B), restyled client + instructor console | one stylesheet, palette swapped by a `.dark` root class |
| **FR-06/08** | **Contest controls**: start, pause/resume (clock genuinely stops), extend ±minutes, freeze/unfreeze, end | pause held remaining at 6019s across 6 s; +15 min moved the deadline exactly 15 min |
| **UIF-20/21** | **Instructor console rebuilt** — live server clock, elapsed/remaining, state badge, state-aware buttons, contest table, problem upload | replaces the bland skeleton; every control explains what it does |
| — | **Multi-contest**: student contest picker; submissions derive their contest from the problem; standings broadcast per contest | picker auto-skips when only one contest is joinable |
| — | **Error messages now reach clients** (`server.error.include-message`) | Spring was dropping every `ResponseStatusException` reason — clients only ever saw "Forbidden" |
| **S3-C1** | **Client standings view** (UIF-13..16) — Standings tab, runtime problem columns, self-row highlight, freeze banner, "last updated" | compiles; **needs a visual pass on Linux/macOS — see Known issue 7** |
| **S4-B1** | **Docker-backed sandbox** (FR-10, NFR-S03/S04) — `SandboxExecutor` now runs every compile and every test/checker run inside a throwaway container (`scripts/docker/Dockerfile`), replacing the unshare(Linux)/ulimit(dev) split entirely. No participant code ever runs as a direct child of the JVM, on any platform. Supersedes the sudoers/process-group approach originally sketched for this chunk. | 49/49 server tests green; live end-to-end across cpp17/java17/python310 (AC/WA/TLE/CE) through the real HTTP→queue→judge→verdict path, with `docker ps` + host `ps` sampled throughout judging confirming containers appear/reap correctly and zero host toolchain processes (`g++`/`javac`/`python3`/`prog`) ever run directly. Closes known issues 1, 9, 10 below. |
| — | **Ubuntu client/UI punch list** — theme toggle icon fixed (color-emoji glyphs on Linux ignored `-fx-text-fill`; swapped for monochrome dingbats `☾`/`☼`); window now clamps to the actual screen (`Screen.getPrimary().getVisualBounds()`) instead of forcing a fixed 1280×768; F11 is real `Stage.setFullScreen()`, wired once at the Stage level so it works on every screen, not just Main. | client compiles; visual pass not done on a real Ubuntu box in this session (no GUI access here) — verify on the actual lab hardware before calling this closed |
| — | **MAC address tracking** (Participants) — client reports its own MAC at login (`NetworkInterface`, best-effort); `users.mac_address`/`mac_changed_at` (V63); a change raises an admin `MAC_CHANGED` notification through the existing `NotificationService` poll channel, no new push infra. | live-verified via curl + admin panel: Participants table shows the MAC and a red ⚠ on recent change; notification appears in `/api/admin/notifications` |
| — | **Single active session, confirm-to-kick** — a second login while one is active gets 409 `ALREADY_LOGGED_IN`; the caller (JavaFX `LoginController`, and the admin panel's own login gate) shows a confirm dialog and retries with `force=true`, which invalidates the first session's token on its very next request (`ActiveSessionRegistry`, a `sid` JWT claim, checked in `JwtAuthFilter`). In-memory only — correct for this single-server deployment (D2/D3), not something to carry over if that ever changes. | live-verified end to end via curl (409 → force login → old token 401s, new token works) and through the real admin panel UI in two browser tabs |
| — | **Admin Problems "View"** — read-only statement + full test suite, alongside Edit/Delete (`GET /api/admin/problems/{id}/testcases`). | live-verified in the admin panel: rendered statement + all 8 test cases for problem A |
| — | **Presence disconnect debounce** — a heartbeat miss or few-ms blip no longer fires a DISCONNECTED+RECONNECTED notification pair; `PresenceTracker` waits 4s and rechecks before deciding someone's really gone (was instant, zero grace period). Client watchdog matches: polls every 4s (was 3s) and waits for 2 consecutive misses before flipping the visible "offline" banner. | server compiles/tests green; not soak-tested against a real flaky LAN in this session |
| — | **"Username" relabeled "Student ID"** in all user-visible copy (login/register screens, admin CSV/HTML export headers) — `username` unchanged as the internal field/column/URL-path name everywhere (a rename there is a much bigger contract-change, not what was asked). | |
| — | **Real syntax highlighting in the code editor** — `EditorController.highlight()` went from one un-grouped keyword regex to a combined named-group pattern per language (comment/string/number/keyword/type/function), each with its own CSS class (`.type`/`.string`/`.comment`/`.number`/`.function`, VSCode-Dark+-inspired, alongside the existing `.keyword`). | all three language patterns validated standalone against real C++/Java/Python snippets (correct token-by-token classification); not yet eyeballed in the running JavaFX app in this session — no GUI access here |
| — | **Enter splits an auto-closed bracket pair onto three lines** (opener / indented blank / dedented closer) instead of leaving the closer on the cursor's line — `autoIndentNewline()` now checks the chars either side of the caret before falling back to the old single-line behavior. **Tab/Shift+Tab on a selection indents/dedents every selected line** (preserving the text and the selection) instead of replacing the selection with four spaces; Shift+Tab dedent didn't exist at all before, on a selection or a bare caret. | compiles; same "not yet run live" caveat as above |
| **—** | **Clarification approval gate** — a public clarification's answer no longer reaches the class the instant it's saved; an admin must explicitly approve it (`clarifications.approved`, V64, backfilled `true` for what was already answered+public pre-migration so nothing already-visible gets hidden). The asker always sees their own answer immediately either way. New `POST /api/admin/clarifications/{id}/approve`; admin panel gets a "Pending approval" badge + Approve/Unapprove button; JavaFX client shows "awaiting approval" on the asker's own unapproved entry. | live-verified end to end via curl + admin panel: asked public → answered → confirmed invisible to a second student account → approved → confirmed now visible; approve-before-answer correctly 409s |
| — | **Docker binary auto-detection + actionable diagnostics** — `SandboxExecutor` no longer trusts a bare `"docker"` on PATH: if that fails, it tries the common per-OS install locations (`/usr/bin`, `/usr/local/bin`, Homebrew, Docker Desktop on macOS/Windows) before giving up, so a machine-specific `docker-binary` override is no longer needed on every lab PC. `verifyDockerReady()` now captures the actual failure text and prints a specific remediation (not on PATH vs. daemon not running vs. **not in the `docker` group — needs a fresh login, not just a new terminal**, the exact trap that was hitting `unix:///var/run/docker.sock` "permission denied"). | `mvn clean install` + targeted tests green (`SandboxExecutorTest` 7/7); live-started server logged `Docker sandbox ready: image arbitrator-judge:latest via /usr/local/bin/docker` |
| — | **Threading and process-exec calls rejected as CE** — `JudgeWorker` regex-scans the raw source per language (C++: `<thread>`/`std::thread`/`std::async`/`system()`/`popen()`/`fork()`/exec family; Java: `Thread`/`Executors`/`CompletableFuture`/`Runtime.exec`/`ProcessBuilder`; Python: `threading`/`multiprocessing`/`concurrent.futures`/`os.system`/`subprocess`/`os.fork`) before writing anything to disk or spending a container on it — a hit returns a normal CE verdict naming what was disallowed. | regex table sanity-checked standalone (8/8 cases: legitimate code with a variable named `system_count` etc. does NOT false-positive; every banned call does) |
| — | **Queue-flooding guard: one in-flight submission per user** — `SubmissionService.submit()` now 429s if the caller already has a PENDING/JUDGING submission, on top of the existing 10 s cooldown. Closes the "submit 2,000 infinite loops" attack: previously the cooldown bounded submission *rate* but not backlog *depth*, so one student could still occupy a large share of the shared judge pool for the rest of the contest. | `SubmissionRepository.existsByUserIdAndStatusNot` + service check compile clean; full `mvn install` green |
| — | **Submission cooldown restored to 10s in production** (`application.yml` default `ARBITRATOR_SUBMIT_COOLDOWN`), was left at 0 from testing. Local dev (`application-local.yml`) and the test profile explicitly keep 0 so nothing else had to change to keep developing/testing fast. | |
| — | **Editor highlighting rewritten to RichTextFX's own debounced pattern** — `EditorController` now wires `codeArea.multiPlainChanges().successionEnds(Duration.ofMillis(75))` instead of applying `setStyleSpans` from a per-keystroke `textProperty` listener (first via a synchronous call, then via a `Platform.runLater` defer — both superseded). Verified with a standalone JavaFX harness (real `KeyEvent`s fired programmatically, far faster than human typing, across plain typing / auto-close-bracket / real-Enter multi-line scenarios): the buffer matched expected output exactly in every run, for both the old and new wiring — the specific reentrancy theory was not reproduced, so the underlying corruption reported live is still unconfirmed. The debounced pattern is shipped regardless since it's strictly more defensible (RichTextFX's own documented approach, zero reentrancy surface, no per-keystroke backlog risk) — **still needs a real click-test / more specific repro steps from whoever hits it** to close for good. |
| — | **Test-case view horizontal-scroll + scrollbar-visibility fix (admin panel)** — two distinct bugs, both in `admin/index.html`: (1) the input/expected `<pre>` boxes are flex children with the browser default `min-width:auto`, so a long unbroken line grew the box itself instead of scrolling inside it, blowing the whole test-case panel sideways — fixed with `min-width:0` + `max-width:100%`; (2) no `color-scheme` CSS property was ever set, so native scrollbars (and form controls) followed the OS/browser's own light-or-dark preference instead of this page's manually-toggled `data-theme` — toggling to dark while the OS was light left every scrollbar rendering in light-scheme style, a pale thumb functionally invisible against the dark `.code` panels. Fixed with `color-scheme` on `:root`/`[data-theme]`, plus explicit `scrollbar-color` + `::-webkit-scrollbar` rules as a second layer. Live-verified in-browser (not just structurally): logged in as admin, opened a problem with a 299-char test-case line, confirmed via JS the `pre` box overflows *internally* (`scrollWidth 2186 > clientWidth 512`) while the page itself does not (`document.body.scrollWidth === clientWidth`), and confirmed `el.scrollLeft = 400` actually shifts the visible content within the box. |
| — | **Zoom + F11 fullscreen distortion, strengthened** — the first fix (one `Platform.runLater` pulse after `Stage.fullScreenProperty()` changes) wasn't enough: the native OS fullscreen transition is a multi-hundred-millisecond system animation (sliding to/from a new Space on macOS), not a single JavaFX pulse, so one deferred call could itself still land mid-animation. Now re-applies `applyZoom()` at five staggered delays (0/80/180/350/600ms) after the transition fires, comfortably spanning the real animation length; each call is a cheap resize computation with no visible flicker. |
| — | **Queue-flooding guard redesigned per explicit request** — the earlier "one in-flight submission per user" simplification is removed; replaced with the originally-specified hysteresis band: `SubmissionService` now blocks further submits once a user reaches 20 PENDING/JUDGING submissions, and holds that block until the backlog drops back below 5 (`QUEUE_BLOCK_AT`/`QUEUE_UNBLOCK_BELOW`), not a flat cap of 1. `SubmissionRepository.existsByUserIdAndStatusNot` replaced with `countByUserIdAndStatusNot`. |
| — | **Real root cause found for "docker not found everywhere" and "cooldown still 0 in production"** — both traced to the SAME bug: `application-local.yml` is git-ignored (rules.md Rule 6) so it never enters version control, but `.gitignore` has zero effect on Maven — the file was still physically copied into every packaged server jar by whichever machine happened to build it, with `spring.profiles.include: local` loading it unconditionally at runtime. One developer's Mac-only `docker-binary: /usr/local/bin/docker` and testing-only `submit-cooldown-seconds: 0` were baked into jars shipped to every OS. Fixed at the source: `arbitrator-server/pom.xml` now explicitly excludes `application-local.yml` from the build's `<resources>`, so a packaged jar can never contain it regardless of whose machine builds it. Confirmed on a clean rebuild: the jar no longer contains the file, and a live server started from it logs `Docker sandbox ready: image arbitrator-judge:latest via docker` (the bare, auto-resolved default) instead of the hardcoded Mac path. |
| — | **`resolveDockerBinary` fallback broadened** — the auto-detection added earlier only searched the per-OS candidate list when the configured value was the *literal unmodified default* (`"docker"`), deliberately skipping the search for any explicit override on the theory that "an operator who pointed at a specific path wants exactly that path." In practice the opposite was true: an explicit override is almost always a personal, git-ignored `application-local.yml` copying whatever path happened to work on *that* developer's machine — exactly how the bug above reached a teammate's Linux box with a macOS Homebrew path baked in. Now always searches the fallback candidates when the configured binary fails to run, whatever it's set to. |
| — | **Two test files had a stale pre-Docker guard** — `CheckerRunnerTest` and `VerdictPushIntegrationTest` both checked `g++ not on PATH` to decide whether to skip, a leftover from before S4-B1 moved every compile/run into Docker; g++ being on PATH is irrelevant now, so on a machine with g++ installed but Docker not yet configured these ran for real (and failed for real reasons) instead of skipping cleanly like `SandboxExecutorTest`/`JudgeWorkerTest` already do. Both now check Docker readiness (`docker version` + the `arbitrator-judge:latest` image) instead, matching the existing pattern. This is exactly what surfaced a teammate's actual environment issue clearly for the first time: `permission denied ... docker.sock` — not in the `docker` group yet, or hasn't logged out/in since being added (needs a fresh login, not just a new terminal). |
| — | **Host disk-space guard + problem-limit upper bounds** (found during a self-directed re-check, not user-reported) — `SandboxExecutor` now refuses to start a container (clean RE, not a host-disk-fill risk) when free space on the work-root filesystem drops below 1 GiB; `AdminProblemController.update()` now enforces the same time/memory-limit range `ProblemPackageService` already enforced on upload, closing a gap where an in-place edit had no upper bound at all. | `mvn clean install` + 39 tests across `SandboxExecutorTest`/`CheckerRunnerTest`/`JudgeWorkerTest`/`VerdictEvaluatorTest`/`VerdictPushIntegrationTest`/`ProblemPackageServiceTest` all green against a real Docker daemon |
| — | **Every compile was failing on Windows** — `SandboxExecutor.runInContainer` redirected the `docker run -i` process's stdin from `new File("/dev/null")` whenever no test-input file applies (always true during compile). `/dev/null` doesn't exist on Windows — there's no single portable null-device path Java's `File` accepts the same way on every OS — so this failed on every Windows admin PC, unconditionally, independent of whether the docker binary itself resolved correctly. Reported live from a real Windows box: `Cannot run program "..." : \dev\null (The system cannot find the path specified)`. Fixed to not redirect at all and instead close the child process's own stdin stream immediately after `start()` — same "stdin is EOF right away" effect, no OS-specific path involved. | `CheckerRunnerTest` (compiles a real checker binary every run, exercising exactly this code path) passes; 39/39 total against a real Docker daemon |
| — | **Docker infrastructure failures were shown to students as their own Compile Error** — `permission denied ... docker.sock` (or any other docker-CLI-level failure that never gets a container running) exits the `docker run` process with code 1 — the CLI's generic client-side failure code — which `runInContainer` only ever specifically caught as 125 (docker's own "daemon received the request but failed to start the container" code). Everything else, including exit 1, fell through to a normal `ExecutionResult`, and `JudgeWorker` treated any non-zero compile exit as a real `Outcome.ce(...)`, showing the raw Docker permission error to the student as if it were their own mistake. Fixed with a more general, exit-code-independent signal: `/usr/bin/time` runs *inside* the container wrapping the real command and writes its report even when that command fails — a genuine compile error still produces a rusage file, so "non-zero exit, not a timeout (124), and no rusage file at all" now reliably means docker itself never started a container, regardless of which specific exit code that particular failure mode happens to use. |
| — | **Duplicate-submission guard** (FR request) — a contestant can no longer submit byte-for-byte identical source code twice for the same problem; `SubmissionService.submit()` now checks `SubmissionRepository.existsByUserIdAndProblemIdAndSourceCodeAndActiveTrue` and rejects with 409 before persisting. Scoped to `active=true` so a fresh contest run (DOC-9 archives the previous run's submissions on restart/clone) never treats a prior run's attempt as a duplicate. The client needed no changes — it already surfaces any server error message generically via toast. |

## Next up — Sprint 2 remainder

Three parallel branches, disjoint files, no conflicts possible:

| Dev | Chunk | Branch | Work |
|---|---|---|---|
| Eshad | `S2-A1` | `feat/eshad-S2-A1-contest-lifecycle` | Contest CRUD polish + multi-contest support (see Known issue 2) |
| Mahir | `S2-B6` | — | Reject submissions after deadline by server clock (BR-02) — partially in `ContestService` |
| Zahin | `S2-C1` | `feat/zahin-S2-C1-problem-list` | Problem list panel polish, Codeforces status badges |
| Zahin | `S2-C2` | — | Statement renderer + copy-to-clipboard sample blocks |

`S2-A2` and `S2-A4` landed — see the Done table above.

**Checkpoint I2** (`WORKFLOW_PLAN.md` §6) is nearly met — the submit→judge→verdict chain works. What remains
is confirming the live WebSocket banner in the client UI, then Sprint 2's breadth items above.

## Not started — Sprint 3 and 4

Everything in `WORKFLOW_PLAN.md` §6 Sprint 3/4. The big absent pieces:
leaderboard + penalty engine (FR-17/18), freeze (FR-19), announcements (FR-07), float judging (FR-13),
custom checkers (FR-14), rejudge (UC-14), Java/Python judging paths (only C++ exercised so far),
submission history UI, reconnect/backoff, i18n, installer, TestFX, JMeter, JaCoCo.

## Known issues / risks

1. ~~The Linux sandbox has never actually run.~~ **RESOLVED (S4-B1).** `sandbox-run.sh` and the unshare/ulimit
   split are gone; every compile/run/checker execution goes through Docker, which is the same mechanism on
   every platform. MLE, network isolation and fork-bomb containment are all verified — see S4-B1 in Done above.
2. **Single live contest by design (was a bug, now enforced).** Starting a contest now ENDs any other
   ACTIVE/FROZEN one, and `requireCurrent()` prefers a live contest over a finished one. Previously two
   ACTIVE contests made "current" resolve to whichever had the higher id — orphaning the other's problems
   and failing every submission with a misleading 403. Genuine multi-contest (browsing past contests)
   is still out of scope.
3. **Flyway 9.22 warns on MySQL 8.4** ("newer than tested"). Harmless so far; upgrade Flyway if it becomes real.
4. **PDF statements are not supported.** The SRS (FR-05) allows PDF or HTML; the importer accepts
   `.html`/`.htm`/`.txt`/`.md` and rejects PDF-only packages with an actionable message. Rendering PDF needs
   file storage plus a PDF view the JavaFX client doesn't have. Either implement it or amend the SRS to
   HTML-only — don't leave the mismatch unstated at acceptance.
6. **Mockito is unusable on JDK 26** (Byte Buddy in the Boot 3.2.5 BOM caps at Java 22). Tests use
   `java.lang.reflect.Proxy` fakes instead — see `ProblemPackageServiceTest`. Keep new tests Mockito-free.
8. **Auto-reconnect is implemented but NOT proven.** The client now shows a live/offline indicator and a
   watchdog re-subscribes every 3 s when `isLive()` is false, and STOMP heartbeats (10 s each way) are
   configured on both sides so a dead peer can be detected at all. But in testing, `StompSession.isConnected()`
   kept returning true after the server process was killed on loopback, so the drop was never detected in that
   scenario. Chunk **S3-C7** must verify this properly on a real LAN (pull the cable, not `kill`) before
   NFR-R03 / FMEA-03 can be claimed. Until then, assume a genuine disconnect may still need a client restart.
10. ~~Isolation is namespace-based, NOT a container — two gaps remain.~~ **RESOLVED (S4-B1).** Now an actual
   container: `--read-only` root fs with only the work root bind-mounted (no host filesystem visibility), and
   `--memory`/`--memory-swap` set equal for a real cgroup hard RSS cap (not `prlimit --as`'s address-space-only
   bound). Deploying on Linux is no longer required for any of this to hold — Docker gives the same guarantees
   on macOS/Windows dev machines too, which is strictly stronger than decision D2 required.
9. ~~Fork-bomb containment is implemented but NOT verified.~~ **RESOLVED (S4-B1).** `--pids-limit` verified live
   on this (macOS) dev machine: the fork-bomb fixture is reaped in well under a second with zero surviving
   processes, and the same `SandboxExecutorTest.forkBombIsContained` case now runs unconditionally (previously
   `@EnabledOnOs(OS.LINUX)`, never executed in this project until today).
7. **The client UI has never been visually inspected.** Everything compiles and the data layer is proven,
   but no one has *looked* at the standings table, the self-row highlight, the freeze banner, or the
   verdict banner rendering. Two acceptance criteria in §4.8 are specifically visual —
   "30 students x 6 problems with no horizontal scroll at 1280 px" (UIF-13) and the verdict banner timing
   (UIF-10). Run `mvn -pl arbitrator-client javafx:run -Darbitrator.mock=true` and eyeball it against
   m1.codeforces.com before claiming §4 is done. This is the largest unverified surface in the project.
5. `ARBITRATOR_BUNDLE.txt` is a **snapshot** and goes stale after every change. Regenerate with
   `python3 scripts/make-bundle.py` before handing it to anyone — or just use git once the repo is pushed.

## Fixes applied after the initial build (don't re-break these)

- **Uploaded statements ship their own `<style>` block.** It lands after the client's theme CSS in the
  document, so any theme rule it can also set must be `!important` — otherwise source order wins and dark
  mode gets white boxes with white text on them. Backgrounds and borders both need reclaiming, not just
  colours.
- **Nothing may be live after a server restart** (`ContestBootReset`). Anything that looks up "the contest"
  by `state != DRAFT` breaks on a fresh boot — `VerdictPushIntegrationTest` did exactly that and started
  failing the moment this landed.
- `spring.config.import: optional:classpath:languages.yml` added to `application.yml` — without it the judge
  silently returns RE for every submission.
- `-static` removed from the C++ compile command — breaks macOS, unnecessary on Linux.
- `@JdbcTypeCode(SqlTypes.VARCHAR)` added to all five `@Enumerated` fields — Hibernate 6 / MySQL dialect
  otherwise demands native `ENUM(...)` columns and fails schema validation.
- `tomcat-websocket` given an explicit `${tomcat.version}` in the client pom (not in the Boot BOM).
- **Docker must be running before the server starts judging anything** (S4-B1). `SandboxExecutor` checks this
  loudly at startup (`docker version` + `docker image inspect arbitrator-judge:latest`) and logs an error
  rather than crashing the app — but every submission comes back as a judge-error RE until Docker Desktop/
  Engine is up and `scripts/docker/build-sandbox-image.sh` has been run at least once.
