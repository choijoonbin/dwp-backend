CREATE TABLE usr_workspace_app_access_requests (
    app_access_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    app_key VARCHAR(80) NOT NULL,
    requested_permission_code VARCHAR(50) NOT NULL DEFAULT 'VIEW',
    justification VARCHAR(1000) NOT NULL,
    request_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    requested_until TIMESTAMPTZ,
    decision_note VARCHAR(1000),
    decided_at TIMESTAMPTZ,
    decided_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_usr_workspace_app_access_request_app
        FOREIGN KEY (tenant_id, app_key)
        REFERENCES adm_workspace_apps(tenant_id, app_key),
    CONSTRAINT ck_usr_workspace_app_access_request_state
        CHECK (request_state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_usr_workspace_app_access_request_permission
        CHECK (requested_permission_code IN ('VIEW')),
    CONSTRAINT ck_usr_workspace_app_access_request_justification
        CHECK (length(btrim(justification)) >= 10),
    CONSTRAINT ck_usr_workspace_app_access_request_decision
        CHECK (
            (request_state IN ('APPROVED', 'REJECTED')
                AND decided_at IS NOT NULL AND decided_by IS NOT NULL
                AND decision_note IS NOT NULL)
            OR (request_state IN ('PENDING', 'CANCELLED'))
        )
);

CREATE UNIQUE INDEX uk_usr_workspace_app_access_request_open
    ON usr_workspace_app_access_requests(tenant_id, user_id, app_key)
    WHERE request_state IN ('PENDING', 'APPROVED');
CREATE INDEX idx_usr_workspace_app_access_request_queue
    ON usr_workspace_app_access_requests(tenant_id, request_state, created_at);
CREATE INDEX idx_usr_workspace_app_access_request_user
    ON usr_workspace_app_access_requests(tenant_id, user_id, updated_at DESC);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES (
    'PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.STATE', 'dwp-platform-server',
    'Workspace app access request state',
    'Decision lifecycle for user access requests raised from the workspace app catalog.',
    'SYSTEM', 'CHECK', 'usr_workspace_app_access_requests.request_state', 'STATE_MACHINE'
);

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.STATE', 'PENDING', 'Pending',
     '{"ko":"검토 대기","en":"Pending"}', 10, '{"open":true}'),
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.STATE', 'APPROVED', 'Approved',
     '{"ko":"승인됨","en":"Approved"}', 20, '{"awaitsEntitlementSync":true}'),
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.STATE', 'REJECTED', 'Rejected',
     '{"ko":"반려됨","en":"Rejected"}', 30, '{"terminal":true}'),
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.STATE', 'CANCELLED', 'Cancelled',
     '{"ko":"취소됨","en":"Cancelled"}', 40, '{"terminal":true}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES (
    'PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.STATE', 'dwp-platform-server',
    'DATABASE_COLUMN', 'usr_workspace_app_access_requests.request_state', 'CHECK'
);

COMMENT ON TABLE usr_workspace_app_access_requests IS
    'Tenant-scoped self-service app access requests; IAM remains the entitlement authority.';
COMMENT ON COLUMN usr_workspace_app_access_requests.request_state IS
    'APPROVED records the business decision while runtime availability still requires IAM sync.';
