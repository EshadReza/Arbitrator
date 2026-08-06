-- V56__submission_result_output.sql — owner: Mahir (range V50-V79, rules.md Rule 5)
-- What the contestant's program actually printed for each test it ran.
--
-- Without it the test view could show the input and the expected answer but not
-- the one thing a student needs to see — what their own code produced — which
-- makes a WA impossible to learn anything from. Truncated in Java before it
-- reaches here; a runaway program can print megabytes and the judge already
-- caps captured output at 10 MiB.
ALTER TABLE submission_results
    ADD COLUMN actual_output TEXT NULL;
