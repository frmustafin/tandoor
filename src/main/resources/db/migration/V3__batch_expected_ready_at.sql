-- The baker sets the ready time per batch, so the target moves from a global setting to the
-- row. Existing announced batches get the default the setting had at the time (15 minutes).
ALTER TABLE batches ADD COLUMN expected_ready_at TIMESTAMP WITH TIME ZONE;

UPDATE batches SET expected_ready_at = announced_at + INTERVAL '15' MINUTE
WHERE expected_ready_at IS NULL;

ALTER TABLE batches ALTER COLUMN expected_ready_at SET NOT NULL;

CREATE INDEX idx_batches_status_expected ON batches (status, expected_ready_at);
