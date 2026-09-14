# rules.md — how we push and pull without merge conflicts

**Team:** Eshad (Platform & Data) · Mahir (Judge & Real-time) · Zahin (JavaFX Client)

Read this before your first commit. Every rule here exists because breaking it causes a specific, predictable conflict. There are only nine of them.

---

## Rule 1 — You only edit files you own

| Owner | Paths |
|---|---|
| **Eshad** | `arbitrator-server/src/main/java/com/arbitrator/server/{config,security,entity,repo}/**`<br>`.../service/{user,contest,problem,report}/**` · `.../controller/{auth,admin,contest,problem,user}/**`<br>`arbitrator-server/src/main/resources/db/migration/**` · `.../static/admin/**`<br>root `pom.xml` · `README.md` · `.github/**` |
| **Mahir** | `arbitrator-server/src/main/java/com/arbitrator/server/{judge,realtime,leaderboard}/**`<br>`.../service/{submission,announcement}/**` · `.../controller/{submission,announcement}/**`<br>`arbitrator-server/src/main/resources/languages.yml` · `scripts/docker/**` |
| **Zahin** | `arbitrator-client/**` (everything, including `codeforces.css`) |
| **Shared** | `arbitrator-common/**` — see Rule 2 |

**If you need a change in someone else's file, you do not edit it.** Open a GitHub issue titled `[contract-change] <what you need>`, assign the owner, and keep working on something else. The owner lands it, usually same day.

Git cannot produce a conflict in a file only one person edits. This rule alone removes ~90% of them.

---

### After anyone changes `arbitrator-common`, everyone runs Alt+F5

Adding a DTO or constant is allowed without ceremony (see below) — but on **every other machine**, Eclipse's
build path goes stale the moment it happens. Eclipse then compiles error-stub `.class` files over the top of
Maven's good output, and the app fails at runtime with an unqualified `ClassNotFoundException` even though
`mvn clean install` reported success.

So: when a PR touching `arbitrator-common` lands on `dev`, after you pull —
**select all four projects → Maven → Update Project (Alt+F5) → Project → Clean all → `mvn clean install`.**
Ten seconds, and it saves an hour of debugging a build that "should" work.

---

## Rule 2 — `arbitrator-common` is frozen after day 3

We design it together on day 2 and push it on day 3. After that:

- Changing an existing DTO field, enum constant, or path constant → **issue tagged `contract-change`, all three must 👍 it**, then Eshad lands it in a single commit named `contract: <what changed>`.
- **Adding** a brand-new DTO or constant is allowed without ceremony — additions can't break anyone.
- Never rename anything in `common` on a feature branch. That breaks both other people's compile on their next pull.

---

## Rule 3 — Never commit IDE or build files

`.gitignore` must contain exactly this before the first commit:

```gitignore
target/
*.class
.classpath
.project
.settings/
.metadata/
bin/
*.iml
.idea/
application-local.yml
.env
*.log
/uploads/
/submissions/
.DS_Store
```

`.classpath` and `.settings/` change every time Eclipse touches the project, differ per machine, and conflict on **every single pull**. If someone already committed them:

```bash
git rm -r --cached .classpath .project .settings bin target
git commit -m "chore: stop tracking Eclipse metadata"
```

---

## Rule 4 — Format on save, with the shared formatter

`Window → Preferences → Java → Code Style → Formatter → Import…` → `config/eclipse-formatter.xml`
`Window → Preferences → Java → Editor → Save Actions` → tick **Format source code** and **Organize imports** (import order: `config/eclipse.importorder`).

Without this, one person's editor reindents a file and produces a 200-line diff that conflicts with a real 3-line change. Whitespace noise is the second-biggest source of false conflicts after Rule 3.

---

## Rule 5 — Flyway migration numbers are pre-assigned

| Who | Range |
|---|---|
| Eshad | `V1__` … `V49__` |
| Mahir | `V50__` … `V79__` |
| Hotfix / integration | `V80__` … `V99__` |

Two people creating `V3__add_column.sql` on the same day is a conflict Git *cannot* auto-merge and Flyway refuses to run. Stay in your range.

**Never edit a migration that has already been pushed.** It's already applied on two other machines; Flyway checksums will fail for them. Write a new one.

---

## Rule 6 — Your own MySQL, your own credentials

Everyone runs a local `mysql-server-8.0`. Commit `application.yml` with placeholders only:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/arbitrator?serverTimezone=UTC
    username: ${DB_USER:arbitrator}
    password: ${DB_PASS:changeme}
```

Real values go in `application-local.yml` (git-ignored) or environment variables. Nobody's password ever reaches the repo, and nobody's local config ever conflicts.

---

## Rule 7 — Branch names and the merge flow

```
main                       always demoable, protected, tagged at each checkpoint
 └─ dev                    integration branch
     ├─ feat/eshad-S1-A4-auth-jwt
     ├─ feat/mahir-S2-B4-stomp-verdicts
     └─ feat/zahin-S2-C3-code-editor
```

Branch name = `feat/<yourname>-<chunk-id>-<3-word-slug>`. The chunk ID comes from `WORKFLOW_PLAN.md` §6, so anyone can see what a branch is for without asking.

**Start work:**
```bash
git checkout dev && git pull origin dev
git checkout -b feat/zahin-S2-C3-code-editor
```

**Finish work:**
```bash
git checkout dev && git pull origin dev
git checkout feat/zahin-S2-C3-code-editor
git merge dev          # resolve here, on YOUR branch, alone — never on dev
mvn clean install      # must pass before you open the PR
git push -u origin feat/zahin-S2-C3-code-editor
```
Then open a PR into `dev`. **One other person approves.** Because ownership is disjoint, reviews take five minutes.

Never `git push --force` to `main` or `dev`. On your own feature branch it's fine.

---

## Rule 8 — Sync twice a week, no exceptions

**Every Wednesday and Saturday, before you stop working:**

```bash
git checkout dev && git pull origin dev
git checkout <your-branch> && git merge dev
```

A branch that has drifted for six days conflicts. A branch that merges `dev` every three days almost never does. This is the difference between a 2-minute merge and a lost evening.

Corollary: **keep branches small.** One chunk = one branch = one PR, ideally under three days of work. If a chunk is taking a week, split it.

---

## Rule 9 — If a conflict happens anyway

It will, once or twice. Don't panic and don't `git checkout --theirs` blindly.

```bash
git status                  # see exactly which files conflict
git diff --name-only --diff-filter=U
```

Then, by file type:

- **`arbitrator-common`** → stop. Message the group chat. Whoever changed the contract without an issue reverts, and we redo it under Rule 2.
- **A file you own** → your version is authoritative. Fix it, `git add`, `git commit`.
- **A file you don't own** → you violated Rule 1. `git checkout --theirs <file>` to take the owner's version, then open the `contract-change` issue you should have opened.
- **`pom.xml`** → almost always both people added a dependency. Keep both blocks, save, run `mvn clean install`.
- **A migration file** → you violated Rule 5. Rename yours into your own range and re-run.

**Escape hatch:** `git merge --abort` puts you back exactly where you were. Nothing is ever lost. Use it, ask in the group chat, then retry.

---

## Daily rhythm

- **Standup 3×/week, 15 minutes:** what I merged · what I'm blocked on · any `contract-change` requests.
- **Tag at every checkpoint** so there's always a working demo to fall back to:
  `v0.1-I1` (week 2) · `v0.2-I2` (week 4) · `v0.3-I3` (week 6) · `v1.0` (week 8).
- **`dev` must always compile.** If your merge breaks it, you fix it or revert it — that hour, not tomorrow.

---

## First-day checklist

- [ ] Repo created on GitHub, `main` and `dev` branches exist, `main` protected
- [ ] `.gitignore` from Rule 3 committed **before any Eclipse import**
- [ ] `config/eclipse-formatter.xml` + `eclipse.importorder` committed, all three imported them
- [ ] The Maven parent and all three modules import cleanly; `mvn clean install` green on all three machines
- [ ] Local MySQL 8 running for each dev, `application-local.yml` created, not tracked
- [ ] `arbitrator-common` designed together (day 2) and pushed (day 3)
- [ ] Everyone has read this file
