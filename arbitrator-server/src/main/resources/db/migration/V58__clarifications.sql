-- V58__clarifications.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- Contestants ask, the instructor answers, and the whole room can read both.
--
-- The board is public by design: a clarification that only reaches one team is
-- an unfair advantage, which is why every real contest publishes them. The
-- asker's identity is stored here but withheld from the participant view —
-- that filtering happens in the service, never by leaving the column out,
-- because the instructor does need to know who is asking.
--
-- problem_id is nullable: some questions are about the contest itself rather
-- than any one problem.
CREATE TABLE clarifications (
    id          BIGINT     NOT NULL AUTO_INCREMENT,
    contest_id  BIGINT     NOT NULL,
    problem_id  BIGINT     NULL,
    user_id     BIGINT     NOT NULL,
    question    MEDIUMTEXT NOT NULL,
    answer      MEDIUMTEXT NULL,
    asked_at    TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    answered_at TIMESTAMP  NULL,
    PRIMARY KEY (id),
    KEY idx_clarifications_contest (contest_id, asked_at),
    CONSTRAINT fk_clarifications_contest FOREIGN KEY (contest_id) REFERENCES contests (id),
    CONSTRAINT fk_clarifications_problem FOREIGN KEY (problem_id) REFERENCES problems (id),
    CONSTRAINT fk_clarifications_user    FOREIGN KEY (user_id)    REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
