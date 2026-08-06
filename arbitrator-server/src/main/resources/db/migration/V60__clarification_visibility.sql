-- V60__clarification_visibility.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- Lets the asker choose whether a clarification joins the public board or stays
-- between them and the instructor. Default TRUE (public): that was the only
-- behaviour before this migration, so every existing row keeps meaning exactly
-- what it already meant.
ALTER TABLE clarifications
    ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT TRUE;
