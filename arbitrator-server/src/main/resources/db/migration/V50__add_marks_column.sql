-- V50__add_marks_column.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- Instructor-assigned marks for grading (new admin feature).
ALTER TABLE submissions
    ADD COLUMN marks INT NULL AFTER failed_test_index;
