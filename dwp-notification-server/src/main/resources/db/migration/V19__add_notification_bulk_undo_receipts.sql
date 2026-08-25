CREATE TABLE ntf_bulk_undo_receipts (
    undo_token UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_bulk_undo_scope UNIQUE (tenant_id, user_id, undo_token),
    CONSTRAINT ck_ntf_bulk_undo_state CHECK (state IN ('AVAILABLE', 'COMPLETED')),
    CONSTRAINT ck_ntf_bulk_undo_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_ntf_bulk_undo_completion CHECK (
        (state = 'AVAILABLE' AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL)
    )
);

CREATE TABLE ntf_bulk_undo_items (
    undo_token UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    notification_id UUID NOT NULL,
    before_inbox_state VARCHAR(32) NOT NULL,
    before_read_at TIMESTAMPTZ,
    before_saved_at TIMESTAMPTZ,
    before_completed_at TIMESTAMPTZ,
    before_snoozed_until TIMESTAMPTZ,
    expected_version BIGINT NOT NULL,
    undone_at TIMESTAMPTZ,
    PRIMARY KEY (undo_token, notification_id),
    CONSTRAINT fk_ntf_bulk_undo_receipt
        FOREIGN KEY (tenant_id, user_id, undo_token)
        REFERENCES ntf_bulk_undo_receipts (tenant_id, user_id, undo_token)
        ON DELETE CASCADE,
    CONSTRAINT ck_ntf_bulk_undo_inbox_state
        CHECK (before_inbox_state IN ('ACTIVE', 'DONE')),
    CONSTRAINT ck_ntf_bulk_undo_version CHECK (expected_version > 0)
);

CREATE INDEX ix_ntf_bulk_undo_expiry
    ON ntf_bulk_undo_receipts (tenant_id, expires_at, undo_token);
CREATE INDEX ix_ntf_bulk_undo_pending
    ON ntf_bulk_undo_items (tenant_id, user_id, undo_token, notification_id)
    WHERE undone_at IS NULL;

ALTER TABLE ntf_bulk_undo_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_bulk_undo_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_bulk_undo_receipt_scope ON ntf_bulk_undo_receipts
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (ntf_is_worker() OR (ntf_is_api() AND user_id = ntf_current_user_id()))
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (ntf_is_worker() OR (ntf_is_api() AND user_id = ntf_current_user_id()))
    );

ALTER TABLE ntf_bulk_undo_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_bulk_undo_items FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_bulk_undo_item_scope ON ntf_bulk_undo_items
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (ntf_is_worker() OR (ntf_is_api() AND user_id = ntf_current_user_id()))
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (ntf_is_worker() OR (ntf_is_api() AND user_id = ntf_current_user_id()))
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON ntf_bulk_undo_receipts,
    ntf_bulk_undo_items TO dwp_notification_api;
GRANT SELECT, DELETE ON ntf_bulk_undo_receipts,
    ntf_bulk_undo_items TO dwp_notification_worker;

COMMENT ON TABLE ntf_bulk_undo_receipts IS
    'Short-lived opaque compensation receipts for user-owned bulk triage commands.';
COMMENT ON TABLE ntf_bulk_undo_items IS
    'Exact pre-command projection state restored only when the post-command version is unchanged.';
