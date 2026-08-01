-- One-time local setup, per developer (rules.md Rule 6):
--   sudo mysql < scripts/init-db.sql
-- Then put YOUR chosen password in application-local.yml (git-ignored).
CREATE DATABASE IF NOT EXISTS labjudge
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'labjudge'@'localhost' IDENTIFIED BY 'changeme';
GRANT ALL PRIVILEGES ON labjudge.* TO 'labjudge'@'localhost';
FLUSH PRIVILEGES;
