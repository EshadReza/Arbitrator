-- Preserve validated admission time instead of rounding to whole seconds.
ALTER TABLE submissions MODIFY COLUMN queued_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
