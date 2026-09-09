-- Durable, payload-free handoff from Meeting invitation events to the Notification owner.
-- The parent event keeps the existing public PENDING/DELIVERED/FAILED/CANCELLED states.
-- Recipient rows store only stable identities and Notification acceptance receipts; rendered
-- notification content remains exclusively in the Notification service database.

ALTER TABLE vm_meeting_invitation_outbox
    ADD COLUMN dispatch_fence UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN last_failure_code VARCHAR(48),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT uq_vm_meeting_invitation_event_scope
        UNIQUE (event_id, tenant_id, meeting_id),
    ADD CONSTRAINT ck_vm_meeting_invitation_dispatch_lease CHECK (
        (dispatch_fence IS NULL) = (lease_expires_at IS NULL)),
    ADD CONSTRAINT ck_vm_meeting_invitation_failure_code CHECK (
        last_failure_code IS NULL
        OR last_failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$');

CREATE TABLE vm_meeting_invitation_notification_deliveries (
    event_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    source_event_id UUID NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (delivery_state IN (
            'PENDING', 'ACCEPTED', 'FAILED', 'CANCELLED', 'SUPERSEDED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 32),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivery_fence UUID,
    lease_expires_at TIMESTAMPTZ,
    notification_intent_id UUID,
    notification_id UUID,
    recipient_count SMALLINT CHECK (recipient_count BETWEEN 0 AND 1),
    owner_duplicate BOOLEAN,
    highest_change_version VARCHAR(20)
        CHECK (highest_change_version IS NULL
            OR highest_change_version ~ '^(0|[1-9][0-9]{0,18})$'),
    accepted_at TIMESTAMPTZ,
    last_failure_code VARCHAR(48),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, recipient_user_id),
    UNIQUE (tenant_id, source_event_id),
    FOREIGN KEY (event_id, tenant_id, meeting_id)
        REFERENCES vm_meeting_invitation_outbox (event_id, tenant_id, meeting_id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, recipient_user_id)
        REFERENCES vm_people_snapshot (tenant_id, user_id),
    CHECK ((delivery_fence IS NULL) = (lease_expires_at IS NULL)),
    CHECK ((delivery_state IN ('ACCEPTED', 'SUPERSEDED') AND accepted_at IS NOT NULL
            AND notification_intent_id IS NOT NULL AND notification_id IS NOT NULL
            AND recipient_count = 1 AND owner_duplicate IS NOT NULL
            AND highest_change_version IS NOT NULL)
        OR (delivery_state NOT IN ('ACCEPTED', 'SUPERSEDED') AND accepted_at IS NULL
            AND notification_intent_id IS NULL AND notification_id IS NULL
            AND recipient_count IS NULL AND owner_duplicate IS NULL
            AND highest_change_version IS NULL)),
    CHECK (last_failure_code IS NULL
        OR last_failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$')
);

CREATE INDEX ix_vm_meeting_invitation_dispatch_claim
    ON vm_meeting_invitation_outbox (available_at, created_at, event_id)
    WHERE delivery_state = 'PENDING';

CREATE INDEX ix_vm_meeting_invitation_aggregate_order
    ON vm_meeting_invitation_outbox (
        tenant_id, meeting_id, invitation_revision, created_at, event_id)
    WHERE delivery_state = 'PENDING';

CREATE INDEX ix_vm_meeting_invitation_recipient_claim
    ON vm_meeting_invitation_notification_deliveries (
        event_id, delivery_state, available_at, recipient_user_id);

CREATE INDEX ix_vm_meeting_invitation_recipient_identity
    ON vm_meeting_invitation_notification_deliveries (tenant_id, recipient_user_id);

COMMENT ON TABLE vm_meeting_invitation_outbox IS
    'Payload-free Meeting delivery intent. The dispatcher resolves the current active internal roster and closes this event only after every Notification owner acceptance.';
COMMENT ON TABLE vm_meeting_invitation_notification_deliveries IS
    'Per-recipient stable Notification intent identity, bounded retry state, lease fence, and owner acceptance receipt. No title, agenda, email address, message body, access token, or provider delivery/read receipt is stored here.';
COMMENT ON COLUMN vm_meeting_invitation_notification_deliveries.source_event_id IS
    'Deterministic per event and recipient; a lost HTTP response retries the exact Notification idempotency identity.';
COMMENT ON COLUMN vm_meeting_invitation_notification_deliveries.delivery_state IS
    'SUPERSEDED retains an owner acceptance receipt when the recipient lost current eligibility while the HTTP request was in flight; it is not counted as delivery success.';
