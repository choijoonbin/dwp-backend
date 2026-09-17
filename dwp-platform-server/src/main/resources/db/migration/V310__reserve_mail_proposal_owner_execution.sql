ALTER TABLE mail_action_proposals
    DROP CONSTRAINT ck_mail_proposal_owner_state,
    ADD CONSTRAINT ck_mail_proposal_owner_state CHECK (
        owner_state IS NULL OR owner_state IN (
            'ACCEPTED', 'EXECUTING', 'EXECUTED', 'CANCELLED', 'FAILED', 'UNKNOWN'));

ALTER TABLE mail_action_proposals
    DROP CONSTRAINT ck_mail_proposal_owner_result,
    ADD CONSTRAINT ck_mail_proposal_owner_result CHECK (
        owner_state IS NULL OR owner_state IN ('ACCEPTED', 'EXECUTING')
        OR (result_ref IS NOT NULL AND BTRIM(result_ref) <> ''));

COMMENT ON COLUMN mail_action_proposals.owner_state IS
    'Owner handoff state. EXECUTING is a durable reservation that fences user cancellation before an owner write.';
