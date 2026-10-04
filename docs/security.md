# Security status and open decisions

**Last verification update:** 2026-10-04. Security findings and user decisions were last consolidated 2026-09-27.

**Deployment status:** beta for supervised lab use; not certified for public production.

This is the consolidated index of known unresolved audit items and user decisions. [audit-history.md](audit-history.md) retains detailed findings, attack-test boundaries, fixes and dated verification history. Earlier proposals and “next item” statements in that archive are historical, not new approval or current status. This index does not claim an exhaustive fresh security scan.

The review reached the end of the 105-item checklist. That does **not** mean every item was reviewed, passed or fixed: items 68–73 were skipped, and the following risks and verification gaps remain. Multiple rows can describe one underlying issue; do not count these as independent proven vulnerabilities.

## On this page

- [Test status](#current-verification-including-the-failure)
- [Unfixed findings](#unfixed-findings-and-source-level-risks)
- [Deferred improvements](#deferred-hardening-and-operational-limitations)
- [Unverified checks](#skipped-or-externally-unverified-checks)
- [Retained policies](#retained-policies-and-non-security-verification-limits)
- [Completed fixes](#completed-work-that-must-not-be-reopened-as-missing)
- [Continuing the review](#instructions-for-the-next-person-or-assistant)

## Current verification, including the failure

**The latest full Maven run passed: 333 tests, zero failures, errors, or skips.** This does not close the earlier intermittent fork/OOM reporting defect or the deferred security findings.

- Latest command: `mvn -B test -Djavafx.platform=linux`, finished **2026-10-04 13:02:06 +06** under JDK 17. Results: **332 server tests + 1 client source-policy test** passed against isolated `arbitrator_test`, with Docker available. `SandboxExecutorTest` passed 40/40, including the previously intermittent fork test.
- The user's earlier 2026-10-04 run used Java 27-ea and produced four test errors in `JwtAuthFilterHealthTest` and `HealthChecksTest`: the managed Byte Buddy version cannot instrument Java 27 classes. That run had no assertion failures but did not reach the client module. This is a test-JDK compatibility issue, not evidence of a production defect; the JDK 17 run above is the actual current full-suite result.
- Previous full command: `mvn -o -B --fail-at-end test -Djavafx.platform=linux`, finished **2026-09-27 00:58:36 +06**. Results: **332 server tests + 1 client source-policy test** passed against isolated `arbitrator_test`, with Docker available.
- Live testing on September 27 exercised the real instructor browser, real JavaFX participant client through an approved temporary harness, and contest APIs. It found an immediate-start submission rejection and repeated paused-state log messages. See [coverage, reproduction evidence, and limits](audit-history.md#live-validation-2026-09-27).
- The September 26 18:04:20 +06 full run had 331 server passes and one `SandboxExecutorTest.forkBombIsContained` error; the client module did not run. An isolated rerun reproduced it. Docker OOM-killed the process (exit 137); missing timing metrics caused startup-failure reporting. That was a reporting/reliability defect, not a demonstrated escape.
- The earlier fork/OOM error did not recur in the latest JDK 17 run. Neither the classifier nor its test was changed. The user's deferred-fix decision still applies; a passing rerun is not proof that the issue is fixed.
- This is local functional evidence, not a clean Ubuntu installation, realistic multi-client LAN rehearsal, independent penetration test, or production certification.

## Unfixed findings and source-level risks

### Item 100 — Fork and memory-error reporting

**Finding:** Reproduced fork/OOM reporting error described above.

**Decision:** User declined/deferred the production fix.

### Item 34 — Writable measurement file

**Finding:** Submitted code can write `/run-metrics`; a marker write was demonstrated. Successful final metric/verdict forgery was **not** established.

**Decision:** Measurement-file hardening declined/deferred.

### Item 52 — Local configuration

**Finding:** Local configuration is tracked, including literal public development credentials; scoped ignore rules are missing. The limited scan was not an exhaustive history/secret audit or proof of a production-secret leak.

**Decision:** Untracking/ignore changes proposed but not approved; user moved on. Preserve working configuration.

### Item 81 — Legacy bundle generator

**Finding:** Legacy text-bundle generator does not exclude runtime material/backup directories; `.gitignore` does not govern its traversal.

**Decision:** Possible accidental bundle disclosure, not a demonstrated distributed leak. No generator fix approved.

### Items 38, 91 — Pre-release problem metadata

**Finding:** A synthetic pre-release submission row exposes its problem code through the owner's history. Normal participant flows cannot create that row; statement/source/tests remain gated.

**Decision:** Low-severity metadata hardening explicitly declined.

### Item 89 — Concurrent grading updates

**Finding:** Worker/admin full-row saves can overwrite grading corrections; concurrent penalty increments can be lost; unfinished rows accept overrides.

**Decision:** Source-level grading/concurrency risks, not dedicated race reproductions or a proven student bypass. Proposed tests/fixes declined.

### Item 103 — MySQL listener

**Finding:** Selected local listener check found `*:3306` around 23:57 +06 on September 26. No 8080/2375/2376 listener at that instant.

**Decision:** MySQL host investigation/binding change declined. This is not proof of LAN/internet reachability; firewall and other DB clients are unverified.

## Deferred hardening and operational limitations

### Items 16, 49 — Content Security Policy

CSP **is implemented**. Compatible script/style policy still permits `'unsafe-inline'`; strict CSP needs admin UI refactoring. Initial absence of CSP was superseded by item 49.

### Item 23 — Existing account passwords

New-account password rules are enforced, but existing accounts were not forced through a migration/reset. User retained the modest minimum; do not impose a 15-character policy.

### Item 25 — Multi-factor authentication

Admin MFA was deferred; password-based authentication remains.

### Item 32 — Docker logs

Docker logging inherits daemon behavior; no dedicated logging/disk-hardening change approved. Do not assert demonstrated unlimited persistent logs.

### Items 50, 51 — Transport encryption

HTTP/WS lacks transport encryption. User explicitly removed HTTPS/certificate setup to preserve build/run workflow. Do not reintroduce it.

### Item 54 — Database privileges

App account has schema-level ALL, not global superuser privileges. A narrower compatible allowlist was proposed but not approved. Flyway uses the same datasource at ordinary startup; do not break migrations.

### Items 55, 56 — Web and judge separation

Trusted web/judge orchestration shares the JVM and datasource. Untrusted compiler/runtime/checker processes remain isolated in containers without DB credentials/network access. Separate services/credentials were not approved.

### Item 60 — Audit log integrity

Durable security audit history is in the same DB, not independently tamper-proof against trusted-host/DB compromise. No separate log service approved.

### Items 63, 66 — Dependency and image updates

Targeted Tomcat update and local image refresh were completed; broader dependency migration/residual image findings remain. September 24 image scan results are historical, not current exploit proof; `linux-libc-dev` headers are not the running host kernel. No new vulnerability scan in this documentation pass.

### Item 64 — Reproducible image builds

Container base tag and apt inputs are not fully reproducibly locked. Runtime image identity **is** pinned locally; do not describe startup as accepting any mutable tag.

### Item 65 — Software bill of materials

No SBOM was added.

### Items 81–83 — Backup and restore

Coordinated DB/material backup and restore workflow/drill are not verified. Recovery documentation alone is not a successful restore.

### Items 85, 86 — External alerts

Monitoring and local health checks exist; external security/outage alert delivery was deferred.

### Item 98 — Shared-host side channels

Shared-kernel/hardware side-channel limitations and readable proc system metadata were retained. No cross-submission secret recovery was demonstrated.

### Item 100 — Release test enforcement

No mandatory every-release live-Docker security gate. Docker/image assumptions can skip tests; deliberate `-DskipTests` and copying existing bundle JARs remain possible. A new gate would change accepted build/release behavior and was not approved.

## Skipped or externally unverified checks

### Item 67 — Repository protections

Actual repository branch protection, collaborator MFA and workflow-token permissions are unverified. Historical public/signed-out inspection is not an administrative settings audit.

### Items 68–73 — Automated security checks

Dedicated secret scanning, SAST, DAST, automated dependency checks, automated container checks and fuzzing were skipped. Related one-off reviews/tests do not close the skipped checks.

### Items 87, 88 — Proxy and traffic protection

No approved proxy/WAF/DDoS workflow or sustained hostile-network/slow-client test. These are conditional exposure/availability concerns, not proof that the supported LAN deployment is internet-facing. A same-host proxy can defeat direct-peer loopback gates unless separately designed and tested.

### Item 102 — Production configuration

Actual production machine/configuration was not identified. Effective overrides, credentials, accounts, grants and logs are not certified. Fresh-empty-DB demo seeding is a known source behavior, not proof that current deployed demo passwords work.

### Items 103, 104 — External network reachability

External authorized port/firewall reachability and deployed network policy remain unverified. Selected local ports and container probes were not a full production scan.

### Item 105 — Independent penetration test

No independent penetration-test report identified in examined project docs; outside reviews are unknown. No external reviewer was contacted, hired or delegated to.

## Retained policies and non-security verification limits

- Item 28: duplicate-registration response intentionally reveals an existing ID; enumeration behavior was retained.
- Item 31: queue remains shared global FIFO. No per-user scheduling fairness guarantee was requested.
- Item 75: initial contest-duration edge cases/arithmetic review was deferred. **Negative time adjustments are intentional** so instructors can shorten a contest; do not ban them. Other approved item-75 numeric fixes remain implemented.
- Item 90: client-reported MAC is not device identity proof; optional “different device” wording clarification was deferred. No plagiarism detector or automatic sanction was added.
- Raw-source ban regexes can match comments/strings. Java memory overhead, real LAN load/reconnect and JavaFX visual behavior need separate validation; these are not automatically exploitable security flaws.

## Completed work that must not be reopened as missing

See the [audit history](audit-history.md) for detailed records and test scope. In particular: symlink-safe host staging (35); duplicate-conflict mapping/problem edit version checks (36); authoritative admission timestamps (37); custom-checker limit violations reported as checker RE, not contestant MLE/OLE (41); PDF signatures/opaque material downloads and ZIP bounds (44–45); CORS/Origin rules (48); enforcing compatible headers/CSP (49); targeted Tomcat update (63); approved request-field/numeric/media-type validation (74–80); early-error no-store headers (92); and representative multi-language/bounded hostile regressions (99–100) are implemented.

Item-89 Submission races are distinct from the fixed item-36 Problem races.

## Terms used here

- **Deferred:** the issue remains open; the user chose not to implement the proposed change.
- **Unverified:** the review lacks enough evidence to establish the result.
- **Source-level risk:** code review identified a possible problem, but a dedicated attack test has not reproduced it.
- **OOM:** out of memory.
- **CSP:** Content Security Policy, which limits what content a browser may run or load.
- **MFA:** multi-factor authentication.
- **SBOM:** software bill of materials, an inventory of shipped components.
- **SAST / DAST:** static code analysis / testing a running application.
- **FIFO:** first in, first out; submissions share one ordered queue.

## Instructions for the next person or assistant

1. Read this index, the [archived verification snapshot](audit-history.md#verification-snapshot) and the detailed section for the chosen item before acting. Preserve earlier failed runs as evidence; do not weaken tests to manufacture green results.
2. Continue **one concern at a time**: verify the actual gap, explain the verdict/proposed fix plainly, then obtain approval before implementing. A recorded skip or proposal is not permission. Reaching 105 is not authorization to implement the generic architecture/release recommendations after the checklist.
3. Preserve the existing build/run workflow: no new certificate setup, mandatory environment variable, separate service or provisioning step without explicit new direction. Preserve global FIFO and negative time adjustments.
4. For this audit, the user's explicit instruction overrides part-ownership restrictions naming Mahir, Eshad and Zahin. This does not authorize destructive Git operations or unrelated changes. Preserve the existing dirty worktree and applied Flyway migrations.
5. Identify and authorize actual external/deployed targets before testing them. Do not launch the app against the development DB merely to run tests; use the isolated test schema and bounded fake-data probes.
6. Record approved changes, test commands/results, limitations and final user disposition in the project docs. Update this file for current dispositions and append dated evidence to the audit record; do not duplicate the audit chronology in the root documents.
