CREATE TABLE apr_request_payload_versions (
    payload_version_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    payload JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    change_type VARCHAR(40) NOT NULL,
    changed_by BIGINT,
    change_reason VARCHAR(2000),
    correlation_id VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_apr_payload_version_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES apr_requests(tenant_id, request_id) ON DELETE CASCADE,
    CONSTRAINT uk_apr_payload_version_revision
        UNIQUE (tenant_id, request_id, revision_number),
    CONSTRAINT ck_apr_payload_version_payload
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_apr_payload_version_revision
        CHECK (revision_number > 0),
    CONSTRAINT ck_apr_payload_version_change
        CHECK (change_type IN (
            'BASELINE', 'DRAFT_CREATED', 'DRAFT_UPDATED', 'INFORMATION_RESPONDED'))
);

INSERT INTO apr_request_payload_versions (
    payload_version_id, tenant_id, request_id, revision_number,
    payload, payload_sha256, change_type, changed_by, change_reason, created_at)
SELECT gen_random_uuid(), payload.tenant_id, payload.request_id,
       payload.schema_version, payload.payload, payload.payload_sha256,
       'BASELINE', request.updated_by,
       'Payload baseline captured during immutable revision upgrade',
       payload.updated_at
  FROM apr_request_payloads payload
  JOIN apr_requests request
    ON request.tenant_id = payload.tenant_id
   AND request.request_id = payload.request_id
ON CONFLICT (tenant_id, request_id, revision_number) DO NOTHING;

CREATE INDEX idx_apr_payload_version_request
    ON apr_request_payload_versions (tenant_id, request_id, revision_number DESC);

COMMENT ON TABLE apr_request_payload_versions IS
    'Append-only request payload evidence retained across draft edits and information-response amendments.';
