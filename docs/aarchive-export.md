# Arbitrator → AArchive export format

**Format:** `arbitrator-aarchive` · **formatVersion:** `1` · **Producer:** Arbitrator (schema version 83)

This document fully specifies the archive Arbitrator produces with **Export for AArchive**.
It is written for whoever builds **AArchive**, the online repository that imports these
archives and presents each contest as if it had been run on AArchive itself. Read it top to
bottom once. Everything an importer needs is here; the Arbitrator source is not required.

## Contents

1. [What Arbitrator is (domain primer)](#1-what-arbitrator-is-domain-primer)
2. [Producing an archive](#2-producing-an-archive)
3. [Container layout](#3-container-layout)
4. [Conventions used in every field](#4-conventions-used-in-every-field)
5. [Top-level object](#5-top-level-object)
6. [Sections in detail](#6-sections-in-detail)
7. [Rules AArchive must reproduce](#7-rules-aarchive-must-reproduce)
8. [What a contestant could see during the contest](#8-what-a-contestant-could-see-during-the-contest)
9. [Security and privacy requirements for AArchive](#9-security-and-privacy-requirements-for-aarchive)
10. [What the archive does not contain](#10-what-the-archive-does-not-contain)
11. [Import algorithm](#11-import-algorithm)
12. [Suggested AArchive pages](#12-suggested-aarchive-pages)
13. [Versioning](#13-versioning)
14. [Complete sample](#14-complete-sample)

---

## 1. What Arbitrator is (domain primer)

Arbitrator is an offline, LAN-based programming-contest system used in university labs.
An instructor (role `ADMIN`) creates a **contest**, adds **problems** (each with hidden **test
cases**), and runs the contest. Students (role `STUDENT`) join, read statements, and send
**submissions** (source code in C++17, Java 17 or Python 3.10). A sandboxed judge compiles and
runs each submission against the test cases in order and assigns a **verdict**. Standings are
ICPC-style: most problems solved, then least penalty time.

During a contest the instructor can also post **announcements**, answer **clarifications**
(questions from contestants), share downloadable **materials**, pause/resume or extend the
clock, freeze the public standings, override verdicts, adjust penalty minutes, and assign
**marks** (a 0–100 grade per participant).

One archive = one contest, with everything above.

## 2. Producing an archive

* **Console:** Instructor console → *Standings* → **⭳ Export for AArchive**. If the contest is not
  `ENDED`, the console asks for confirmation first.
* **HTTP:** `GET /api/admin/contests/{contestId}/export/aarchive` with an `ADMIN` bearer token, from
  the server machine itself (admin APIs only answer loopback requests).
  * `200` → `Content-Type: application/zip`, `Content-Disposition: attachment; filename="aarchive_<title-slug>_<YYYY-MM-DD>.zip"`
  * `404` unknown contest · `401`/`403` not an admin · `5xx` export failed (no partial ZIP is ever sent)
* Each export is recorded in Arbitrator's security audit log.

The export is read-only. It never changes contest state.

## 3. Container layout

A standard ZIP (deflate, UTF-8 entry names):

```
aarchive.json                         ← the manifest: all structured data (always present)
problems/<problemId>/statement.pdf    ← only for PDF-statement problems
problems/<problemId>/tests/<idx>.in   ← test input, UTF-8 text
problems/<problemId>/tests/<idx>.out  ← expected output, UTF-8 text
materials/<materialId>/<filename>     ← instructor materials, raw bytes
```

Rules:

* `aarchive.json` is **the** source of truth. Every other entry is referenced from it by a
  [`FileRef`](#fileref) and also listed in the top-level [`files`](#files) index.
* **Never derive meaning from entry paths.** Resolve files only through `FileRef.path` values,
  and treat each path as an opaque key into the ZIP. Do not extract to disk using entry names
  (zip-slip); store blobs under keys you generate.
* Every non-manifest entry appears in `files` exactly once; every `FileRef` points to one of them.
* Verify each blob's `sizeBytes` and `sha256` (lowercase hex SHA-256 of the raw bytes) before
  accepting an import.
* Test data files are text exactly as stored (line endings preserved, no BOM added).
* The manifest is pretty-printed UTF-8 JSON. It is written last in the ZIP; read it by name.

Size expectations: a single material may be up to 64 MiB, a statement PDF up to 16 MiB, a
single test file up to 16 MiB. Source code is at most 256 KiB per submission. Stream blobs;
do not hold a whole archive in memory if you can avoid it.

## 4. Conventions used in every field

| Topic | Convention |
|---|---|
| Field naming | camelCase. Every field listed here is always present; absent values are JSON `null`, never omitted. |
| Timestamps | ISO-8601 UTC strings ending in `Z`, e.g. `"2026-10-20T12:39:07.946055Z"`. Fractional seconds are optional and up to 9 digits. Parse with any ISO-8601 parser. |
| Timestamp precision | Varies by source column — see [precision table](#timestamp-precision). Never assume sub-second precision on contest-level times. |
| IDs | Positive integers local to the exporting Arbitrator server (database auto-increment). They are unique **within one archive** but not across archives or servers. Namespace them by archive in AArchive (e.g. `(archiveId, submissionId)`). |
| Sentinel `-1` | Numeric "not applicable/unknown" (e.g. `execTimeMs: -1` for a compile error). Distinct from `null`. |
| Enums | Upper-case strings. Full value sets are listed with each field. Accept unknown values gracefully (display them raw). |
| Usernames | `username` is the contestant's login ID (in the lab, typically a student ID). Case-sensitive. Unique per server. `displayName` is a free-form name (may contain any Unicode and markup characters — always render as text). |
| Text | All strings are Unicode. Source code, outputs, questions, answers are **plain text** — never HTML. |
| HTML | Only `problems[].statement.html` and `announcements[].bodyHtml` are HTML, already sanitized (§9). |

### Timestamp precision

| Field | Precision | Notes |
|---|---|---|
| `contest.startTime`, `scheduledStartAt`, `pausedAt`, `frozenAt`, `endedAt`, `effectiveEndTime` | whole seconds | MySQL `DATETIME` rounds to the nearest second. All scoring uses these stored values. |
| `submissions[].submittedAt` | microseconds | The authoritative submission instant. |
| `submissions[].judgedAt`, `announcements[].createdAt`, `clarifications[].askedAt/answeredAt`, `materials[].uploadedAt`, `participants[].accountCreatedAt/accessGrantedAt` | whole seconds | |
| `auditEvents[].occurredAt` | microseconds | |
| Instants inside `auditEvents[].details` | nanoseconds | The in-memory value at the moment of the change, before database rounding. |
| `generatedAt`, `standings.computedAt` | sub-second | Export time. |

## 5. Top-level object

```jsonc
{
  "format": "arbitrator-aarchive",   // constant; reject anything else
  "formatVersion": 1,                // integer; see §13
  "generatedAt": "<timestamp>",      // when the export ran
  "generator": { "application": "Arbitrator", "schemaVersion": "83" },
  "contest": { … },                  // §6.1
  "judge": { … },                    // §6.2  judging configuration
  "problems": [ … ],                 // §6.3  in contest order
  "participants": [ … ],             // §6.4
  "submissions": [ … ],              // §6.5  in submission order
  "standings": { … },                // §6.6  final standings as Arbitrator computed them
  "announcements": [ … ],            // §6.7  oldest first
  "clarifications": [ … ],           // §6.8  oldest first
  "materials": [ … ],                // §6.9  oldest first
  "auditEvents": [ … ],              // §6.10 oldest first
  "liveNotifications": [ … ],        // §6.11
  "files": [ … ],                    // §6.12 index of every blob in the ZIP
  "warnings": [ "…" ]                // §6.13 human-readable caveats; empty for a clean export
}
```

`generator.schemaVersion` is Arbitrator's database migration version (string, may be `null`).
It is informational; `formatVersion` is what governs parsing.

## 6. Sections in detail

### 6.1 `contest`

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Contest ID. |
| `title` | string | Unique per server, ≤ 200 chars. |
| `state` | enum | State at export time: `DRAFT`, `LOBBY`, `ACTIVE`, `PAUSED`, `FROZEN`, `ENDED`. Normally `ENDED` (see §7.1). |
| `hasPassword` | bool | Whether joining required a contest password. The password itself is never exported. |
| `durationMinutes` | int | Scheduled length **including every extension/reduction** the instructor applied. The original planned length is only recoverable from `auditEvents` (§7.9). |
| `scheduledStartAt` | timestamp\|null | A lobby auto-start countdown target, if one was set and not cleared. |
| `startTime` | timestamp\|null | When the contest clock started (`null` = never started). **Time zero for all scoring.** |
| `pausedAt` | timestamp\|null | Set only if a pause was in progress at export time (never for an `ENDED` contest). |
| `pausedMillis` | int | Total milliseconds of completed pauses. |
| `frozenAt` | timestamp\|null | When the standings were frozen. Non-null only if still frozen when the contest ended (unfreezing clears it). See §7.4. |
| `endedAt` | timestamp\|null | When the contest actually ended (early end by instructor, or the clock running out). |
| `effectiveEndTime` | timestamp\|null | `endedAt` if set, else `startTime + durationMinutes + pausedMillis`, else `null`. |
| `showTestCases` | bool | Whether contestants could view the test data behind their own verdicts (§8). |
| `allowPrivateClarifications` | bool | Whether askers could mark clarifications private. |

### 6.2 `judge`

Judging configuration of the exporting server **at export time** (it is server-wide and could in
principle have changed since the contest ran; in practice it rarely does).

| Field | Type | Meaning |
|---|---|---|
| `penaltyPerRejectMinutes` | int | Penalty minutes per rejected attempt before an accepted one (currently 20). |
| `submitCooldownSeconds` | int | Minimum seconds between two submissions by one user. |
| `compileTimeoutMs` | int | Compilation wall-clock limit. |
| `checkerTimeLimitMs`, `checkerMemoryLimitKb` | int | Limits applied to custom checkers. |
| `maxSourceBytes` | int | Largest accepted source file. |
| `dockerImage` | string | Sandbox image tag used for compile/run. |
| `compilerOutputLimitChars` | int | `submissions[].compilerOutput` is truncated to this many chars. |
| `storedOutputLimitChars` | int | `testResults[].actualOutput` is truncated to this many chars. |
| `languages[]` | list | One entry per supported language (see below). |
| `verdicts[]` | list | Reference table of every verdict code. |

`languages[]`:

| Field | Example | Meaning |
|---|---|---|
| `id` | `"CPP17"` | The value used in `submissions[].language`. One of `CPP17`, `JAVA17`, `PYTHON310`. |
| `displayName` | `"C++17"` | Human label. |
| `configKey` | `"cpp17"` | Internal key. |
| `sourceFile` | `"main.cpp"` | File name the source was saved as (`Main.java` for Java — the class must be `Main`). Use it for syntax-highlighting and "download source". |
| `compileCommand` | `"g++ -O2 -std=c++17 -o {exe} {src}"` | Empty string = interpreted (no compile step). `null` = not configured. Placeholders: `{src}` source path, `{exe}` binary path, `{dir}` work dir. |
| `runCommand` | `"{exe}"` | Same placeholders. |

`verdicts[]` (complete set):

| `code` | `label` | `countsAsRejectedAttempt` |
|---|---|---|
| `AC` | Accepted | false |
| `WA` | Wrong Answer | true |
| `TLE` | Time Limit Exceeded | true |
| `MLE` | Memory Limit Exceeded | true |
| `CE` | Compilation Error | false |
| `RE` | Runtime Error | true |
| `OLE` | Output Limit Exceeded | true |

Show the full label next to every verdict badge (e.g. "WA — Wrong Answer"); Arbitrator does.
`RE` is also used for judge-side failures; those carry a `compilerOutput` starting with
`"Judge error: "`.

### 6.3 `problems[]`

Ordered as contestants saw them (by `ordering`, then `code`).

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Problem ID. |
| `code` | string | Display letter: `"A"`, `"B"`, … (≤ 8 chars, unique within the contest). Standings columns are keyed by this. |
| `title` | string | ≤ 200 chars. |
| `ordering` | int | Sort key. |
| `timeLimitMs` | int | Per-test wall-clock limit. |
| `memoryLimitKb` | int | Per-test memory limit in KiB (262144 = 256 MiB). |
| `checkerType` | enum | `EXACT` (built-in comparison, §7.6) or `CUSTOM` (instructor checker). |
| `checkerSource` | string\|null | For `CUSTOM`: C++17 source of the checker (compiled with `g++ -O2 -std=c++17`). It is invoked as `checker <input> <contestant_output> <expected_output>`; exit code `0` = AC, `1` or `2` = WA, anything else / crash / limit = judge failure (`RE`). Instructor-only material — never show to contestants. |
| `statement` | object | See below. |
| `testCases[]` | list | All test cases, ascending by `idx`. Hidden data — see §8/§9. |

`statement`:

| Field | Type | Meaning |
|---|---|---|
| `format` | `"HTML"` \| `"PDF"` | Which one is the real statement. |
| `html` | string | Sanitized HTML statement. For `PDF` problems this is only a short placeholder; show the PDF instead. |
| `pdf` | FileRef\|null | The PDF statement for `PDF` problems (`null` for HTML problems, or if the PDF was missing — then a warning exists). |

Statement HTML may contain headings, paragraphs, lists, tables, `<pre>`/`<code>`, `<sup>`/`<sub>`,
emphasis, HTML entities, and math written with those (Arbitrator deliberately avoids LaTeX). It
contains no scripts, styles, images, links, forms or embedded content.

`testCases[]`:

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Test case ID. |
| `idx` | int | Stored index; tests run in ascending `idx`. Usually 1, 2, 3 … but may have gaps. |
| `ordinal` | int | 1-based position in run order. `submissions[].failedTestIndex` counts in **ordinals**; `testResults[].testIndex` uses **idx**. They coincide whenever `idx` is gap-free from 1. |
| `input` | FileRef | Test input. |
| `expectedOutput` | FileRef | The reference answer. For `CUSTOM` problems it is *a* correct answer, not necessarily the only one. |

### 6.4 `participants[]`

Every user connected to this contest: anyone granted access (entered the contest), anyone who
submitted, and anyone who asked a clarification. Ordered by access-grant time, then first appearance.
A participant may have zero submissions. Usually all have `role: "STUDENT"`.

| Field | Type | Meaning |
|---|---|---|
| `userId` | int | User ID, referenced by submissions/clarifications. |
| `username` | string | Login/student ID. |
| `displayName` | string | Shown name. Render as text. |
| `role` | enum | `STUDENT` or `ADMIN`. |
| `accountCreatedAt` | timestamp\|null | When the Arbitrator account was registered. |
| `accessGrantedAt` | timestamp\|null | When they first entered this contest (`null` = never formally joined, e.g. a submitter from a pre-grant legacy contest). |
| `marks` | int\|null | Instructor-assigned grade 0–100, or `null` if none. Same rule as the instructor's standings view (§7.5). |
| `macAddress` | string\|null | **Sensitive.** Hardware address of the machine that *most recently* logged in as this user — a server-wide value, not specific to this contest. |
| `macChangedAt` | timestamp\|null | **Sensitive.** When that address last changed from a different one. |

### 6.5 `submissions[]`

Every submission ever stored for this contest, ordered by `submittedAt` then `id`.

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Submission ID ("run ID"). |
| `userId`, `username` | int, string\|null | Author. |
| `problemId`, `problemCode` | int, string\|null | Target problem. |
| `language` | enum | `CPP17`, `JAVA17`, `PYTHON310` (see `judge.languages`). |
| `sourceCode` | string | The exact source as submitted. |
| `status` | enum | `DONE` (judged), `PENDING` (queued), `JUDGING`. Non-`DONE` only if exported mid-contest. |
| `verdict` | enum\|null | Final verdict (`null` while not judged). **Authoritative**, including instructor overrides — it can disagree with `testResults` (see §7.8). |
| `execTimeMs` | int | Maximum wall time over the tests that ran; `-1` for CE / judge error. |
| `peakMemoryKb` | int | Maximum peak memory over the tests that ran; `-1` if unknown (e.g. killed on timeout before measurement, CE). |
| `compilerOutput` | string\|null | Compiler diagnostics for CE (paths stripped, ≤ 4096 chars), a forbidden-API message (e.g. threads/process spawning are banned), or `"Judge error: …"`. Otherwise usually `null`. |
| `failedTestIndex` | int | 1-based **ordinal** of the first failing test; `-1` for AC, CE, judge error, or unjudged. |
| `marks` | int\|null | Raw per-submission marks value (see §7.5 for the participant-level rule). |
| `manualPenaltyDelta` | int | Instructor penalty adjustment in minutes stored on this submission (may be negative). |
| `workstationIp` | string\|null | **Sensitive.** LAN IP the submission came from (academic-integrity log). |
| `submittedAt` | timestamp | Server-validated submission instant (µs). The time used for scoring. |
| `judgedAt` | timestamp\|null | When judging finished (whole seconds). |
| `elapsedMillisFromStart` | int\|null | `submittedAt − contest.startTime` in ms, wall clock (pauses are **not** subtracted). `null` if the contest never started. |
| `active` | bool | `false` = archived from an earlier run of the same contest; excluded from all standings, history, and counts. Show such rows only in an admin view, if at all. |
| `testResults[]` | list | Per-test outcomes in the order they were recorded. |

`testResults[]`:

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Row ID; higher = recorded later. |
| `testIndex` | int | The test's `idx` (join to `problems[].testCases[].idx`). |
| `verdict` | enum | Verdict of this single test. |
| `execTimeMs` | int | Wall time on this test (may be `-1`). |
| `peakMemoryKb` | int | Peak memory on this test; `-1` if not measured (normal for a timed-out run). |
| `actualOutput` | string\|null | What the program printed, truncated to 4096 chars. `null` = not recorded (judged by an older build), which is different from `""` (printed nothing). |

Judging is **fail-fast**: tests run in ascending `idx` and stop at the first non-AC, so a rejected
submission has rows for the passing tests plus the failing one, and none after. CE submissions have
no rows. If a submission was re-judged after a server crash, it can have two rows for the same
`testIndex`; **the row with the higher `id` wins**.

### 6.6 `standings`

The standings exactly as Arbitrator computed them at export time. For an `ENDED` contest these are
the final standings. AArchive should display these as the official result, and may recompute them
(§7.3) for "standings at time T" replays.

| Field | Type | Meaning |
|---|---|---|
| `frozen` | bool | `true` only if exported while the contest was `FROZEN`; then rows are the freeze snapshot. |
| `computedAt` | timestamp | |
| `problemCodes` | string[] | Column order. |
| `rows[]` | list | Ranked rows, rank 1 first. |

`rows[]`:

| Field | Type | Meaning |
|---|---|---|
| `rank` | int | 1, 2, 3 … strictly sequential (ties are broken, never shared — §7.3). |
| `userId`, `username`, `displayName` | | Participant. |
| `solved` | int | Problems solved. |
| `penaltyMinutes` | int | Total penalty (≥ 0). |
| `marks` | int\|null | Instructor grade (§7.5). |
| `bestSubmissionId` | int\|null | The instructor console's "best" submission for this participant (§7.5). |
| `cells[]` | list | One per problem, in `problemCodes` order. |

`cells[]`:

| Field | Type | Meaning |
|---|---|---|
| `problemId`, `problemCode` | | Column. |
| `solved` | bool | |
| `failedAttempts` | int | If solved: rejected attempts before the first AC. If unsolved: all rejected attempts. CE never counts. |
| `solvedAtMinutes` | int | Minutes from start to the first AC (floor); `-1` if unsolved. |
| `solvedAtSeconds` | int | Same instant in seconds (for an `HH:MM:SS` display); `-1` if unsolved. |
| `firstSolve` | bool | This cell was the first AC on this problem in the whole contest ("first blood"). |
| `ceAttempts` | int | Compile errors on this problem (no penalty, but the cell was "touched"). |
| `firstAcceptedSubmissionId` | int\|null | The AC that solved it. |
| `bestSubmissionId` | int\|null | The instructor console's "best" submission on this problem (§7.5). |

Display convention (ICPC/Codeforces style): solved → `+` or `+N` (N = `failedAttempts`) and the
time; unsolved with attempts → `-N`; only CEs → show as touched (e.g. a muted mark); untouched → empty.
Highlight `firstSolve` cells.

Only participants with at least one **judged, active** submission on a current problem appear in
`rows`. Others are still in `participants`.

### 6.7 `announcements[]`

| Field | Type | Meaning |
|---|---|---|
| `id` | int | |
| `bodyHtml` | string | Sanitized HTML (same allowlist as statements). |
| `createdAt` | timestamp | When it was broadcast. Every connected contestant saw it as a pop-up at this moment. |

### 6.8 `clarifications[]`

| Field | Type | Meaning |
|---|---|---|
| `id` | int | |
| `problemId`, `problemCode` | int\|null, string\|null | `null` = a general question about the contest. |
| `userId`, `username` | | Who asked. **Never shown to other contestants** in Arbitrator. |
| `question` | string | Plain text, ≤ 4096 chars. |
| `answer` | string\|null | Plain text, ≤ 8192 chars; `null` = unanswered. |
| `askedAt` | timestamp | |
| `answeredAt` | timestamp\|null | Time of the latest answer (answers can be revised). |
| `publicRequested` | bool | The asker's choice: `true` = may be shared with everyone; `false` = private between asker and instructor. |
| `approved` | bool | Instructor published the answered question to the whole contest. |
| `visibleToAll` | bool | `publicRequested && approved` — whether every contestant could see it. |

### 6.9 `materials[]`

Files the instructor shared (slides, PDFs, anything) for download.

| Field | Type | Meaning |
|---|---|---|
| `id` | int | |
| `filename` | string | Original display name (sanitized; ≤ 200 chars). Use it as the download name. |
| `contentType` | string | MIME type as uploaded (falls back to `application/octet-stream`). Untrusted — see §9. |
| `sizeBytes` | int | Size recorded at upload. |
| `uploadedAt` | timestamp | |
| `file` | FileRef\|null | The bytes. `null` means the file was missing on the server's disk (a warning exists). |

### 6.10 `auditEvents[]`

Arbitrator's security/change log rows that concern this contest: changes to the contest row itself,
and any change whose snapshot names this contest (problems, submissions, announcements,
clarifications, materials) or one of its problems (test cases). This is the **only** record of the
contest timeline (opening, start, pauses, freezes, extensions, verdict overrides…). See §7.9.

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Monotonic. |
| `occurredAt` | timestamp | |
| `actor` | string\|null | Admin username who caused it. |
| `action` | string | `"<Controller>.<method>"`, e.g. `AdminContestController.pause`. |
| `targetType` | string\|null | Entity: `Contest`, `Problem`, `TestCase`, `Submission`, `Announcement`, `Clarification`, `Material`. |
| `targetId` | string\|null | Entity ID as a string. |
| `peerIp` | string\|null | **Sensitive.** |
| `outcome` | string | Always `COMMITTED_CHANGE` for these rows. |
| `httpStatus` | int\|null | |
| `details` | object | `{"before": {…}\|null, "after": {…}\|null}` — `before: null` = created, `after: null` = deleted. Only an allowlisted set of non-sensitive scalar fields is ever recorded (no text bodies, code, or titles). Instants are ISO strings. |

Fields that can appear in `details.before/after` include: `state`, `durationMinutes`, `startTime`,
`endedAt`, `pausedAt`, `pausedMillis`, `scheduledStartAt`, `frozenAt`, `showTestCases`,
`allowPrivateClarifications` (contest); `contestId`, `problemId`, `userId`, `verdict`, `status`,
`marks`, `manualPenaltyDelta`, `active` (submission); `timeLimitMs`, `memoryLimitKb`, `ordering`,
`checkerType`, `statementIsPdf` (problem); `idx` (test case); `approved`, `isPublic` (clarification);
`sizeBytes` (material).

### 6.11 `liveNotifications[]`

A best-effort snapshot of the instructor's in-memory connectivity alerts for this contest at export
time (lost on server restart, and cleared when a contest ends — usually empty).

| Field | Type | Meaning |
|---|---|---|
| `id` | int | |
| `username`, `displayName` | | |
| `type` | string | `DISCONNECTED`, `RECONNECTED`, or `MAC_CHANGED`. |
| `minutesOffline` | int | For `RECONNECTED`: how long they were away. |
| `at` | timestamp | |

### 6.12 `files[]`

| Field | Type | Meaning |
|---|---|---|
| `path` | string | ZIP entry name. |
| `kind` | enum | `TEST_INPUT`, `TEST_EXPECTED_OUTPUT`, `STATEMENT_PDF`, `MATERIAL`. |
| `sizeBytes` | int | Exact byte length. |
| `sha256` | string | Lowercase hex SHA-256. |

<a id="fileref"></a>**FileRef** (used inside sections): `{ "path", "sizeBytes", "sha256" }` — same values as the matching `files[]` entry.

### 6.13 `warnings[]`

Plain-English caveats found while exporting, e.g.:

* `Contest was ACTIVE when exported, not ENDED; its data may still change.`
* `3 submission(s) had not finished judging when exported.`
* `Contest was FROZEN when exported: standings are the freeze-time snapshot.`
* `Problem B is marked as a PDF statement but its PDF is missing.`
* `Material "slides.pdf" (id 12) is missing on disk.`

Show these to the uploader before publishing. An archive with an empty `warnings` list is complete.

## 7. Rules AArchive must reproduce

### 7.1 Contest lifecycle

```
DRAFT ──open──▶ LOBBY ──start──▶ ACTIVE ⇄ PAUSED
                                   │  ▲
                              freeze  unfreeze
                                   ▼  │
                                  FROZEN ──pause──▶ PAUSED (resume → ACTIVE)
ACTIVE / FROZEN / PAUSED ──end (or clock runs out)──▶ ENDED  (terminal)
```

* `LOBBY`: contestants can enter and wait; problems are hidden; no clock. An optional
  `scheduledStartAt` auto-starts the contest.
* `ACTIVE`: running, accepting submissions.
* `PAUSED`: clock stopped; submissions refused; the paused time is given back (the deadline moves).
* `FROZEN`: running and accepting submissions, but the public standings stay as they were at `frozenAt`.
* `ENDED`: permanent. Submissions refused. An ended contest is never restarted (it can be cloned
  into a new contest, which would be a separate archive).
* Only one contest is live on a server at a time.

### 7.2 Clock

* Time zero is `contest.startTime`.
* The deadline is `startTime + durationMinutes + pausedMillis` (pauses extend it) unless the
  contest ended explicitly, in which case it is `endedAt`. `effectiveEndTime` gives this value.
* A submission was accepted only if `startTime ≤ submittedAt < deadline` while the state was
  `ACTIVE` or `FROZEN`.
* **Elapsed time for scoring is wall-clock time since `startTime`; pause time is not subtracted.**
  A problem solved after a 10-minute pause carries those 10 minutes in its time.
* Contest "duration" shown to users = `durationMinutes` (includes extensions). Total running time
  actually observed = `effectiveEndTime − startTime − pausedMillis`.

### 7.3 Scoring and ranking (exact algorithm)

Inputs: `contest.startTime` (`T0`; if null use the Unix epoch), the contest's problems, and the
submissions with `active == true`, sorted by `submittedAt` ascending (then `id`).

```
for each submission s in order:
    skip if s.verdict is null                         # not judged yet
    skip if s.problemId is not a current problem
    if contest is FROZEN (only when exported FROZEN, or when replaying a freeze, §7.4):
        skip if s.submittedAt > frozenAt
        skip if s.judgedAt is null or s.judgedAt > frozenAt
    t = tally[s.userId][s.problemId]                  # created on first use
    t.manualDelta += s.manualPenaltyDelta             # ALWAYS, even after the problem is solved
    if t.solved: continue                             # later submissions never change a solved cell
    if s.verdict == AC:
        t.solved = true
        t.rejectsBeforeAc = t.rejects
        t.acceptedAt = s.submittedAt
        elapsed = s.submittedAt - T0
        t.solvedAtMinutes = max(0, floor(elapsed in whole minutes))
        t.solvedAtSeconds = max(0, floor(elapsed in whole seconds))
    elif s.verdict == CE:
        t.ceAttempts += 1                             # never a penalty
    else:                                             # WA, TLE, MLE, RE, OLE
        t.rejects += 1

for each user with at least one tally:
    solved = number of solved tallies
    penalty = Σ over solved tallies (solvedAtMinutes + 20 × rejectsBeforeAc)
            + Σ over ALL of the user's tallies (manualDelta)
    penalty = max(0, penalty)
    lastAc  = max solvedAtMinutes over solved tallies, or +∞ if none

sort users by:
    1. solved            descending
    2. penalty           ascending
    3. lastAc            ascending  (earlier last accepted problem wins)
    4. username          ascending  (plain code-unit string comparison, case-sensitive)
rank = position in that order, starting at 1 (no shared ranks)

firstSolve for problem P = the solved tally with the earliest acceptedAt (exact instant);
                           on an exact tie, the lower userId.
```

Use `judge.penaltyPerRejectMinutes` instead of the literal 20. Cell `failedAttempts` is
`rejectsBeforeAc` when solved, else `rejects`.

Note the penalty uses the time of **submission**, never the judging time.

### 7.4 Freeze

When the instructor freezes, `frozenAt` is recorded and everyone (contestants and spectators) sees
the standings as known at that instant: submissions sent after `frozenAt`, **and** submissions sent
before but judged after it, are left out. Judging continues; contestants still see their own
verdicts immediately. Unfreezing reveals everything and clears `frozenAt`.

If `contest.frozenAt` is non-null in an `ENDED` archive, the contest ended while still frozen —
AArchive can offer a "frozen board → final board" reveal by running §7.3 with and without the
freeze filter. Earlier freeze/unfreeze cycles are only visible in `auditEvents`.

### 7.5 Instructor-only derived values

* **Participant marks:** walk the participant's *active* submissions in submission order; the last
  non-null `marks` value wins; `null` if none. (Assigning marks to a participant writes the value on
  all their submissions.)
* **"Best" submission** (`bestSubmissionId`): among the set (all of a participant's active
  submissions, or one problem's), the `AC` with the lowest `execTimeMs` (the earliest on a tie);
  if there is no AC, the latest submission (including unjudged ones).
* **Manual penalty**: an instructor adjustment is added to the participant's latest active
  submission's `manualPenaltyDelta`; it counts towards the problem of that submission's tally but
  effectively adjusts the participant's total.

### 7.6 Judging a submission

1. Source scan: threading and process-spawning APIs are banned → `CE` with an explanation in
   `compilerOutput` (no compile happens).
2. Compile (if the language compiles). Failure → `CE`, `execTimeMs = -1`.
3. Run each test in ascending `idx`, each in a fresh sandbox (no network), with the problem's limits.
   The first applicable verdict wins, checked in this order:
   1. `TLE` — timed out or wall time > `timeLimitMs`
   2. `MLE` — peak memory > `memoryLimitKb`
   3. `OLE` — output exceeded the sandbox's output cap
   4. `RE` — non-zero exit code
   5. checker: `CUSTOM` → run the checker (§6.3); `EXACT` → compare (below) → `AC` or `WA`
4. Stop at the first non-`AC` test (`failedTestIndex` = its ordinal). All pass → `AC`.

`EXACT` comparison: normalize both expected and actual output by converting `\r\n` to `\n`,
stripping trailing whitespace from every line, and dropping trailing blank lines; then they must be
identical. Leading whitespace, inner spaces and letter case all matter.

### 7.7 Test numbering

`failedTestIndex` (submission) is an **ordinal**; `testResults[].testIndex` is a test **idx**. Map
between them with `problems[].testCases[]`. "Passed N of M tests" = `M` if AC, else
`max(0, failedTestIndex − 1)`, where `M` = number of test cases.

### 7.8 Verdict overrides

An instructor can override any submission's `verdict`. The override changes `verdict` only —
`testResults`, `failedTestIndex`, `execTimeMs` keep the machine's original judgement. Always display
`verdict`. A `Submission` audit event with `action: "AdminStandingsController.overrideVerdict"`
whose `details.before.verdict` differs from `details.after.verdict` identifies the override; AArchive
may mark such submissions as "verdict set by instructor".

### 7.9 Reconstructing the timeline

Read `auditEvents` with `targetType == "Contest"` in order; each one's `details.before`/`after`
shows the transition. Typical actions:

| `action` | Meaning |
|---|---|
| `AdminContestController.create` / `.clone` | Contest created (`before: null`). The first `after.durationMinutes` is the originally planned length. |
| `AdminContestController.open` | `DRAFT → LOBBY` |
| `AdminMonitorController.scheduleContest` | Lobby countdown set (`scheduledStartAt`). |
| `AdminContestController.start` | `→ ACTIVE`, `startTime` set. |
| `AdminContestController.pause` / `.resume` | `→ PAUSED` / `→ ACTIVE`; `pausedMillis` grows on resume. |
| `AdminContestController.extend` | `durationMinutes` changed (positive or negative). |
| `AdminContestController.freeze` / `.unfreeze` | `→ FROZEN` with `frozenAt` / `→ ACTIVE`. |
| `AdminContestController.end` | `→ ENDED` early, `endedAt` set. |
| `AdminContestController.setTestVisibility` / `.setClarificationPrivacy` | Toggles changed. |

Transitions caused by the server itself — the scheduled lobby auto-start and the clock running out —
are **not** audited. Infer them: if no `.start` event exists but `startTime` is set, it auto-started
at `startTime`; if no `.end` event exists, it ended at `endedAt` by the clock. The audit log keeps
at most 90 days / 100 000 rows server-wide, so very old exports may have an incomplete or empty
`auditEvents`; always fall back to the contest fields.

## 8. What a contestant could see during the contest

To make the archive feel like the real contest, mirror Arbitrator's visibility. In Arbitrator:

| Data | Contestant view |
|---|---|
| Problem statements | Hidden in `LOBBY`; visible from start onward. |
| Standings | Everyone's rows; frozen view while `FROZEN`. Hidden (no problem codes) in `LOBBY`. |
| Others' attempts | From a standings cell, anyone could list another participant's attempts on that problem: id, language, verdict, time, submittedAt, failed test, passed/total — **never the source or compiler output**. |
| Own submissions | Full history with source and compiler output. |
| Others' source code | Never. |
| Test data | Only if `contest.showTestCases`, only for their **own** submissions, only the tests that submission actually ran (passed ones plus the failing one), each field clipped to 4096 chars. For `CUSTOM`-checker problems contestants got only a pass/fail summary — never the expected output. |
| Clarifications | Those with `visibleToAll`, plus their own (any state). Never the asker's identity. |
| Announcements | All, at `createdAt`. |
| Materials | All, downloadable. |
| Marks, manual penalties, IPs, MACs, audit, checker source | Never. |

AArchive is free to choose a different publication policy after the contest (e.g. publish all
source code like Codeforces does), but that is a product decision; the table above is what
"as it was in the contest" means.

## 9. Security and privacy requirements for AArchive

The archive is produced by a trusted instructor, but AArchive is a public website — treat every
string and blob as untrusted.

1. **HTML:** `statement.html` and `bodyHtml` were sanitized by Arbitrator (allowlist: basic
   formatting, lists, tables, `pre`/`code`, `sup`/`sub`; no scripts, event handlers, styles, links,
   images, forms, or embeds). **Sanitize again** with your own allowlist sanitizer before storing or
   rendering, and render statements in a sandboxed iframe or with a strict CSP.
2. **Everything else is text.** Source code, outputs, compiler messages, questions, answers,
   display names, usernames, filenames, titles: insert with `textContent`/escaped templating only.
   Never build HTML or inline JavaScript from them.
3. **Files:** serve materials and PDFs with `Content-Disposition: attachment` (or `inline` only for
   PDFs, from a separate cookie-less origin), `X-Content-Type-Options: nosniff`, and do not trust
   `contentType` for anything other than a download hint. Never execute or render uploaded HTML/SVG
   from materials inline.
4. **ZIP handling:** cap total uncompressed size and entry count, verify `sha256`, reject entry
   names you didn't expect, and never write entries to disk by their names.
5. **Sensitive fields** — keep admin-only or drop on import: `submissions[].workstationIp`,
   `participants[].macAddress`, `participants[].macChangedAt`, `auditEvents[].peerIp`. Also treat
   as restricted by default: `auditEvents` as a whole, `marks`, `manualPenaltyDelta`,
   `checkerSource`, test data, and source code (owner/admin) unless your publication policy says
   otherwise.
6. **Credentials are never in the archive.** No password hashes (user or contest) are exported.
   AArchive accounts must be separate; link Arbitrator users to AArchive identities only via
   `username` and an explicit claiming process if you need one.

## 10. What the archive does not contain

* User password hashes and the contest join password (by design).
* Custom "Run" executions (contestants testing code on their own input) — Arbitrator never stores them.
* Presence history (who was online when) — kept only in memory; `liveNotifications` is a snapshot.
* Announcements and clarifications deleted by the instructor (audit events may show that something
  was deleted, but not its text).
* Problems deleted from the contest before export.
* Per-test results after the first failing test (fail-fast), full program output beyond 4096 chars,
  compiler output beyond 4096 chars.
* Exact judge environment history: `judge` reflects the configuration at export time.
* A contest-specific MAC address (the MAC is the user's latest login, server-wide).

## 11. Import algorithm

1. Open the ZIP; enforce size/entry limits; read `aarchive.json`.
2. Require `format == "arbitrator-aarchive"`; require `formatVersion` you support (§13).
3. For every `files[]` entry: read the blob, check `sizeBytes` and `sha256`. Fail the import on mismatch.
4. Show `warnings` to the uploader and require confirmation if non-empty.
5. Create an archive record (your own ID); store `contest`, `judge`, `generatedAt`.
6. Insert problems, then test cases (link blobs), then participants, then submissions with their
   `testResults`, then announcements, clarifications, materials, audit events. Keep the original
   Arbitrator IDs as columns scoped to your archive ID — submissions reference users and problems by
   those IDs, and standings reference submissions.
7. Store `standings` as the official result. Optionally recompute with §7.3 and compare as a
   self-check (they must match for an `ENDED` contest).
8. Sanitize HTML again (§9) and strip sensitive fields per your policy.
9. Re-importing the same contest: identify it by `(contest.id, contest.title, contest.startTime)`
   plus the exporting server if you track it; replace rather than duplicate.

## 12. Suggested AArchive pages

To make the contest look like it was held on AArchive:

* **Contest home** — title, start/end, duration, state badge, problem count, participant count,
  number of submissions, a "contest is over" banner with the final standings link.
* **Problems** — table `code | title | time limit | memory limit | solved by N`. Problem page shows
  the HTML statement (sandboxed) or embeds the PDF; links to materials.
* **Status (all submissions)** — Codeforces-style table: `#id | when (elapsed hh:mm) | who | problem |
  language | verdict (code + label) | time | memory`, filterable by user/problem/verdict.
* **Submission page** — source with syntax highlighting (`judge.languages[].sourceFile` gives the
  extension), verdict, compiler output, per-test table (`testResults`, mapped to tests via `idx`).
* **Standings** — ICPC table per §6.6 with first-solve highlighting; a time slider replaying §7.3 with
  submissions up to time T; a "frozen → final" reveal when §7.4 applies.
* **Clarifications** and **Announcements** — chronological, at their original timestamps relative
  to the contest start.
* **Materials** — downloads.
* **Participant page** — their row, solved problems, submissions.
* **Admin view** — marks, audit timeline (§7.9), sensitive fields, warnings.

## 13. Versioning

* `formatVersion` is an integer. It is incremented for any change an importer could notice:
  removed/renamed fields, changed meaning, new enum values, new required behavior.
* Purely additive changes (new fields, new sections) may also bump it; importers should ignore
  unknown fields regardless.
* An importer must refuse `format` values other than `arbitrator-aarchive` and should refuse
  `formatVersion` values higher than it was built for, with a clear message.
* This document always describes the current `formatVersion` and is changed in the same commit as
  the producer (`AArchiveExportService` in the Arbitrator server).

## 14. Complete sample

Taken from a real export of a small integration-test contest (two problems: A with a custom
checker and two tests, B with a PDF statement; one participant with a WA then an AC; one
announcement, one private clarification, one material; three audit events), compacted for reading.
IDs, hashes, contest and submission times are the real output. Two values were edited: only one of
the three audit events is shown, and `submitCooldownSeconds` shows the production default (10)
rather than the test profile's 0.

```
aarchive.json
problems/806/tests/1.in
problems/806/tests/1.out
problems/806/tests/2.in
problems/806/tests/2.out
problems/807/statement.pdf
materials/748/Week 1 slides.pdf
```

```json
{
  "format" : "arbitrator-aarchive",
  "formatVersion" : 1,
  "generatedAt" : "2026-10-20T13:29:08.428099840Z",
  "generator" : { "application" : "Arbitrator", "schemaVersion" : "83" },
  "contest" : {
    "id" : 1879,
    "title" : "AArchive export 44eb5930-ef96-4180-830f-92e2de68cd6c",
    "state" : "ENDED",
    "hasPassword" : true,
    "durationMinutes" : 90,
    "scheduledStartAt" : null,
    "startTime" : "2026-10-20T12:29:08Z",
    "pausedAt" : null,
    "pausedMillis" : 120000,
    "frozenAt" : null,
    "endedAt" : "2026-10-20T13:59:08Z",
    "effectiveEndTime" : "2026-10-20T13:59:08Z",
    "showTestCases" : true,
    "allowPrivateClarifications" : true
  },
  "judge" : {
    "penaltyPerRejectMinutes" : 20,
    "submitCooldownSeconds" : 10,
    "compileTimeoutMs" : 30000,
    "checkerTimeLimitMs" : 5000,
    "checkerMemoryLimitKb" : 262144,
    "maxSourceBytes" : 262144,
    "dockerImage" : "arbitrator-judge:latest",
    "compilerOutputLimitChars" : 4096,
    "storedOutputLimitChars" : 4096,
    "languages" : [
      { "id" : "CPP17", "displayName" : "C++17", "configKey" : "cpp17", "sourceFile" : "main.cpp",
        "compileCommand" : "g++ -O2 -std=c++17 -o {exe} {src}", "runCommand" : "{exe}" },
      { "id" : "JAVA17", "displayName" : "Java 17", "configKey" : "java17", "sourceFile" : "Main.java",
        "compileCommand" : "javac -d {dir} {src}", "runCommand" : "java -XX:+UseSerialGC -Xss256m -cp {dir} Main" },
      { "id" : "PYTHON310", "displayName" : "Python 3.10", "configKey" : "python310", "sourceFile" : "main.py",
        "compileCommand" : "", "runCommand" : "python3 {src}" }
    ],
    "verdicts" : [
      { "code" : "AC", "label" : "Accepted", "countsAsRejectedAttempt" : false },
      { "code" : "WA", "label" : "Wrong Answer", "countsAsRejectedAttempt" : true },
      { "code" : "TLE", "label" : "Time Limit Exceeded", "countsAsRejectedAttempt" : true },
      { "code" : "MLE", "label" : "Memory Limit Exceeded", "countsAsRejectedAttempt" : true },
      { "code" : "CE", "label" : "Compilation Error", "countsAsRejectedAttempt" : false },
      { "code" : "RE", "label" : "Runtime Error", "countsAsRejectedAttempt" : true },
      { "code" : "OLE", "label" : "Output Limit Exceeded", "countsAsRejectedAttempt" : true }
    ]
  },
  "problems" : [ {
    "id" : 806, "code" : "A", "title" : "Sum", "ordering" : 0,
    "timeLimitMs" : 2000, "memoryLimitKb" : 262144,
    "checkerType" : "CUSTOM", "checkerSource" : "int main(){return 0;}",
    "statement" : { "format" : "HTML", "html" : "<p>Add <b>two</b> numbers</p>", "pdf" : null },
    "testCases" : [ {
      "id" : 88, "idx" : 1, "ordinal" : 1,
      "input" : { "path" : "problems/806/tests/1.in", "sizeBytes" : 4,
                  "sha256" : "f251ddc12234e0da8d3b778bd0f7463fb477f16f47757f5617dc8b4ff4d4f14a" },
      "expectedOutput" : { "path" : "problems/806/tests/1.out", "sizeBytes" : 2,
                  "sha256" : "1121cfccd5913f0a63fec40a6ffd44ea64f9dc135c66634ba001d10bcf4302a2" }
    }, {
      "id" : 89, "idx" : 2, "ordinal" : 2,
      "input" : { "path" : "problems/806/tests/2.in", "sizeBytes" : 16,
                  "sha256" : "386a49ef1354bbbbb2d59ce729fac13cb5456029773d6571d159867b4f2b2b1f" },
      "expectedOutput" : { "path" : "problems/806/tests/2.out", "sizeBytes" : 3,
                  "sha256" : "917df3320d778ddbaa5c5c7742bc4046bf803c36ed2b050f30844ed206783469" }
    } ]
  }, {
    "id" : 807, "code" : "B", "title" : "Paper", "ordering" : 1,
    "timeLimitMs" : 2000, "memoryLimitKb" : 262144,
    "checkerType" : "EXACT", "checkerSource" : null,
    "statement" : { "format" : "PDF", "html" : "<p>See PDF</p>",
                    "pdf" : { "path" : "problems/807/statement.pdf", "sizeBytes" : 23,
                              "sha256" : "0c38813ef90236056f656c19a41607db54d38ba86944f306593aa90622fe0b11" } },
    "testCases" : [ ]
  } ],
  "participants" : [ {
    "userId" : 2149, "username" : "aa-student-2c91ad49", "displayName" : "Disposable STUDENT",
    "role" : "STUDENT", "accountCreatedAt" : "2026-10-20T13:29:07Z", "accessGrantedAt" : "2026-10-20T13:29:07Z",
    "marks" : 87, "macAddress" : null, "macChangedAt" : null
  } ],
  "submissions" : [ {
    "id" : 415, "userId" : 2149, "username" : "aa-student-2c91ad49",
    "problemId" : 806, "problemCode" : "A", "language" : "CPP17", "sourceCode" : "int main(){}",
    "status" : "DONE", "verdict" : "WA", "execTimeMs" : 12, "peakMemoryKb" : 3000,
    "compilerOutput" : null, "failedTestIndex" : 2, "marks" : null, "manualPenaltyDelta" : 0,
    "workstationIp" : "127.0.0.1",
    "submittedAt" : "2026-10-20T12:39:07.946055Z", "judgedAt" : "2026-10-20T12:39:10Z",
    "elapsedMillisFromStart" : 599946, "active" : true,
    "testResults" : [
      { "id" : 734, "testIndex" : 1, "verdict" : "AC", "execTimeMs" : 12, "peakMemoryKb" : 3000, "actualOutput" : "3\n" },
      { "id" : 735, "testIndex" : 2, "verdict" : "WA", "execTimeMs" : 11, "peakMemoryKb" : 2900, "actualOutput" : "9\n" }
    ]
  }, {
    "id" : 416, "userId" : 2149, "username" : "aa-student-2c91ad49",
    "problemId" : 806, "problemCode" : "A", "language" : "CPP17", "sourceCode" : "int main(){}",
    "status" : "DONE", "verdict" : "AC", "execTimeMs" : 12, "peakMemoryKb" : 3000,
    "compilerOutput" : null, "failedTestIndex" : -1, "marks" : 87, "manualPenaltyDelta" : -5,
    "workstationIp" : "127.0.0.1",
    "submittedAt" : "2026-10-20T12:59:07.946055Z", "judgedAt" : "2026-10-20T12:59:10Z",
    "elapsedMillisFromStart" : 1799946, "active" : true,
    "testResults" : [ ]
  } ],
  "standings" : {
    "frozen" : false,
    "computedAt" : "2026-10-20T13:29:08.607Z",
    "problemCodes" : [ "A", "B" ],
    "rows" : [ {
      "rank" : 1, "userId" : 2149, "username" : "aa-student-2c91ad49", "displayName" : "Disposable STUDENT",
      "solved" : 1, "penaltyMinutes" : 44, "marks" : 87, "bestSubmissionId" : 416,
      "cells" : [
        { "problemId" : 806, "problemCode" : "A", "solved" : true, "failedAttempts" : 1,
          "solvedAtMinutes" : 29, "solvedAtSeconds" : 1799, "firstSolve" : true, "ceAttempts" : 0,
          "firstAcceptedSubmissionId" : 416, "bestSubmissionId" : 416 },
        { "problemId" : 807, "problemCode" : "B", "solved" : false, "failedAttempts" : 0,
          "solvedAtMinutes" : -1, "solvedAtSeconds" : -1, "firstSolve" : false, "ceAttempts" : 0,
          "firstAcceptedSubmissionId" : null, "bestSubmissionId" : null }
      ]
    } ]
  },
  "announcements" : [ { "id" : 7, "bodyHtml" : "<p>Clarified limits</p>", "createdAt" : "2026-10-20T13:29:08Z" } ],
  "clarifications" : [ {
    "id" : 162, "problemId" : 806, "problemCode" : "A", "userId" : 2149, "username" : "aa-student-2c91ad49",
    "question" : "Can numbers be negative?", "answer" : "No.",
    "askedAt" : "2026-10-20T13:29:08Z", "answeredAt" : "2026-10-20T13:29:08Z",
    "publicRequested" : false, "approved" : false, "visibleToAll" : false
  } ],
  "materials" : [ {
    "id" : 748, "filename" : "Week 1 slides.pdf", "contentType" : "application/pdf", "sizeBytes" : 11,
    "uploadedAt" : "2026-10-20T13:29:08Z",
    "file" : { "path" : "materials/748/Week 1 slides.pdf", "sizeBytes" : 11,
               "sha256" : "5f50090e6b35236c23cdd21929efe11d09e5b8fce28fd80980dc0dc064d4c922" }
  } ],
  "auditEvents" : [ {
    "id" : 10391, "occurredAt" : "2026-10-20T13:29:08.157363Z", "actor" : "aarchive-test", "action" : "test",
    "targetType" : "Contest", "targetId" : "1879", "peerIp" : null, "outcome" : "COMMITTED_CHANGE",
    "httpStatus" : null,
    "details" : { "before" : { "state" : "ACTIVE" }, "after" : { "state" : "PAUSED" } }
  } ],
  "liveNotifications" : [ ],
  "files" : [
    { "path" : "problems/806/tests/1.in", "kind" : "TEST_INPUT", "sizeBytes" : 4,
      "sha256" : "f251ddc12234e0da8d3b778bd0f7463fb477f16f47757f5617dc8b4ff4d4f14a" },
    { "path" : "problems/806/tests/1.out", "kind" : "TEST_EXPECTED_OUTPUT", "sizeBytes" : 2,
      "sha256" : "1121cfccd5913f0a63fec40a6ffd44ea64f9dc135c66634ba001d10bcf4302a2" },
    { "path" : "problems/806/tests/2.in", "kind" : "TEST_INPUT", "sizeBytes" : 16,
      "sha256" : "386a49ef1354bbbbb2d59ce729fac13cb5456029773d6571d159867b4f2b2b1f" },
    { "path" : "problems/806/tests/2.out", "kind" : "TEST_EXPECTED_OUTPUT", "sizeBytes" : 3,
      "sha256" : "917df3320d778ddbaa5c5c7742bc4046bf803c36ed2b050f30844ed206783469" },
    { "path" : "problems/807/statement.pdf", "kind" : "STATEMENT_PDF", "sizeBytes" : 23,
      "sha256" : "0c38813ef90236056f656c19a41607db54d38ba86944f306593aa90622fe0b11" },
    { "path" : "materials/748/Week 1 slides.pdf", "kind" : "MATERIAL", "sizeBytes" : 11,
      "sha256" : "5f50090e6b35236c23cdd21929efe11d09e5b8fce28fd80980dc0dc064d4c922" }
  ],
  "warnings" : [ ]
}
```

Worked check of the sample against §7.3: the WA at elapsed 9:59 is a rejected attempt; the AC at
elapsed 29:59.946 gives `solvedAtMinutes = 29`; penalty = 29 + 20 × 1 + (−5 manual) = **44**.
