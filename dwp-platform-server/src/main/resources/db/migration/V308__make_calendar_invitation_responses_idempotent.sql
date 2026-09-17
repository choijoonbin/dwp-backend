-- Calendar invitation response idempotency contract.
ALTER TABLE cal_event_attendees
    ADD COLUMN response_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE cal_event_attendees
    ADD CONSTRAINT ck_cal_attendee_response_version
        CHECK (response_version >= 0);

CREATE TABLE cal_invitation_response_commands (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_person_public_id UUID,
    idempotency_key UUID NOT NULL,
    event_id UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    expected_event_version BIGINT NOT NULL,
    result_event_version BIGINT NOT NULL,
    result_response_status VARCHAR(20) NOT NULL,
    result_attendee_response_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT fk_cal_invitation_response_command_event
        FOREIGN KEY (tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE,
    CONSTRAINT ck_cal_invitation_response_command_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cal_invitation_response_command_versions
        CHECK (
            expected_event_version >= 0
            AND result_event_version = expected_event_version + 1
            AND result_attendee_response_version > 0),
    CONSTRAINT ck_cal_invitation_response_command_status
        CHECK (result_response_status IN ('ACCEPTED', 'TENTATIVE', 'DECLINED'))
);

CREATE INDEX idx_cal_invitation_response_commands_event
    ON cal_invitation_response_commands (tenant_id, event_id, created_at DESC);

COMMENT ON COLUMN cal_event_attendees.response_version IS
    'Monotonic invitation-response revision used to reject stale and away-and-back replays.';

COMMENT ON TABLE cal_invitation_response_commands IS
    'Durable per-actor invitation response receipts for exact, fail-closed replay.';
