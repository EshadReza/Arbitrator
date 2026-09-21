-- Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
-- All rights reserved.
-- DEVELOPMENT ONLY. Wipes all submissions so you can test from a clean slate.
--
--   mysql -u root arbitrator < scripts/reset-dev-data.sql
--
-- This deliberately violates DBR-04 (submissions are never deleted). That rule
-- protects a real contest's academic-integrity record; it does not apply to a
-- developer's scratch database. NEVER run this against a machine that has held
-- a real contest.
--
-- Problems and users are kept. To also drop uploaded problems, uncomment the
-- problems/test_cases lines below (the demo seeder only re-creates problem A
-- when the users table is empty, so uploaded packages would need re-uploading).

SET FOREIGN_KEY_CHECKS = 0;

DELETE FROM submission_results;
DELETE FROM submissions;
ALTER TABLE submissions AUTO_INCREMENT = 1;
ALTER TABLE submission_results AUTO_INCREMENT = 1;

-- Uncomment to also remove every problem and its tests:
-- DELETE FROM test_cases;
-- DELETE FROM problems;
-- ALTER TABLE problems AUTO_INCREMENT = 1;
-- ALTER TABLE test_cases AUTO_INCREMENT = 1;

-- Restart the contest clock so the window is open again (BR-02 rejects
-- submissions outside it, which looks like a broken Submit button).
--
-- UTC_TIMESTAMP(), *never* NOW(): start_time is a DATETIME (no zone) and the
-- JDBC url sets serverTimezone=UTC, so Java reads whatever is stored as UTC.
-- NOW() writes local time, which on a +06 machine puts the contest six hours
-- in the future and makes every submission fail with 403.
-- Only the contest that actually holds problems is reopened, and every other
-- contest is closed. Activating them all (an UPDATE with no WHERE) leaves two
-- ACTIVE contests, "current contest" resolves to the empty newer one, and
-- every submission fails 403 — which is exactly what happened once already.
UPDATE contests SET state = 'ENDED';

UPDATE contests
   SET state = 'ACTIVE',
       start_time = UTC_TIMESTAMP(),
       duration_minutes = 180
 WHERE id = (SELECT contest_id FROM (
                 SELECT contest_id FROM problems
                 GROUP BY contest_id ORDER BY COUNT(*) DESC, contest_id ASC LIMIT 1
             ) AS pick);

SET FOREIGN_KEY_CHECKS = 1;

SELECT 'submissions cleared, contest window reopened' AS status;
