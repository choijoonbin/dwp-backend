ALTER TABLE ntf_routing_policies
    ADD COLUMN created_by BIGINT,
    ADD COLUMN approved_by BIGINT,
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN change_reason VARCHAR(500),
    ADD COLUMN supersedes_policy_id UUID REFERENCES ntf_routing_policies(policy_id);

CREATE INDEX ix_ntf_policy_tenant_scope_history
    ON ntf_routing_policies (tenant_id, scope_type, scope_key, version DESC);

CREATE UNIQUE INDEX uq_ntf_policy_open_draft
    ON ntf_routing_policies (tenant_id, scope_type, scope_key)
    WHERE state = 'DRAFT' AND tenant_id IS NOT NULL;

COMMENT ON COLUMN ntf_routing_policies.created_by IS
    'Tenant actor who authored the immutable policy version.';
COMMENT ON COLUMN ntf_routing_policies.approved_by IS
    'Independent tenant actor who approved publication.';
COMMENT ON COLUMN ntf_routing_policies.change_reason IS
    'Operator justification retained with the governed policy version.';
