-- V51__add_manual_penalty_and_scheduled_start.sql — owner: Mahir
-- Support for manual penalty adjustments on submissions.
ALTER TABLE submissions ADD COLUMN manual_penalty_delta INT NOT NULL DEFAULT 0;
