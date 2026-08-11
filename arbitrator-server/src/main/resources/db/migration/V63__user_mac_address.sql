-- V63__user_mac_address.sql — owner: Mahir (rules.md Rule 5, V50-V79 range)
-- Tracks the MAC address a user's client last reported at login, so the
-- instructor can spot a student account signing in from an unexpected
-- machine (Participants view + admin notification on change).
ALTER TABLE users
    ADD COLUMN mac_address VARCHAR(17) NULL,
    ADD COLUMN mac_changed_at DATETIME NULL;
