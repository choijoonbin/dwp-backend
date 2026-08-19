ALTER TABLE spc_policy_evaluations
    DROP CONSTRAINT ck_spc_policy_evaluation_type;

ALTER TABLE spc_policy_evaluations
    ADD CONSTRAINT ck_spc_policy_evaluation_type CHECK (
        policy_type IN ('SPACE_CREATION', 'SPACE_ACCESS', 'CONTENT_PUBLICATION',
                        'MEMBERSHIP_CHANGE', 'LIFECYCLE', 'SPACE_OWNER_RECOVERY'));

COMMENT ON COLUMN spc_policy_evaluations.policy_type IS
    'Governed transition type, including audited ownerless-Space recovery.';
