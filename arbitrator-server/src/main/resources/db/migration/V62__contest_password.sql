-- V62__contest_password.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- Optional per-contest join password. NULL means "no password required" —
-- every existing contest keeps working exactly as it did before this
-- migration. Stores a bcrypt hash only, never the raw password (NFR-S06,
-- same PasswordEncoder bean users already use).
ALTER TABLE contests
    ADD COLUMN password_hash VARCHAR(255) NULL;
