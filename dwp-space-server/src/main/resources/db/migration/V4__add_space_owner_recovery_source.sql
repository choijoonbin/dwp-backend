ALTER TABLE spc_memberships
    DROP CONSTRAINT ck_spc_membership_source;

ALTER TABLE spc_memberships
    ADD CONSTRAINT ck_spc_membership_source CHECK (
        membership_source IN (
            'DIRECT', 'GROUP', 'REQUEST', 'TEMPLATE', 'PROVISIONING', 'RECOVERY'));

COMMENT ON COLUMN spc_memberships.membership_source IS
    'Membership provenance. RECOVERY is reserved for audited ownerless-Space remediation.';
