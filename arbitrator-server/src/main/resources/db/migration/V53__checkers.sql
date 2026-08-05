-- V53__checkers.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- FR-14: Custom checker support per problem.
ALTER TABLE problems
    ADD COLUMN checker_type   VARCHAR(16) NOT NULL DEFAULT 'EXACT',
    ADD COLUMN checker_source MEDIUMTEXT  NULL;
