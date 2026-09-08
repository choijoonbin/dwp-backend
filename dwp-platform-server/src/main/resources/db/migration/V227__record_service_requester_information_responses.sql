-- Requester response commands preserve source ownership and exact replay identity.
CREATE TABLE svc_request_information_responses (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    requester_user_id BIGINT NOT NULL CHECK (requester_user_id > 0),
    command_id UUID NOT NULL,
    service_request_id UUID NOT NULL,
    request_values JSONB NOT NULL CHECK (jsonb_typeof(request_values) = 'object'),
    message VARCHAR(2000) NOT NULL CHECK (length(btrim(message)) BETWEEN 10 AND 2000),
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    resulting_version BIGINT NOT NULL CHECK (resulting_version = expected_version + 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, requester_user_id, command_id),
    FOREIGN KEY (tenant_id, service_request_id) REFERENCES svc_requests(tenant_id, service_request_id)
);
CREATE INDEX idx_svc_information_response_request
    ON svc_request_information_responses (tenant_id, requester_user_id, service_request_id, created_at);
