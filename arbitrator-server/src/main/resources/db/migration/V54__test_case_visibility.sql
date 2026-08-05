-- V54__test_case_visibility.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- Lets the instructor decide, per contest, whether contestants may see the test
-- data behind their own verdicts (the tests they passed, plus the one that
-- failed them). Default FALSE: revealing tests changes what a contest measures,
-- so it has to be switched on deliberately, never inherited by an old contest.
ALTER TABLE contests
    ADD COLUMN show_test_cases BOOLEAN NOT NULL DEFAULT FALSE;
