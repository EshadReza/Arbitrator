-- V65__materials.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- FR-07 sibling: downloadable instructor materials (slides, PDFs, any file).
--
-- Unlike problem_statement_pdfs (V59), the bytes are NOT stored here. Materials
-- are expected to be larger and more numerous than the handful of statement
-- PDFs, so they live on disk under arbitrator.materials.root (see
-- MaterialService) and this table is just the catalog: original filename for
-- display/download, and the UUID-based name actually used on disk so a
-- collision or a path-traversal attempt in the original filename never
-- reaches the filesystem.
CREATE TABLE materials (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    contest_id   BIGINT       NOT NULL,
    filename     VARCHAR(255) NOT NULL,
    stored_name  VARCHAR(255) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    uploaded_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_materials_contest (contest_id, uploaded_at),
    CONSTRAINT fk_materials_contest
        FOREIGN KEY (contest_id) REFERENCES contests (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
