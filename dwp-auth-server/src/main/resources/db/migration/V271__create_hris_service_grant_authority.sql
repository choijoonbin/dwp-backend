-- HRM-013 service-grant authority persistence prerequisite.
--
-- This migration creates only the empty, fail-closed Auth control-plane schema
-- and the immutable v62/default-OFF catalog successor. It deliberately creates
-- no tenant grant, key, credential, assignment, enrollment, activation or
-- automatic product-authorization import.

CREATE TABLE auth_hris_service_grant_sets (
    grant_set_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    grant_revision BIGINT NOT NULL,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    supersedes_grant_set_id UUID,
    rotation_readiness_digest CHAR(64),
    incident_evidence_digest CHAR(64),
    grant_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_auth_hris_grant_set_tenant UNIQUE (tenant_id, grant_set_id),
    CONSTRAINT uk_auth_hris_grant_revision UNIQUE (tenant_id, grant_revision),
    CONSTRAINT uk_auth_hris_grant_authority_revision UNIQUE (
        tenant_id, tenant_grant_revision, authorization_revision),
    CONSTRAINT fk_auth_hris_grant_predecessor FOREIGN KEY (
        tenant_id, supersedes_grant_set_id)
        REFERENCES auth_hris_service_grant_sets(tenant_id, grant_set_id),
    CONSTRAINT ck_auth_hris_grant_revisions CHECK (
        grant_revision > 0 AND tenant_grant_revision > 0
        AND authorization_revision > 0),
    CONSTRAINT ck_auth_hris_grant_decision CHECK (
        decision IN ('ADMIT', 'REVOKE')),
    CONSTRAINT ck_auth_hris_grant_chain_shape CHECK (
        (grant_revision = 1 AND decision = 'ADMIT'
            AND supersedes_grant_set_id IS NULL
            AND rotation_readiness_digest IS NULL
            AND incident_evidence_digest IS NULL)
        OR (grant_revision > 1 AND decision = 'ADMIT'
            AND supersedes_grant_set_id IS NOT NULL
            AND rotation_readiness_digest ~ '^[a-f0-9]{64}$'
            AND incident_evidence_digest IS NULL)
        OR (grant_revision > 1 AND decision = 'REVOKE'
            AND supersedes_grant_set_id IS NOT NULL
            AND rotation_readiness_digest IS NULL
            AND incident_evidence_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_auth_hris_grant_digest CHECK (
        grant_digest ~ '^[a-f0-9]{64}$')
);

CREATE INDEX idx_auth_hris_grant_current
    ON auth_hris_service_grant_sets (tenant_id, grant_revision DESC);

CREATE FUNCTION auth_hris_rs256_modulus_valid(p_modulus TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog
AS $$
DECLARE
    decoded BYTEA;
    canonical TEXT;
BEGIN
    IF p_modulus !~ '^[A-Za-z0-9_-]+$'
       OR length(p_modulus) % 4 = 1 THEN
        RETURN FALSE;
    END IF;
    decoded := decode(
        translate(p_modulus, '-_', '+/')
            || repeat('=', (4 - length(p_modulus) % 4) % 4),
        'base64');
    canonical := rtrim(translate(
        replace(encode(decoded, 'base64'), E'\n', ''), '+/', '-_'), '=');
    IF canonical <> p_modulus
       OR octet_length(decoded) < 256
       OR get_byte(decoded, 0) = 0
       OR (octet_length(decoded) = 256 AND get_byte(decoded, 0) < 128) THEN
        RETURN FALSE;
    END IF;
    RETURN TRUE;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END $$;

CREATE TABLE auth_hris_service_grant_bindings (
    grant_binding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    grant_set_id UUID NOT NULL,
    binding_id VARCHAR(40) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    caller_spiffe_id VARCHAR(240) NOT NULL,
    caller_signing_key_id VARCHAR(128) NOT NULL,
    caller_signing_public_jwk JSONB NOT NULL,
    owner_workload_id VARCHAR(120) NOT NULL,
    owner_spiffe_id VARCHAR(240) NOT NULL,
    owner_signing_key_id VARCHAR(128) NOT NULL,
    owner_signing_public_jwk JSONB NOT NULL,
    purpose VARCHAR(128) NOT NULL,
    binding_digest CHAR(64) NOT NULL,
    CONSTRAINT fk_auth_hris_binding_set FOREIGN KEY (tenant_id, grant_set_id)
        REFERENCES auth_hris_service_grant_sets(tenant_id, grant_set_id),
    CONSTRAINT uk_auth_hris_binding_tuple UNIQUE (
        tenant_id, grant_set_id, binding_id, caller_workload_id,
        owner_workload_id, purpose),
    CONSTRAINT ck_auth_hris_binding_code CHECK (
        binding_id ~ '^SVC-[A-Z]{3}-[A-Z]{3}-[0-9]{3}$'
        AND purpose ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_auth_hris_binding_workloads CHECK (
        caller_workload_id ~ '^dwp-[a-z0-9-]{1,96}-server$'
        AND owner_workload_id ~ '^dwp-[a-z0-9-]{1,96}-server$'
        AND caller_workload_id <> owner_workload_id),
    CONSTRAINT ck_auth_hris_binding_spiffe CHECK (
        caller_spiffe_id = 'spiffe://dwp/service/' || caller_workload_id
        AND owner_spiffe_id = 'spiffe://dwp/service/' || owner_workload_id),
    CONSTRAINT ck_auth_hris_binding_key_ids CHECK (
        caller_signing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$'
        AND owner_signing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$'),
    CONSTRAINT ck_auth_hris_binding_caller_jwk CHECK ((
        jsonb_typeof(caller_signing_public_jwk) = 'object'
        AND caller_signing_public_jwk ?& ARRAY['alg','e','kid','kty','n','use']
        AND caller_signing_public_jwk - ARRAY['alg','e','kid','kty','n','use'] = '{}'::jsonb
        AND jsonb_typeof(caller_signing_public_jwk->'alg') = 'string'
        AND jsonb_typeof(caller_signing_public_jwk->'e') = 'string'
        AND jsonb_typeof(caller_signing_public_jwk->'kid') = 'string'
        AND jsonb_typeof(caller_signing_public_jwk->'kty') = 'string'
        AND jsonb_typeof(caller_signing_public_jwk->'n') = 'string'
        AND jsonb_typeof(caller_signing_public_jwk->'use') = 'string'
        AND caller_signing_public_jwk->>'alg' = 'RS256'
        AND caller_signing_public_jwk->>'kty' = 'RSA'
        AND caller_signing_public_jwk->>'use' = 'sig'
        AND caller_signing_public_jwk->>'e' = 'AQAB'
        AND caller_signing_public_jwk->>'kid' = caller_signing_key_id
        AND auth_hris_rs256_modulus_valid(
            caller_signing_public_jwk->>'n')) IS TRUE),
    CONSTRAINT ck_auth_hris_binding_owner_jwk CHECK ((
        jsonb_typeof(owner_signing_public_jwk) = 'object'
        AND owner_signing_public_jwk ?& ARRAY['alg','e','kid','kty','n','use']
        AND owner_signing_public_jwk - ARRAY['alg','e','kid','kty','n','use'] = '{}'::jsonb
        AND jsonb_typeof(owner_signing_public_jwk->'alg') = 'string'
        AND jsonb_typeof(owner_signing_public_jwk->'e') = 'string'
        AND jsonb_typeof(owner_signing_public_jwk->'kid') = 'string'
        AND jsonb_typeof(owner_signing_public_jwk->'kty') = 'string'
        AND jsonb_typeof(owner_signing_public_jwk->'n') = 'string'
        AND jsonb_typeof(owner_signing_public_jwk->'use') = 'string'
        AND owner_signing_public_jwk->>'alg' = 'RS256'
        AND owner_signing_public_jwk->>'kty' = 'RSA'
        AND owner_signing_public_jwk->>'use' = 'sig'
        AND owner_signing_public_jwk->>'e' = 'AQAB'
        AND owner_signing_public_jwk->>'kid' = owner_signing_key_id
        AND auth_hris_rs256_modulus_valid(
            owner_signing_public_jwk->>'n')) IS TRUE),
    CONSTRAINT ck_auth_hris_binding_digest CHECK (
        binding_digest ~ '^[a-f0-9]{64}$')
);

CREATE INDEX idx_auth_hris_binding_resolve
    ON auth_hris_service_grant_bindings (
        tenant_id, binding_id, caller_workload_id, owner_workload_id,
        purpose, grant_set_id);

CREATE TABLE auth_hris_service_authority_proof_replays (
    proof_replay_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    proof_scope VARCHAR(32) NOT NULL,
    binding_id VARCHAR(40) NOT NULL,
    requesting_workload_id VARCHAR(120) NOT NULL,
    token_kind VARCHAR(16) NOT NULL,
    token_digest CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_auth_hris_proof_digest UNIQUE (tenant_id, token_digest),
    CONSTRAINT ck_auth_hris_proof_scope CHECK (
        proof_scope IN (
            'AUTHORITY_RESOLVE', 'REQUESTER_IDENTITY', 'ROTATION_READINESS')),
    CONSTRAINT ck_auth_hris_proof_binding CHECK (
        binding_id ~ '^SVC-[A-Z]{3}-[A-Z]{3}-[0-9]{3}$'
        AND requesting_workload_id ~ '^dwp-[a-z0-9-]{1,96}-server$'),
    CONSTRAINT ck_auth_hris_proof_kind CHECK (
        token_kind IN ('JTI', 'REQUEST_NONCE')),
    CONSTRAINT ck_auth_hris_proof_digest CHECK (
        token_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_auth_hris_proof_window CHECK (
        expires_at > reserved_at
        AND expires_at <= reserved_at + INTERVAL '300 seconds'),
    CONSTRAINT ck_auth_hris_proof_profile CHECK (
        (proof_scope = 'AUTHORITY_RESOLVE' AND (
            (binding_id = 'SVC-AUT-HRM-011'
                AND requesting_workload_id = 'dwp-people-server')
            OR (binding_id = 'SVC-AUT-HRM-012'
                AND requesting_workload_id = 'dwp-approval-server')
            OR (binding_id = 'SVC-AUT-HRM-015'
                AND requesting_workload_id = 'dwp-time-server')
            OR (binding_id = 'SVC-AUT-HRM-016'
                AND requesting_workload_id = 'dwp-payroll-server')))
        OR (proof_scope = 'REQUESTER_IDENTITY'
            AND binding_id = 'SVC-AUT-HRM-013'
            AND requesting_workload_id = 'dwp-approval-server')
        OR proof_scope = 'ROTATION_READINESS')
);

CREATE INDEX idx_auth_hris_proof_expiry
    ON auth_hris_service_authority_proof_replays (tenant_id, expires_at);

CREATE FUNCTION enforce_auth_hris_proof_replay_window()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    NEW.reserved_at := transaction_timestamp();
    IF NEW.expires_at <= NEW.reserved_at
       OR NEW.expires_at > NEW.reserved_at + INTERVAL '300 seconds' THEN
        RAISE EXCEPTION 'HRM-013 Auth proof expiry must be within 300 seconds of DB reservation time'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_auth_hris_proof_window
    BEFORE INSERT ON auth_hris_service_authority_proof_replays
    FOR EACH ROW EXECUTE FUNCTION enforce_auth_hris_proof_replay_window();

CREATE TABLE auth_hris_service_grant_quiesce_fences (
    quiesce_fence_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    old_grant_set_id UUID NOT NULL,
    binding_id VARCHAR(40) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    owner_workload_id VARCHAR(120) NOT NULL,
    purpose VARCHAR(128) NOT NULL,
    quiesce_revision BIGINT NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL,
    supersedes_quiesce_fence_id UUID,
    effective_at TIMESTAMPTZ NOT NULL,
    successor_command_correlation_id UUID NOT NULL,
    fence_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_auth_hris_fence_scope UNIQUE (tenant_id, quiesce_fence_id),
    CONSTRAINT uk_auth_hris_fence_tuple_revision UNIQUE (
        tenant_id, binding_id, caller_workload_id, owner_workload_id,
        purpose, quiesce_revision),
    CONSTRAINT uk_auth_hris_fence_binding UNIQUE (
        tenant_id, quiesce_fence_id, old_grant_set_id, binding_id,
        caller_workload_id, owner_workload_id, purpose),
    CONSTRAINT fk_auth_hris_fence_grant FOREIGN KEY (
        tenant_id, old_grant_set_id)
        REFERENCES auth_hris_service_grant_sets(tenant_id, grant_set_id),
    CONSTRAINT fk_auth_hris_fence_predecessor FOREIGN KEY (
        tenant_id, supersedes_quiesce_fence_id)
        REFERENCES auth_hris_service_grant_quiesce_fences(
            tenant_id, quiesce_fence_id),
    CONSTRAINT ck_auth_hris_fence_revision CHECK (quiesce_revision > 0),
    CONSTRAINT ck_auth_hris_fence_state CHECK (
        lifecycle_state IN ('QUIESCING', 'CUTOVER', 'ABORTED')),
    CONSTRAINT ck_auth_hris_fence_chain_shape CHECK (
        (quiesce_revision = 1 AND lifecycle_state = 'QUIESCING'
            AND supersedes_quiesce_fence_id IS NULL)
        OR (quiesce_revision > 1
            AND supersedes_quiesce_fence_id IS NOT NULL)),
    CONSTRAINT ck_auth_hris_fence_digest CHECK (
        fence_digest ~ '^[a-f0-9]{64}$')
);

CREATE INDEX idx_auth_hris_fence_current
    ON auth_hris_service_grant_quiesce_fences (
        tenant_id, binding_id, caller_workload_id, owner_workload_id,
        purpose, quiesce_revision DESC);

CREATE TABLE auth_hris_service_grant_drain_activities (
    drain_activity_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    quiesce_fence_id UUID NOT NULL,
    old_grant_set_id UUID NOT NULL,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    binding_id VARCHAR(40) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    owner_workload_id VARCHAR(120) NOT NULL,
    purpose VARCHAR(128) NOT NULL,
    attesting_service_role VARCHAR(16) NOT NULL,
    service_workload_id VARCHAR(120) NOT NULL,
    activity_revision BIGINT NOT NULL,
    continuation_kind VARCHAR(24) NOT NULL,
    root_work_item_id UUID NOT NULL,
    root_created_at TIMESTAMPTZ NOT NULL,
    root_payload_sha256 CHAR(64) NOT NULL,
    continuation_event_id UUID NOT NULL,
    continuation_revision BIGINT NOT NULL,
    continuation_payload_sha256 CHAR(64) NOT NULL,
    resolver_proof_sha256 CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_auth_hris_drain_stream_revision UNIQUE (
        tenant_id, quiesce_fence_id, attesting_service_role,
        activity_revision),
    CONSTRAINT fk_auth_hris_drain_fence FOREIGN KEY (
        tenant_id, quiesce_fence_id, old_grant_set_id, binding_id,
        caller_workload_id, owner_workload_id, purpose)
        REFERENCES auth_hris_service_grant_quiesce_fences(
            tenant_id, quiesce_fence_id, old_grant_set_id, binding_id,
            caller_workload_id, owner_workload_id, purpose),
    CONSTRAINT ck_auth_hris_drain_revisions CHECK (
        tenant_grant_revision > 0 AND authorization_revision > 0
        AND activity_revision > 0 AND continuation_revision >= 0),
    CONSTRAINT ck_auth_hris_drain_role CHECK (
        (attesting_service_role = 'CALLER'
            AND service_workload_id = caller_workload_id)
        OR (attesting_service_role = 'OWNER'
            AND service_workload_id = owner_workload_id)),
    CONSTRAINT ck_auth_hris_drain_kind CHECK (
        continuation_kind IN (
            'ROOT_DELIVERY', 'DEPENDENT_READ', 'TERMINAL_CHILD')),
    CONSTRAINT ck_auth_hris_drain_digests CHECK (
        root_payload_sha256 ~ '^[a-f0-9]{64}$'
        AND continuation_payload_sha256 ~ '^[a-f0-9]{64}$'
        AND resolver_proof_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_auth_hris_drain_time CHECK (root_created_at <= recorded_at)
);

CREATE INDEX idx_auth_hris_drain_current
    ON auth_hris_service_grant_drain_activities (
        tenant_id, quiesce_fence_id, attesting_service_role,
        activity_revision DESC);

CREATE FUNCTION enforce_auth_hris_grant_chain()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    prior_id UUID;
    prior_revision BIGINT;
    prior_tenant_revision BIGINT;
    prior_authorization_revision BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'DWP_AUTH_HRM013_GRANT_V1:' || NEW.tenant_id::text, 0));

    SELECT grant_set_id, grant_revision, tenant_grant_revision,
           authorization_revision
      INTO prior_id, prior_revision, prior_tenant_revision,
           prior_authorization_revision
      FROM public.auth_hris_service_grant_sets
     WHERE tenant_id = NEW.tenant_id
     ORDER BY grant_revision DESC
     LIMIT 1
     FOR SHARE;

    IF prior_id IS NULL THEN
        IF NEW.grant_revision <> 1 OR NEW.supersedes_grant_set_id IS NOT NULL THEN
            RAISE EXCEPTION 'The first Auth HRM-013 grant set must be revision one'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.grant_revision <> prior_revision + 1
       OR NEW.supersedes_grant_set_id IS DISTINCT FROM prior_id
       OR NEW.tenant_grant_revision <= prior_tenant_revision
       OR NEW.authorization_revision <= prior_authorization_revision THEN
        RAISE EXCEPTION 'The Auth HRM-013 grant set must name and advance the immediate predecessor'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_auth_hris_grant_chain
    BEFORE INSERT ON auth_hris_service_grant_sets
    FOR EACH ROW EXECUTE FUNCTION enforce_auth_hris_grant_chain();

CREATE FUNCTION reject_auth_hris_authority_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'HRM-013 Auth authority rows are append-only'
        USING ERRCODE = '23514';
END $$;

CREATE TRIGGER trg_auth_hris_grant_immutable
    BEFORE UPDATE OR DELETE ON auth_hris_service_grant_sets
    FOR EACH ROW EXECUTE FUNCTION reject_auth_hris_authority_mutation();
CREATE TRIGGER trg_auth_hris_binding_immutable
    BEFORE UPDATE OR DELETE ON auth_hris_service_grant_bindings
    FOR EACH ROW EXECUTE FUNCTION reject_auth_hris_authority_mutation();
CREATE TRIGGER trg_auth_hris_fence_immutable
    BEFORE UPDATE OR DELETE ON auth_hris_service_grant_quiesce_fences
    FOR EACH ROW EXECUTE FUNCTION reject_auth_hris_authority_mutation();
CREATE TRIGGER trg_auth_hris_drain_immutable
    BEFORE UPDATE OR DELETE ON auth_hris_service_grant_drain_activities
    FOR EACH ROW EXECUTE FUNCTION reject_auth_hris_authority_mutation();

CREATE FUNCTION guard_auth_hris_proof_replay_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'HRM-013 Auth proof reservations cannot be updated'
            USING ERRCODE = '23514';
    END IF;
    IF transaction_timestamp() < OLD.expires_at + INTERVAL '5 seconds' THEN
        RAISE EXCEPTION 'HRM-013 Auth proof reservation retention has not elapsed'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END $$;

CREATE TRIGGER trg_auth_hris_proof_retained
    BEFORE UPDATE OR DELETE ON auth_hris_service_authority_proof_replays
    FOR EACH ROW EXECUTE FUNCTION guard_auth_hris_proof_replay_mutation();

CREATE FUNCTION enforce_auth_hris_binding_closure()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    binding_count INTEGER;
    waiver_count INTEGER;
    dispatch_time BOOLEAN;
    callback_time BOOLEAN;
    dispatch_payroll BOOLEAN;
    callback_payroll BOOLEAN;
    dispatch_identity BOOLEAN;
    callback_identity BOOLEAN;
BEGIN
    SELECT count(*) INTO binding_count
      FROM public.auth_hris_service_grant_bindings
     WHERE tenant_id = NEW.tenant_id AND grant_set_id = NEW.grant_set_id;
    IF binding_count = 0 THEN
        RAISE EXCEPTION 'An Auth HRM-013 grant set must contain a closed binding set'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO waiver_count
      FROM public.auth_hris_service_grant_bindings
     WHERE tenant_id = NEW.tenant_id AND grant_set_id = NEW.grant_set_id
       AND binding_id IN ('SVC-PEP-APR-002', 'SVC-PEP-HRM-010', 'SVC-AUT-HRM-013');
    IF waiver_count NOT IN (0, 3) OR (waiver_count = 3 AND NOT (
        EXISTS (SELECT 1 FROM public.auth_hris_service_grant_bindings
                 WHERE tenant_id = NEW.tenant_id AND grant_set_id = NEW.grant_set_id
                   AND binding_id = 'SVC-PEP-APR-002'
                   AND caller_workload_id = 'dwp-people-server'
                   AND owner_workload_id = 'dwp-approval-server'
                   AND purpose = 'HRIS_SEPARATION_TASK_WAIVER_REQUEST')
        AND EXISTS (SELECT 1 FROM public.auth_hris_service_grant_bindings
                 WHERE tenant_id = NEW.tenant_id AND grant_set_id = NEW.grant_set_id
                   AND binding_id = 'SVC-PEP-HRM-010'
                   AND caller_workload_id = 'dwp-approval-server'
                   AND owner_workload_id = 'dwp-people-server'
                   AND purpose = 'HRIS_SEPARATION_TASK_WAIVER_DECISION')
        AND EXISTS (SELECT 1 FROM public.auth_hris_service_grant_bindings
                 WHERE tenant_id = NEW.tenant_id AND grant_set_id = NEW.grant_set_id
                   AND binding_id = 'SVC-AUT-HRM-013'
                   AND caller_workload_id = 'dwp-approval-server'
                   AND owner_workload_id = 'dwp-auth-server'
                   AND purpose = 'HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE'))) THEN
        RAISE EXCEPTION 'The HRM-013 waiver dependency group must be exact and atomic'
            USING ERRCODE = '23514';
    END IF;

    SELECT
        bool_or(binding_id = 'SVC-PEP-TIM-004'
            AND caller_workload_id = 'dwp-people-server'
            AND owner_workload_id = 'dwp-time-server'
            AND purpose = 'HRIS_SEPARATION_TIME_TASK_DISPATCH'),
        bool_or(binding_id = 'SVC-PEP-HRM-008'
            AND caller_workload_id = 'dwp-time-server'
            AND owner_workload_id = 'dwp-people-server'
            AND purpose = 'HRIS_SEPARATION_TIME_TASK_RECEIPT'),
        bool_or(binding_id = 'SVC-PEP-PAY-002'
            AND caller_workload_id = 'dwp-people-server'
            AND owner_workload_id = 'dwp-payroll-server'
            AND purpose = 'HRIS_SEPARATION_PAYROLL_TASK_DISPATCH'),
        bool_or(binding_id = 'SVC-PEP-HRM-008'
            AND caller_workload_id = 'dwp-payroll-server'
            AND owner_workload_id = 'dwp-people-server'
            AND purpose = 'HRIS_SEPARATION_PAYROLL_TASK_RECEIPT'),
        bool_or(binding_id = 'SVC-AUT-HRM-014'
            AND caller_workload_id = 'dwp-people-server'
            AND owner_workload_id = 'dwp-auth-server'
            AND purpose = 'HRIS_SEPARATION_IDENTITY_TASK_DISPATCH'),
        bool_or(binding_id = 'SVC-PEP-HRM-008'
            AND caller_workload_id = 'dwp-auth-server'
            AND owner_workload_id = 'dwp-people-server'
            AND purpose = 'HRIS_SEPARATION_IDENTITY_TASK_RECEIPT')
      INTO dispatch_time, callback_time, dispatch_payroll, callback_payroll,
           dispatch_identity, callback_identity
      FROM public.auth_hris_service_grant_bindings
     WHERE tenant_id = NEW.tenant_id AND grant_set_id = NEW.grant_set_id;
    IF dispatch_time IS DISTINCT FROM callback_time
       OR dispatch_payroll IS DISTINCT FROM callback_payroll
       OR dispatch_identity IS DISTINCT FROM callback_identity THEN
        RAISE EXCEPTION 'HRM-013 task dispatch and callback bindings must rotate as exact pairs'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_auth_hris_grant_closed
    AFTER INSERT ON auth_hris_service_grant_sets
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_auth_hris_binding_closure();
CREATE CONSTRAINT TRIGGER trg_auth_hris_binding_closed
    AFTER INSERT ON auth_hris_service_grant_bindings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_auth_hris_binding_closure();

CREATE FUNCTION enforce_auth_hris_fence_chain()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    prior_id UUID;
    prior_old_grant_set_id UUID;
    prior_revision BIGINT;
    prior_state TEXT;
    prior_effective_at TIMESTAMPTZ;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'DWP_AUTH_HRM013_FENCE_V1:' || NEW.tenant_id::text || ':'
        || NEW.binding_id || ':' || NEW.caller_workload_id || ':'
        || NEW.owner_workload_id || ':' || NEW.purpose, 0));

    IF NOT EXISTS (
        SELECT 1 FROM public.auth_hris_service_grant_bindings binding
         WHERE binding.tenant_id = NEW.tenant_id
           AND binding.grant_set_id = NEW.old_grant_set_id
           AND binding.binding_id = NEW.binding_id
           AND binding.caller_workload_id = NEW.caller_workload_id
           AND binding.owner_workload_id = NEW.owner_workload_id
           AND binding.purpose = NEW.purpose) THEN
        RAISE EXCEPTION 'The quiesce fence tuple is not a member of the old grant set'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.lifecycle_state = 'QUIESCING' AND NOT EXISTS (
        SELECT 1
          FROM public.auth_hris_service_grant_sets current_grant
         WHERE current_grant.tenant_id = NEW.tenant_id
           AND current_grant.grant_set_id = NEW.old_grant_set_id
           AND current_grant.decision = 'ADMIT'
           AND NOT EXISTS (
               SELECT 1
                 FROM public.auth_hris_service_grant_sets newer_grant
                WHERE newer_grant.tenant_id = current_grant.tenant_id
                  AND newer_grant.grant_revision > current_grant.grant_revision)) THEN
        RAISE EXCEPTION 'A new HRM-013 quiesce cycle requires the current ADMIT grant set'
            USING ERRCODE = '23514';
    END IF;

    SELECT quiesce_fence_id, old_grant_set_id, quiesce_revision,
           lifecycle_state, effective_at
      INTO prior_id, prior_old_grant_set_id, prior_revision,
           prior_state, prior_effective_at
      FROM public.auth_hris_service_grant_quiesce_fences
     WHERE tenant_id = NEW.tenant_id
       AND binding_id = NEW.binding_id
       AND caller_workload_id = NEW.caller_workload_id
       AND owner_workload_id = NEW.owner_workload_id
       AND purpose = NEW.purpose
     ORDER BY quiesce_revision DESC
     LIMIT 1
     FOR SHARE;

    IF prior_id IS NULL THEN
        IF NEW.quiesce_revision <> 1 OR NEW.lifecycle_state <> 'QUIESCING'
           OR NEW.supersedes_quiesce_fence_id IS NOT NULL THEN
            RAISE EXCEPTION 'The first HRM-013 quiesce fence must be QUIESCING revision one'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.quiesce_revision <> prior_revision + 1
       OR NEW.supersedes_quiesce_fence_id IS DISTINCT FROM prior_id
       OR (prior_state = 'QUIESCING'
           AND NEW.lifecycle_state NOT IN ('CUTOVER', 'ABORTED'))
       OR (prior_state IN ('ABORTED', 'CUTOVER')
           AND NEW.lifecycle_state <> 'QUIESCING')
       OR (NEW.lifecycle_state IN ('CUTOVER', 'ABORTED')
           AND (NEW.old_grant_set_id IS DISTINCT FROM prior_old_grant_set_id
                OR NEW.effective_at IS DISTINCT FROM prior_effective_at)) THEN
        RAISE EXCEPTION 'The HRM-013 quiesce fence transition is invalid'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_auth_hris_fence_chain
    BEFORE INSERT ON auth_hris_service_grant_quiesce_fences
    FOR EACH ROW EXECUTE FUNCTION enforce_auth_hris_fence_chain();

CREATE FUNCTION enforce_auth_hris_drain_chain()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    current_fence_id UUID;
    current_old_grant_set_id UUID;
    current_revision BIGINT;
    fence_effective_at TIMESTAMPTZ;
    fence_state TEXT;
    grant_tenant_revision BIGINT;
    grant_authorization_revision BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'DWP_AUTH_HRM013_FENCE_V1:' || NEW.tenant_id::text || ':'
        || NEW.binding_id || ':' || NEW.caller_workload_id || ':'
        || NEW.owner_workload_id || ':' || NEW.purpose, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'DWP_AUTH_HRM013_DRAIN_V1:' || NEW.tenant_id::text || ':'
        || NEW.quiesce_fence_id::text || ':' || NEW.attesting_service_role, 0));

    NEW.recorded_at := transaction_timestamp();

    SELECT fence.quiesce_fence_id, fence.old_grant_set_id,
           fence.effective_at, fence.lifecycle_state,
           grant_set.tenant_grant_revision, grant_set.authorization_revision
      INTO current_fence_id, current_old_grant_set_id,
           fence_effective_at, fence_state,
           grant_tenant_revision, grant_authorization_revision
      FROM public.auth_hris_service_grant_quiesce_fences fence
      JOIN public.auth_hris_service_grant_sets grant_set
        ON grant_set.tenant_id = fence.tenant_id
       AND grant_set.grant_set_id = fence.old_grant_set_id
     WHERE fence.tenant_id = NEW.tenant_id
       AND fence.binding_id = NEW.binding_id
       AND fence.caller_workload_id = NEW.caller_workload_id
       AND fence.owner_workload_id = NEW.owner_workload_id
       AND fence.purpose = NEW.purpose
     ORDER BY fence.quiesce_revision DESC
     LIMIT 1
     FOR SHARE OF fence, grant_set;
    IF current_fence_id IS DISTINCT FROM NEW.quiesce_fence_id
       OR current_old_grant_set_id IS DISTINCT FROM NEW.old_grant_set_id
       OR fence_state IS DISTINCT FROM 'QUIESCING'
       OR NEW.tenant_grant_revision IS DISTINCT FROM grant_tenant_revision
       OR NEW.authorization_revision IS DISTINCT FROM grant_authorization_revision
       OR NEW.recorded_at < fence_effective_at
       OR NEW.root_created_at > fence_effective_at THEN
        RAISE EXCEPTION 'The HRM-013 drain activity does not match the current quiesce fence'
            USING ERRCODE = '23514';
    END IF;

    SELECT max(activity_revision) INTO current_revision
      FROM public.auth_hris_service_grant_drain_activities
     WHERE tenant_id = NEW.tenant_id
       AND quiesce_fence_id = NEW.quiesce_fence_id
       AND attesting_service_role = NEW.attesting_service_role;
    IF NEW.activity_revision <> COALESCE(current_revision, 0) + 1 THEN
        RAISE EXCEPTION 'The HRM-013 drain activity revision must be gap-free'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_auth_hris_drain_chain
    BEFORE INSERT ON auth_hris_service_grant_drain_activities
    FOR EACH ROW EXECUTE FUNCTION enforce_auth_hris_drain_chain();

ALTER TABLE auth_hris_service_grant_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hris_service_grant_sets FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hris_service_grant_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hris_service_grant_bindings FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hris_service_authority_proof_replays ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hris_service_authority_proof_replays FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hris_service_grant_quiesce_fences ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hris_service_grant_quiesce_fences FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hris_service_grant_drain_activities ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hris_service_grant_drain_activities FORCE ROW LEVEL SECURITY;

CREATE POLICY auth_hris_grant_set_tenant_scope
    ON auth_hris_service_grant_sets
    USING (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hris_binding_tenant_scope
    ON auth_hris_service_grant_bindings
    USING (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hris_proof_tenant_scope
    ON auth_hris_service_authority_proof_replays
    USING (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hris_fence_tenant_scope
    ON auth_hris_service_grant_quiesce_fences
    USING (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hris_drain_tenant_scope
    ON auth_hris_service_grant_drain_activities
    USING (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);

COMMENT ON TABLE auth_hris_service_grant_sets IS
    'Append-only tenant HRM-013 service-grant authority-set revisions; V271 seeds no rows.';
COMMENT ON TABLE auth_hris_service_grant_bindings IS
    'Immutable exact workload, SPIFFE and public-JWK members of an HRM-013 grant set.';
COMMENT ON TABLE auth_hris_service_authority_proof_replays IS
    'Digest-only JTI and requestNonce reservations retained through proof expiry plus five seconds.';
COMMENT ON TABLE auth_hris_service_grant_quiesce_fences IS
    'Append-only planned-cutover fences for exact HRM-013 service binding tuples.';
COMMENT ON TABLE auth_hris_service_grant_drain_activities IS
    'Gap-free per-role DRAIN_EXISTING admission activity under a current quiesce fence.';

INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces', 62,
    'cb370fd02a896452a64187fca8afb8bb3e3d281e490fba02e76f7a3c0d7f9770',
    'product-surfaces-v1.bundle-v62.generated.json', 'DRAFT', FALSE)
ON CONFLICT (bundle_key, version) DO NOTHING;

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES (
    'HRIS_HRM_EXIT_RETURN_WORKBENCH', 2, 'hcm', 'APP.HCM', 62, 'OFF', 'HRM')
ON CONFLICT (feature_key, policy_version) DO NOTHING;

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key,
    app_resource_key, minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.operations.exit-return.page',
     'HRIS_HRM_EXIT_RETURN_WORKBENCH', 2,
     'hcm', 'APP.HCM', 62, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.exit-return.data',
     'HRIS_HRM_EXIT_RETURN_WORKBENCH', 2,
     'hcm', 'APP.HCM', 62, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.separation-case.action',
     'HRIS_HRM_EXIT_RETURN_WORKBENCH', 2,
     'hcm', 'APP.HCM', 62, 'ACTION', 'PRODUCT', 'hcm.operations')
ON CONFLICT (route_contract_key, feature_key, policy_version) DO NOTHING;

DO $$
BEGIN
    IF (SELECT count(*) FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces' AND version = 62) <> 1
       OR NOT EXISTS (
           SELECT 1 FROM auth_product_authorization_seed_release
            WHERE bundle_key = 'product-surfaces' AND version = 62
              AND checksum =
                  'cb370fd02a896452a64187fca8afb8bb3e3d281e490fba02e76f7a3c0d7f9770'
              AND auth_seed_artifact =
                  'product-surfaces-v1.bundle-v62.generated.json'
              AND intended_bundle_status = 'DRAFT'
              AND automatic_import_enabled = FALSE) THEN
        RAISE EXCEPTION 'V271 conflict: v62 seed release differs from the sealed tuple';
    END IF;

    IF (SELECT count(*) FROM sys_product_feature_activation_policies
         WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH') <> 2
       OR NOT EXISTS (
           SELECT 1 FROM sys_product_feature_activation_policies
            WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH'
              AND policy_version = 1 AND product_key = 'hcm'
              AND app_resource_key = 'APP.HCM'
              AND minimum_bundle_version = 61 AND baseline_state = 'OFF'
              AND lifecycle_state = 'PUBLISHED' AND owner_key = 'HRM')
       OR NOT EXISTS (
           SELECT 1 FROM sys_product_feature_activation_policies
            WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH'
              AND policy_version = 2 AND product_key = 'hcm'
              AND app_resource_key = 'APP.HCM'
              AND minimum_bundle_version = 62 AND baseline_state = 'OFF'
              AND lifecycle_state = 'PUBLISHED' AND owner_key = 'HRM') THEN
        RAISE EXCEPTION 'V271 conflict: exit-return feature-policy lineage differs from the sealed tuples';
    END IF;

    IF (SELECT count(*) FROM sys_product_feature_route_owners
         WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH') <> 3
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.operations.exit-return.page'),
                 ('route.hcm.operations.exit-return.data'),
                 ('route.hcm.operations.separation-case.action')
             ) AS expected(route_contract_key)
             LEFT JOIN sys_product_feature_route_owners owner_row
               ON owner_row.route_contract_key = expected.route_contract_key
              AND owner_row.feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH'
            WHERE owner_row.route_contract_key IS NULL) THEN
        RAISE EXCEPTION 'V271 conflict: V270 route-owner snapshot differs from the sealed tuples';
    END IF;

    IF (SELECT count(*) FROM sys_product_feature_route_bindings
         WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH') <> 6
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.operations.exit-return.page', 'PAGE'),
                 ('route.hcm.operations.exit-return.data', 'DATA'),
                 ('route.hcm.operations.separation-case.action', 'ACTION')
             ) AS expected(route_contract_key, route_kind)
             LEFT JOIN sys_product_feature_route_bindings binding
               ON binding.route_contract_key = expected.route_contract_key
              AND binding.feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH'
              AND binding.policy_version = 2
              AND binding.product_key = 'hcm'
              AND binding.app_resource_key = 'APP.HCM'
              AND binding.minimum_bundle_version = 62
              AND binding.route_kind = expected.route_kind
              AND binding.subject_type = 'PRODUCT'
              AND binding.surface_key = 'hcm.operations'
              AND binding.lifecycle_state = 'ACTIVE'
            WHERE binding.route_contract_key IS NULL) THEN
        RAISE EXCEPTION 'V271 conflict: policy-v2 route bindings differ from the sealed tuples';
    END IF;

    IF EXISTS (SELECT 1 FROM auth_hris_service_grant_sets)
       OR EXISTS (SELECT 1 FROM auth_hris_service_grant_bindings)
       OR EXISTS (SELECT 1 FROM auth_hris_service_authority_proof_replays)
       OR EXISTS (SELECT 1 FROM auth_hris_service_grant_quiesce_fences)
       OR EXISTS (SELECT 1 FROM auth_hris_service_grant_drain_activities)
       OR EXISTS (
           SELECT 1 FROM auth_product_feature_activation_changes
            WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH')
       OR EXISTS (
           SELECT 1 FROM com_tenant_feature_enrollment_changes
            WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH')
       OR EXISTS (
           SELECT 1 FROM com_admin_scoped_duty_assignments
            WHERE duty_code = 'HRIS_DUTY_SEPARATION_MAKER')
       OR EXISTS (
           SELECT 1 FROM com_admin_resource_set_members
            WHERE resource_key = 'ACTION.HRIS_SEPARATION_CASE')
       OR EXISTS (
           SELECT 1
             FROM com_principal_resource_grants grant_record
             JOIN com_resources resource
               ON resource.resource_id = grant_record.resource_id
            WHERE resource.key = 'ACTION.HRIS_SEPARATION_CASE') THEN
        RAISE EXCEPTION 'V271 conflict: migration must retain zero derived or service authority';
    END IF;
END;
$$;
