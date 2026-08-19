CREATE TABLE spc_access_requests (
    access_request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    space_id UUID NOT NULL,
    requester_user_id BIGINT NOT NULL,
    requester_person_public_id UUID,
    requester_name VARCHAR(200),
    requested_role VARCHAR(20) NOT NULL DEFAULT 'VIEWER',
    justification VARCHAR(2000) NOT NULL,
    decision_mode VARCHAR(20) NOT NULL DEFAULT 'OWNER_REVIEW',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decided_by BIGINT,
    decision_note VARCHAR(2000),
    decided_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_access_request_scope UNIQUE (tenant_id, access_request_id),
    CONSTRAINT fk_spc_access_request_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT ck_spc_access_request_role CHECK (requested_role IN ('VIEWER', 'CONTRIBUTOR')),
    CONSTRAINT ck_spc_access_request_mode CHECK (decision_mode IN ('AUTO', 'OWNER_REVIEW')),
    CONSTRAINT ck_spc_access_request_state CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uk_spc_access_request_pending
    ON spc_access_requests (tenant_id, space_id, requester_user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_spc_access_request_queue
    ON spc_access_requests (tenant_id, space_id, status, created_at);
