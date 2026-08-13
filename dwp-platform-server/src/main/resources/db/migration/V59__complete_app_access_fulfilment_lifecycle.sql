ALTER TABLE usr_workspace_app_access_requests
    ADD COLUMN fulfillment_state VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN fulfillment_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN fulfillment_note VARCHAR(1000),
    ADD COLUMN last_fulfillment_at TIMESTAMPTZ,
    ADD COLUMN last_fulfillment_error VARCHAR(1000),
    ADD COLUMN fulfilled_at TIMESTAMPTZ,
    ADD COLUMN fulfilled_by BIGINT,
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN revoked_by BIGINT,
    ADD COLUMN revocation_note VARCHAR(1000);

UPDATE usr_workspace_app_access_requests
   SET fulfillment_state = CASE
       WHEN request_state = 'APPROVED' THEN 'PENDING'
       WHEN request_state = 'EXPIRED' AND decided_at IS NOT NULL THEN 'EXPIRED'
       ELSE 'NOT_REQUIRED'
   END;

ALTER TABLE usr_workspace_app_access_requests
    DROP CONSTRAINT ck_usr_workspace_app_access_request_state;
ALTER TABLE usr_workspace_app_access_requests
    DROP CONSTRAINT ck_usr_workspace_app_access_request_decision;

ALTER TABLE usr_workspace_app_access_requests
    ADD CONSTRAINT ck_usr_workspace_app_access_request_state
        CHECK (request_state IN (
            'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'REVOKED')),
    ADD CONSTRAINT ck_usr_workspace_app_access_request_decision
        CHECK (
            (request_state IN ('APPROVED', 'REJECTED', 'REVOKED')
                AND decided_at IS NOT NULL AND decided_by IS NOT NULL
                AND decision_note IS NOT NULL)
            OR request_state IN ('PENDING', 'CANCELLED', 'EXPIRED')
        ),
    ADD CONSTRAINT ck_usr_workspace_app_access_fulfillment_state
        CHECK (fulfillment_state IN (
            'NOT_REQUIRED', 'PENDING', 'SUCCEEDED', 'FAILED', 'REVOKED', 'EXPIRED')),
    ADD CONSTRAINT ck_usr_workspace_app_access_fulfillment_attempts
        CHECK (fulfillment_attempts >= 0),
    ADD CONSTRAINT ck_usr_workspace_app_access_fulfillment_consistency
        CHECK (
            (request_state IN ('PENDING', 'REJECTED', 'CANCELLED')
                AND fulfillment_state = 'NOT_REQUIRED')
            OR (request_state = 'APPROVED'
                AND fulfillment_state IN ('PENDING', 'SUCCEEDED', 'FAILED'))
            OR (request_state = 'REVOKED' AND fulfillment_state = 'REVOKED')
            OR (request_state = 'EXPIRED'
                AND fulfillment_state IN ('NOT_REQUIRED', 'EXPIRED'))
        ),
    ADD CONSTRAINT ck_usr_workspace_app_access_fulfillment_success
        CHECK (
            (fulfillment_state IN ('SUCCEEDED', 'REVOKED')
                AND fulfilled_at IS NOT NULL AND fulfilled_by IS NOT NULL)
            OR fulfillment_state IN ('NOT_REQUIRED', 'PENDING', 'FAILED', 'EXPIRED')
        ),
    ADD CONSTRAINT ck_usr_workspace_app_access_revocation
        CHECK (
            (request_state = 'REVOKED'
                AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL
                AND revocation_note IS NOT NULL)
            OR (request_state <> 'REVOKED'
                AND revoked_at IS NULL AND revoked_by IS NULL AND revocation_note IS NULL)
        );

CREATE INDEX idx_usr_workspace_app_access_fulfillment_queue
    ON usr_workspace_app_access_requests (
        tenant_id, fulfillment_state, updated_at, app_access_request_id)
    WHERE request_state = 'APPROVED'
      AND fulfillment_state IN ('PENDING', 'FAILED');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES (
    'PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.STATE', 'REVOKED', 'Revoked',
    '{"ko":"회수됨","en":"Revoked"}', 60,
    '{"terminal":true,"reRequestAllowed":true,"transitionOwner":"APP_ACCESS_MANAGER"}')
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES (
    'PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.FULFILLMENT_STATE', 'dwp-platform-server',
    'Workspace app access fulfillment state',
    'Runtime entitlement synchronization lifecycle after an independent access decision.',
    'SYSTEM', 'CHECK', 'usr_workspace_app_access_requests.fulfillment_state', 'STATE_MACHINE')
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.FULFILLMENT_STATE', 'NOT_REQUIRED',
     'Not required', '{"ko":"실행 불필요","en":"Not required"}', 10, '{}'),
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.FULFILLMENT_STATE', 'PENDING',
     'Pending fulfillment', '{"ko":"권한 실행 대기","en":"Pending fulfillment"}', 20,
     '{"actionOwner":"APP_ACCESS_MANAGER"}'),
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.FULFILLMENT_STATE', 'SUCCEEDED',
     'Fulfilled', '{"ko":"권한 적용 완료","en":"Fulfilled"}', 30,
     '{"runtimeAccess":true}'),
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.FULFILLMENT_STATE', 'FAILED',
     'Fulfillment failed', '{"ko":"권한 적용 실패","en":"Fulfillment failed"}', 40,
     '{"retryable":true}'),
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.FULFILLMENT_STATE', 'REVOKED',
     'Revoked', '{"ko":"권한 회수 완료","en":"Revoked"}', 50,
     '{"terminal":true}'),
    ('PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.FULFILLMENT_STATE', 'EXPIRED',
     'Expired', '{"ko":"권한 만료","en":"Expired"}', 60,
     '{"terminal":true}')
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES (
    'PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.FULFILLMENT_STATE', 'dwp-platform-server',
    'DATABASE_COLUMN', 'usr_workspace_app_access_requests.fulfillment_state', 'CHECK')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;

COMMENT ON COLUMN usr_workspace_app_access_requests.fulfillment_state IS
    'Independent runtime entitlement execution state; approval alone never grants access.';
COMMENT ON COLUMN usr_workspace_app_access_requests.last_fulfillment_error IS
    'Sanitized operational failure evidence retained for retry and monitoring.';
