# Arbitrator — workflow and delivery plan

**Team:** Eshad · Mahir · Zahin
**Current stack:** Java 17 · Spring Boot 3.2.5 · JavaFX 21 · MySQL 8 · Docker · offline LAN
**Current stage:** feature-rich beta moving into correctness, security, and release hardening

This document began as the four-sprint implementation plan. Most of that feature work now exists, so this revision describes the architecture that actually shipped and the remaining path to v1.0. Requirement/chunk identifiers remain useful for traceability, but `STATUS.md` is the source of truth for completion and open defects.

## 1. Locked product decisions

| # | Decision | Current consequence |
|---|---|---|
| D1 | MySQL 8 is the only datastore. | Flyway owns the schema; Hibernate validates it. PostgreSQL references in the original proposal/SRS are obsolete. |
| D2 | Ubuntu 22.04 is the target deployment. | The lab release is Linux, while Docker Desktop allows judge development on macOS/Windows. |
| D3 | Instructor surfaces are local to the server machine. | `/admin/**` and `/api/admin/**` require loopback; admin APIs also require an `ADMIN` JWT. |
| D4 | HTTPS/WSS is deferred. | v1 uses HTTP/WS on a controlled LAN. This remains an explicit security tradeoff. |
| D5 | One contest is joinable/live at a time. | Historical contests remain stored, but opening/starting another contest resolves the previous live state. |
| D6 | Participant code never runs directly on the host. | Compilation, execution, and custom checkers all use disposable Docker containers. |

The active UI direction is information-dense and Codeforces-inspired with light and dark themes. Monaco is out of scope; the client uses RichTextFX.

## 2. Repository and runtime architecture

```text
arbitrator/
├── pom.xml                         Maven parent/aggregator
├── arbitrator-common/              shared API contract
├── arbitrator-server/              Spring Boot server and static admin console
│   ├── src/main/java/.../
│   │   ├── config, security
│   │   ├── entity, repo, service, controller
│   │   ├── judge
│   │   ├── realtime
│   │   └── leaderboard
│   └── src/main/resources/
│       ├── application.yml, languages.yml
│       ├── db/migration/
│       └── static/admin/
├── arbitrator-client/              JavaFX participant application
├── arbitrator-web/                 independent React toolchain experiment
├── scripts/                        database, Docker, and legacy bundle helpers
└── README.md, STATUS.md, rules.md
```

The root Maven reactor builds only `arbitrator-common`, `arbitrator-server`, and `arbitrator-client`. `arbitrator-web` has its own npm lifecycle and is not connected to the product.

### Runtime data flow

1. Spring Boot starts, Flyway migrates MySQL, the demo seeder fills an empty database, and the server checks Docker readiness.
2. The instructor signs into the loopback admin console and creates or selects a contest.
3. Problems are imported from ZIP packages and stored as relational metadata/tests plus optional PDF blobs.
4. Students authenticate in JavaFX, choose the joinable contest, and load released content through REST.
5. A submission is validated and persisted before being placed on the judge executor.
6. A worker creates a temporary workspace, compiles in Docker, runs test cases in Docker, invokes the selected checker, and persists the result.
7. The student receives a private verdict event. Updated standings and contest state are broadcast to subscribed clients.
8. Announcements, materials, clarifications, presence, and monitoring share the same server and contest identity model.

### Persistence boundaries

- MySQL: users, contests, problems, tests, submissions, announcements, clarifications, material metadata, and most operational state
- JDBC side tables: per-test submission results and PDF statement blobs
- Filesystem: uploaded material bodies under `arbitrator.materials.root`
- In-memory: active-session registry, presence, pending notifications, judge executor, and scheduled broadcasters

Database backup alone does not capture uploaded material files.

## 3. Shared contract

`arbitrator-common` is the compile-time contract between server and client. It contains:

- Enums: roles, languages, verdicts, checker types, and contest states
- Records: authentication, contests, problems, submissions, tests, standings, attempts, announcements, materials, clarifications, custom runs, and live events
- Constants: all shared REST paths and STOMP destinations

Existing record fields, enum values, and paths must not be renamed casually. A contract change needs coordinated updates to both consumers and regression tests. Additive records/constants are lower risk but still require review under `rules.md`.

## 4. Ownership and coordination

`rules.md` is authoritative. The broad domains are:

| Track | Owner | Main domain |
|---|---|---|
| Platform, data, and admin | Eshad | configuration, security, entities, repositories, Flyway, platform services/controllers, static admin console, root build/docs |
| Judge and real-time | Mahir | judge, realtime, leaderboard, submission/announcement flow, language configuration, Docker image |
| JavaFX client | Zahin | all client application code, FXML, CSS, resources, and client packaging |
| Shared contract | Team | `arbitrator-common` |

Material and clarification code was added after the original ownership table and is not named explicitly in `rules.md`. Treat it as coordinated platform/API work until the team formally updates the rule.

Migration ranges remain:

- Eshad: V1–V49
- Mahir: V50–V79
- Integration/hotfix: V80–V99

Never edit an applied Flyway migration. Add a new version.

## 5. Current functional baseline

The following end-to-end paths exist:

- Register/login/logout, including replacement of an existing REST session
- Create contest → open lobby → start → pause/resume → adjust time → freeze/unfreeze → end/clone
- Import/view/edit/reorder/delete problems and inspect test cases
- Submit C++/Java/Python → queue → Docker compile/run → exact/custom check → persist → push verdict
- Custom run without storing a contest submission
- Frozen and unfrozen ICPC standings with attempt drill-downs
- Participant source/test history with ownership restrictions
- Instructor live monitoring, source/test inspection, marks, overrides, penalties, exports, and notifications
- Announcements, materials, and clarification ask/answer/approval flows
- HTML, text, Markdown, and PDF problem statements
- JavaFX drafts, editor assistance, themes, fullscreen modes, refresh/reconnect handling, and contest switching

The original “Bundle 1” terminology now refers only to an old vertical-slice snapshot. It does not describe the current repository.

## 6. Historical sprint reconciliation

| Original area | Current state |
|---|---|
| Sprint 1 foundations | Delivered and substantially extended |
| Sprint 2 submit/judge/verdict slice | Delivered end to end |
| Java/Python judging | Delivered |
| Docker sandbox hardening | Delivered, replacing the original host `unshare`/`prlimit` design |
| Leaderboard and freeze | Delivered |
| Announcements | Delivered |
| Custom checker | Delivered |
| Rejudge | Not delivered |
| Float-tolerance checker | Not delivered |
| CSV bulk user import and password reset UI | Not present as a supported current flow |
| Automated backup on contest end | Not delivered |
| JaCoCo, dependency check, JMeter, TestFX | Not delivered |
| Installer/systemd/operator release | Not delivered |
| Bengali localization | Not delivered |
| Full multi-machine rehearsal | Not evidenced in the current repository |

## 7. v1.0 hardening plan

### Phase A — authorization and injection safety

1. **Completed 2026-09-15:** replace the contest-password picker check with persistent server-side contest-access grants established by `POST /api/contests/{id}/join`; the client no longer retains or retransmits plaintext.
2. **Completed 2026-09-15:** enforce grants across contest state, problem/PDF, material, clarification, custom-run/submission, standings, and contest-topic STOMP paths; keep lobby statements and problem codes hidden. Four MySQL-backed HTTP/STOMP tests and a live bypass replay verify the boundary.
3. **Completed 2026-09-15:** generated admin controls use delegated `data-action` event binding rather than interpolating fetched values into inline handlers; student-controlled names are hydrated with `textContent`. Static and live-browser regression checks cover the prior stored-XSS path.
4. **Completed 2026-09-15:** registration normalizes and restricts student IDs to `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`; Unicode display names are capped at 128 code points and reject control characters.
5. **Completed 2026-09-15:** WebSocket handshakes require an active JWT `sid`; live sockets are tracked by `sid` and closed immediately on replacement or logout. A MySQL-backed HTTP/STOMP test verifies old-socket closure, REST rejection, stale reconnect rejection, and replacement-token connectivity.

### Phase B — correctness and data lifecycle

1. **Completed 2026-09-15:** duplicate detection uses exact source equality within the same user/problem scope. Seven tests verify exact-repeat rejection without collapsing indentation, string whitespace, token boundaries, line endings, or trailing spaces.
2. **Completed 2026-09-15:** delete material metadata within the contest transaction and remove files after commit. MySQL regression tests verify rollback preservation and filesystem-failure handling; the live reproduction now deletes successfully. Post-commit file cleanup remains best-effort, with failures logged for manual cleanup.
3. **Completed 2026-09-15:** ZIP import probes beyond the per-entry cap, canonicalizes paths, and rejects traversal and duplicate canonical names while retaining entry-count and aggregate-size limits. Tests cover exact-cap reads, unknown-size overflow, and path aliases.
4. Reconcile the 64 MB multipart limit with the material service's advertised 200 MB cap.
5. Decide and document queue capacity/backpressure behavior.
6. Replace raw source-ban regex scanning with a safer policy or explicitly accept and test its false-positive behavior.

### Phase C — missing contest features

1. Implement rejudge with immutable audit information and predictable leaderboard updates.
2. Implement absolute/relative float tolerance as an explicit checker mode.
3. Decide whether bulk user import, password reset, post-contest reports, and automatic contest-end backup remain v1 requirements.

### Phase D — verification

1. Run all Docker-dependent tests without skips on the target server.
2. Extend integration coverage beyond the now-tested session replacement, contest access, materials, contest deletion, registration policy, and admin rendering safety paths.
3. Add TestFX coverage for login, contest picker, submit/verdict, reconnect banner, standings freeze, and PDF display.
4. Add a 30-user load test for REST, STOMP broadcasting, and judge backlog behavior.
5. Conduct a two-hour rehearsal with 20+ Ubuntu clients, multiple languages, at least six problems, network interruption, restart/recovery, and a frozen scoreboard.

### Phase E — release engineering

1. Repair `.gitignore` and remove already-tracked IDE/local/generated artifacts without deleting developers' local files.
2. Replace the stale bundle generator or retire it in favor of tagged Git releases.
3. Align distribution scripts with `arbitrator-client-<platform>.jar` and build Linux artifacts explicitly.
4. Package a JRE, client configuration, server JAR, Docker-image setup, MySQL setup, and operator scripts as a coherent release.
5. Document and test database-plus-material backup/restore.
6. Produce instructor and student quick-start guides from the tagged release.

## 8. Development workflow

Use the branch/review process in `rules.md`:

```bash
git checkout dev
git pull origin dev
git checkout -b feat/<name>-<chunk-or-issue>-<slug>
```

Before opening a PR:

```bash
git checkout dev
git pull origin dev
git checkout <feature-branch>
git merge dev
mvn clean install
```

Also run the smallest relevant targeted tests while iterating. Changes to the React experiment use its own `npm run build` and `npm run lint` checks.

Keep branches scoped to one behavior. Changes spanning ownership domains should be divided into coordinated commits or made by the owning developer. Never rewrite applied migrations, commit credentials, or run contestant/fork-bomb fixtures directly on the host.

## 9. Test strategy

| Layer | Current coverage | Required next coverage |
|---|---|---|
| Pure judge/scoring | Exact verdict and leaderboard unit tests | Float checker, duplicate policy, source-ban edge cases |
| Problem importer | Broad package validation tests, including unknown-size/over-cap and duplicate canonical-path cases | Aggregate-limit and entry-count stress cases |
| Docker execution | Sandbox/worker/checker tests, environment-skippable | Mandatory target-host run and long-run cleanup checks |
| WebSocket | Server integration test, environment-skippable | Active-session invalidation and real JavaFX reconnect |
| Services/controllers | Material/contest deletion commit and rollback tests on MySQL | Authorization matrix, material authorization, restart behavior |
| JavaFX | Compile/manual verification | TestFX plus Ubuntu visual acceptance |
| Admin console | Manual/live checks | Injection-safe DOM tests and browser workflow tests |
| Performance | No repeatable suite | REST/STOMP/judge load and two-hour soak |

The latest audit run is recorded in `STATUS.md` and must be updated when the verification state changes.

## 10. Acceptance gate

A v1.0 tag requires all of the following:

- No open Priority 0 or Priority 1 findings from `STATUS.md`
- Full server test suite on the deployment host with no environment skips
- Successful Ubuntu client install on a clean machine
- Successful multi-client LAN rehearsal and reconnect/restart drill
- Repeatable backup and restore of MySQL plus material files
- Docker judge image and daemon validated under the real server account
- Documentation, configuration templates, and artifacts generated from the same commit
- Instructor sign-off on the end-to-end contest, monitoring, freeze, export, and recovery flows

Post-v1 candidates remain HTTPS/WSS, Bengali localization, partial scoring, a production web frontend, and support for distributed/multi-server deployment.
