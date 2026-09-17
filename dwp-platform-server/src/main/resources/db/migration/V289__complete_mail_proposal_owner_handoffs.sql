ALTER TABLE mail_action_proposals
    ADD COLUMN owner_command_id UUID,
    ADD COLUMN owner_state VARCHAR(20),
    ADD COLUMN result_ref VARCHAR(500),
    ADD COLUMN owner_updated_at TIMESTAMPTZ;

UPDATE mail_action_proposals
   SET owner_command_id = gen_random_uuid(),
       owner_state = CASE proposal_status
           WHEN 'EXECUTED' THEN 'EXECUTED'
           ELSE 'ACCEPTED'
       END,
       result_ref = CASE proposal_status
           WHEN 'EXECUTED' THEN 'legacy-executed:' || proposal_id
           ELSE NULL
       END,
       owner_updated_at = COALESCE(decided_at, updated_at)
 WHERE proposal_status IN ('ACCEPTED', 'EXECUTED');

ALTER TABLE mail_action_proposals
    ADD CONSTRAINT uq_mail_proposal_owner_command UNIQUE (tenant_id, owner_command_id),
    ADD CONSTRAINT ck_mail_proposal_owner_state CHECK (
        owner_state IS NULL OR owner_state IN (
            'ACCEPTED', 'EXECUTED', 'CANCELLED', 'FAILED', 'UNKNOWN')),
    ADD CONSTRAINT ck_mail_proposal_owner_contract CHECK (
        (owner_command_id IS NULL AND owner_state IS NULL
            AND result_ref IS NULL AND owner_updated_at IS NULL)
        OR (owner_command_id IS NOT NULL AND owner_state IS NOT NULL
            AND owner_updated_at IS NOT NULL)),
    ADD CONSTRAINT ck_mail_proposal_owner_result CHECK (
        owner_state IS NULL OR owner_state = 'ACCEPTED'
        OR (result_ref IS NOT NULL AND BTRIM(result_ref) <> ''));

CREATE INDEX idx_mail_proposal_owner_handoff
    ON mail_action_proposals (tenant_id, owner_state, owner_updated_at DESC)
    WHERE owner_command_id IS NOT NULL;

COMMENT ON COLUMN mail_action_proposals.owner_command_id IS
    'Stable command identity passed to the responsible application after human acceptance.';
COMMENT ON COLUMN mail_action_proposals.owner_state IS
    'Responsible-application outcome. ACCEPTED is a handoff, not proof of execution.';
COMMENT ON COLUMN mail_action_proposals.result_ref IS
    'Bounded opaque reference to the responsible application result or failure evidence.';
