ALTER TABLE mail_action_proposals
    DROP CONSTRAINT ck_mail_proposal_contract_version;

ALTER TABLE mail_action_proposals
    ADD CONSTRAINT ck_mail_proposal_contract_version
        CHECK (action_contract_version > 0),
    ADD CONSTRAINT ck_mail_proposal_evidence_required
        CHECK (jsonb_array_length(evidence) > 0),
    ADD CONSTRAINT ck_mail_proposal_confirmation_required
        CHECK (proposed_payload @> '{"requiresConfirmation": true}'::jsonb);

ALTER TABLE mail_action_proposals
    ALTER COLUMN required_resource_key SET NOT NULL,
    ALTER COLUMN required_permission_code SET NOT NULL,
    ALTER COLUMN target_route SET NOT NULL,
    ALTER COLUMN expires_at SET NOT NULL;

COMMENT ON CONSTRAINT ck_mail_proposal_contract_version ON mail_action_proposals IS
    'The database can retain positive contract versions during rolling upgrades; each runtime accepts only explicitly registered versions.';
