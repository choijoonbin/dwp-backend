CREATE TABLE vm_meeting_followup_assertion_replay (
    jti UUID PRIMARY KEY,
    key_id VARCHAR(64) NOT NULL,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    report_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_vm_followup_assertion_key CHECK (
        key_id ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'),
    CONSTRAINT ck_vm_followup_assertion_actor CHECK (
        tenant_id > 0 AND actor_user_id > 0),
    CONSTRAINT ck_vm_followup_assertion_action CHECK (
        action IN ('READ', 'CREATE', 'REASSIGN')),
    CONSTRAINT ck_vm_followup_assertion_expiry CHECK (
        expires_at > consumed_at)
);

CREATE INDEX ix_vm_followup_assertion_expiry
    ON vm_meeting_followup_assertion_replay (expires_at);

COMMENT ON TABLE vm_meeting_followup_assertion_replay IS
    'Content-free single-use evidence for body-bound Platform Work to Meeting source assertions.';
