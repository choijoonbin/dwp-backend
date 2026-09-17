-- A recurring occurrence is an explicit, versioned exception to its owning series.
-- Attendees and resources remain series-owned so an occurrence command cannot silently
-- create invitation or room-booking state that Calendar cannot reconcile.
ALTER TABLE cal_event_occurrence_overrides
    ADD COLUMN title VARCHAR(300),
    ADD COLUMN description VARCHAR(4000),
    ADD COLUMN event_type VARCHAR(30),
    ADD COLUMN all_day BOOLEAN,
    ADD COLUMN location VARCHAR(240),
    ADD COLUMN conference_url VARCHAR(1000),
    ADD COLUMN visibility VARCHAR(20),
    ADD COLUMN response_required BOOLEAN;

ALTER TABLE cal_event_occurrence_overrides
    ADD CONSTRAINT ck_cal_occurrence_override_event_type
        CHECK (event_type IS NULL OR event_type IN (
            'MEETING', 'FOCUS', 'TASK', 'OUT_OF_OFFICE', 'REMINDER')),
    ADD CONSTRAINT ck_cal_occurrence_override_visibility
        CHECK (visibility IS NULL OR visibility IN (
            'DEFAULT', 'PUBLIC', 'PRIVATE', 'CONFIDENTIAL'));

CREATE TABLE cal_occurrence_command_receipts (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    event_id UUID NOT NULL,
    original_starts_at TIMESTAMPTZ NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    resulting_event_version BIGINT NOT NULL,
    resulting_override_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT fk_cal_occurrence_command_receipt_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE,
    CONSTRAINT ck_cal_occurrence_command_receipt_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cal_occurrence_command_receipt_versions
        CHECK (resulting_event_version >= 0 AND resulting_override_version >= 0)
);

CREATE INDEX idx_cal_occurrence_command_receipts_event
    ON cal_occurrence_command_receipts (tenant_id, event_id, created_at DESC);
