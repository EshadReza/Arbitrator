# Arbitrator — Workflow & Work-Split Plan

**Team:** Eshad · Mahir · Zahin
**Stack:** Spring Boot 3.2 + JavaFX 21 + MySQL 8 · Linux only · Eclipse IDE · offline LAN
**UI reference:** Codeforces (m1.codeforces.com)
**Source SRS:** `Arbitrator_SRS_v1.1.docx` · **Reference project:** `XOROJ-main` (partial reuse only)

---

## 0. Locked decisions (v1.0 — do not reopen)

| # | Decision | Consequence |
|---|---|---|
| D1 | **MySQL 8.0+** is the only datastore | `mysql-connector-j` + `flyway-mysql`. SRS §1.4, §2.2.1, DBR-01, DBR-05, NFR-R02 must be edited: PostgreSQL → MySQL 8.0, `pg_dump` → `mysqldump`. "mySQL 15+" in the dependency table is wrong — there is no MySQL 15. |
| D2 | **Linux only** — server *and* client (Ubuntu 22.04 LTS target) | Real sandboxing becomes achievable (namespaces, rlimits, cgroups v2). Drops the Windows smoke test from §3.3.5 Portability. **Resolves TBD-02, TBD-05, TBD-08.** |
| D3 | **Admin panel = loopback bind + ADMIN JWT** (both, not either) | **Resolves TBD-06.** Servlet filter rejects any non-`127.0.0.1` request to `/admin/**` and `/api/admin/**` with 403, *and* `@PreAuthorize("hasRole('ADMIN')")` on top. |
| D4 | **HTTPS deferred to post-v1.0** | Plain HTTP + WS over the closed LAN for now. NFR-S05 is explicitly waived for v1.0 — write that in the SRS so it isn't read as a defect. The client keeps `server.scheme=http` in its properties file so enabling TLS later is a one-line change, not a refactor. |

**Additional consequence of D2 — the sandbox stops being hand-wavy.** This is the single biggest win. Dev-B's executor runs each submission as:

```
sudo -u arbitrator-sandbox \
  unshare -Urn \                          # new user + NETWORK namespace → no network (NFR-S04, FMEA-08)
  prlimit --cpu=<tl+1> --as=<ml*2> --nproc=64 --fsize=64M --nofile=64 \
  <compiled binary>                        # cwd = per-submission temp dir only (NFR-S03)
```

- Peak memory = `VmHWM` from `/proc/<pid>/status`, or cgroup v2 `memory.peak` → **MLE verdict is now real** (TBD-02 resolved).
- `--fsize=64M` caps disk output (TBD-05 resolved).
- `--nproc=64` + process-group `SIGKILL` stops fork bombs (FMEA-08).
- Hard kill at 2× time limit satisfies NFR-R04.
- No Windows Defender/AV exclusion needed (TBD-08 resolved).

### UI direction — Codeforces, not the SRS's dark theme

SRS §4 opens with *"dark-themed, high-contrast design."* You've chosen the Codeforces look, which is **light**: white content boxes with a thin border, a colored header strip per box (`#E1E1E1` bar, bold title), blue links (`#0000EE`-ish), Verdana/sans body text, monospace sample blocks with a copy button, and a right-hand sidebar. **Edit §4's opening sentence to "light, information-dense theme modeled on Codeforces"** or the UI will contradict the SRS at acceptance. Zahin builds one `codeforces.css` in Sprint 1 and every screen inherits it.

Keep from Codeforces: the boxed-panel grammar, the standings table (rank / handle / solved / penalty / per-problem cells green-or-red), verdict color language, the problem page layout (statement left, limits box top-right). Ignore: ratings, colored handles, blogs, gyms — not in the SRS.

---

## 1. What the XorOJ reference is actually worth

| Reference asset | Verdict |
|---|---|
| `pom.xml` skeleton, Lombok, layering | **Reuse** — swap driver to `mysql-connector-j`, add `spring-boot-starter-websocket`, `flyway-core` + `flyway-mysql`. |
| `JWTService`, `JWTFilter`, `SecurityConfig`, `PasswordConfig`, `XUserDetailsService` | **Reuse (port)** — raise bcrypt cost to 12 (NFR-S06), move secret to env var (NFR-S07). |
| `FileStorageService` | **Reuse** — fits problem-package storage. |
| `ScoreboardService`, `StandingsSnapshotEntity`, `StandingsScheduler` | **Reuse the idea, rewrite the code** — SRS penalty formula (FR-18) differs and freeze (FR-19) doesn't exist there. |
| `entity/` — `XUser`, `Contest`, `Problem`, `TestFile`, `Submission` | **Port selectively** — keep the shape, drop XorOJ columns. |
| `judge/CppExecutor.java` (412 lines) | **Rewrite.** C++ only; compares against a *main solution* instead of stored `.out` files; `/proc` memory read that returns 0 on Windows; TLE faked via exit code 124; **no MLE, no custom checker, no float judging, no fail-fast, no real isolation.** Keep only the async stream-reader pattern. |
| Blog / vote / star / comment / recommendations / `AdvancedDB*` / `AuditLog` | **Delete** — not in the SRS. ~35% of the backend files. |
| `frontend/` (React + Vite + Tailwind + Monaco) | **Delete** — replaced by JavaFX. |
| WebSocket / STOMP | **Absent from the reference entirely.** FR-06, FR-07, FR-15, FR-17 all depend on it. Built from scratch. |

**≈30% port, ≈70% new.** Start a clean workspace and copy classes in deliberately — do not try to "convert" XorOJ.

---

## 2. Repository layout

One Git repo, four Maven projects, one Eclipse workspace.

```
arbitrator/
├─ pom.xml                     parent aggregator (versions only)
├─ rules.md                    ← collaboration rules, read before first commit
├─ WORKFLOW_PLAN.md            this file
├─ config/
│   ├─ eclipse-formatter.xml
│   └─ eclipse.importorder
├─ arbitrator-common/            SHARED CONTRACT — frozen day 3
│   └─ src/main/java/com/arbitrator/common/{dto,enums,api}
├─ arbitrator-server/
│   ├─ src/main/java/com/arbitrator/server/
│   │    {config,security,entity,repo,service,controller,judge,realtime,leaderboard}
│   └─ src/main/resources/{application.yml, db/migration, languages.yml, static/admin}
└─ arbitrator-client/
    ├─ src/main/java/com/arbitrator/client/{app,view,controller,net,model,util}
    └─ src/main/resources/{fxml,css,i18n,images}
```

Zahin never opens a file under `arbitrator-server`. Eshad and Mahir never open a file under `arbitrator-client`. The only shared code is `arbitrator-common`, which is deliberately tiny and frozen on day 3.

### Eclipse setup — identical on all three machines

- Eclipse IDE for Enterprise Java and Web Developers · **JDK 17** · Ubuntu 22.04.
- Plugins: **Spring Tools 4** (Eshad, Mahir) · **e(fx)clipse** + **Scene Builder** (Zahin).
- Import once: `File → Import → Maven → Existing Maven Projects` → repo root → all four projects appear.
- JavaFX arrives as **Maven dependencies** (`org.openjfx:javafx-controls`/`javafx-fxml:21`) plus `javafx-maven-plugin`. **Never** configure a JavaFX SDK or manual module-path — that setting lives in `.classpath` and will conflict on every pull.
- `.gitignore` must exclude `target/`, `.classpath`, `.project`, `.settings/`, `application-local.yml`, `.env`. Committed Eclipse metadata is the number-one source of junk merge conflicts on student teams.
- Everyone imports `config/eclipse-formatter.xml` and enables *Save Actions → format source code*. This eliminates whitespace-only diffs, which is half of all false conflicts.

Local prerequisites on each machine: `mysql-server-8.0`, `g++ 11`, `openjdk-17-jdk`, `python3.10`, `util-linux` (for `prlimit`/`unshare`).

---

## 3. Ownership map — the anti-collision rule

> **You may only edit files inside packages you own.** Need a change elsewhere? Open a GitHub issue tagged `contract-change`; the owner makes it. No exceptions. This one rule is why three people can work daily without merge conflicts.

| Track | Owner | Exclusively owned paths |
|---|---|---|
| Shared contract | **All three** (co-designed day 2, frozen day 3) | `arbitrator-common/**` |
| **A — Platform, Data & Admin** | **Eshad** | `server/config`, `server/security`, `server/entity`, `server/repo`, `server/service/{user,contest,problem,report}`, `server/controller/{auth,admin,contest,problem,user}`, `resources/db/migration`, `resources/static/admin` |
| **B — Judge Engine & Real-time** | **Mahir** | `server/judge/**`, `server/realtime/**`, `server/leaderboard/**`, `server/service/{submission,announcement}`, `server/controller/{submission,announcement}`, `resources/languages.yml`, `scripts/sandbox-run.sh` |
| **C — JavaFX Client** | **Zahin** | `arbitrator-client/**` (entire project, including `codeforces.css`) |
| Build, CI, docs | **Eshad** (PR-reviewed) | root `pom.xml`, `README.md`, `.github/` |

*Roles are labels — swapping any two is a find-and-replace in this file and `rules.md`. Decide in the day-1 meeting and don't revisit.*

### The four known friction points, pre-solved

| Friction | Fix |
|---|---|
| `Submission` entity — Eshad owns it, Mahir judges it | Eshad creates it in **Sprint 1** with every field Mahir needs: `verdict`, `execTimeMs`, `peakMemoryKb`, `compilerOutput`, `queuedAt`, `judgedAt`, `language`, `workstationIp`, `failedTestIndex`. **Frozen after Sprint 1.** |
| `SecurityConfig` — Eshad owns, Mahir adds endpoints under it | Eshad writes **path patterns, not per-endpoint rules**: `/api/admin/**` → ADMIN, `/admin/**` → loopback + ADMIN, `/api/**` → authenticated, `/ws/**` → authenticated. Mahir never opens the file. |
| Flyway migration version collisions | **Reserved ranges.** Eshad `V1__`–`V49__` · Mahir `V50__`–`V79__` · hotfix `V80__`+. Two people can never pick the same number. |
| Shared database | Each developer runs their **own local MySQL 8**. `application.yml` is committed with placeholders; real credentials live in untracked `application-local.yml`. Never point two machines at one DB during development. |

---

## 4. The contract — build this first (Sprint 1, days 2–3)

`arbitrator-common` holds exactly three things. Once it's frozen, nobody blocks anybody.

**Enums** — `Verdict {AC,WA,TLE,MLE,CE,RE}` · `Language {CPP17,JAVA17,PYTHON310}` · `ContestState {DRAFT,ACTIVE,FROZEN,ENDED}` · `Role {STUDENT,ADMIN}`

**DTOs** — `LoginRequest/Response` · `ProblemSummaryDto` · `ProblemDetailDto` · `SubmitRequest` · `SubmitAckDto(submissionId, queuePosition)` · `VerdictEventDto` · `SubmissionHistoryDto` · `LeaderboardDto(rows[], frozen, lastUpdated)` · `AnnouncementDto` · `ContestStateDto(state, serverTimeMs, endTimeMs)`

**Constants** — every REST path and STOMP destination as a `String` constant, so a typo is a compile error rather than a runtime 404.

| REST | STOMP |
|---|---|
| `POST /api/auth/login` | `/topic/contest/{id}/leaderboard` |
| `GET  /api/contests/current` | `/topic/contest/{id}/announcements` |
| `GET  /api/problems/{id}` | `/topic/contest/{id}/state` |
| `POST /api/submissions` | `/user/queue/verdicts` |
| `GET  /api/submissions/mine` | |
| `GET  /api/leaderboard/{cid}` | |
| `POST /api/admin/**` (loopback + ADMIN) | |

**Sprint 1 day 3: this module compiles and is pushed to `main`.** Everything after that runs in parallel.

---

## 5. Nobody waits on anybody

- **Mahir** does not wait for Eshad's database. The judge engine (`SandboxExecutor`, `VerdictEvaluator`, `CheckerRunner`) takes **file paths and limits, not entities**, and is developed with plain JUnit against fixtures in `src/test/resources`. Wiring it to `SubmissionRepository` is a one-day task in Sprint 3.
- **Zahin** does not wait for the server. The client talks to an interface `JudgeApi` with two implementations: `HttpJudgeApi` (real) and `FakeJudgeApi` (canned problems, fake verdicts on a timer, fake leaderboard ticks), switched by `-Darbitrator.mock=true`. The **entire UI is demoable before the server exists**.
- **Eshad** starts at the bottom of the stack and is never blocked.

---

## 6. Sprint plan — 8 weeks, 4 sprints × 2 weeks

### Sprint 1 — Foundations & contract (weeks 1–2)

| Dev | Chunks | Done when |
|---|---|---|
| **Eshad** | `S1-A1` repo + parent pom + 4 modules + `.gitignore` + formatter · `S1-A2` MySQL 8 schema via Flyway: `user, contest, problem, test_case, submission, submission_result, announcement, leaderboard_snapshot` (DBR-03) · `S1-A3` JPA entities + repositories (port from XorOJ) · `S1-A4` auth: register/login, bcrypt(12), JWT 12 h, `SecurityConfig` path rules + **loopback filter for `/admin/**`** (FR-01/02/03, D3) | `mvn clean install` green · login returns a JWT · ADMIN endpoint 403s for a STUDENT token · `/admin` 403s from another machine |
| **Mahir** | `S1-B1` `sandbox-run.sh` + `SandboxExecutor`: `unshare -Urn` + `prlimit` + kill at 2× TL, real peak-RSS read (FR-10, NFR-R04, NFR-S03/S04) · `S1-B2` `languages.yml` compile/run command config (FR-21) · `S1-B3` JUnit fixture suite: AC / WA / TLE / **MLE** / CE / RE programs · `S1-B4` **spike the STOMP + JWT handshake** in the remaining time | All six fixture programs produce the correct verdict from a unit test, no Spring context needed · a fork-bomb fixture is contained |
| **Zahin** | `S1-C1` JavaFX project, Maven run/package · `S1-C2` **`codeforces.css`** — boxed panels, header strips, link blues, monospace blocks · `S1-C3` login screen (UIF-01…04) with connection-status dot · `S1-C4` app shell: three-panel layout + top status bar + nav tabs · `S1-C5` `JudgeApi` interface + `FakeJudgeApi` | Client reaches login in < 5 s (NFR-P05) · shell is navigable keyboard-only (NFR-U04) · side-by-side screenshot against m1.codeforces.com looks like a sibling |
| **All** | `S1-X` `arbitrator-common` designed together day 2, pushed day 3, **frozen** | Module compiles; all three depend on it |

**Checkpoint I1 (end of week 2):** Zahin's client logs into Eshad's real server and receives a JWT.

### Sprint 2 — Vertical slice: submit → judge → verdict (weeks 3–4)

| Dev | Chunks |
|---|---|
| **Eshad** | `S2-A1` contest CRUD + start/stop + authoritative server clock (FR-04/06/08) · `S2-A2` problem-package ZIP upload: extract, validate `.in`/`.out` pairs + `config.json`, reject malformed (FR-05, FMEA-07) · `S2-A3` `GET /api/problems/{id}` + statement serving · `S2-A4` admin panel skeleton (static HTML/JS, loopback-only) — Contests + Problems pages |
| **Mahir** | `S2-B1` `POST /api/submissions` → **persist before queueing** (FMEA-01), 202 + queue position (FR-09) · `S2-B2` judge queue: bounded `ThreadPoolExecutor`, ≥10 parallel jobs, depth gauge (NFR-P04, FMEA-10) · `S2-B3` `VerdictEvaluator` — exact match, whitespace-normalised, fail-fast (FR-12, BR-05) · `S2-B4` WebSocket/STOMP config + JWT handshake auth + `/user/queue/verdicts` (FR-15) · `S2-B5` 1-submission-per-30 s rate limit (BR-01) · `S2-B6` reject after deadline by server clock (BR-02) |
| **Zahin** | `S2-C1` problem-list panel with Codeforces-style status badges · `S2-C2` statement renderer + copy-to-clipboard sample blocks · `S2-C3` code editor (**RichTextFX `CodeArea`**) — highlighting for 3 languages, line numbers, language selector · `S2-C4` submit flow + toast (UIF-08) · `S2-C5` STOMP client + verdict banner, six colour states (UIF-10) |

**Checkpoint I2 (end of week 4) — make or break:** a student logs in, opens a problem, submits C++, and an **AC banner arrives over WebSocket in under 30 s**. Everything after this is breadth, not risk.

### Sprint 3 — Full judging, leaderboard, live features (weeks 5–6)

| Dev | Chunks |
|---|---|
| **Eshad** | `S3-A1` Java 17 + Python 3.10 toolchain config & validation · `S3-A2` user admin: CSV bulk import, reset password (UIF-21) · `S3-A3` admin panel: Submissions table + rejudge button, Users page, Announcements composer, confirmation dialogs on destructive actions (UIF-22) · `S3-A4` post-contest report endpoint (FR-22) · `S3-A5` `mysqldump` backup on contest end (DBR-05) |
| **Mahir** | `S3-B1` float-tolerance judging `max(abs_eps, rel_eps×\|expected\|)` (FR-13) · `S3-B2` custom checker: compile + run `<in> <out> <ans>`, exit-code mapping (FR-14) · `S3-B3` leaderboard engine: `firstACminutes + 20×priorWA`, CE excluded, first-AC only, tiebreakers (FR-17/18, BR-03/04/06) · `S3-B4` freeze / unfreeze (FR-19) · `S3-B5` announcement broadcast (FR-07) · `S3-B6` rejudge pipeline (UC-14) · `S3-B7` CE stderr truncated to 4096 chars (FR-20) |
| **Zahin** | `S3-C1` leaderboard view — Codeforces standings table, per-problem cells, self-row highlight, freeze banner, no h-scroll at 1280 px (UIF-13…16) · `S3-C2` click-handle modal (UIF-17) · `S3-C3` announcement modal + bell + side panel (UIF-18/19) · `S3-C4` submission-history table + read-only source viewer (UIF-12) · `S3-C5` CE log panel (UIF-11) · `S3-C6` contest timer, server-derived, red pulse under 5 min, "Contest Ended" (UIF-05…07) · `S3-C7` reconnect with exponential backoff + "Connection Lost" banner (NFR-R03, FMEA-03) |

**Checkpoint I3 (end of week 6):** three machines, one contest, three languages, live leaderboard, announcements landing.

### Sprint 4 — Hardening, deployment, acceptance (weeks 7–8)

| Dev | Chunks |
|---|---|
| **Eshad** | `S4-A1` HikariCP 5/20, DB-level FKs, soft-delete (DBR-04/06/07) · `S4-A2` deployment: single fat JAR + `install-server.md`, pre-contest disk-space check (FMEA-04) · `S4-A3` JaCoCo ≥ 70 % on server · `S4-A4` OWASP dependency-check + `NOTICES` file (NFR-C03, LRR-03) · `S4-A5` systemd unit with auto-restart (FMEA-01) |
| **Mahir** | `S4-B1` sandbox hardening review: dedicated `arbitrator-sandbox` user, sudoers entry, process-group kill, verify no network from inside (NFR-S03/S04, FMEA-02/08) · `S4-B2` crash recovery — requeue `PENDING` submissions on startup (NFR-R02, FMEA-01) · `S4-B3` JMeter, 30 virtual users, P95 ≤ 500 ms (NFR-P01) · `S4-B4` admin dashboard metrics feed: queue depth, submission rate, connected clients (UIF-23) |
| **Zahin** | `S4-C1` i18n resource bundle, UTF-8 throughout (I18N-01/02) · `S4-C2` silent installer script for lab machines + `server.properties` (host, port, **scheme**) · `S4-C3` TestFX automated tests for UIF-01/08/10 · `S4-C4` smoke test on Ubuntu 22.04 · `S4-C5` one-page student quick-start (NFR-U01) |
| **All** | `S4-X` dress rehearsal: 20+ machines, 6 problems, 2-hour mock contest, then bug triage |

**Deferred to post-v1.0:** HTTPS/WSS + truststore (D4) · Bengali localisation (TBD-01) · partial scoring (TBD-07) · Windows client support.

**Final acceptance:** walk the six Gherkin scenarios in SRS §3.1.3 in front of the supervisor. They are your demo script — rehearse them verbatim.

---

## 7. Bundle 1 — the runnable vertical slice (what gets written first)

**Decision:** the first deliverable is a *runnable vertical slice*, not a full v1.0 and not an empty skeleton. It compiles and runs end-to-end on day one, and it deliberately contains the two riskiest pieces — the Linux sandbox and the STOMP+JWT handshake — so they are proven before anyone builds breadth on top of them.

Everything in Bundle 1 maps to a Sprint 1 or Sprint 2 chunk. Nothing from Sprint 3–4 is included.

**Naming:** repo name and base package are yours to set. Below they appear as `arbitrator` / `com.arbitrator` — substitute freely; the name occurs only in the four `pom.xml` files and in `package`/`import` declarations.

### Root — 8 files
```
pom.xml                        parent aggregator, dependency versions
.gitignore                     exactly as rules.md Rule 3
rules.md · WORKFLOW_PLAN.md
config/eclipse-formatter.xml · config/eclipse.importorder
scripts/sandbox-run.sh         unshare -Urn + prlimit wrapper   [Mahir]
scripts/init-db.sql            creates schema + labjudge user   [Eshad]
```

### `arbitrator-common` — 14 files · shared, frozen day 3
```
pom.xml
enums/   Verdict · Language · ContestState · Role
dto/     LoginRequest · LoginResponse · ProblemSummaryDto · ProblemDetailDto
         SubmitRequest · SubmitAckDto · VerdictEventDto · SubmissionHistoryDto · ContestStateDto
api/     ApiPaths · StompDestinations
```

### `arbitrator-server` — ~40 files
| Package | Files | Owner |
|---|---|---|
| root | `pom.xml`, `application.yml`, `application-local.yml.example`, `languages.yml`, `ArbitratorServerApplication` | Eshad (`languages.yml` → Mahir) |
| `db/migration` | `V1__baseline.sql` — user, contest, problem, test_case, submission, submission_result | Eshad |
| `config` | `SecurityConfig`, `PasswordConfig`, `JacksonConfig`, **`LoopbackAdminFilter`** | Eshad |
| `security` | `JwtService`, `JwtAuthFilter`, `UserDetailsServiceImpl`, **`JwtHandshakeInterceptor`** | Eshad (interceptor co-reviewed with Mahir) |
| `entity` | `User`, `Contest`, `Problem`, `TestCase`, **`Submission` (all fields, frozen)** | Eshad |
| `repo` | 5 Spring Data interfaces | Eshad |
| `service` | `UserService`, `ContestService`, `ProblemService` | Eshad |
| `controller` | `AuthController`, `ContestController`, `ProblemController` | Eshad |
| `judge` | `SandboxExecutor`, `ExecutionResult`, `LanguageConfig`, `JudgeQueue`, `JudgeWorker`, `VerdictEvaluator`, `ExactMatcher` | Mahir |
| `service` / `controller` | `SubmissionService`, `SubmissionController` (persist-before-queue, 202 + queue position, 30 s rate limit) | Mahir |
| `realtime` | `WebSocketConfig`, `VerdictPublisher` | Mahir |
| `src/test` | `SandboxExecutorTest` + 7 fixtures: `ac.cpp`, `wa.cpp`, `tle.cpp`, `mle.cpp`, `ce.cpp`, `re.cpp`, `forkbomb.cpp` | Mahir |

### `arbitrator-client` — ~22 files · all Zahin
```
pom.xml                        openjfx 21 + javafx-maven-plugin + richtextfx
app/       ArbitratorApp · AppState · SceneRouter
net/       JudgeApi (interface) · HttpJudgeApi · FakeJudgeApi · StompClientAdapter
controller/ LoginController · MainController · EditorController · VerdictBanner
fxml/      login.fxml · main.fxml · problem-panel.fxml · editor-panel.fxml
css/       codeforces.css        ← the whole visual language, one file
resources/ server.properties (host, port, scheme) · messages_en.properties
```

**Definition of done for Bundle 1:** on one Ubuntu 22.04 machine — `mvn clean install` green · server starts against local MySQL · client launches, logs in, loads a seeded problem, submits `ac.cpp`, and an AC banner arrives over WebSocket in under 30 s · `SandboxExecutorTest` shows all six verdicts plus fork-bomb containment · `/admin` returns 403 from a second machine.

**Not in Bundle 1** (Sprint 3–4, added later): leaderboard + penalty engine, freeze, float judging, custom checkers, announcements, rejudge, admin panel pages, CSV import, reports, i18n, installer, TestFX, JMeter.

---

## 8. Dividing the files and pushing to GitHub

### The counter-intuitive part: do **not** split the first push three ways

Bundle 1 is a single coherent tree that must compile as a unit. Splitting it across three people's first commits creates exactly the mess this plan exists to avoid — half-resolved imports, a `common` module that doesn't exist yet for two of you, three conflicting `pom.xml` versions.

**One person (Eshad) unpacks the bundle and pushes the whole thing as the initial commit.** Ownership begins at commit #2. From then on, §3's ownership map and `rules.md` govern everything.

### Day 0 — Eshad, once, alone (~20 minutes)

```bash
# 1. create the repo on github.com (private, no README, no .gitignore, no license)

# 2. unpack the bundle into an empty folder
mkdir labjudge && cd labjudge
# place ARBITRATOR_BUNDLE.txt here and run the python3 snippet embedded in
# its header — it writes all 96 files and chmods scripts/sandbox-run.sh

# 3. sanity-check BEFORE the first commit
mvn clean install           # must be green

# 4. first commit — .gitignore goes in first, before Eclipse ever touches the folder
git init -b main
git add .gitignore && git commit -m "chore: gitignore before anything else"
git add . && git commit -m "feat: Arbitrator vertical slice — common, server, client"
git remote add origin git@github.com:<org>/<repo>.git
git push -u origin main

# 5. branches and protection
git checkout -b dev && git push -u origin dev
# on github.com → Settings → Branches → protect `main` and `dev`:
#   require 1 approving review, require branches be up to date before merge
git tag v0.0-bundle && git push --tags
```

Then invite Mahir and Zahin as collaborators and post the clone command in the group chat.

### Day 0 — Mahir and Zahin, once each (~15 minutes)

```bash
git clone git@github.com:<org>/<repo>.git && cd <repo>
mvn clean install                                    # must be green before Eclipse
sudo apt install mysql-server-8.0 g++ openjdk-17-jdk python3.10 util-linux
sudo mysql < scripts/init-db.sql
cp arbitrator-server/src/main/resources/application-local.yml.example \
   arbitrator-server/src/main/resources/application-local.yml   # git-ignored; put your password here
```
Then in Eclipse: `File → Import → Maven → Existing Maven Projects` → repo root → all four appear. Import `config/eclipse-formatter.xml`, enable Save Actions (rules.md Rule 4). Verify `git status` is **clean** — if `.classpath` or `.settings/` shows up, the `.gitignore` didn't land and you fix that before anything else.

### Day 1 onward — everyone

Nobody pushes to `main` or `dev` directly again. Every change is a branch → PR → one approval → merge to `dev`, per `rules.md` Rule 7. `main` only moves at the four checkpoint tags.

```bash
git checkout dev && git pull origin dev
git checkout -b feat/<yourname>-<chunk-id>-<slug>     # e.g. feat/mahir-S2-B4-stomp-verdicts
```

### The first three branches off Bundle 1

| Dev | Branch | What it does |
|---|---|---|
| Eshad | `feat/eshad-S2-A1-contest-lifecycle` | contest CRUD, start/stop, authoritative server clock |
| Mahir | `feat/mahir-S2-B3-exact-matcher` | fail-fast evaluation across all test cases + whitespace normalisation |
| Zahin | `feat/zahin-S2-C1-problem-list` | left-panel problem list with Codeforces status badges |

Three branches, three disjoint file sets, zero possible conflict. That's the pattern for the rest of the project.

---

## 9. Risk watchlist

1. **STOMP + JWT handshake** is where student teams lose three days. Mahir spikes it in Sprint 1 slack (`S1-B4`), not in Sprint 2 under pressure.
2. **`unshare -Urn` needs unprivileged user namespaces enabled.** Ubuntu 22.04 ships them on, but a hardened lab image may set `kernel.unprivileged_userns_clone=0`. Verify on the **actual lab machines** in week 1 — the fallback (a setuid helper or a `nftables` rule per uid) costs Mahir two days if discovered late.
3. **Don't embed Monaco in JavaFX.** RichTextFX `CodeArea` — SRS §4.2 explicitly permits it.
4. **Scope creep from XorOJ.** Blogs, ratings, recommendations are not in the SRS. Deleting them is a feature.
5. **Bus factor on the client.** Zahin owns a whole project alone. Pair Zahin with Eshad for one day in Sprint 2 so someone else can build and run it.
