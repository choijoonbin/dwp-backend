-- BASE-TFR-SYS-012: principal-private Work Explorer preferences, recents and
-- the shared Platform HRIS command-receipt store. Route labels, owner payloads
-- and cross-service references deliberately remain outside this schema.

CREATE TABLE sys_hris_explorer_preferences (
    explorer_preference_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    principal_public_id UUID NOT NULL,
    preference_revision BIGINT NOT NULL DEFAULT 1,
    route_catalog_revision BIGINT NOT NULL,
    entitlement_revision BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL,
    CONSTRAINT uq_sys_hris_explorer_preference_id
        UNIQUE (tenant_id, principal_public_id, explorer_preference_id),
    CONSTRAINT uq_sys_hris_explorer_preference_principal
        UNIQUE (tenant_id, principal_public_id),
    CONSTRAINT ck_sys_hris_explorer_preference_tenant
        CHECK (tenant_id > 0),
    CONSTRAINT ck_sys_hris_explorer_preference_revisions
        CHECK (preference_revision > 0
            AND route_catalog_revision > 0
            AND entitlement_revision > 0),
    CONSTRAINT ck_sys_hris_explorer_preference_principal
        CHECK (updated_by = principal_public_id)
);

CREATE TABLE sys_hris_explorer_favorites (
    favorite_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    principal_public_id UUID NOT NULL,
    explorer_preference_id UUID NOT NULL,
    route_key VARCHAR(96) NOT NULL,
    position SMALLINT NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sys_hris_explorer_favorite_id
        UNIQUE (tenant_id, principal_public_id, favorite_id),
    CONSTRAINT uq_sys_hris_explorer_favorite_route
        UNIQUE (
            tenant_id, principal_public_id, explorer_preference_id, route_key),
    CONSTRAINT uq_sys_hris_explorer_favorite_position
        UNIQUE (
            tenant_id, principal_public_id, explorer_preference_id, position),
    CONSTRAINT fk_sys_hris_explorer_favorite_preference
        FOREIGN KEY (
            tenant_id, principal_public_id, explorer_preference_id)
        REFERENCES sys_hris_explorer_preferences
            (tenant_id, principal_public_id, explorer_preference_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_sys_hris_explorer_favorite_tenant
        CHECK (tenant_id > 0),
    CONSTRAINT ck_sys_hris_explorer_favorite_route
        CHECK (route_key ~ '^[a-z][a-z0-9.-]{2,95}$'),
    CONSTRAINT ck_sys_hris_explorer_favorite_position
        CHECK (position BETWEEN 1 AND 20)
);

CREATE INDEX ix_sys_hris_explorer_favorite_order
    ON sys_hris_explorer_favorites
        (tenant_id, principal_public_id, explorer_preference_id, position);

CREATE TABLE sys_hris_explorer_recents (
    recent_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    principal_public_id UUID NOT NULL,
    route_key VARCHAR(96) NOT NULL,
    last_visited_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    route_catalog_revision BIGINT NOT NULL,
    entitlement_revision BIGINT NOT NULL,
    CONSTRAINT uq_sys_hris_explorer_recent_id
        UNIQUE (tenant_id, recent_id),
    CONSTRAINT uq_sys_hris_explorer_recent_route
        UNIQUE (tenant_id, principal_public_id, route_key),
    CONSTRAINT ck_sys_hris_explorer_recent_tenant
        CHECK (tenant_id > 0),
    CONSTRAINT ck_sys_hris_explorer_recent_route
        CHECK (route_key ~ '^[a-z][a-z0-9.-]{2,95}$'),
    CONSTRAINT ck_sys_hris_explorer_recent_revisions
        CHECK (route_catalog_revision > 0 AND entitlement_revision > 0),
    CONSTRAINT ck_sys_hris_explorer_recent_ttl
        CHECK (expires_at = last_visited_at + INTERVAL '30 days')
);

CREATE INDEX ix_sys_hris_explorer_recent_lru
    ON sys_hris_explorer_recents
        (tenant_id, principal_public_id, last_visited_at DESC, recent_id DESC);

CREATE INDEX ix_sys_hris_explorer_recent_expiry
    ON sys_hris_explorer_recents (expires_at);

-- Shared by Explorer and later Platform-owned HRIS commands. This is not an
-- Explorer-only receipt table; its immutable seal binds replay to the verified
-- tenant, authenticated subject, authorization context and request digest.
CREATE TABLE sys_hris_command_receipts (
    command_receipt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    command_type VARCHAR(100) NOT NULL,
    originating_action VARCHAR(128) NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    authority_population_scope_digest CHAR(64) NOT NULL,
    field_policy_revision BIGINT NOT NULL,
    authority_field_policy_revision BIGINT NOT NULL,
    purpose_code VARCHAR(64) NOT NULL,
    authorization_revision BIGINT NOT NULL,
    authority_authorization_revision BIGINT NOT NULL,
    origin_route_contract_key VARCHAR(240) NOT NULL,
    origin_context_key VARCHAR(500) NOT NULL,
    origin_context_scope_key VARCHAR(500) NOT NULL,
    origin_decision_revision VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    aggregate_public_id UUID,
    aggregate_revision BIGINT,
    status VARCHAR(24) NOT NULL,
    result_ref VARCHAR(500),
    error_code VARCHAR(80),
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_sys_hris_command_receipt_public
        UNIQUE (tenant_id, public_id),
    CONSTRAINT uq_sys_hris_command_receipt_idempotency
        UNIQUE (
            tenant_id,
            subject_principal_public_id,
            originating_action,
            idempotency_key),
    CONSTRAINT ck_sys_hris_command_receipt_tenant
        CHECK (tenant_id > 0),
    CONSTRAINT ck_sys_hris_command_receipt_names
        CHECK (length(command_type) BETWEEN 1 AND 100
            AND length(originating_action) BETWEEN 1 AND 128
            AND length(purpose_code) BETWEEN 3 AND 64
            AND length(origin_route_contract_key) BETWEEN 1 AND 240
            AND length(origin_context_key) BETWEEN 1 AND 500
            AND length(origin_context_scope_key) BETWEEN 1 AND 500
            AND length(origin_decision_revision) BETWEEN 1 AND 200),
    CONSTRAINT ck_sys_hris_command_receipt_idempotency_key
        CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_sys_hris_command_receipt_revisions
        CHECK (field_policy_revision BETWEEN 1 AND 9007199254740991
            AND authorization_revision BETWEEN 1 AND 9007199254740991
            AND authority_field_policy_revision > 0
            AND authority_authorization_revision > 0
            AND field_policy_revision = CASE
                WHEN (authority_field_policy_revision & 9007199254740991) = 0
                    THEN 1
                ELSE (authority_field_policy_revision & 9007199254740991)
                END
            AND authorization_revision = CASE
                WHEN (authority_authorization_revision & 9007199254740991) = 0
                    THEN 1
                ELSE (authority_authorization_revision & 9007199254740991)
                END
            AND (aggregate_revision IS NULL OR aggregate_revision >= 0)),
    CONSTRAINT ck_sys_hris_command_receipt_population_digest
        CHECK (population_scope_digest ~ '^[a-f0-9]{64}$'
            AND authority_population_scope_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_sys_hris_command_receipt_request_digest
        CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_sys_hris_command_receipt_result_ref
        CHECK (result_ref IS NULL OR length(result_ref) BETWEEN 1 AND 500),
    CONSTRAINT ck_sys_hris_command_receipt_completion_time
        CHECK (completed_at IS NULL OR completed_at >= created_at),
    CONSTRAINT ck_sys_hris_command_receipt_status
        CHECK (status IN (
            'ACCEPTED',
            'RUNNING',
            'SUCCEEDED',
            'REJECTED',
            'FAILED',
            'RESULT_UNKNOWN'))
);

CREATE OR REPLACE FUNCTION sys_hris_guard_command_receipt_seal()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Platform HRIS command receipt cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(
           NEW.public_id,
           NEW.tenant_id,
           NEW.command_type,
           NEW.originating_action,
           NEW.subject_principal_public_id,
           NEW.population_scope_digest,
           NEW.authority_population_scope_digest,
           NEW.field_policy_revision,
           NEW.authority_field_policy_revision,
           NEW.purpose_code,
           NEW.authorization_revision,
           NEW.authority_authorization_revision,
           NEW.origin_route_contract_key,
           NEW.origin_context_key,
           NEW.origin_context_scope_key,
           NEW.origin_decision_revision,
           NEW.idempotency_key,
           NEW.request_digest,
           NEW.correlation_id)
       IS DISTINCT FROM
       ROW(
           OLD.public_id,
           OLD.tenant_id,
           OLD.command_type,
           OLD.originating_action,
           OLD.subject_principal_public_id,
           OLD.population_scope_digest,
           OLD.authority_population_scope_digest,
           OLD.field_policy_revision,
           OLD.authority_field_policy_revision,
           OLD.purpose_code,
           OLD.authorization_revision,
           OLD.authority_authorization_revision,
           OLD.origin_route_contract_key,
           OLD.origin_context_key,
           OLD.origin_context_scope_key,
           OLD.origin_decision_revision,
           OLD.idempotency_key,
           OLD.request_digest,
           OLD.correlation_id) THEN
        RAISE EXCEPTION
            'Platform HRIS command receipt authorization and idempotency seal is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_hris_command_receipt_seal
BEFORE UPDATE OR DELETE ON sys_hris_command_receipts
FOR EACH ROW EXECUTE FUNCTION sys_hris_guard_command_receipt_seal();

-- Runtime transactions bind the gateway-verified tenant and authenticated
-- principal with SET LOCAL before accessing any of the four private tables.
ALTER TABLE sys_hris_explorer_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_hris_explorer_preferences FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_hris_explorer_favorites ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_hris_explorer_favorites FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_hris_explorer_recents ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_hris_explorer_recents FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_hris_command_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_hris_command_receipts FORCE ROW LEVEL SECURITY;

CREATE POLICY sys_hris_explorer_preferences_tenant_principal_policy
    ON sys_hris_explorer_preferences
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
        AND principal_public_id = NULLIF(
            current_setting('dwp.principal_public_id', TRUE), '')::UUID)
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
        AND principal_public_id = NULLIF(
            current_setting('dwp.principal_public_id', TRUE), '')::UUID);

CREATE POLICY sys_hris_explorer_favorites_tenant_principal_policy
    ON sys_hris_explorer_favorites
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
        AND principal_public_id = NULLIF(
            current_setting('dwp.principal_public_id', TRUE), '')::UUID)
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
        AND principal_public_id = NULLIF(
            current_setting('dwp.principal_public_id', TRUE), '')::UUID);

CREATE POLICY sys_hris_explorer_recents_tenant_principal_policy
    ON sys_hris_explorer_recents
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
        AND principal_public_id = NULLIF(
            current_setting('dwp.principal_public_id', TRUE), '')::UUID)
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
        AND principal_public_id = NULLIF(
            current_setting('dwp.principal_public_id', TRUE), '')::UUID);

CREATE POLICY sys_hris_command_receipts_tenant_principal_policy
    ON sys_hris_command_receipts
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
        AND subject_principal_public_id = NULLIF(
            current_setting('dwp.principal_public_id', TRUE), '')::UUID)
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
        AND subject_principal_public_id = NULLIF(
            current_setting('dwp.principal_public_id', TRUE), '')::UUID);
