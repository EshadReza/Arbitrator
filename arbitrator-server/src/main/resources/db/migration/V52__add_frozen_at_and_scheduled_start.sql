-- V52__add_frozen_at_and_scheduled_start.sql — owner: Mahir
-- Support for leaderboard freeze timestamp and lobby scheduled start countdown.
ALTER TABLE contests
    ADD COLUMN frozen_at DATETIME NULL,
    ADD COLUMN scheduled_start_at DATETIME NULL;
