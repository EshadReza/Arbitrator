-- V1__baseline.sql — owner: Eshad (range V1-V49, rules.md Rule 5)
-- MySQL 8, InnoDB, utf8mb4 (I18N-01). FKs enforced at DB level (DBR-07).
-- NEVER edit this file after it has been pushed; write a new migration.

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,             -- bcrypt cost 12 (NFR-S06)
    role          VARCHAR(16)  NOT NULL,             -- STUDENT | ADMIN (FR-03)
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE contests (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    title            VARCHAR(200) NOT NULL,
    state            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',  -- FR-08
    start_time       DATETIME     NULL,
    duration_minutes INT          NOT NULL DEFAULT 120,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contests_title (title)                     -- UC-08 exception
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE problems (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    contest_id      BIGINT       NOT NULL,
    code            VARCHAR(8)   NOT NULL,           -- "A", "B", ...
    title           VARCHAR(200) NOT NULL,
    statement_html  MEDIUMTEXT   NOT NULL,           -- UTF-8 HTML (I18N-01)
    time_limit_ms   INT          NOT NULL DEFAULT 2000,
    memory_limit_kb INT          NOT NULL DEFAULT 262144,
    ordering        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_problems_contest_code (contest_id, code),
    CONSTRAINT fk_problems_contest
        FOREIGN KEY (contest_id) REFERENCES contests (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Bundle 1 stores test data inline; the ZIP-package upload chunk (S2-A2)
-- adds file-based storage in a later migration without touching this table.
CREATE TABLE test_cases (
    id              BIGINT     NOT NULL AUTO_INCREMENT,
    problem_id      BIGINT     NOT NULL,
    idx             INT        NOT NULL,             -- evaluated ascending (BR-05)
    input_data      MEDIUMTEXT NOT NULL,
    expected_output MEDIUMTEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_test_cases_problem_idx (problem_id, idx),
    CONSTRAINT fk_test_cases_problem
        FOREIGN KEY (problem_id) REFERENCES problems (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Persisted BEFORE queueing so a server crash loses nothing (FMEA-01).
-- Soft-delete only, never DELETE (DBR-04).
CREATE TABLE submissions (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    user_id           BIGINT      NOT NULL,
    problem_id        BIGINT      NOT NULL,
    contest_id        BIGINT      NOT NULL,
    language          VARCHAR(16) NOT NULL,
    source_code       MEDIUMTEXT  NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING|JUDGING|DONE
    verdict           VARCHAR(8)  NULL,             -- AC|WA|TLE|MLE|CE|RE
    exec_time_ms      BIGINT      NOT NULL DEFAULT -1,
    peak_memory_kb    BIGINT      NOT NULL DEFAULT -1,
    compiler_output   TEXT        NULL,             -- truncated to 4096 (FR-20)
    failed_test_index INT         NOT NULL DEFAULT -1,
    workstation_ip    VARCHAR(45) NULL,             -- NFR-C02
    queued_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    judged_at         TIMESTAMP   NULL,
    active            BOOLEAN     NOT NULL DEFAULT TRUE,      -- DBR-04
    PRIMARY KEY (id),
    KEY idx_submissions_user_problem (user_id, problem_id),
    KEY idx_submissions_status (status),
    CONSTRAINT fk_submissions_user    FOREIGN KEY (user_id)    REFERENCES users (id),
    CONSTRAINT fk_submissions_problem FOREIGN KEY (problem_id) REFERENCES problems (id),
    CONSTRAINT fk_submissions_contest FOREIGN KEY (contest_id) REFERENCES contests (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Per-test outcomes (DBR-03). Written by the judge worker; no JPA entity yet —
-- the leaderboard chunk (S3-B3) adds one when it needs to read them.
CREATE TABLE submission_results (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    submission_id  BIGINT      NOT NULL,
    test_index     INT         NOT NULL,
    verdict        VARCHAR(8)  NOT NULL,
    exec_time_ms   BIGINT      NOT NULL DEFAULT -1,
    peak_memory_kb BIGINT      NOT NULL DEFAULT -1,
    PRIMARY KEY (id),
    KEY idx_submission_results_submission (submission_id),
    CONSTRAINT fk_submission_results_submission
        FOREIGN KEY (submission_id) REFERENCES submissions (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
