ALTER TABLE prv_support_sessions
    ADD COLUMN customer_approval_required BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE prv_support_sessions
   SET customer_approval_required = access_mode = 'STANDARD';

ALTER TABLE prv_support_sessions
    DROP CONSTRAINT ck_prv_support_sessions_approval,
    ADD CONSTRAINT ck_prv_support_sessions_approval
        CHECK (
            access_mode = 'BREAK_GLASS'
            OR NOT customer_approval_required
            OR (approval_reference IS NOT NULL AND LENGTH(BTRIM(approval_reference)) > 0)
        );
