-- V61__clarification_privacy_toggle.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- Per-contest kill switch for the private-clarification option added in V60.
-- Defaults to TRUE: every contest that already exists keeps the behaviour it
-- shipped with (askers could always choose private) until an instructor
-- deliberately turns it off for a specific contest.
ALTER TABLE contests
    ADD COLUMN allow_private_clarifications BOOLEAN NOT NULL DEFAULT TRUE;
