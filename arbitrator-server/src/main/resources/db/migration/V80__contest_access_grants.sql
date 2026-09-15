-- A contest password is an authorization boundary, not a client-side prompt.
-- Once a student enters correctly, this table records that admission so every
-- later REST request and STOMP subscription can enforce it without receiving
-- or retaining the plaintext password again.
CREATE TABLE contest_access_grants (
    contest_id BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (contest_id, user_id),
    KEY idx_contest_access_user (user_id, contest_id),
    CONSTRAINT fk_contest_access_contest
        FOREIGN KEY (contest_id) REFERENCES contests (id) ON DELETE CASCADE,
    CONSTRAINT fk_contest_access_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Existing submissions prove that the student participated in that contest.
-- Preserve their access to protected historical contest data after migration.
INSERT IGNORE INTO contest_access_grants (contest_id, user_id, granted_at)
SELECT contest_id, user_id, MIN(queued_at)
  FROM submissions
 GROUP BY contest_id, user_id;
