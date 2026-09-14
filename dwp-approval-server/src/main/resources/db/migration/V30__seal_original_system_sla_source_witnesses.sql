-- Original producer evidence only. Historical missing evidence is never provisioned on reads.
CREATE FUNCTION public.system_sla_witness_canonical_json(value JSONB,depth INTEGER DEFAULT 0) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
DECLARE result TEXT;
BEGIN
    IF depth<0 OR depth>40 THEN RAISE EXCEPTION 'Bounded SYSTEM SLA JSON required' USING ERRCODE='23514'; END IF;
    CASE jsonb_typeof(value)
        WHEN 'object' THEN SELECT '{'||COALESCE(string_agg(to_json(key)::text||':'||
            public.system_sla_witness_canonical_json(item,depth+1),',' ORDER BY key COLLATE "C"),'')||'}'
            INTO result FROM jsonb_each(value) AS entry(key,item);
        WHEN 'array' THEN SELECT '['||COALESCE(string_agg(public.system_sla_witness_canonical_json(item,depth+1),
            ',' ORDER BY ordinal),'')||']' INTO result FROM jsonb_array_elements(value) WITH ORDINALITY AS entry(item,ordinal);
        WHEN 'number' THEN
            IF value::text !~ '^(0|[1-9][0-9]{0,15})$' OR value::numeric>9007199254740991 THEN
                RAISE EXCEPTION 'Exact bounded SYSTEM SLA integer required' USING ERRCODE='23514';
            END IF;
            result:=value::text;
        ELSE result:=value::text;
    END CASE;
    RETURN result;
END $$;
CREATE UNIQUE INDEX uq_apr_system_sla_event_owner ON apr_request_events(tenant_id,request_id,event_id);
CREATE TABLE apr_system_sla_source_witnesses (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    request_id UUID NOT NULL,
    step_id UUID NOT NULL,
    timer_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK(generation>0),
    original_classification VARCHAR(20) NOT NULL CHECK(original_classification IN('INTERNAL','CONFIDENTIAL','RESTRICTED')),
    original_request_version BIGINT NOT NULL CHECK(original_request_version>=0),
    original_stage_version BIGINT NOT NULL CHECK(original_stage_version>=0),
    source_body BYTEA NOT NULL CHECK(octet_length(source_body)<=524288),
    transport_proof TEXT NOT NULL CHECK(octet_length(transport_proof)<=2048),
    auth_attestation TEXT NOT NULL CHECK(octet_length(auth_attestation)<=524288),
    owner_jti UUID NOT NULL UNIQUE,
    transport_jti UUID NOT NULL UNIQUE,
    auth_jti UUID NOT NULL UNIQUE,
    original_source_digest CHAR(64) NOT NULL CHECK(original_source_digest ~ '^[a-f0-9]{64}$'),
    bindings_sha256 CHAR(64) NOT NULL CHECK(bindings_sha256 ~ '^[a-f0-9]{64}$'),
    native_vector_sha256 CHAR(64) NOT NULL CHECK(native_vector_sha256 ~ '^[a-f0-9]{64}$'),
    authority_revision VARCHAR(69) NOT NULL CHECK(authority_revision ~ '^asla-[a-f0-9]{64}$'),
    proof_sha256 CHAR(64) NOT NULL CHECK(proof_sha256 ~ '^[a-f0-9]{64}$'),
    raw_envelope_sha256 CHAR(64) NOT NULL CHECK(raw_envelope_sha256 ~ '^[a-f0-9]{64}$'),
    canonical_envelope_sha256 CHAR(64) NOT NULL CHECK(canonical_envelope_sha256 ~ '^[a-f0-9]{64}$'),
    recipient_snapshot_sha256 CHAR(64) NOT NULL CHECK(recipient_snapshot_sha256 ~ '^[a-f0-9]{64}$'),
    source_pins_sha256 CHAR(64) NOT NULL CHECK(source_pins_sha256 ~ '^[a-f0-9]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,event_id),
    CHECK(owner_jti<>transport_jti AND owner_jti<>auth_jti AND transport_jti<>auth_jti),
    FOREIGN KEY(tenant_id,request_id,event_id) REFERENCES apr_request_events(tenant_id,request_id,event_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES apr_requests(tenant_id,request_id),
    FOREIGN KEY(timer_id) REFERENCES apr_quorum_sla_timers(timer_id)
);
CREATE FUNCTION validate_apr_system_sla_source_witness() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE envelope JSONB; body JSONB; original JSONB; binding JSONB; source JSONB; request public.apr_requests; event public.apr_request_events; timer public.apr_quorum_sla_timers; raw TEXT;
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(TG_RELID::regclass,to_jsonb(OLD)) THEN RETURN OLD; END IF;
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Original SYSTEM SLA source evidence is immutable' USING ERRCODE='23514'; END IF;
    SELECT * INTO request FROM public.apr_requests WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id;
    SELECT * INTO event FROM public.apr_request_events WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id AND event_id=NEW.event_id;
    SELECT * INTO timer FROM public.apr_quorum_sla_timers WHERE timer_id=NEW.timer_id AND tenant_id=NEW.tenant_id AND request_id=NEW.request_id;
    SELECT payload::text INTO raw FROM public.apr_integration_outbox WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id AND event_id=NEW.event_id;
    original:=convert_from(NEW.source_body,'UTF8')::jsonb; binding:=original->'bindings'; source:=binding->'source'; envelope:=raw::jsonb; body:=envelope->'payload';
    IF COALESCE(request.request_id IS NULL OR event.event_id IS NULL OR raw IS NULL OR timer.timer_id IS NULL
       OR (SELECT xmin::text::bigint FROM public.apr_request_events WHERE event_id=NEW.event_id)<>txid_current()%4294967296
       OR event.actor_type<>'SYSTEM' OR event.event_type NOT IN('Approval.Quorum.SlaWarning','Approval.Quorum.SlaBreached')
       OR timer.status<>'CLAIMED' OR timer.event_id IS NOT NULL OR timer.step_id<>NEW.step_id OR timer.generation<>NEW.generation
       OR request.status<>'IN_REVIEW' OR request.deleted_at IS NOT NULL OR request.data_classification<>NEW.original_classification
       OR NEW.created_at>=NEW.expires_at OR NEW.created_at>clock_timestamp() OR NEW.expires_at>NEW.created_at+interval '30 seconds'
       OR (SELECT count(*) FROM jsonb_object_keys(original))<>2 OR NOT(original ?& ARRAY['sourceProof','bindings'])
       OR (binding->>'tenantId')::bigint<>NEW.tenant_id OR binding->>'operation'<>'PRODUCE' OR source->'event'<>'null'::jsonb
       OR source->'request'->>'requestId'<>NEW.request_id::text OR source->'request'->>'dataClassification'<>NEW.original_classification
       OR (source->'request'->>'requestVersion')::bigint<>NEW.original_request_version OR request.version<>NEW.original_request_version
       OR (source->'stage'->>'version')::bigint<>NEW.original_stage_version OR source->'stage'->>'stepId'<>NEW.step_id::text
       OR (source->'stage'->>'generation')::bigint<>NEW.generation OR source->'timer'->>'timerId'<>NEW.timer_id::text
       OR source->'workflow'->>'workflowVersionId'<>request.workflow_version_id::text OR source->'form'->>'formVersionId'<>request.form_version_id::text
       OR body IS DISTINCT FROM event.event_data OR body->>'stepId'<>NEW.step_id::text OR body->>'timerId'<>NEW.timer_id::text
       OR (body->>'generation')::bigint<>NEW.generation OR (body->>'requestVersion')::bigint<>NEW.original_request_version
       OR (body->>'stageVersion')::bigint<>NEW.original_stage_version OR (body->>'leaseEpoch')::bigint<>timer.lease_epoch
       OR body->>'authorityRevision'<>'asla-'||encode(sha256(convert_to(NEW.authority_revision||E'\n'||NEW.native_vector_sha256,'UTF8')),'hex')
       OR binding->>'sourceDigest'<>NEW.original_source_digest
       OR NEW.original_source_digest<>encode(sha256(convert_to(public.system_sla_witness_canonical_json(jsonb_build_object('tenantId',NEW.tenant_id,'operation','PRODUCE','source',source,'audience',binding->'audience')),'UTF8')),'hex')
       OR NEW.bindings_sha256<>encode(sha256(convert_to(public.system_sla_witness_canonical_json(binding),'UTF8')),'hex')
       OR NEW.raw_envelope_sha256<>encode(sha256(convert_to(raw,'UTF8')),'hex')
       OR NEW.canonical_envelope_sha256<>encode(sha256(convert_to(public.system_sla_witness_canonical_json(envelope),'UTF8')),'hex')
       OR body->>'recipientSnapshotSha256'<>NEW.recipient_snapshot_sha256,true) THEN
       RAISE EXCEPTION 'Original SYSTEM SLA source witness binding changed' USING ERRCODE='23514';
    END IF;
    IF jsonb_typeof(original)<>'object' OR jsonb_typeof(binding)<>'object' OR jsonb_typeof(source)<>'object'
       OR (SELECT count(*) FROM jsonb_object_keys(binding))<>6
       OR NOT(binding ?& ARRAY['tenantId','operation','source','audience','sourceDigest','authorityValidUntil'])
       OR (SELECT count(*) FROM jsonb_object_keys(source))<>8
       OR NOT(source ?& ARRAY['request','workflow','form','payload','policy','stage','timer','event'])
       OR jsonb_typeof(original->'sourceProof')<>'string' OR octet_length(original->>'sourceProof') NOT BETWEEN 1 AND 16384
       OR octet_length(NEW.source_body)=0 OR octet_length(NEW.transport_proof)=0 OR octet_length(NEW.auth_attestation)=0
       OR NEW.proof_sha256<>encode(sha256(convert_to(public.system_sla_witness_canonical_json(jsonb_build_object(
          'sourceBodySha256',encode(sha256(NEW.source_body),'hex'),'transportProof',NEW.transport_proof,'authAttestation',NEW.auth_attestation)),'UTF8')),'hex') THEN
       RAISE EXCEPTION 'Original SYSTEM SLA source witness structure changed' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
ALTER FUNCTION validate_apr_system_sla_source_witness() OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON FUNCTION validate_apr_system_sla_source_witness() FROM PUBLIC;
CREATE TRIGGER trg_system_sla_source_witness BEFORE INSERT OR UPDATE OR DELETE ON apr_system_sla_source_witnesses
    FOR EACH ROW EXECUTE FUNCTION validate_apr_system_sla_source_witness();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON apr_system_sla_source_witnesses
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
ALTER TABLE apr_system_sla_source_witnesses ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_system_sla_source_witnesses FORCE ROW LEVEL SECURITY;
CREATE POLICY system_sla_witness_read ON apr_system_sla_source_witnesses FOR SELECT
    USING(tenant_id=COALESCE(NULLIF(current_setting('dwp.approval.system_sla.tenant',true),''),'0')::bigint);
CREATE POLICY system_sla_witness_insert ON apr_system_sla_source_witnesses FOR INSERT
    WITH CHECK(tenant_id=COALESCE(NULLIF(current_setting('dwp.approval.system_sla.tenant',true),''),'0')::bigint);
CREATE POLICY system_sla_witness_retention_read ON apr_system_sla_source_witnesses FOR SELECT TO dwp_approval_retention_owner USING(true);
CREATE POLICY system_sla_witness_retention_delete ON apr_system_sla_source_witnesses FOR DELETE TO dwp_approval_retention_owner
    USING(apr_retention_internal.authorized_delete('public.apr_system_sla_source_witnesses'::regclass,to_jsonb(apr_system_sla_source_witnesses)));
GRANT SELECT,DELETE ON apr_system_sla_source_witnesses TO dwp_approval_retention_owner;
COMMENT ON TABLE apr_system_sla_source_witnesses IS 'Original verified producer source, not fresh authority. Missing historical witnesses deny delivery; exact retention inventory adapter required before purge.';
