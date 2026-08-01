# STATUS

**Last updated:** 2026-08-01
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
| UIF-12 | **Submitted code in submission details** — lazy-loaded per row; LRR-02 enforced (another student gets 403) | verified live |
| UIF-21 | **Live monitoring in the console** — who's online, rank/solved/penalty/submission count, full submission feed, view any code | presence tracked from STOMP subscriptions |
| UI | **Sign out**, **switch contest**, **manual refresh** (⟳) in the client | refresh re-pulls contest state, problems, standings and history |
| UI | **Dark mode** (top-bar toggle / Ctrl+D), fullscreen statement (F11) and editor, collapsible problem list (Ctrl+B), restyled client + instructor console | one stylesheet, palette swapped by a `.dark` root class |
| **FR-06/08** | **Contest controls**: start, pause/resume (clock genuinely stops), extend ±minutes, freeze/unfreeze, end | pause held remaining at 6019s across 6 s; +15 min moved the deadline exactly 15 min |
| **UIF-20/21** | **Instructor console rebuilt** — live server clock, elapsed/remaining, state badge, state-aware buttons, contest table, problem upload | replaces the bland skeleton; every control explains what it does |
| — | **Multi-contest**: student contest picker; submissions derive their contest from the problem; standings broadcast per contest | picker auto-skips when only one contest is joinable |
| — | **Error messages now reach clients** (`server.error.include-message`) | Spring was dropping every `ResponseStatusException` reason — clients only ever saw "Forbidden" |
| **S3-C1** | **Client standings view** (UIF-13..16) — Standings tab, runtime problem columns, self-row highlight, freeze banner, "last updated" | compiles; **needs a visual pass on Linux/macOS — see Known issue 7** |

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

1. **The Linux sandbox has never actually run.** `sandbox-run.sh` is written and wired, but every verification
   so far used the macOS fallback path (plain `ProcessBuilder`, no isolation, `peakMemoryKb = -1`).
   **MLE, network isolation, and fork-bomb containment are unproven.** First task on a real Linux box:
   `unshare -Urn true` must succeed, then `mvn test -pl arbitrator-server` — the two skipped tests must go green.
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
7. **The client UI has never been visually inspected.** Everything compiles and the data layer is proven,
   but no one has *looked* at the standings table, the self-row highlight, the freeze banner, or the
   verdict banner rendering. Two acceptance criteria in §4.8 are specifically visual —
   "30 students x 6 problems with no horizontal scroll at 1280 px" (UIF-13) and the verdict banner timing
   (UIF-10). Run `mvn -pl arbitrator-client javafx:run -Darbitrator.mock=true` and eyeball it against
   m1.codeforces.com before claiming §4 is done. This is the largest unverified surface in the project.
5. `ARBITRATOR_BUNDLE.txt` is a **snapshot** and goes stale after every change. Regenerate with
   `python3 scripts/make-bundle.py` before handing it to anyone — or just use git once the repo is pushed.

## Fixes applied after the initial build (don't re-break these)

- `spring.config.import: optional:classpath:languages.yml` added to `application.yml` — without it the judge
  silently returns RE for every submission.
- `-static` removed from the C++ compile command — breaks macOS, unnecessary on Linux.
- `@JdbcTypeCode(SqlTypes.VARCHAR)` added to all five `@Enumerated` fields — Hibernate 6 / MySQL dialect
  otherwise demands native `ENUM(...)` columns and fails schema validation.
- `tomcat-websocket` given an explicit `${tomcat.version}` in the client pom (not in the Boot BOM).
