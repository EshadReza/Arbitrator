-- V55__contest_ended_at.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- When an instructor ends a contest early, the deadline is *now*, not the time
-- the schedule originally said. Without this the end instant stayed at
-- start + duration, so every clock derived from it kept counting down after the
-- contest was over. Nullable: only a contest that has actually ended has one,
-- and start() clears it so a restart gets a full run again.
ALTER TABLE contests
    ADD COLUMN ended_at DATETIME NULL;
