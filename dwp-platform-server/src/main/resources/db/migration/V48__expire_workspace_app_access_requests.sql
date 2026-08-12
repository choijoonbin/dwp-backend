ALTER TABLE usr_workspace_app_access_requests
    DROP CONSTRAINT ck_usr_workspace_app_access_request_state;
ALTER TABLE usr_workspace_app_access_requests
    DROP CONSTRAINT ck_usr_workspace_app_access_request_decision;

ALTER TABLE usr_workspace_app_access_requests
    ADD CONSTRAINT ck_usr_workspace_app_access_request_state
        CHECK (request_state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'));
ALTER TABLE usr_workspace_app_access_requests
    ADD CONSTRAINT ck_usr_workspace_app_access_request_decision
        CHECK (
            (request_state IN ('APPROVED', 'REJECTED')
                AND decided_at IS NOT NULL AND decided_by IS NOT NULL
                AND decision_note IS NOT NULL)
            OR request_state IN ('PENDING', 'CANCELLED', 'EXPIRED')
        );

UPDATE usr_workspace_app_access_requests
   SET request_state = 'EXPIRED', version = version + 1,
       updated_at = CURRENT_TIMESTAMP, updated_by = NULL
 WHERE request_state IN ('PENDING', 'APPROVED')
   AND requested_until <= CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES (
    'PLATFORM.WORKSPACE_APP_ACCESS_REQUEST.STATE', 'EXPIRED', 'Expired',
    '{"ko":"기한 만료","en":"Expired"}', 50,
    '{"terminal":true,"reRequestAllowed":true,"transitionOwner":"SYSTEM"}');

COMMENT ON COLUMN usr_workspace_app_access_requests.requested_until IS
    'Requested access end time; open requests transition to EXPIRED after this timestamp.';
