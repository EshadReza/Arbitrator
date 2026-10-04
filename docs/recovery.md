# Incident recovery — current single-host deployment

This is an operator guide for the existing Ubuntu/MySQL/Docker deployment. It does **not** certify disaster recovery. Secure database-plus-material backups and clean restore tests are deferred (security checklist items 81–82). If the database or material files are lost, this guide cannot recreate them.

Paths and commands below are relative to the repository root unless stated otherwise. Normal build and launch commands remain in [README.md](../README.md); this guide adds no startup dependency or mandatory step. Use the actual service/IDE launcher and configured paths on the affected host. The default port is 8080, the default judge work root is the Java temporary directory's `arbitrator` folder, and the default material root is `./arbitrator-data/materials` relative to the server process's working directory.

## On this page

- [First response](#first-response-for-any-incident)
- [MySQL or missing data](#mysql-unavailable-or-data-missing)
- [Docker or judge failure](#docker-or-judge-host-unavailable)
- [Disk space](#disk-full-or-nearly-full)
- [Failed deployment](#failed-application-deployment)
- [Recovery limits](#what-remains-unproven)

## First response for any incident

1. Record the incident start time, host, affected contest and submission IDs, symptoms, and the running application/JAR version. Keep logs and diagnostics in a restricted location; do not post tokens, passwords, private source, test data or database dumps to a public issue.
2. If submissions are receiving infrastructure errors, the database is unavailable, or contest fairness is uncertain, coordinate with the instructor and stop the server through its **existing** launcher/service while investigating. There is no maintenance-mode switch. Do not start a second server against the same live database.
3. Perform read-only checks on the server host. Adapt service names if this installation does not use systemd:

   ```bash
   date -u
   df -h
   df -i
   systemctl status mysql docker --no-pager
   docker info --format '{{.ServerVersion}}'
   docker image inspect arbitrator-judge:latest --format '{{.Id}}'
   curl --max-time 8 -i http://127.0.0.1:8080/admin/health/live
   curl --max-time 8 -i http://127.0.0.1:8080/admin/health/ready
   ```

   Run these from the server host; remote clients cannot access them. `/live` returning 200 proves only that the web process answers. `/ready` returns 200 only when the database, queue acceptance and Docker judge checks pass, or 503 with coarse `database`, `queue` and `judge` states when not ready. It does not execute a submission or prove an individual worker will finish a job, and neither route can alert anyone if the process is stopped. `docker` commands can fail if the daemon is down or the image is missing—that failure is diagnostic.

### Protect the existing data

Do **not** run `scripts/reset-dev-data.sql` on a contest host: it deletes submissions. Do not use `scripts/init-db.sql` as an outage repair command: it changes the application's MySQL account password to the script's example value. Do not delete the MySQL data directory, material root, unknown Docker resources or backups to make space. Preserve the existing database and material files before any reinstall, replacement or rollback. There is no supported Redis service or TLS certificate in the current deployment.

## MySQL unavailable or data missing

### Identify the cause

Distinguish a stopped/unreachable MySQL service, full disk, bad credentials and actual data loss. Check MySQL's own status/logs and the application's startup errors without printing credentials. Compare the configured database address and account with the known deployment configuration. Fix the underlying host/service issue before restarting **one** server instance.

### Recover an intact database

If the original database is intact, start MySQL through the host's normal service management and restart the application through its normal launcher. Flyway migrations run at startup; review any migration error before retrying or changing versions. Verify with an authorized admin login, existing contest/problem/submission history and a known material download—not just the HTTP page.

### Stop if data is missing

If the database is empty or lost, **stop**. The JDBC URL can create a missing database, and the first-boot seeder creates a demonstration admin/student/contest when the users table is empty. A successful page load or demo login would not mean the real contest was recovered. There is no approved, tested backup restore in this project; obtain the operator's verified external backup and an item-82 clean restore procedure before resuming. Material bytes require a matching copy as well.

### Review contest timing

After any outage, inspect the contest clock and state with the instructor. With the default `reset-on-boot=false`, a live contest normally retains its state, wall-clock time advances while the server is offline, and an expired contest may become `ENDED` on restart. Decide and record any fairness adjustment through supported admin controls; do not silently rewrite database timestamps.

## Docker or judge host unavailable

### Check Docker and the image

Check daemon status and whether `arbitrator-judge:latest` exists. The existing image build command is `bash scripts/docker/build-sandbox-image.sh` if the local image is genuinely missing; do not rebuild an image merely to hide another daemon/permission error.

### Restart after fixing the cause

When Docker or the image is healthy again, restart the server through the normal launcher so startup verification and image-ID pinning run again. Startup also removes stale `arbitrator-sbx-` containers and judge work files. Do not manually remove unrecognized containers, images or volumes.

### Review affected submissions

Submissions are saved before entering the in-memory queue. On restart, non-`DONE` submissions are requeued. An infrastructure failure that was already recorded as `DONE` with judge `RE` is **not** automatically rejudged; this project has no rejudge feature. Preserve affected submission IDs and have the instructor decide and document the contest remedy. Do not tell students a restart will fix completed verdicts.

### Verify the judge

Confirm the startup log reports Docker ready, then use an authorized, non-scored test workflow before resuming a live contest. A responsive HTTP page alone does not verify the judge.

## Disk full or nearly full

### Identify the full filesystem

Identify which filesystem and whether blocks or inodes are exhausted (`df -h`, `df -i`). Check the MySQL data volume, configured material root, Docker storage and judge work-root filesystem separately. `SandboxExecutor` refuses a new sandbox run when its work filesystem lacks reserve space, but that check does **not** protect every database, material or log write.

### Free only disposable data

Stop the server if writes are failing. Have the host operator free space only from **identified disposable** data according to the host's retention policy; never use a blanket prune/delete command against database, materials, backups or unfamiliar Docker volumes. If MySQL reported write errors, verify its health and preserved data before accepting new submissions.

### Restart and verify

Once capacity and services are stable, restart once and apply the same database, contest, unfinished-submission and judge checks described above. Preserve any completed infrastructure-error submission IDs for instructor review.

## Failed application deployment

### Preserve evidence

Stop the broken process, retain its logs, exact application artifact, configuration and current database state, and identify whether Flyway already applied a schema migration.

### Check schema compatibility

Do not assume an older JAR is safe against a migrated schema. The project has no tested automatic rollback or database restore. Assess version/schema compatibility in an isolated environment before changing a contest host. Do not run setup/reset SQL to force an old version to start.

### Verify the corrected deployment

After a corrected deployment, verify authorized login, existing contest/problem/submission history, material downloads, Docker readiness, a non-scored judge check, and the live contest clock before reopening submissions. Record any downtime adjustment and affected verdicts.

## What remains unproven

These instructions were checked against the current source, not exercised as a full disaster drill. A database-host loss, material-storage loss, cross-machine move, secure backup/restore, bad migration rollback and multi-client contest restart have not been rehearsed. Items 81–82 must be completed before claiming data-loss recovery. Item 84 added local monitoring, item 85 security alerts remain deferred, and item 86 added local health probes; none is an independent stopped-server monitor.
