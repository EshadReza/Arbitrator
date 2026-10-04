-- Independent of operational rows: history survives target/account deletion.
CREATE TABLE audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    actor VARCHAR(64) NULL,
    action VARCHAR(120) NOT NULL,
    target_type VARCHAR(64) NULL,
    target_id VARCHAR(64) NULL,
    peer_ip VARCHAR(45) NULL,
    outcome VARCHAR(32) NOT NULL,
    http_status INT NULL,
    details VARCHAR(4096) NOT NULL,
    KEY idx_audit_time (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
