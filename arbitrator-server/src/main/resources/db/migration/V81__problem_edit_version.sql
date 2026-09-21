-- Detect stale concurrent problem writes instead of silently losing edits.
ALTER TABLE problems ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
