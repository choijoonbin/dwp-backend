ALTER TABLE mail_action_proposals
    ADD COLUMN action_contract_version SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE mail_action_proposals
    ADD CONSTRAINT ck_mail_proposal_contract_version
        CHECK (action_contract_version = 1);

COMMENT ON COLUMN mail_action_proposals.action_contract_version IS
    'Version of the governed action payload and authorization contract. Consumers must reject unsupported versions.';
