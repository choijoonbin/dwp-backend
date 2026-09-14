-- Management/dispatch journals are retained control evidence, not record payload.
-- V24/V28 private deletion permits and historical trigger bodies are untouched.
CREATE TABLE apr_retention_management_commands (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id), actor_user_id BIGINT NOT NULL CHECK(actor_user_id>0),
    route VARCHAR(300) NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'),
    resource_set_key VARCHAR(80) NOT NULL, target_id UUID NOT NULL, result_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,actor_user_id,route,idempotency_key)
);
CREATE TRIGGER trg_retention_management_commands BEFORE UPDATE OR DELETE ON apr_retention_management_commands
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();

CREATE TABLE apr_retention_dispatch_intents (
    intent_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    request_id UUID NOT NULL, resource_set_key VARCHAR(80) NOT NULL, actor_user_id BIGINT NOT NULL CHECK(actor_user_id>0),
    request_version BIGINT NOT NULL, policy_id UUID NOT NULL, policy_version BIGINT NOT NULL,
    hold_version BIGINT NOT NULL, inventory_sha256 CHAR(64) NOT NULL CHECK(inventory_sha256 ~ '^[a-f0-9]{64}$'),
    command_fingerprint CHAR(64) NOT NULL CHECK(command_fingerprint ~ '^[a-f0-9]{64}$'),
    authority_sha256 CHAR(64) NOT NULL CHECK(authority_sha256 ~ '^[a-f0-9]{64}$'),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), state VARCHAR(32) NOT NULL DEFAULT 'QUEUED'
        CHECK(state IN('QUEUED','IRREVERSIBLE','LOCAL_DB_PURGED','BLOCKED')),
    execution_claim_id UUID REFERENCES apr_record_purge_claims(claim_id),
    reason_code VARCHAR(120) NOT NULL DEFAULT 'DEDICATED_EXECUTOR_PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,intent_id), FOREIGN KEY(tenant_id,policy_id) REFERENCES apr_retention_policy_heads(tenant_id,policy_id)
);
CREATE UNIQUE INDEX uq_retention_active_intent ON apr_retention_dispatch_intents(tenant_id,request_id)
    WHERE state<>'BLOCKED';
CREATE FUNCTION preserve_retention_dispatch_source() RETURNS TRIGGER LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP='DELETE' OR (to_jsonb(NEW)-ARRAY['version','state','execution_claim_id','reason_code'])
        IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['version','state','execution_claim_id','reason_code'])
        OR NEW.version<>OLD.version+1 THEN
        RAISE EXCEPTION 'Retention dispatch source is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_retention_dispatch_source BEFORE UPDATE OR DELETE ON apr_retention_dispatch_intents
    FOR EACH ROW EXECUTE FUNCTION preserve_retention_dispatch_source();

CREATE TABLE apr_retention_foreign_requests (
    deletion_request_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, intent_id UUID NOT NULL,
    consumer_service VARCHAR(24) NOT NULL CHECK(consumer_service IN('AUDIT','NOTIFICATION')),
    chunk_index INTEGER NOT NULL CHECK(chunk_index BETWEEN 0 AND 999), chunk_count INTEGER NOT NULL CHECK(chunk_count BETWEEN 1 AND 1000),
    producer_event_ids JSONB NOT NULL CHECK(jsonb_typeof(producer_event_ids)='array' AND jsonb_array_length(producer_event_ids)<=100),
    inventory_sha256 CHAR(64) NOT NULL CHECK(inventory_sha256 ~ '^[a-f0-9]{64}$'),
    request_sha256 CHAR(64) NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,intent_id,consumer_service,chunk_index), CHECK(chunk_index<chunk_count),
    UNIQUE(deletion_request_id,tenant_id,consumer_service,request_sha256),
    FOREIGN KEY(tenant_id,intent_id) REFERENCES apr_retention_dispatch_intents(tenant_id,intent_id)
);
CREATE TABLE apr_retention_foreign_acknowledgements (
    deletion_request_id UUID PRIMARY KEY REFERENCES apr_retention_foreign_requests(deletion_request_id),
    tenant_id BIGINT NOT NULL, consumer_service VARCHAR(24) NOT NULL,
    request_sha256 CHAR(64) NOT NULL, consumer_inventory_sha256 CHAR(64) NOT NULL CHECK(consumer_inventory_sha256 ~ '^[a-f0-9]{64}$'),
    outcome VARCHAR(48) NOT NULL CHECK(outcome='DECLARED_COPIES_DELETED'),
    issuer VARCHAR(200) NOT NULL, key_id VARCHAR(100) NOT NULL, nonce UUID NOT NULL UNIQUE,
    signed_proof_sha256 CHAR(64) NOT NULL CHECK(signed_proof_sha256 ~ '^[a-f0-9]{64}$'),
    verified_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(deletion_request_id,tenant_id,consumer_service,request_sha256)
        REFERENCES apr_retention_foreign_requests(deletion_request_id,tenant_id,consumer_service,request_sha256)
);
CREATE TRIGGER trg_retention_foreign_requests BEFORE UPDATE OR DELETE ON apr_retention_foreign_requests
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();
CREATE TRIGGER trg_retention_foreign_acknowledgements BEFORE UPDATE OR DELETE ON apr_retention_foreign_acknowledgements
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();

CREATE FUNCTION apr_retention_internal.dispatch_record(p_intent UUID,p_version BIGINT,p_authority_sha256 TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE i public.apr_retention_dispatch_intents; r public.apr_requests; p public.apr_retention_policy_heads;
    d public.apr_document_heads; c UUID;
BEGIN
    PERFORM apr_retention_internal.require_executor();
    SELECT * INTO i FROM public.apr_retention_dispatch_intents WHERE intent_id=p_intent FOR UPDATE;
    IF i.intent_id IS NULL THEN RAISE EXCEPTION 'Intent unavailable' USING ERRCODE='42501'; END IF;
    IF i.version<>p_version THEN RAISE EXCEPTION 'Intent version changed' USING ERRCODE='40001'; END IF;
    IF p_authority_sha256 IS NULL OR p_authority_sha256 !~ '^[a-f0-9]{64}$' THEN
        RAISE EXCEPTION 'Fresh owner execution proof required' USING ERRCODE='42501'; END IF;
    IF i.state<>'QUEUED' THEN RETURN i.execution_claim_id; END IF;
    SELECT * INTO r FROM public.apr_requests WHERE tenant_id=i.tenant_id AND request_id=i.request_id FOR UPDATE;
    SELECT * INTO p FROM public.apr_retention_policy_heads WHERE tenant_id=i.tenant_id AND policy_id=i.policy_id FOR SHARE;
    SELECT * INTO d FROM public.apr_document_heads WHERE tenant_id=i.tenant_id AND request_id=i.request_id FOR UPDATE;
    IF r.request_id IS NULL OR r.version IS DISTINCT FROM i.request_version
        OR r.management_resource_set_key IS DISTINCT FROM i.resource_set_key OR p.resource_set_key IS DISTINCT FROM i.resource_set_key
        OR p.version IS DISTINCT FROM i.policy_version OR d.hold_version IS DISTINCT FROM i.hold_version
        OR d.hold_active OR d.pending_hold_id IS NOT NULL THEN
        RAISE EXCEPTION 'Dispatch eligibility changed' USING ERRCODE='40001'; END IF;
    c:=apr_retention_internal.prepare_record(i.tenant_id,i.request_id,i.request_version);
    IF (SELECT inventory_sha256 FROM public.apr_record_purge_claims WHERE claim_id=c) IS DISTINCT FROM i.inventory_sha256 THEN
        RAISE EXCEPTION 'Dispatch inventory changed' USING ERRCODE='40001'; END IF;
    PERFORM apr_retention_internal.claim_record(c,(SELECT version FROM public.apr_record_retention_heads WHERE tenant_id=i.tenant_id AND request_id=i.request_id));
    UPDATE public.apr_retention_dispatch_intents SET state='IRREVERSIBLE',version=version+1,execution_claim_id=c,
        reason_code='CURRENT_AUTHORITY_AND_INVENTORY_VERIFIED' WHERE intent_id=p_intent;
    RETURN c;
END $$;
GRANT SELECT,UPDATE ON apr_retention_dispatch_intents TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.dispatch_record(uuid,bigint,text) OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON FUNCTION apr_retention_internal.dispatch_record(uuid,bigint,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apr_retention_internal.dispatch_record(uuid,bigint,text) TO dwp_approval_retention_executor;
GRANT SELECT ON apr_retention_dispatch_intents TO dwp_approval_retention_executor;
COMMENT ON TABLE apr_retention_dispatch_intents IS 'HIGH-bound durable intent. No scheduler, live grant or automatic runtime activation.';
COMMENT ON TABLE apr_retention_foreign_acknowledgements IS 'Verified owner ACKs for declared copies only; control journals and downloaded client copies are not global erasure claims.';
