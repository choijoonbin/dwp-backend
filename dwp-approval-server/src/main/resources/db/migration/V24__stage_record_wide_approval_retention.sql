DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dwp_approval_retention_owner') THEN
        CREATE ROLE dwp_approval_retention_owner NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dwp_approval_retention_executor') THEN
        CREATE ROLE dwp_approval_retention_executor NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END $$;
CREATE SCHEMA apr_retention_internal AUTHORIZATION dwp_approval_retention_owner;
REVOKE ALL ON SCHEMA apr_retention_internal FROM PUBLIC;
GRANT USAGE ON SCHEMA apr_retention_internal TO dwp_approval_retention_executor;

CREATE TABLE apr_retention_policy_heads (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL CHECK(resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    policy_id UUID NOT NULL UNIQUE, version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    published_revision INTEGER NOT NULL DEFAULT 0 CHECK(published_revision>=0), pending_revision INTEGER,
    PRIMARY KEY(tenant_id,resource_set_key), UNIQUE(tenant_id,policy_id)
);
CREATE TABLE apr_retention_policy_versions (
    tenant_id BIGINT NOT NULL, policy_id UUID NOT NULL, revision INTEGER NOT NULL CHECK(revision>=0),
    rules JSONB NOT NULL CHECK(jsonb_typeof(rules)='object'),
    rules_sha256 CHAR(64) NOT NULL CHECK(rules_sha256 ~ '^[a-f0-9]{64}$'),
    maker_user_id BIGINT, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,policy_id,revision),
    FOREIGN KEY(tenant_id,policy_id) REFERENCES apr_retention_policy_heads(tenant_id,policy_id),
    CHECK((revision=0 AND maker_user_id IS NULL) OR (revision>0 AND maker_user_id>0))
);
ALTER TABLE apr_retention_policy_heads ADD CONSTRAINT fk_apr_retention_published
    FOREIGN KEY(tenant_id,policy_id,published_revision) REFERENCES apr_retention_policy_versions(tenant_id,policy_id,revision)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE apr_retention_policy_heads ADD CONSTRAINT fk_apr_retention_pending
    FOREIGN KEY(tenant_id,policy_id,pending_revision) REFERENCES apr_retention_policy_versions(tenant_id,policy_id,revision)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE apr_retention_policy_publications (
    publication_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, policy_id UUID NOT NULL, revision INTEGER NOT NULL,
    maker_user_id BIGINT NOT NULL CHECK(maker_user_id>0), checker_user_id BIGINT NOT NULL CHECK(checker_user_id>0),
    review_comment VARCHAR(1000) NOT NULL CHECK(length(btrim(review_comment))>=10),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(tenant_id,policy_id,revision),
    FOREIGN KEY(tenant_id,policy_id,revision) REFERENCES apr_retention_policy_versions(tenant_id,policy_id,revision),
    CHECK(maker_user_id<>checker_user_id)
);
CREATE TABLE apr_record_retention_heads (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id), request_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'LIVE' CHECK(state IN('LIVE','PREPARED','IRREVERSIBLE','IRREVERSIBLE_BLOCKED','OBJECTS_CONFIRMED','LOCAL_DB_PURGED','COMPLETE')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), claim_id UUID, inventory_sha256 CHAR(64),
    PRIMARY KEY(tenant_id,request_id), UNIQUE(tenant_id,request_id,claim_id)
);
CREATE TABLE apr_record_purge_claims (
    claim_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, request_id UUID NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL, request_version BIGINT NOT NULL, request_status VARCHAR(24) NOT NULL,
    deleted_at TIMESTAMPTZ, policy_id UUID NOT NULL, policy_version BIGINT NOT NULL, policy_revision INTEGER NOT NULL,
    policy_sha256 CHAR(64) NOT NULL, document_policy_version BIGINT NOT NULL, attachment_policy_version BIGINT,
    hold_version BIGINT NOT NULL, comments_version BIGINT NOT NULL, payload_revision INTEGER NOT NULL,
    payload_sha256 CHAR(64) NOT NULL, inventory_sha256 CHAR(64) NOT NULL, row_count INTEGER NOT NULL,
    effective_deadline TIMESTAMPTZ NOT NULL, claimed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,request_id,claim_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES apr_record_retention_heads(tenant_id,request_id),
    FOREIGN KEY(tenant_id,policy_id,policy_revision) REFERENCES apr_retention_policy_versions(tenant_id,policy_id,revision)
);
ALTER TABLE apr_record_retention_heads ADD CONSTRAINT fk_apr_retention_claim
    FOREIGN KEY(tenant_id,request_id,claim_id) REFERENCES apr_record_purge_claims(tenant_id,request_id,claim_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE apr_record_purge_rows (
    claim_id UUID NOT NULL REFERENCES apr_record_purge_claims(claim_id), table_oid REGCLASS NOT NULL,
    primary_key JSONB NOT NULL CHECK(jsonb_typeof(primary_key)='object'), row_sha256 CHAR(64) NOT NULL,
    PRIMARY KEY(claim_id,table_oid,primary_key), CHECK(row_sha256 ~ '^[a-f0-9]{64}$')
);
CREATE TABLE apr_record_purge_objects (
    object_intent_id UUID PRIMARY KEY, claim_id UUID NOT NULL REFERENCES apr_record_purge_claims(claim_id),
    object_key VARCHAR(200) NOT NULL, version_id VARCHAR(1024), content_sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK(size_bytes BETWEEN 1 AND 26214400),
    state VARCHAR(24) NOT NULL CHECK(state IN('UNRESOLVED','PENDING','CLAIMED','UNKNOWN','CONFIRMED')),
    generation BIGINT NOT NULL DEFAULT 0 CHECK(generation>=0), lease_token UUID, lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 100), last_reason VARCHAR(120), confirmed_at TIMESTAMPTZ,
    storage_locator_sha256 CHAR(64), presence_verified_at TIMESTAMPTZ,
    UNIQUE(claim_id,object_key), CHECK(state<>'CONFIRMED' OR (version_id IS NOT NULL AND confirmed_at IS NOT NULL)),
    CHECK(state<>'CLAIMED' OR (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE TABLE apr_record_purge_journal (
    entry_id UUID PRIMARY KEY, claim_id UUID NOT NULL REFERENCES apr_record_purge_claims(claim_id),
    state VARCHAR(32) NOT NULL, reason_code VARCHAR(120) NOT NULL, proof_sha256 CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE apr_record_tombstones (
    tenant_id BIGINT NOT NULL, request_id UUID NOT NULL, claim_id UUID NOT NULL,
    purged_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), inventory_sha256 CHAR(64) NOT NULL,
    PRIMARY KEY(tenant_id,request_id),
    FOREIGN KEY(tenant_id,request_id,claim_id) REFERENCES apr_record_purge_claims(tenant_id,request_id,claim_id)
);
CREATE TABLE apr_retention_internal.delete_permits (
    backend_pid INTEGER NOT NULL, transaction_id BIGINT NOT NULL, claim_id UUID NOT NULL,
    table_oid REGCLASS NOT NULL, primary_key JSONB NOT NULL, row_sha256 CHAR(64) NOT NULL, generation BIGINT NOT NULL,
    PRIMARY KEY(backend_pid,transaction_id,claim_id,table_oid,primary_key),
    FOREIGN KEY(claim_id,table_oid,primary_key) REFERENCES apr_record_purge_rows(claim_id,table_oid,primary_key)
);

CREATE FUNCTION apr_retention_internal.immutable() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path=pg_catalog AS $$ BEGIN RAISE EXCEPTION 'Retention evidence is immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER trg_apr_retention_policy_versions BEFORE UPDATE OR DELETE ON apr_retention_policy_versions FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();
CREATE TRIGGER trg_apr_retention_publications BEFORE UPDATE OR DELETE ON apr_retention_policy_publications FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();
CREATE TRIGGER trg_apr_retention_claims BEFORE UPDATE OR DELETE ON apr_record_purge_claims FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();
CREATE TRIGGER trg_apr_retention_rows BEFORE UPDATE OR DELETE ON apr_record_purge_rows FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();
CREATE TRIGGER trg_apr_retention_journal BEFORE UPDATE OR DELETE ON apr_record_purge_journal FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();
CREATE TRIGGER trg_apr_retention_tombstones BEFORE UPDATE OR DELETE ON apr_record_tombstones FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();

CREATE FUNCTION apr_retention_internal.validate_rules() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE k TEXT; value JSONB; n INTEGER;
BEGIN
    IF (SELECT array_agg(key ORDER BY key) FROM jsonb_object_keys(NEW.rules) key) IS DISTINCT FROM
       ARRAY['allowPurge','allowedClassifications','auditEvidenceRetentionDays','deletedDraftRecoveryDays','holdEvidenceRetentionDays',
             'maxInventoryRows','maxObjectsPerRecord','receiptRetentionDays','recordRetentionDays']::text[]
       OR jsonb_typeof(NEW.rules->'allowPurge') IS DISTINCT FROM 'boolean'
       OR jsonb_typeof(NEW.rules->'allowedClassifications') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'Unknown or malformed retention policy' USING ERRCODE='23514'; END IF;
    IF jsonb_array_length(NEW.rules->'allowedClassifications') NOT BETWEEN 1 AND 3
       OR EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.rules->'allowedClassifications') c
         WHERE c NOT IN('"INTERNAL"'::jsonb,'"CONFIDENTIAL"'::jsonb,'"RESTRICTED"'::jsonb))
       OR (SELECT count(DISTINCT c) FROM jsonb_array_elements(NEW.rules->'allowedClassifications') c)<>jsonb_array_length(NEW.rules->'allowedClassifications') THEN
        RAISE EXCEPTION 'Invalid retention classifications' USING ERRCODE='23514'; END IF;
    FOR k,value IN SELECT j.key,j.value FROM jsonb_each(NEW.rules) j WHERE j.key NOT IN('allowPurge','allowedClassifications') LOOP
        IF jsonb_typeof(value)<>'number' OR (value#>>'{}')!~'^[0-9]{1,5}$' THEN
            RAISE EXCEPTION 'Invalid integer retention bound' USING ERRCODE='23514'; END IF;
        n:=(value#>>'{}')::integer;
        IF n<1 OR n>(CASE k WHEN 'maxInventoryRows' THEN 50000 WHEN 'maxObjectsPerRecord' THEN 1000 ELSE 3650 END) THEN
            RAISE EXCEPTION 'Retention bound exceeded' USING ERRCODE='23514'; END IF;
    END LOOP;
    IF NEW.rules_sha256 IS DISTINCT FROM encode(sha256(convert_to(NEW.rules::text,'UTF8')),'hex')
       OR (NEW.revision=0 AND NEW.rules->>'allowPurge'<>'false') THEN
        RAISE EXCEPTION 'Retention policy integrity or initial grant violation' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_apr_retention_rules BEFORE INSERT ON apr_retention_policy_versions
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.validate_rules();

CREATE FUNCTION apr_retention_internal.require_executor() RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog AS $$ BEGIN
    IF NOT pg_has_role(session_user,'dwp_approval_retention_executor','MEMBER')
       OR pg_has_role(session_user,'dwp_approval_retention_owner','MEMBER')
       OR session_user IS DISTINCT FROM (SELECT a.usename FROM pg_stat_activity a WHERE a.pid=pg_backend_pid())
       OR EXISTS(SELECT 1 FROM pg_roles WHERE rolname=session_user AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION 'Dedicated retention executor required' USING ERRCODE='42501';
    END IF;
END $$;
CREATE FUNCTION apr_retention_internal.exact_primary_key(p_table REGCLASS,p_row JSONB) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE keys TEXT[]; result JSONB;
BEGIN
    keys:=CASE p_table
      WHEN 'public.apr_requests'::regclass THEN ARRAY['tenant_id','request_id']
      WHEN 'public.apr_request_payloads'::regclass THEN ARRAY['tenant_id','request_id']
      WHEN 'public.apr_request_payload_versions'::regclass THEN ARRAY['payload_version_id']
      WHEN 'public.apr_tasks'::regclass THEN ARRAY['task_id'] WHEN 'public.apr_steps'::regclass THEN ARRAY['step_id']
      WHEN 'public.apr_request_events'::regclass THEN ARRAY['event_id'] WHEN 'public.apr_draft_commands'::regclass THEN ARRAY['command_id']
      WHEN 'public.apr_document_heads'::regclass THEN ARRAY['tenant_id','request_id']
      WHEN 'public.apr_document_comments'::regclass THEN ARRAY['comment_id']
      WHEN 'public.apr_document_hold_proposals'::regclass THEN ARRAY['proposal_id']
      WHEN 'public.apr_document_hold_journal'::regclass THEN ARRAY['entry_id']
      WHEN 'public.apr_document_command_receipts'::regclass THEN ARRAY['receipt_id']
      WHEN 'public.apr_attachment_uploads'::regclass THEN ARRAY['upload_id']
      WHEN 'public.apr_attachment_selections'::regclass THEN ARRAY['tenant_id','request_id']
      WHEN 'public.apr_attachment_preparations'::regclass THEN ARRAY['preparation_id']
      WHEN 'public.apr_attachment_manifests'::regclass THEN ARRAY['tenant_id','request_id','payload_revision']
      WHEN 'public.apr_attachment_download_grants'::regclass THEN ARRAY['grant_id']
      WHEN 'public.apr_attachment_command_receipts'::regclass THEN ARRAY['tenant_id','actor_user_id','route','idempotency_key']
      WHEN 'public.apr_attachment_cleanup_journal'::regclass THEN ARRAY['cleanup_id']
      WHEN 'public.apr_quorum_stage_runtime'::regclass THEN ARRAY['tenant_id','request_id','step_id','generation']
      WHEN 'public.apr_quorum_candidates'::regclass THEN ARRAY['tenant_id','request_id','step_id','generation','principal_user_id']
      WHEN 'public.apr_quorum_votes'::regclass THEN ARRAY['vote_id']
      WHEN 'public.apr_quorum_prerequisites'::regclass THEN ARRAY['tenant_id','request_id','step_id','generation','predecessor_step_id']
      WHEN 'public.apr_quorum_sla_timers'::regclass THEN ARRAY['timer_id']
      WHEN 'public.apr_quorum_information_rounds'::regclass THEN ARRAY['tenant_id','request_id','source_generation']
      WHEN 'public.apr_quorum_information_commands'::regclass THEN ARRAY['tenant_id','request_id','idempotency_key']
      WHEN 'public.apr_integration_outbox'::regclass THEN ARRAY['outbox_id']
      WHEN 'public.apr_recovery_auditor_assignment_events'::regclass THEN ARRAY['assignment_event_id']
      WHEN 'public.sys_audit_outbox'::regclass THEN ARRAY['outbox_id'] ELSE NULL END;
    IF keys IS NULL OR EXISTS(SELECT 1 FROM unnest(keys) k WHERE p_row->k IS NULL OR p_row->k='null'::jsonb) THEN
        RAISE EXCEPTION 'Unknown retention catalog identity' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_object_agg(k,p_row->k) INTO result FROM unnest(keys) k;
    RETURN result;
END $$;

CREATE FUNCTION apr_retention_internal.catalog_rows(p_tenant BIGINT,p_request UUID)
RETURNS TABLE(table_oid REGCLASS,primary_key JSONB,row_sha256 TEXT,row_data JSONB)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
WITH rows(table_oid,row_data) AS (
 SELECT 'public.apr_requests'::regclass,to_jsonb(r) FROM public.apr_requests r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_request_payloads'::regclass,to_jsonb(r) FROM public.apr_request_payloads r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_request_payload_versions'::regclass,to_jsonb(r) FROM public.apr_request_payload_versions r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_steps'::regclass,to_jsonb(r) FROM public.apr_steps r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_tasks'::regclass,to_jsonb(r) FROM public.apr_tasks r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_request_events'::regclass,to_jsonb(r) FROM public.apr_request_events r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_draft_commands'::regclass,to_jsonb(r) FROM public.apr_draft_commands r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_document_heads'::regclass,to_jsonb(r) FROM public.apr_document_heads r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_document_comments'::regclass,to_jsonb(r) FROM public.apr_document_comments r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_document_hold_proposals'::regclass,to_jsonb(r) FROM public.apr_document_hold_proposals r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_document_hold_journal'::regclass,to_jsonb(r) FROM public.apr_document_hold_journal r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_document_command_receipts'::regclass,to_jsonb(r) FROM public.apr_document_command_receipts r WHERE tenant_id=p_tenant AND (metadata->>'requestId'=p_request::text OR EXISTS(SELECT 1 FROM jsonb_path_query(metadata,'$.requestedVersions[*].requestId') j WHERE j#>>'{}'=p_request::text))
 UNION ALL SELECT 'public.apr_attachment_uploads'::regclass,to_jsonb(r) FROM public.apr_attachment_uploads r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_attachment_selections'::regclass,to_jsonb(r) FROM public.apr_attachment_selections r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_attachment_preparations'::regclass,to_jsonb(r) FROM public.apr_attachment_preparations r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_attachment_manifests'::regclass,to_jsonb(r) FROM public.apr_attachment_manifests r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_attachment_download_grants'::regclass,to_jsonb(r) FROM public.apr_attachment_download_grants r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_attachment_command_receipts'::regclass,to_jsonb(r) FROM public.apr_attachment_command_receipts r WHERE tenant_id=p_tenant AND (metadata->>'requestId'=p_request::text OR EXISTS(SELECT 1 FROM public.apr_attachment_uploads u WHERE u.tenant_id=p_tenant AND u.request_id=p_request AND (metadata->>'uploadId'=u.upload_id::text OR route LIKE '%/'||u.upload_id::text||'/%')) OR EXISTS(SELECT 1 FROM public.apr_attachment_download_grants g WHERE g.tenant_id=p_tenant AND g.request_id=p_request AND metadata->>'grantId'=g.grant_id::text))
 UNION ALL SELECT 'public.apr_attachment_cleanup_journal'::regclass,to_jsonb(r) FROM public.apr_attachment_cleanup_journal r JOIN public.apr_attachment_uploads u USING(tenant_id,upload_id) WHERE u.tenant_id=p_tenant AND u.request_id=p_request
 UNION ALL SELECT 'public.apr_quorum_stage_runtime'::regclass,to_jsonb(r) FROM public.apr_quorum_stage_runtime r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_quorum_candidates'::regclass,to_jsonb(r) FROM public.apr_quorum_candidates r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_quorum_votes'::regclass,to_jsonb(r) FROM public.apr_quorum_votes r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_quorum_prerequisites'::regclass,to_jsonb(r) FROM public.apr_quorum_prerequisites r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_quorum_sla_timers'::regclass,to_jsonb(r) FROM public.apr_quorum_sla_timers r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_quorum_information_rounds'::regclass,to_jsonb(r) FROM public.apr_quorum_information_rounds r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_quorum_information_commands'::regclass,to_jsonb(r) FROM public.apr_quorum_information_commands r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_integration_outbox'::regclass,to_jsonb(r) FROM public.apr_integration_outbox r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_recovery_auditor_assignment_events'::regclass,to_jsonb(r) FROM public.apr_recovery_auditor_assignment_events r JOIN public.apr_integration_outbox o USING(outbox_id) WHERE o.tenant_id=p_tenant AND o.request_id=p_request
 UNION ALL SELECT 'public.sys_audit_outbox'::regclass,to_jsonb(r) FROM public.sys_audit_outbox r WHERE tenant_id=p_tenant AND (payload->>'approvalId'=p_request::text OR payload->'afterState'->>'requestId'=p_request::text OR (payload->>'targetType' IN('APPROVAL_REQUEST','APPROVAL_DOCUMENT') AND payload->>'targetId'=p_request::text) OR (payload->>'targetType'='APPROVAL_TASK' AND EXISTS(SELECT 1 FROM public.apr_tasks t WHERE t.tenant_id=p_tenant AND t.request_id=p_request AND payload->>'targetId'=t.task_id::text)))
)
SELECT table_oid,apr_retention_internal.exact_primary_key(table_oid,row_data),
 encode(sha256(convert_to(row_data::text,'UTF8')),'hex'),row_data FROM rows;
$$;

CREATE FUNCTION apr_retention_internal.prepare_record(p_tenant BIGINT,p_request UUID,p_version BIGINT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE request public.apr_requests; policy public.apr_retention_policy_heads; revision public.apr_retention_policy_versions;
 doc public.apr_document_heads; doc_policy public.apr_document_policy_heads; attachment_policy public.apr_attachment_policy_heads;
 payload public.apr_request_payloads; rules JSONB; inventory TEXT; row_count INTEGER; deadline TIMESTAMPTZ; claim UUID:=gen_random_uuid();
BEGIN
    PERFORM apr_retention_internal.require_executor();
    SELECT r.* INTO request FROM public.apr_requests r JOIN public.apr_tenants t USING(tenant_id)
      WHERE r.tenant_id=p_tenant AND r.request_id=p_request AND t.lifecycle_state='ACTIVE' FOR UPDATE OF r FOR SHARE OF t;
    IF request.request_id IS NULL THEN RAISE EXCEPTION 'Record unavailable' USING ERRCODE='42501'; END IF;
    IF request.version<>p_version THEN RAISE EXCEPTION 'Record version changed' USING ERRCODE='40001'; END IF;
    SELECT * INTO policy FROM public.apr_retention_policy_heads WHERE tenant_id=p_tenant AND resource_set_key=request.management_resource_set_key FOR SHARE;
    SELECT v.* INTO revision FROM public.apr_retention_policy_versions v WHERE v.tenant_id=p_tenant AND v.policy_id=policy.policy_id AND v.revision=policy.published_revision;
    SELECT * INTO doc_policy FROM public.apr_document_policy_heads WHERE tenant_id=p_tenant AND resource_set_key=request.management_resource_set_key FOR SHARE;
    SELECT * INTO attachment_policy FROM public.apr_attachment_policy_heads WHERE tenant_id=p_tenant AND resource_set_key=request.management_resource_set_key FOR SHARE;
    SELECT * INTO doc FROM public.apr_document_heads WHERE tenant_id=p_tenant AND request_id=p_request FOR UPDATE;
    SELECT * INTO payload FROM public.apr_request_payloads WHERE tenant_id=p_tenant AND request_id=p_request;
    IF policy.policy_id IS NULL OR doc_policy.policy_id IS NULL OR doc.request_id IS NULL OR payload.request_id IS NULL
       OR revision.rules IS NULL OR encode(sha256(convert_to(revision.rules::text,'UTF8')),'hex')<>revision.rules_sha256 THEN
        RAISE EXCEPTION 'Retention dependency unavailable' USING ERRCODE='55000';
    END IF;
    rules:=revision.rules;
    IF (rules->>'allowPurge') IS DISTINCT FROM 'true' OR doc.hold_active OR doc.pending_hold_id IS NOT NULL
       OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements_text(rules->'allowedClassifications') c WHERE c=request.data_classification)
       OR NOT EXISTS(SELECT 1 FROM public.apr_retention_policy_publications p WHERE p.tenant_id=p_tenant AND p.policy_id=policy.policy_id AND p.revision=policy.published_revision AND p.maker_user_id=revision.maker_user_id) THEN
        RAISE EXCEPTION 'Retention or hold denies purge' USING ERRCODE='23514'; END IF;
    IF request.status='DRAFT' AND request.deleted_at IS NOT NULL THEN
        deadline:=request.deleted_at+make_interval(days=>(rules->>'deletedDraftRecoveryDays')::int);
    ELSIF request.status IN('APPROVED','REJECTED','WITHDRAWN','CANCELLED') AND request.completed_at IS NOT NULL THEN
        deadline:=request.completed_at+make_interval(days=>(rules->>'recordRetentionDays')::int);
    ELSE RAISE EXCEPTION 'Record is not terminal' USING ERRCODE='23514'; END IF;
    IF NOT EXISTS(SELECT 1 FROM public.apr_request_payload_versions v WHERE v.tenant_id=p_tenant AND v.request_id=p_request
       AND v.revision_number=payload.schema_version AND v.payload_sha256=payload.payload_sha256 AND v.payload=payload.payload) THEN
        RAISE EXCEPTION 'Immutable payload missing' USING ERRCODE='55000'; END IF;
    IF EXISTS(SELECT 1 FROM public.apr_attachment_uploads WHERE tenant_id=p_tenant AND request_id=p_request AND state IN('UPLOADING','SCANNING'))
       OR EXISTS(SELECT 1 FROM public.apr_attachment_download_grants WHERE tenant_id=p_tenant AND request_id=p_request AND lease_until>clock_timestamp())
       OR EXISTS(SELECT 1 FROM public.apr_quorum_information_commands WHERE tenant_id=p_tenant AND request_id=p_request AND status='UNKNOWN')
       OR EXISTS(SELECT 1 FROM public.apr_quorum_information_rounds WHERE tenant_id=p_tenant AND request_id=p_request AND status='OPEN')
       OR EXISTS(SELECT 1 FROM public.apr_quorum_sla_timers WHERE tenant_id=p_tenant AND request_id=p_request AND status IN('PENDING','CLAIMED')) THEN
        RAISE EXCEPTION 'Record work unsettled' USING ERRCODE='23514'; END IF;
    IF EXISTS(SELECT 1 FROM apr_retention_internal.catalog_rows(p_tenant,p_request) r
       WHERE r.row_data->>'tenant_id' IS DISTINCT FROM p_tenant::text) THEN
        RAISE EXCEPTION 'Retention row tenant mismatch' USING ERRCODE='23514'; END IF;
    IF EXISTS(SELECT 1 FROM apr_retention_internal.catalog_rows(p_tenant,p_request) r
       WHERE r.table_oid IN('public.apr_integration_outbox'::regclass,'public.sys_audit_outbox'::regclass)
         AND ((r.row_data->>'status')<>'PUBLISHED' OR (r.row_data->>'locked_until')::timestamptz>clock_timestamp())) THEN
        RAISE EXCEPTION 'Delivery acknowledgement missing' USING ERRCODE='23514'; END IF;
    IF EXISTS(SELECT 1 FROM apr_retention_internal.catalog_rows(p_tenant,p_request) r,
       LATERAL jsonb_path_query(COALESCE(r.row_data->'metadata',r.row_data->'payload'->'afterState','{}'::jsonb),'$.requestedVersions[*].requestId') j
       WHERE j#>>'{}'<>p_request::text) THEN
        RAISE EXCEPTION 'BLOCKED_SHARED_LINK' USING ERRCODE='23514'; END IF;
    SELECT count(*),encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex'),
       GREATEST(deadline,doc.retain_until,max(GREATEST((row_data->>'retain_until')::timestamptz,(row_data->>'expires_at')::timestamptz,
         (row_data->>'created_at')::timestamptz+make_interval(days=>CASE WHEN table_oid IN('public.apr_document_hold_journal'::regclass,'public.apr_document_hold_proposals'::regclass) THEN (rules->>'holdEvidenceRetentionDays')::int
           WHEN table_oid='public.sys_audit_outbox'::regclass THEN (rules->>'auditEvidenceRetentionDays')::int
           ELSE (rules->>'receiptRetentionDays')::int END))))
       INTO row_count,inventory,deadline FROM apr_retention_internal.catalog_rows(p_tenant,p_request);
    IF row_count>(rules->>'maxInventoryRows')::int OR (SELECT count(*) FROM public.apr_attachment_uploads WHERE tenant_id=p_tenant AND request_id=p_request)>(rules->>'maxObjectsPerRecord')::int THEN
        RAISE EXCEPTION 'Retention inventory exceeds cap' USING ERRCODE='23514'; END IF;
    IF deadline IS NULL OR deadline>clock_timestamp() THEN RAISE EXCEPTION 'Retention deadline not elapsed' USING ERRCODE='23514'; END IF;
    INSERT INTO public.apr_record_retention_heads(tenant_id,request_id) VALUES(p_tenant,p_request) ON CONFLICT DO NOTHING;
    PERFORM 1 FROM public.apr_record_retention_heads WHERE tenant_id=p_tenant AND request_id=p_request AND state='LIVE' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Record already staged' USING ERRCODE='40001'; END IF;
    INSERT INTO public.apr_record_purge_claims(claim_id,tenant_id,request_id,resource_set_key,request_version,request_status,deleted_at,
       policy_id,policy_version,policy_revision,policy_sha256,document_policy_version,attachment_policy_version,
       hold_version,comments_version,payload_revision,payload_sha256,inventory_sha256,row_count,effective_deadline)
    VALUES(claim,p_tenant,p_request,request.management_resource_set_key,request.version,request.status,request.deleted_at,
       policy.policy_id,policy.version,policy.published_revision,revision.rules_sha256,doc_policy.version,attachment_policy.version,
       doc.hold_version,doc.comments_version,payload.schema_version,payload.payload_sha256,inventory,row_count,deadline);
    INSERT INTO public.apr_record_purge_rows SELECT claim,table_oid,primary_key,row_sha256 FROM apr_retention_internal.catalog_rows(p_tenant,p_request);
    INSERT INTO public.apr_record_purge_objects(object_intent_id,claim_id,object_key,version_id,content_sha256,size_bytes,state)
       SELECT gen_random_uuid(),claim,object_key,object_version,content_sha256,size_bytes,
         CASE WHEN object_version IS NULL THEN 'UNRESOLVED' ELSE 'PENDING' END FROM public.apr_attachment_uploads WHERE tenant_id=p_tenant AND request_id=p_request;
    UPDATE public.apr_record_retention_heads SET state='PREPARED',version=version+1,claim_id=claim,inventory_sha256=inventory WHERE tenant_id=p_tenant AND request_id=p_request;
    INSERT INTO public.apr_record_purge_journal VALUES(gen_random_uuid(),claim,'PREPARED','ELIGIBILITY_PINNED',inventory,clock_timestamp());
    RETURN claim;
END $$;

CREATE FUNCTION apr_retention_internal.claim_record(p_claim UUID,p_head_version BIGINT) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE claim public.apr_record_purge_claims; request public.apr_requests; policy public.apr_retention_policy_heads;
 doc public.apr_document_heads; doc_policy public.apr_document_policy_heads; attachment_policy public.apr_attachment_policy_heads; inventory TEXT;
BEGIN
    PERFORM apr_retention_internal.require_executor();
    SELECT * INTO claim FROM public.apr_record_purge_claims WHERE claim_id=p_claim;
    IF claim.claim_id IS NULL THEN RAISE EXCEPTION 'Claim unavailable' USING ERRCODE='42501'; END IF;
    SELECT r.* INTO request FROM public.apr_requests r JOIN public.apr_tenants t USING(tenant_id)
       WHERE r.tenant_id=claim.tenant_id AND r.request_id=claim.request_id AND t.lifecycle_state='ACTIVE'
       FOR UPDATE OF r FOR SHARE OF t;
    SELECT * INTO policy FROM public.apr_retention_policy_heads WHERE tenant_id=claim.tenant_id AND policy_id=claim.policy_id FOR SHARE;
    SELECT * INTO doc_policy FROM public.apr_document_policy_heads WHERE tenant_id=claim.tenant_id AND resource_set_key=claim.resource_set_key FOR SHARE;
    SELECT * INTO attachment_policy FROM public.apr_attachment_policy_heads WHERE tenant_id=claim.tenant_id AND resource_set_key=claim.resource_set_key FOR SHARE;
    SELECT * INTO doc FROM public.apr_document_heads WHERE tenant_id=claim.tenant_id AND request_id=claim.request_id FOR UPDATE;
    IF request.request_id IS NULL OR request.version IS DISTINCT FROM claim.request_version OR request.status IS DISTINCT FROM claim.request_status
       OR policy.version IS DISTINCT FROM claim.policy_version OR policy.published_revision IS DISTINCT FROM claim.policy_revision
       OR doc_policy.version IS DISTINCT FROM claim.document_policy_version OR attachment_policy.version IS DISTINCT FROM claim.attachment_policy_version
       OR doc.hold_version IS DISTINCT FROM claim.hold_version OR doc.comments_version IS DISTINCT FROM claim.comments_version
       OR doc.hold_active OR doc.pending_hold_id IS NOT NULL OR doc.retain_until>clock_timestamp() THEN
        RAISE EXCEPTION 'Claim eligibility changed' USING ERRCODE='40001'; END IF;
    SELECT encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex')
       INTO inventory FROM apr_retention_internal.catalog_rows(claim.tenant_id,claim.request_id);
    IF inventory IS DISTINCT FROM claim.inventory_sha256 THEN RAISE EXCEPTION 'Record inventory changed' USING ERRCODE='40001'; END IF;
    UPDATE public.apr_record_retention_heads SET state='IRREVERSIBLE',version=version+1
       WHERE tenant_id=claim.tenant_id AND request_id=claim.request_id AND state='PREPARED' AND claim_id=p_claim AND version=p_head_version;
    IF NOT FOUND THEN RAISE EXCEPTION 'Claim CAS changed' USING ERRCODE='40001'; END IF;
    INSERT INTO public.apr_record_purge_journal VALUES(gen_random_uuid(),p_claim,'IRREVERSIBLE','EXACT_INVENTORY_RECHECKED',inventory,clock_timestamp());
END $$;

CREATE FUNCTION apr_retention_internal.authorized_delete(p_table REGCLASS,p_old JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
    PERFORM apr_retention_internal.require_executor();
    RETURN EXISTS(SELECT 1 FROM apr_retention_internal.delete_permits p
      JOIN public.apr_record_retention_heads h ON h.claim_id=p.claim_id
      JOIN public.apr_record_purge_claims c ON c.claim_id=p.claim_id
      WHERE p.backend_pid=pg_backend_pid() AND p.transaction_id=txid_current() AND p.table_oid=p_table
       AND p.primary_key=apr_retention_internal.exact_primary_key(p_table,p_old)
       AND p.row_sha256=encode(sha256(convert_to(p_old::text,'UTF8')),'hex')
       AND p.generation=h.version AND h.state='OBJECTS_CONFIRMED' AND h.tenant_id=c.tenant_id AND h.request_id=c.request_id);
END $$;

CREATE FUNCTION apr_retention_internal.lock_object_claim(p_claim UUID) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE c public.apr_record_purge_claims; h public.apr_record_retention_heads; p public.apr_retention_policy_heads; d public.apr_document_heads;
 r public.apr_requests; dp public.apr_document_policy_heads; ap public.apr_attachment_policy_heads; inventory TEXT;
BEGIN
    PERFORM apr_retention_internal.require_executor();
    SELECT * INTO c FROM public.apr_record_purge_claims WHERE claim_id=p_claim;
    IF c.claim_id IS NULL THEN RAISE EXCEPTION 'Claim unavailable' USING ERRCODE='42501'; END IF;
    SELECT request.* INTO r FROM public.apr_requests request JOIN public.apr_tenants t USING(tenant_id)
      WHERE request.tenant_id=c.tenant_id AND request.request_id=c.request_id AND t.lifecycle_state='ACTIVE'
      FOR UPDATE OF request FOR SHARE OF t;
    IF NOT FOUND THEN RAISE EXCEPTION 'Record unavailable' USING ERRCODE='55000'; END IF;
    SELECT * INTO p FROM public.apr_retention_policy_heads WHERE tenant_id=c.tenant_id AND policy_id=c.policy_id FOR SHARE;
    SELECT * INTO dp FROM public.apr_document_policy_heads WHERE tenant_id=c.tenant_id AND resource_set_key=c.resource_set_key FOR SHARE;
    SELECT * INTO ap FROM public.apr_attachment_policy_heads WHERE tenant_id=c.tenant_id AND resource_set_key=c.resource_set_key FOR SHARE;
    SELECT * INTO d FROM public.apr_document_heads WHERE tenant_id=c.tenant_id AND request_id=c.request_id FOR UPDATE;
    SELECT * INTO h FROM public.apr_record_retention_heads WHERE claim_id=p_claim FOR UPDATE;
    IF h.state NOT IN('IRREVERSIBLE','OBJECTS_CONFIRMED') OR r.version IS DISTINCT FROM c.request_version
       OR r.status IS DISTINCT FROM c.request_status OR r.management_resource_set_key IS DISTINCT FROM c.resource_set_key
       OR p.version IS DISTINCT FROM c.policy_version OR dp.version IS DISTINCT FROM c.document_policy_version
       OR ap.version IS DISTINCT FROM c.attachment_policy_version OR d.comments_version IS DISTINCT FROM c.comments_version
       OR p.published_revision IS DISTINCT FROM c.policy_revision OR d.request_id IS NULL
       OR d.hold_version IS DISTINCT FROM c.hold_version OR d.hold_active OR d.pending_hold_id IS NOT NULL
       OR d.retain_until>clock_timestamp() THEN
        RAISE EXCEPTION 'Irreversible cleanup eligibility changed' USING ERRCODE='40001'; END IF;
    SELECT encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex')
      INTO inventory FROM apr_retention_internal.catalog_rows(c.tenant_id,c.request_id);
    IF inventory IS DISTINCT FROM c.inventory_sha256 THEN
        RAISE EXCEPTION 'Irreversible record inventory changed' USING ERRCODE='40001'; END IF;
END $$;
CREATE FUNCTION apr_retention_internal.claim_object(p_claim UUID)
RETURNS TABLE(object_intent_id UUID,object_key TEXT,version_id TEXT,content_sha256 TEXT,size_bytes BIGINT,generation BIGINT,lease_token UUID,storage_locator_sha256 TEXT,presence_verified BOOLEAN)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE id UUID;
BEGIN
    PERFORM apr_retention_internal.lock_object_claim(p_claim);
    SELECT o.object_intent_id INTO id FROM public.apr_record_purge_objects o
      WHERE o.claim_id=p_claim AND o.state<>'CONFIRMED' AND o.attempts<100
       AND (o.state<>'CLAIMED' OR o.lease_until<=clock_timestamp())
      ORDER BY o.object_intent_id FOR UPDATE SKIP LOCKED LIMIT 1;
    IF id IS NULL THEN RETURN; END IF;
    RETURN QUERY UPDATE public.apr_record_purge_objects o
      SET state='CLAIMED',generation=o.generation+1,lease_token=gen_random_uuid(),lease_until=clock_timestamp()+INTERVAL '90 seconds',attempts=o.attempts+1
      WHERE o.object_intent_id=id
      RETURNING o.object_intent_id,o.object_key::text,o.version_id::text,o.content_sha256::text,o.size_bytes,o.generation,o.lease_token,o.storage_locator_sha256::text,o.presence_verified_at IS NOT NULL;
END $$;
CREATE FUNCTION apr_retention_internal.bind_object_presence(p_id UUID,p_generation BIGINT,p_token UUID,p_version TEXT,p_locator TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE c UUID;
BEGIN
    SELECT claim_id INTO c FROM public.apr_record_purge_objects WHERE object_intent_id=p_id;
    PERFORM apr_retention_internal.lock_object_claim(c);
    IF p_version IS NULL OR p_version IN('','null') OR p_locator IS NULL OR p_locator!~'^[a-f0-9]{64}$' THEN
        RAISE EXCEPTION 'Exact storage presence proof required' USING ERRCODE='23514'; END IF;
    UPDATE public.apr_record_purge_objects SET version_id=p_version,storage_locator_sha256=p_locator,presence_verified_at=COALESCE(presence_verified_at,clock_timestamp())
      WHERE object_intent_id=p_id AND generation=p_generation AND lease_token=p_token AND state='CLAIMED' AND lease_until>clock_timestamp()
       AND (version_id IS NULL OR version_id=p_version) AND (storage_locator_sha256 IS NULL OR storage_locator_sha256=p_locator);
    IF NOT FOUND THEN RAISE EXCEPTION 'Storage binding fence changed' USING ERRCODE='40001'; END IF;
END $$;
CREATE FUNCTION apr_retention_internal.finish_object(p_id UUID,p_generation BIGINT,p_token UUID,p_locator TEXT,p_absent BOOLEAN)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE c UUID; proof TEXT;
BEGIN
    SELECT claim_id INTO c FROM public.apr_record_purge_objects WHERE object_intent_id=p_id;
    PERFORM apr_retention_internal.lock_object_claim(c);
    UPDATE public.apr_record_purge_objects
      SET state=CASE WHEN p_absent THEN 'CONFIRMED' ELSE 'UNKNOWN' END,
        confirmed_at=CASE WHEN p_absent THEN clock_timestamp() ELSE NULL END,
        last_reason=CASE WHEN p_absent THEN 'EXACT_VERSION_ABSENCE_CONFIRMED' ELSE 'STORAGE_RESULT_UNKNOWN' END,
        lease_token=NULL,lease_until=NULL
      WHERE object_intent_id=p_id AND generation=p_generation AND lease_token=p_token AND state='CLAIMED' AND lease_until>clock_timestamp()
       AND (NOT p_absent OR (version_id IS NOT NULL AND presence_verified_at IS NOT NULL AND storage_locator_sha256=p_locator))
      RETURNING content_sha256 INTO proof;
    IF NOT FOUND THEN RAISE EXCEPTION 'Object finalization fence changed' USING ERRCODE='40001'; END IF;
    INSERT INTO public.apr_record_purge_journal VALUES(gen_random_uuid(),c,
       CASE WHEN p_absent THEN 'OBJECT_CONFIRMED' ELSE 'OBJECT_UNKNOWN' END,
       CASE WHEN p_absent THEN 'EXACT_VERSION_ABSENCE_CONFIRMED' ELSE 'STORAGE_RESULT_UNKNOWN' END,proof,clock_timestamp());
    IF NOT EXISTS(SELECT 1 FROM public.apr_record_purge_objects WHERE claim_id=c AND state<>'CONFIRMED') THEN
        UPDATE public.apr_record_retention_heads SET state='OBJECTS_CONFIRMED',version=version+1 WHERE claim_id=c AND state='IRREVERSIBLE';
    END IF;
END $$;
CREATE FUNCTION apr_retention_internal.object_identity_immutable() RETURNS TRIGGER LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN
    IF (NEW.object_intent_id,NEW.claim_id,NEW.object_key,NEW.content_sha256,NEW.size_bytes) IS DISTINCT FROM
       (OLD.object_intent_id,OLD.claim_id,OLD.object_key,OLD.content_sha256,OLD.size_bytes)
       OR (OLD.version_id IS NOT NULL AND NEW.version_id IS DISTINCT FROM OLD.version_id)
       OR (OLD.storage_locator_sha256 IS NOT NULL AND NEW.storage_locator_sha256 IS DISTINCT FROM OLD.storage_locator_sha256)
       OR (OLD.presence_verified_at IS NOT NULL AND NEW.presence_verified_at IS DISTINCT FROM OLD.presence_verified_at)
       OR (OLD.state='CONFIRMED' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Purge object identity is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_apr_retention_object_identity BEFORE UPDATE ON apr_record_purge_objects
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.object_identity_immutable();

CREATE FUNCTION apr_retention_internal.deny_unpermitted_delete() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
    IF TG_OP<>'DELETE' OR NOT apr_retention_internal.authorized_delete(TG_RELID::regclass,to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'Exact retention deletion permit required' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
END $$;
CREATE FUNCTION apr_retention_internal.consume_delete_permit() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE affected INTEGER;
BEGIN
    IF NOT apr_retention_internal.authorized_delete(TG_RELID::regclass,to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'Retention deletion proof changed' USING ERRCODE='23514'; END IF;
    DELETE FROM apr_retention_internal.delete_permits
      WHERE backend_pid=pg_backend_pid() AND transaction_id=txid_current() AND table_oid=TG_RELID::regclass
        AND primary_key=apr_retention_internal.exact_primary_key(TG_RELID::regclass,to_jsonb(OLD))
        AND row_sha256=encode(sha256(convert_to(to_jsonb(OLD)::text,'UTF8')),'hex');
    GET DIAGNOSTICS affected=ROW_COUNT;
    IF affected<>1 THEN RAISE EXCEPTION 'Retention permit consumption mismatch' USING ERRCODE='23514'; END IF;
    RETURN OLD;
END $$;

-- BEGIN GENERATED TEN DELETE PREFIXES AND STATIC CATALOG TRIGGERS
CREATE OR REPLACE FUNCTION reject_apr_recovery_assignment_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Recovery auditor assignment events are append-only';
END;
$$;

CREATE OR REPLACE FUNCTION preserve_approval_draft_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF;
    IF OLD.draft_snapshot IS NOT NULL THEN
        RAISE EXCEPTION 'Approval draft revision snapshot is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION preserve_approval_draft_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'DELETE' OR OLD.result IS NOT NULL
       OR (NEW.tenant_id, NEW.actor_user_id, NEW.command_route, NEW.idempotency_key,
           NEW.fingerprint, NEW.expected_version, NEW.source_revision, NEW.command_type)
          IS DISTINCT FROM
          (OLD.tenant_id, OLD.actor_user_id, OLD.command_route, OLD.idempotency_key,
           OLD.fingerprint, OLD.expected_version, OLD.source_revision, OLD.command_type) THEN
        RAISE EXCEPTION 'Approval draft command receipt is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION protect_apr_quorum_immutable_row() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Quorum candidates, votes and prerequisites are immutable';
END;
$$;

CREATE OR REPLACE FUNCTION protect_apr_quorum_stage() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Quorum stage evidence is immutable'; END IF;
    IF (NEW.tenant_id, NEW.request_id, NEW.step_id, NEW.generation, NEW.stage_key, NEW.context,
        NEW.definition_canonical, NEW.definition_sha256) IS DISTINCT FROM
       (OLD.tenant_id, OLD.request_id, OLD.step_id, OLD.generation, OLD.stage_key, OLD.context,
        OLD.definition_canonical, OLD.definition_sha256) THEN
        RAISE EXCEPTION 'Quorum stage pins are immutable';
    END IF;
    IF OLD.snapshot IS NOT NULL AND
       (NEW.snapshot, NEW.candidate_sha256, NEW.eligible_count, NEW.threshold, NEW.opened_at, NEW.due_at)
       IS DISTINCT FROM
       (OLD.snapshot, OLD.candidate_sha256, OLD.eligible_count, OLD.threshold, OLD.opened_at, OLD.due_at) THEN
        RAISE EXCEPTION 'Activated quorum candidate pool is frozen';
    END IF;
    IF NEW.version <> OLD.version + 1 OR
       NOT ((OLD.status = 'WAITING' AND NEW.status IN ('IN_PROGRESS', 'CANCELLED', 'SKIPPED')) OR
            (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('IN_PROGRESS', 'APPROVED', 'REJECTED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'Invalid quorum stage transition or CAS version';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION apr_document_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Approval document journal entries are append-only' USING ERRCODE = '23514';
END $$;

CREATE OR REPLACE FUNCTION protect_approval_attachment_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF; RAISE EXCEPTION 'Approval attachment evidence is immutable' USING ERRCODE='23514'; END $$;

CREATE OR REPLACE FUNCTION protect_approval_attachment_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF;
    IF TG_OP='DELETE' OR (to_jsonb(NEW)-'consumed_revision') IS DISTINCT FROM (to_jsonb(OLD)-'consumed_revision')
       OR (OLD.consumed_revision IS NOT NULL AND NEW.consumed_revision IS DISTINCT FROM OLD.consumed_revision) THEN
        RAISE EXCEPTION 'Approval attachment preparation bindings are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION protect_apr_quorum_information_round() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Information round evidence is immutable'; END IF;
    IF (NEW.round_id, NEW.tenant_id, NEW.request_id, NEW.source_generation, NEW.target_generation,
        NEW.step_id, NEW.task_id, NEW.source_stage_version, NEW.actor_user_id, NEW.actor_person_id,
        NEW.principal_user_id, NEW.principal_person_id, NEW.delegation_id, NEW.context,
        NEW.retained_snapshots, NEW.reason, NEW.opened_at) IS DISTINCT FROM
       (OLD.round_id, OLD.tenant_id, OLD.request_id, OLD.source_generation, OLD.target_generation,
        OLD.step_id, OLD.task_id, OLD.source_stage_version, OLD.actor_user_id, OLD.actor_person_id,
        OLD.principal_user_id, OLD.principal_person_id, OLD.delegation_id, OLD.context,
        OLD.retained_snapshots, OLD.reason, OLD.opened_at)
       OR OLD.status <> 'OPEN' OR NEW.status <> 'RESPONDED' OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Invalid information round pins, transition or CAS version';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION protect_apr_quorum_information_command() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(
        TG_RELID::regclass,to_jsonb(OLD)) THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Information command evidence is immutable'; END IF;
    IF (NEW.tenant_id, NEW.request_id, NEW.idempotency_key, NEW.operation, NEW.command_sha256, NEW.created_at)
       IS DISTINCT FROM (OLD.tenant_id, OLD.request_id, OLD.idempotency_key, OLD.operation, OLD.command_sha256, OLD.created_at)
       OR OLD.status <> 'UNKNOWN' OR NEW.status <> 'COMPLETED' THEN
        RAISE EXCEPTION 'Invalid information command replay or transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_requests
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_requests
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_request_payloads
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_request_payloads
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_request_payload_versions
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_request_payload_versions
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_steps
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_steps
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_tasks
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_tasks
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_request_events
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_request_events
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_draft_commands
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_draft_commands
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_document_heads
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_document_heads
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_document_comments
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_document_comments
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_document_hold_proposals
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_document_hold_proposals
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_document_hold_journal
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_document_hold_journal
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_document_command_receipts
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_document_command_receipts
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_attachment_uploads
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_attachment_uploads
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_attachment_selections
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_attachment_selections
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_attachment_preparations
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_attachment_preparations
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_attachment_manifests
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_attachment_manifests
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_attachment_download_grants
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_attachment_download_grants
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_attachment_command_receipts
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_attachment_command_receipts
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_attachment_cleanup_journal
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_attachment_cleanup_journal
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_quorum_stage_runtime
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_quorum_stage_runtime
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_quorum_candidates
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_quorum_candidates
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_quorum_votes
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_quorum_votes
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_quorum_prerequisites
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_quorum_prerequisites
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_quorum_sla_timers
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_quorum_sla_timers
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_quorum_information_rounds
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_quorum_information_rounds
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_quorum_information_commands
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_quorum_information_commands
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_integration_outbox
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_integration_outbox
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_recovery_auditor_assignment_events
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_recovery_auditor_assignment_events
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.sys_audit_outbox
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.sys_audit_outbox
 FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
-- END GENERATED TEN DELETE PREFIXES AND STATIC CATALOG TRIGGERS

GRANT SELECT ON apr_requests,apr_tenants,apr_request_payloads,apr_request_payload_versions,apr_steps,apr_tasks,apr_request_events,
 apr_draft_commands,apr_document_heads,apr_document_comments,apr_document_hold_proposals,apr_document_hold_journal,apr_document_command_receipts,
 apr_document_policy_heads,apr_attachment_policy_heads,apr_attachment_uploads,apr_attachment_selections,apr_attachment_preparations,apr_attachment_manifests,
 apr_attachment_download_grants,apr_attachment_command_receipts,apr_attachment_cleanup_journal,apr_quorum_stage_runtime,apr_quorum_candidates,
 apr_quorum_votes,apr_quorum_prerequisites,apr_quorum_sla_timers,apr_quorum_information_rounds,apr_quorum_information_commands,
 apr_integration_outbox,apr_recovery_auditor_assignment_events,sys_audit_outbox TO dwp_approval_retention_owner;
-- Row locking requires UPDATE privilege; no actual mutation of these existing tables is granted by a callable routine.
GRANT UPDATE ON apr_requests,apr_tenants,apr_document_heads,apr_document_policy_heads,apr_attachment_policy_heads TO dwp_approval_retention_owner;
ALTER TABLE apr_retention_policy_heads OWNER TO dwp_approval_retention_owner;
ALTER TABLE apr_retention_policy_versions OWNER TO dwp_approval_retention_owner;
ALTER TABLE apr_retention_policy_publications OWNER TO dwp_approval_retention_owner;
ALTER TABLE apr_record_retention_heads OWNER TO dwp_approval_retention_owner;
ALTER TABLE apr_record_purge_claims OWNER TO dwp_approval_retention_owner;
ALTER TABLE apr_record_purge_rows OWNER TO dwp_approval_retention_owner;
ALTER TABLE apr_record_purge_objects OWNER TO dwp_approval_retention_owner;
ALTER TABLE apr_record_purge_journal OWNER TO dwp_approval_retention_owner;
ALTER TABLE apr_record_tombstones OWNER TO dwp_approval_retention_owner;
ALTER TABLE apr_retention_internal.delete_permits OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.immutable() OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.validate_rules() OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.require_executor() OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.prepare_record(bigint,uuid,bigint) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.claim_record(uuid,bigint) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.authorized_delete(regclass,jsonb) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.lock_object_claim(uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.claim_object(uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.bind_object_presence(uuid,bigint,uuid,text,text) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.finish_object(uuid,bigint,uuid,text,boolean) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.object_identity_immutable() OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.deny_unpermitted_delete() OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.consume_delete_permit() OWNER TO dwp_approval_retention_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA apr_retention_internal FROM PUBLIC,dwp_approval_retention_executor;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA apr_retention_internal FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apr_retention_internal.prepare_record(bigint,uuid,bigint),apr_retention_internal.claim_record(uuid,bigint)
 TO dwp_approval_retention_executor;
GRANT EXECUTE ON FUNCTION apr_retention_internal.claim_object(uuid),apr_retention_internal.bind_object_presence(uuid,bigint,uuid,text,text),apr_retention_internal.finish_object(uuid,bigint,uuid,text,boolean)
 TO dwp_approval_retention_executor;
COMMENT ON TABLE apr_record_retention_heads IS 'Protocol-only. Runtime remains disabled until owner reads/writes, hold, timer and outbox fences are reviewed and installed.';
COMMENT ON TABLE apr_record_tombstones IS 'Minimal linkable correlation metadata, not anonymous data, global erase proof, WORM or KMS assurance.';
