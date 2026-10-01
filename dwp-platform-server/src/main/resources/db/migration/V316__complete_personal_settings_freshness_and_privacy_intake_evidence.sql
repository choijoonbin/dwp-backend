CREATE TABLE usr_personal_settings_workspace_states (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    last_change_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_confirmed_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT ck_usr_personal_settings_workspace_confirmation
        CHECK (last_confirmed_at IS NULL OR last_confirmed_at <= updated_at)
);

CREATE TABLE usr_personal_privacy_request_events (
    personal_privacy_request_event_id UUID PRIMARY KEY,
    personal_privacy_request_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    request_state VARCHAR(24) NOT NULL,
    detail_key VARCHAR(96) NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_usr_personal_privacy_request_event_request
        FOREIGN KEY (personal_privacy_request_id)
        REFERENCES usr_personal_privacy_requests (personal_privacy_request_id),
    CONSTRAINT ck_usr_personal_privacy_request_event_type
        CHECK (event_type IN (
            'REQUEST_RECEIVED',
            'FULFILLMENT_BOUNDARY_RECORDED',
            'REQUEST_CANCELLED'
        )),
    CONSTRAINT ck_usr_personal_privacy_request_event_state
        CHECK (request_state IN ('RECEIVED', 'CANCELLED'))
);

CREATE INDEX idx_usr_personal_privacy_request_event_owner
    ON usr_personal_privacy_request_events
    (tenant_id, user_id, personal_privacy_request_id, occurred_at, personal_privacy_request_event_id);

CREATE TABLE usr_personal_privacy_request_receipts (
    personal_privacy_request_id UUID PRIMARY KEY,
    receipt_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    receipt_type VARCHAR(24) NOT NULL,
    evidence_state VARCHAR(24) NOT NULL,
    fulfillment_boundary VARCHAR(96) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_usr_personal_privacy_request_receipt_request
        FOREIGN KEY (personal_privacy_request_id)
        REFERENCES usr_personal_privacy_requests (personal_privacy_request_id),
    CONSTRAINT ck_usr_personal_privacy_request_receipt_type
        CHECK (receipt_type = 'INTAKE'),
    CONSTRAINT ck_usr_personal_privacy_request_receipt_evidence
        CHECK (evidence_state = 'INTAKE_ONLY'),
    CONSTRAINT ck_usr_personal_privacy_request_receipt_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

INSERT INTO usr_personal_settings_workspace_states (
    tenant_id, user_id, last_change_at, created_at, updated_at
)
SELECT owner.tenant_id, owner.user_id, MAX(owner.changed_at),
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM (
        SELECT tenant_id, user_id, updated_at AS changed_at
          FROM usr_personal_setting_favorites
        UNION ALL
        SELECT tenant_id, user_id, occurred_at AS changed_at
          FROM usr_personal_setting_activity
       ) owner
 GROUP BY owner.tenant_id, owner.user_id
ON CONFLICT (tenant_id, user_id) DO NOTHING;

INSERT INTO usr_personal_privacy_request_receipts (
    personal_privacy_request_id, receipt_id, tenant_id, user_id, receipt_type,
    evidence_state, fulfillment_boundary, request_fingerprint, issued_at
)
SELECT request.personal_privacy_request_id, gen_random_uuid(), request.tenant_id, request.user_id,
       'INTAKE', 'INTAKE_ONLY', 'PRIVACY_OWNER_EXECUTION_NOT_CONNECTED',
       encode(sha256(convert_to(concat_ws('|',
           request.personal_privacy_request_id::text,
           request.tenant_id::text,
           request.user_id::text,
           request.request_type,
           request.requested_scope,
           request.created_at::text), 'UTF8')), 'hex'),
       request.created_at
  FROM usr_personal_privacy_requests request
ON CONFLICT (personal_privacy_request_id) DO NOTHING;

INSERT INTO usr_personal_privacy_request_events (
    personal_privacy_request_event_id, personal_privacy_request_id, tenant_id, user_id,
    event_type, request_state, detail_key, occurred_at
)
SELECT gen_random_uuid(), request.personal_privacy_request_id, request.tenant_id, request.user_id,
       'REQUEST_RECEIVED', 'RECEIVED', 'PRIVACY_REQUEST_INTAKE_RECORDED', request.created_at
  FROM usr_personal_privacy_requests request;

INSERT INTO usr_personal_privacy_request_events (
    personal_privacy_request_event_id, personal_privacy_request_id, tenant_id, user_id,
    event_type, request_state, detail_key, occurred_at
)
SELECT gen_random_uuid(), request.personal_privacy_request_id, request.tenant_id, request.user_id,
       'FULFILLMENT_BOUNDARY_RECORDED', 'RECEIVED',
       'PRIVACY_OWNER_EXECUTION_NOT_CONNECTED', request.created_at
  FROM usr_personal_privacy_requests request;

INSERT INTO usr_personal_privacy_request_events (
    personal_privacy_request_event_id, personal_privacy_request_id, tenant_id, user_id,
    event_type, request_state, detail_key, occurred_at
)
SELECT gen_random_uuid(), request.personal_privacy_request_id, request.tenant_id, request.user_id,
       'REQUEST_CANCELLED', 'CANCELLED', 'CANCELLED_BY_REQUEST_OWNER', request.updated_at
  FROM usr_personal_privacy_requests request
 WHERE request.request_state = 'CANCELLED';

CREATE OR REPLACE FUNCTION reject_personal_privacy_evidence_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'personal privacy evidence is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_usr_personal_privacy_request_events_immutable
    BEFORE UPDATE OR DELETE ON usr_personal_privacy_request_events
    FOR EACH ROW EXECUTE FUNCTION reject_personal_privacy_evidence_mutation();

CREATE TRIGGER trg_usr_personal_privacy_request_receipts_immutable
    BEFORE UPDATE OR DELETE ON usr_personal_privacy_request_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_personal_privacy_evidence_mutation();

COMMENT ON TABLE usr_personal_settings_workspace_states IS
    'Tenant/user-bound freshness and explicit reconfirmation state for the personal-settings workspace.';
COMMENT ON TABLE usr_personal_privacy_request_events IS
    'Immutable internal lifecycle evidence. It never represents external collection, export, or deletion completion.';
COMMENT ON TABLE usr_personal_privacy_request_receipts IS
    'Immutable intake receipts. INTAKE_ONLY is proof of registration, not fulfillment.';
