ALTER TABLE apr_dwaion_proposal_handoffs
    ADD COLUMN dead_lettered_at TIMESTAMPTZ,
    ADD COLUMN failure_code VARCHAR(80);

ALTER TABLE apr_dwaion_proposal_handoffs
    DROP CONSTRAINT ck_apr_dwaion_proposal_delivery;

ALTER TABLE apr_dwaion_proposal_handoffs
    ADD CONSTRAINT ck_apr_dwaion_proposal_delivery CHECK (
        delivery_state IN ('DRAFT', 'PENDING', 'SENDING', 'RETRY', 'COMPLETED', 'TERMINAL', 'DEAD')),
    ADD CONSTRAINT ck_apr_dwaion_proposal_dead_letter CHECK (
        (delivery_state = 'DEAD' AND dead_lettered_at IS NOT NULL AND failure_code IS NOT NULL)
        OR (delivery_state <> 'DEAD' AND dead_lettered_at IS NULL)),
    ADD CONSTRAINT uk_apr_dwaion_proposal_binding_tenant UNIQUE (binding_id, tenant_id);

CREATE TABLE apr_dwaion_proposal_handoff_events (
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
    CONSTRAINT ck_apr_dwaion_proposal_event_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_apr_dwaion_proposal_event_delivery CHECK (
        delivery_state IN ('DRAFT', 'PENDING', 'SENDING', 'RETRY', 'COMPLETED', 'TERMINAL', 'DEAD')),
    CONSTRAINT fk_apr_dwaion_proposal_event_binding FOREIGN KEY (binding_id, tenant_id)
        REFERENCES apr_dwaion_proposal_handoffs(binding_id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX idx_apr_dwaion_proposal_event_history
    ON apr_dwaion_proposal_handoff_events (binding_id, event_sequence);

CREATE FUNCTION guard_apr_dwaion_proposal_handoff_event_immutable()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'DWAI-ON Approval handoff delivery evidence is immutable'
        USING ERRCODE = '23514';
END $$;

CREATE TRIGGER trg_apr_dwaion_proposal_handoff_event_immutable
    BEFORE UPDATE OR DELETE ON apr_dwaion_proposal_handoff_events
    FOR EACH ROW EXECUTE FUNCTION guard_apr_dwaion_proposal_handoff_event_immutable();

COMMENT ON COLUMN apr_dwaion_proposal_handoffs.failure_code IS
    'Bounded, non-sensitive delivery failure classification recorded when the outbox reaches DEAD.';
COMMENT ON TABLE apr_dwaion_proposal_handoff_events IS
    'Immutable operational evidence for DWAI-ON Approval proposal delivery, retry, completion, and dead-letter transitions.';
