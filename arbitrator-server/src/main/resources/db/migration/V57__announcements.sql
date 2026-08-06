-- V57__announcements.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- FR-07: instructor announcements, broadcast to every client in a contest.
--
-- body is HTML, not plain text, for the same reason problem statements are:
-- an announcement routinely needs mathematics ("for n <= 10^9", "a_i > b_i"),
-- and HTML gives superscripts, subscripts and symbol entities without dragging
-- a LaTeX engine and its fonts onto a machine with no internet.
CREATE TABLE announcements (
    id         BIGINT     NOT NULL AUTO_INCREMENT,
    contest_id BIGINT     NOT NULL,
    body       MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_announcements_contest (contest_id, created_at),
    CONSTRAINT fk_announcements_contest
        FOREIGN KEY (contest_id) REFERENCES contests (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
