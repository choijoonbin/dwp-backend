CREATE TABLE platform_dwaion_proposal_handoffs (
    binding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    person_public_id UUID,
    handoff_id UUID NOT NULL UNIQUE,
    proposal_id UUID NOT NULL,
    action_key VARCHAR(128) NOT NULL,
    handoff_version BIGINT NOT NULL,
    auth_session_id VARCHAR(160) NOT NULL,
    roles VARCHAR(4000) NOT NULL,
    permissions VARCHAR(8000) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    domain_name VARCHAR(32) NOT NULL,
    domain_operation VARCHAR(40) NOT NULL,
    resource_id UUID NOT NULL,
    domain_status VARCHAR(40) NOT NULL,
    domain_version BIGINT NOT NULL,
    domain_committed_at TIMESTAMPTZ NOT NULL,
    delivery_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    next_observation VARCHAR(20) NOT NULL DEFAULT 'HANDED_OFF',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(160),
    locked_until TIMESTAMPTZ,
    receipt_id UUID,
    completed_at TIMESTAMPTZ,
    dead_lettered_at TIMESTAMPTZ,
    failure_code VARCHAR(80),
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_platform_dwaion_domain_resource
        UNIQUE (tenant_id, domain_name, resource_id),
    CONSTRAINT uk_platform_dwaion_binding_tenant UNIQUE (binding_id, tenant_id),
    CONSTRAINT ck_platform_dwaion_action CHECK (
        (action_key='CALENDAR.EVENT.CREATE' AND domain_name='CALENDAR'
            AND domain_operation='EVENT_CREATE'
            AND domain_status IN ('CONFIRMED','TENTATIVE'))
        OR (action_key='MAIL.DRAFT.CREATE' AND domain_name='MAIL'
            AND domain_operation='DRAFT_CREATE' AND domain_status='DRAFT')
        OR (action_key='SERVICE.REQUEST.CREATE' AND domain_name='SERVICE'
            AND domain_operation='REQUEST_CREATE'
            AND domain_status IN ('DRAFT','SUBMITTED'))),
    CONSTRAINT ck_platform_dwaion_version CHECK (
        handoff_version >= 1 AND domain_version >= 0),
    CONSTRAINT ck_platform_dwaion_delivery CHECK (
        delivery_state IN ('PENDING','SENDING','RETRY','COMPLETED','TERMINAL','DEAD')),
    CONSTRAINT ck_platform_dwaion_observation CHECK (
        next_observation IN ('HANDED_OFF','RUNNING','COMPLETED')),
    CONSTRAINT ck_platform_dwaion_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_platform_dwaion_completion CHECK (
        (delivery_state='COMPLETED' AND receipt_id IS NOT NULL AND completed_at IS NOT NULL)
        OR (delivery_state<>'COMPLETED' AND completed_at IS NULL)),
    CONSTRAINT ck_platform_dwaion_dead_letter CHECK (
        (delivery_state='DEAD' AND dead_lettered_at IS NOT NULL AND failure_code IS NOT NULL)
        OR (delivery_state<>'DEAD' AND dead_lettered_at IS NULL))
);

CREATE INDEX idx_platform_dwaion_delivery
    ON platform_dwaion_proposal_handoffs (available_at, created_at, binding_id)
    WHERE delivery_state IN ('PENDING','RETRY','SENDING');

CREATE TABLE platform_dwaion_proposal_handoff_events (
    event_id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    binding_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    delivery_state VARCHAR(20) NOT NULL,
    observation_state VARCHAR(20),
    attempt_count INTEGER NOT NULL,
    safe_error_code VARCHAR(80),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_platform_dwaion_event_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_platform_dwaion_event_delivery CHECK (
        delivery_state IN ('PENDING','SENDING','RETRY','COMPLETED','TERMINAL','DEAD')),
    CONSTRAINT fk_platform_dwaion_event_binding FOREIGN KEY (binding_id, tenant_id)
        REFERENCES platform_dwaion_proposal_handoffs(binding_id, tenant_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_platform_dwaion_event_history
    ON platform_dwaion_proposal_handoff_events (binding_id, event_sequence);

CREATE FUNCTION guard_platform_dwaion_handoff_event_immutable()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'DWAI-ON platform handoff delivery evidence is immutable'
        USING ERRCODE = '23514';
END $$;

CREATE TRIGGER trg_platform_dwaion_handoff_event_immutable
    BEFORE UPDATE OR DELETE ON platform_dwaion_proposal_handoff_events
    FOR EACH ROW EXECUTE FUNCTION guard_platform_dwaion_handoff_event_immutable();

COMMENT ON TABLE platform_dwaion_proposal_handoffs IS
    'Durable owner completion bridge from reviewed DWAI-ON proposals to committed Calendar, Mail, and Service resources.';
COMMENT ON COLUMN platform_dwaion_proposal_handoffs.domain_committed_at IS
    'Database commit evidence captured in the same transaction that creates the owning domain resource.';
COMMENT ON TABLE platform_dwaion_proposal_handoff_events IS
    'Immutable retry, transition, terminal, and receipt evidence for the platform DWAI-ON completion bridge.';
