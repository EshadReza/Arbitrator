-- V59__pdf_statements.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- FR-05 finally honoured: the SRS always allowed a PDF statement, and the
-- importer has been rejecting them with "export it as HTML" ever since.
--
-- The bytes live in their own table rather than a column on problems. Every
-- problem-list query loads Problem entities, and a MEDIUMBLOB hanging off that
-- entity would be dragged along on each one — Hibernate cannot lazily fetch a
-- basic attribute without bytecode enhancement, so the "lazy" blob would in
-- practice be eager. A side table keeps Problem exactly as cheap as it was and
-- the bytes are read only by the one endpoint that serves them.
CREATE TABLE problem_statement_pdfs (
    problem_id BIGINT     NOT NULL,
    data       MEDIUMBLOB NOT NULL,
    PRIMARY KEY (problem_id),
    CONSTRAINT fk_statement_pdf_problem
        FOREIGN KEY (problem_id) REFERENCES problems (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Which rendering path the client should take. A PDF problem still carries
-- statement_html (a short placeholder), because that column is NOT NULL and
-- every existing reader expects something there.
ALTER TABLE problems
    ADD COLUMN statement_is_pdf BOOLEAN NOT NULL DEFAULT FALSE;
