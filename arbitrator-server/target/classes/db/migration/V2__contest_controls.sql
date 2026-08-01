-- V2__contest_controls.sql — owner: Eshad (range V1-V49, rules.md Rule 5)
-- Pause/resume and time extension for a running contest (FR-06, FR-08).
--
-- Pausing must genuinely stop the clock, so the deadline has to move forward by
-- however long the contest was paused. paused_millis accumulates completed
-- pauses; paused_at marks a pause currently in progress.

ALTER TABLE contests
    ADD COLUMN paused_at     DATETIME NULL AFTER start_time,
    ADD COLUMN paused_millis BIGINT   NOT NULL DEFAULT 0 AFTER paused_at;
