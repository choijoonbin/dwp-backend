ALTER TABLE apr_policy_automation_commands
    DROP CONSTRAINT apr_policy_automation_commands_operation_check,
    ADD CONSTRAINT apr_policy_automation_commands_operation_check CHECK (
        operation IN ('SAVE_CALENDAR', 'SAVE_CHANNEL', 'OBSERVE_CHANNEL',
                      'SAVE_POLICY_DRAFT', 'PUBLISH_POLICY', 'REVIEW_DELEGATION',
                      'CREATE_DELEGATION', 'UPDATE_DELEGATION', 'REVOKE_DELEGATION',
                      'CANCEL_SCHEDULED_DELEGATION', 'KILL_SWITCH_DELEGATIONS'));

CREATE TABLE apr_delegation_control_heads (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    last_kill_switch_id UUID,
    last_reason VARCHAR(1000),
    last_invoked_by BIGINT,
    last_invoked_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, resource_set_key),
    CONSTRAINT ck_apr_delegation_control_scope CHECK (
        resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CONSTRAINT ck_apr_delegation_control_version CHECK (
        version BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_apr_delegation_control_last CHECK (
        (version = 0 AND last_kill_switch_id IS NULL AND last_reason IS NULL
                     AND last_invoked_by IS NULL AND last_invoked_at IS NULL)
        OR (version > 0 AND last_kill_switch_id IS NOT NULL
                     AND last_reason IS NOT NULL AND btrim(last_reason) <> ''
                     AND last_invoked_by > 0 AND last_invoked_at IS NOT NULL))
);

CREATE TABLE apr_delegation_governance_events (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    delegation_id UUID,
    action VARCHAR(40) NOT NULL,
    expected_version BIGINT NOT NULL,
    resulting_version BIGINT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    command_sha256 CHAR(64) NOT NULL,
    before_state JSONB NOT NULL,
    after_state JSONB NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id, resource_set_key, delegation_id)
        REFERENCES apr_delegations(tenant_id, management_resource_set_key, delegation_id),
    CONSTRAINT ck_apr_delegation_event_scope CHECK (
        resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CONSTRAINT ck_apr_delegation_event_action CHECK (
        action IN ('CREATE', 'UPDATE', 'REVOKE', 'CANCEL_SCHEDULED',
                   'KILL_SWITCH', 'REVIEW')),
    CONSTRAINT ck_apr_delegation_event_target CHECK (
        (action = 'KILL_SWITCH' AND delegation_id IS NULL)
        OR (action <> 'KILL_SWITCH' AND delegation_id IS NOT NULL)),
    CONSTRAINT ck_apr_delegation_event_versions CHECK (
        expected_version BETWEEN 0 AND 9007199254740991
        AND resulting_version BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_apr_delegation_event_idempotency CHECK (
        idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CONSTRAINT ck_apr_delegation_event_hash CHECK (
        command_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_apr_delegation_event_states CHECK (
        jsonb_typeof(before_state) = 'object' AND jsonb_typeof(after_state) = 'object'),
    CONSTRAINT ck_apr_delegation_event_reason CHECK (
        btrim(reason) <> '' AND actor_user_id > 0)
);

CREATE INDEX idx_apr_delegation_governance_event_history
    ON apr_delegation_governance_events(
        tenant_id, resource_set_key, delegation_id, occurred_at DESC, event_id);

CREATE TRIGGER trg_apr_delegation_governance_event_append_only
    BEFORE UPDATE OR DELETE ON apr_delegation_governance_events
    FOR EACH ROW EXECUTE FUNCTION reject_apr_policy_automation_evidence_mutation();

COMMENT ON TABLE apr_delegation_control_heads IS
    'Version-fenced management-scope control head for explicit delegation kill-switch commands.';
COMMENT ON TABLE apr_delegation_governance_events IS
    'Append-only administrator delegation command ledger with exact before and after state.';
