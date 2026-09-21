-- Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
-- All rights reserved.
-- One-time local setup, per developer (rules.md Rule 6):
--   sudo mysql < scripts/init-db.sql
-- Then put YOUR chosen password in application-local.yml (git-ignored).
CREATE DATABASE IF NOT EXISTS arbitrator
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- CREATE USER IF NOT EXISTS alone is not enough to keep this idempotent:
-- IF NOT EXISTS means the IDENTIFIED BY clause is silently skipped when the
-- user already exists, so re-running this file (e.g. a rebuilt deployment
-- package with a freshly generated password) leaves MySQL holding whatever
-- password was set the FIRST time, while the app's own config now expects
-- the new one -- "Access denied for user 'arbitrator'@'localhost' (using
-- password: YES)" even though the password being sent is the one right
-- here in this file. ALTER USER, unconditionally, always resyncs it.
CREATE USER IF NOT EXISTS 'arbitrator'@'localhost';
ALTER USER 'arbitrator'@'localhost' IDENTIFIED BY 'changeme';
GRANT ALL PRIVILEGES ON arbitrator.* TO 'arbitrator'@'localhost';
FLUSH PRIVILEGES;
