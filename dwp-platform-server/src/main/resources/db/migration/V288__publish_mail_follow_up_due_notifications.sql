CREATE TABLE mail_follow_up_notification_ledger (
    ledger_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    follow_up_id UUID NOT NULL REFERENCES mail_follow_up_trackers(follow_up_id) ON DELETE CASCADE,
    owner_user_id BIGINT NOT NULL,
    thread_id UUID NOT NULL REFERENCES mail_threads(thread_id) ON DELETE CASCADE,
    follow_up_version BIGINT NOT NULL,
    expected_reply_at TIMESTAMPTZ NOT NULL,
    decision VARCHAR(16) NOT NULL DEFAULT 'CLAIMED',
    domain_event_id UUID,
    claimed_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_mail_follow_up_notification_occurrence UNIQUE (
        tenant_id, follow_up_id, follow_up_version, expected_reply_at),
    CONSTRAINT ck_mail_follow_up_notification_version CHECK (follow_up_version >= 0),
    CONSTRAINT ck_mail_follow_up_notification_decision CHECK (
        decision IN ('CLAIMED', 'EMITTED', 'SUPPRESSED')),
    CONSTRAINT ck_mail_follow_up_notification_completion CHECK (
        (decision = 'CLAIMED' AND domain_event_id IS NULL AND completed_at IS NULL)
        OR (decision = 'EMITTED' AND domain_event_id IS NOT NULL AND completed_at IS NOT NULL)
        OR (decision = 'SUPPRESSED' AND domain_event_id IS NULL AND completed_at IS NOT NULL))
);

CREATE INDEX idx_mail_follow_up_notification_claims
    ON mail_follow_up_notification_ledger (tenant_id, decision, claimed_at DESC);

COMMENT ON TABLE mail_follow_up_notification_ledger IS
    'Exactly-once publication ledger for each versioned Mail follow-up due occurrence.';
COMMENT ON COLUMN mail_follow_up_notification_ledger.decision IS
    'EMITTED records the durable domain event; SUPPRESSED records the user preference decision.';
