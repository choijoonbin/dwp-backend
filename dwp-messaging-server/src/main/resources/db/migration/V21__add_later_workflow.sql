ALTER TABLE msg_saved_items
    ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'SAVED',
    ADD COLUMN reminder_at TIMESTAMPTZ,
    ADD COLUMN snoozed_until TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_msg_saved_item_state
        CHECK (state IN ('SAVED', 'SNOOZED', 'DUE', 'COMPLETED')),
    ADD CONSTRAINT ck_msg_saved_item_snooze
        CHECK (state <> 'SNOOZED' OR snoozed_until IS NOT NULL),
    ADD CONSTRAINT ck_msg_saved_item_completion
        CHECK (state <> 'COMPLETED' OR completed_at IS NOT NULL);

CREATE INDEX ix_msg_saved_items_work_queue
    ON msg_saved_items (tenant_id, user_id, state, reminder_at, updated_at DESC, message_id DESC);

COMMENT ON COLUMN msg_saved_items.state IS
    'User-owned Later workflow state. DUE is materialized when a snooze or reminder expires.';

COMMENT ON COLUMN msg_saved_items.version IS
    'Optimistic concurrency token for Later workflow mutations.';
