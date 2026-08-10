ALTER TABLE sys_people_outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_error VARCHAR(1000),
    ADD COLUMN dead_lettered_at TIMESTAMPTZ;

UPDATE sys_people_outbox_events
SET published_at = CURRENT_TIMESTAMP,
    last_error = 'Superseded by identity sync contract v1'
WHERE event_type = 'people.worker-projection.changed'
  AND published_at IS NULL;

DROP INDEX idx_sys_people_outbox_pending;

CREATE INDEX idx_sys_people_outbox_pending
    ON sys_people_outbox_events(next_attempt_at, occurred_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;
