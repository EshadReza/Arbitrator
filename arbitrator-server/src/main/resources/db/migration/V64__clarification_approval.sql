-- V64__clarification_approval.sql — owner: Mahir (rules.md Rule 5, V50-V79 range)
-- A public clarification's answer no longer goes live the instant it's
-- saved — an admin must explicitly approve it first (see
-- ClarificationService.setApproved). Grandfather in what's already answered
-- and public today, so deploying this doesn't retroactively hide answers
-- students can already see.
ALTER TABLE clarifications ADD COLUMN approved BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE clarifications SET approved = TRUE WHERE is_public = TRUE AND answer IS NOT NULL;
