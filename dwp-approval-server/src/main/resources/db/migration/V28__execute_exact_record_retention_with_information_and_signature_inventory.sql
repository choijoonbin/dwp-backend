-- Forward-only extension. No existing migration, publication or live grant is rewritten.
ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb) RENAME TO exact_primary_key_v24;
CREATE FUNCTION apr_retention_internal.exact_primary_key(p_table REGCLASS,p_row JSONB) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE keys TEXT[]; result JSONB;
BEGIN
    keys:=CASE p_table
      WHEN 'public.apr_quorum_information_admissions'::regclass THEN ARRAY['tenant_id','request_id','idempotency_key']
      WHEN 'public.apr_quorum_information_completion_transactions'::regclass THEN ARRAY['tenant_id','request_id','idempotency_key']
      WHEN 'public.apr_self_attestation_artifacts'::regclass THEN ARRAY['tenant_id','request_id','artifact_id']
      WHEN 'public.apr_self_attestations'::regclass THEN ARRAY['tenant_id','signature_request_id']
      WHEN 'public.apr_self_attestation_consents'::regclass THEN ARRAY['tenant_id','signature_request_id','consent_receipt_id']
      WHEN 'public.apr_self_attestation_evidence'::regclass THEN ARRAY['tenant_id','signature_request_id','evidence_id']
      WHEN 'public.apr_self_attestation_commands'::regclass THEN ARRAY['tenant_id','actor_user_id','operation','idempotency_key']
      WHEN 'public.apr_self_attestation_events'::regclass THEN ARRAY['tenant_id','signature_request_id','sequence'] ELSE NULL END;
    IF keys IS NULL THEN RETURN apr_retention_internal.exact_primary_key_v24(p_table,p_row); END IF;
    IF EXISTS(SELECT 1 FROM unnest(keys) k WHERE p_row->k IS NULL OR p_row->k='null'::jsonb) THEN
        RAISE EXCEPTION 'Incomplete retention catalog identity' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_object_agg(k,p_row->k) INTO result FROM unnest(keys) k;
    RETURN result;
END $$;
ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid) RENAME TO catalog_rows_v24;
CREATE FUNCTION apr_retention_internal.catalog_rows(p_tenant BIGINT,p_request UUID)
RETURNS TABLE(table_oid REGCLASS,primary_key JSONB,row_sha256 TEXT,row_data JSONB)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
WITH rows(table_oid,row_data) AS (
 SELECT 'public.apr_quorum_information_admissions'::regclass,to_jsonb(r) FROM public.apr_quorum_information_admissions r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_quorum_information_completion_transactions'::regclass,to_jsonb(r) FROM public.apr_quorum_information_completion_transactions r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_self_attestation_artifacts'::regclass,to_jsonb(r) FROM public.apr_self_attestation_artifacts r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_self_attestations'::regclass,to_jsonb(r) FROM public.apr_self_attestations r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_self_attestation_commands'::regclass,to_jsonb(r) FROM public.apr_self_attestation_commands r WHERE tenant_id=p_tenant AND request_id=p_request
 UNION ALL SELECT 'public.apr_self_attestation_consents'::regclass,to_jsonb(r) FROM public.apr_self_attestation_consents r JOIN public.apr_self_attestations s USING(tenant_id,signature_request_id) WHERE s.tenant_id=p_tenant AND s.request_id=p_request
 UNION ALL SELECT 'public.apr_self_attestation_evidence'::regclass,to_jsonb(r) FROM public.apr_self_attestation_evidence r JOIN public.apr_self_attestations s USING(tenant_id,signature_request_id) WHERE s.tenant_id=p_tenant AND s.request_id=p_request
 UNION ALL SELECT 'public.apr_self_attestation_events'::regclass,to_jsonb(r) FROM public.apr_self_attestation_events r JOIN public.apr_self_attestations s USING(tenant_id,signature_request_id) WHERE s.tenant_id=p_tenant AND s.request_id=p_request
)
SELECT * FROM apr_retention_internal.catalog_rows_v24(p_tenant,p_request)
UNION ALL SELECT table_oid,apr_retention_internal.exact_primary_key(table_oid,row_data),encode(sha256(convert_to(row_data::text,'UTF8')),'hex'),row_data FROM rows;
$$;

CREATE OR REPLACE FUNCTION apr_retention_internal.prepare_record(p_tenant BIGINT,p_request UUID,p_version BIGINT)
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
         GREATEST((row_data->>'created_at')::timestamptz,(row_data->>'completed_at')::timestamptz,
           (row_data->>'accepted_at')::timestamptz,(row_data->>'consented_at')::timestamptz,
           (row_data->>'occurred_at')::timestamptz)+make_interval(days=>CASE WHEN table_oid IN('public.apr_document_hold_journal'::regclass,'public.apr_document_hold_proposals'::regclass) THEN (rules->>'holdEvidenceRetentionDays')::int
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

CREATE FUNCTION apr_retention_internal.purge_local_record(p_claim UUID,p_head_version BIGINT) RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE c public.apr_record_purge_claims; h public.apr_record_retention_heads;
BEGIN
    PERFORM apr_retention_internal.require_executor();
    SELECT * INTO c FROM public.apr_record_purge_claims WHERE claim_id=p_claim;
    IF c.claim_id IS NULL THEN RAISE EXCEPTION 'Claim unavailable' USING ERRCODE='42501'; END IF;
    SELECT * INTO h FROM public.apr_record_retention_heads WHERE claim_id=p_claim;
    IF h.state='LOCAL_DB_PURGED' THEN
        IF h.version<>p_head_version OR NOT EXISTS(SELECT 1 FROM public.apr_record_tombstones WHERE tenant_id=c.tenant_id AND request_id=c.request_id AND claim_id=p_claim AND inventory_sha256=c.inventory_sha256) THEN
            RAISE EXCEPTION 'Purged record reconciliation mismatch' USING ERRCODE='40001'; END IF;
        RETURN 'LOCAL_DB_PURGED';
    END IF;
    PERFORM apr_retention_internal.lock_object_claim(p_claim);
    SELECT * INTO h FROM public.apr_record_retention_heads WHERE claim_id=p_claim FOR UPDATE;
    IF h.version<>p_head_version THEN RAISE EXCEPTION 'Executor head version changed' USING ERRCODE='40001'; END IF;
    IF EXISTS(SELECT 1 FROM public.apr_record_purge_objects WHERE claim_id=p_claim AND (state<>'CONFIRMED' OR version_id IS NULL OR presence_verified_at IS NULL OR storage_locator_sha256 IS NULL)) THEN
        RAISE EXCEPTION 'Exact object deletion not confirmed' USING ERRCODE='55000'; END IF;
    IF h.state='IRREVERSIBLE' AND NOT EXISTS(SELECT 1 FROM public.apr_record_purge_objects WHERE claim_id=p_claim) THEN
        UPDATE public.apr_record_retention_heads SET state='OBJECTS_CONFIRMED',version=version+1 WHERE claim_id=p_claim;
        SELECT * INTO h FROM public.apr_record_retention_heads WHERE claim_id=p_claim;
    END IF;
    IF h.state<>'OBJECTS_CONFIRMED' THEN RAISE EXCEPTION 'Objects must be confirmed' USING ERRCODE='40001'; END IF;
    IF EXISTS(SELECT 1 FROM public.apr_record_tombstones WHERE tenant_id=c.tenant_id AND request_id=c.request_id) THEN
        RAISE EXCEPTION 'Record already tombstoned' USING ERRCODE='40001'; END IF;
    INSERT INTO apr_retention_internal.delete_permits
      SELECT pg_backend_pid(),txid_current(),p_claim,table_oid,primary_key,row_sha256,h.version FROM public.apr_record_purge_rows WHERE claim_id=p_claim;
    -- Exact static DELETE statements are appended below in FK dependency order.
    -- V27's consent/head cycle is already initially deferred; no source row is modified to break it.
    SET CONSTRAINTS public.fk_apr_self_attestation_consent DEFERRED;
    SET CONSTRAINTS public.fk_apr_document_pending_hold DEFERRED;
    DELETE FROM public.sys_audit_outbox row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.sys_audit_outbox'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_recovery_auditor_assignment_events row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_recovery_auditor_assignment_events'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_integration_outbox row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_integration_outbox'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_draft_commands row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_draft_commands'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_quorum_information_admissions row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_quorum_information_admissions'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_quorum_information_completion_transactions row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_quorum_information_completion_transactions'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_quorum_information_commands row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_quorum_information_commands'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_quorum_information_rounds row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_quorum_information_rounds'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_self_attestation_commands row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_self_attestation_commands'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_self_attestation_evidence row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_self_attestation_evidence'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_self_attestation_events row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_self_attestation_events'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_self_attestation_consents row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_self_attestation_consents'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_self_attestations row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_self_attestations'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_self_attestation_artifacts row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_self_attestation_artifacts'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_document_command_receipts row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_document_command_receipts'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_document_comments row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_document_comments'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_document_hold_journal row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_document_hold_journal'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_document_hold_proposals row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_document_hold_proposals'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_document_heads row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_document_heads'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_attachment_command_receipts row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_attachment_command_receipts'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_attachment_download_grants row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_attachment_download_grants'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_attachment_cleanup_journal row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_attachment_cleanup_journal'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_attachment_preparations row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_attachment_preparations'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_attachment_manifests row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_attachment_manifests'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_attachment_selections row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_attachment_selections'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_attachment_uploads row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_attachment_uploads'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_quorum_sla_timers row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_quorum_sla_timers'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_quorum_prerequisites row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_quorum_prerequisites'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_quorum_votes row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_quorum_votes'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_quorum_candidates row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_quorum_candidates'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_quorum_stage_runtime row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_quorum_stage_runtime'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_tasks row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_tasks'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_steps row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_steps'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_request_events row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_request_events'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_request_payload_versions row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_request_payload_versions'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_request_payloads row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_request_payloads'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_requests row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_requests'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    IF EXISTS(SELECT 1 FROM apr_retention_internal.delete_permits WHERE claim_id=p_claim AND backend_pid=pg_backend_pid() AND transaction_id=txid_current()) THEN
        RAISE EXCEPTION 'Incomplete exact row deletion' USING ERRCODE='40001'; END IF;
    IF EXISTS(SELECT 1 FROM apr_retention_internal.catalog_rows(c.tenant_id,c.request_id)) THEN
        RAISE EXCEPTION 'Record rows remain after deletion' USING ERRCODE='40001'; END IF;
    INSERT INTO public.apr_record_tombstones(tenant_id,request_id,claim_id,inventory_sha256) VALUES(c.tenant_id,c.request_id,p_claim,c.inventory_sha256);
    UPDATE public.apr_record_retention_heads SET state='LOCAL_DB_PURGED',version=version+1 WHERE claim_id=p_claim AND state='OBJECTS_CONFIRMED' AND version=h.version;
    IF NOT FOUND THEN RAISE EXCEPTION 'Local purge CAS changed' USING ERRCODE='40001'; END IF;
    INSERT INTO public.apr_record_purge_journal VALUES(gen_random_uuid(),p_claim,'LOCAL_DB_PURGED','LOCAL_ONLY_FOREIGN_COPY_ACKS_PENDING',c.inventory_sha256,clock_timestamp());
    RETURN 'LOCAL_DB_PURGED';
END $$;

CREATE OR REPLACE FUNCTION public.protect_apr_information_completion_transaction() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF NOT apr_retention_internal.authorized_delete(TG_RELID,to_jsonb(OLD)) THEN
            RAISE EXCEPTION 'Exact retention permit required' USING ERRCODE='42501';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP <> 'INSERT' OR pg_trigger_depth() <> 2 OR NEW.transaction_id <> pg_current_xact_id()
        OR NOT EXISTS (SELECT 1 FROM apr_quorum_information_commands WHERE tenant_id=NEW.tenant_id
            AND request_id=NEW.request_id AND idempotency_key=NEW.idempotency_key
            AND status='COMPLETED' AND command_sha256=NEW.command_sha256) THEN
        RAISE EXCEPTION 'A completion transaction marker can only be recorded by the completed command transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION public.validate_apr_quorum_information_admission() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    command apr_quorum_information_commands%ROWTYPE;
    original apr_quorum_information_rounds%ROWTYPE;
    task apr_tasks%ROWTYPE;
    expected_keys TEXT[] := ARRAY['acceptedAt','admissionEvaluatedAt','admissionExpiresAt','admissionIssuer',
        'admissionJti','admissionKeyId','admissionOwnerAuthRevision','admissionOwnerPolicyRevision',
        'admissionSha256','admissionSourceRevision','admissionSourceVectorSha256','commandActorId',
        'commandActorPersonPublicId','commandSha256','completedAt','materialChange','operation',
        'originalExpectedVersion','ownerProofJti','rawBodySha256','receiptGeneration','receiptPayloadRevision',
        'receiptPayloadSha256','receiptRequestVersion','receiptSha256','roundId','sourceGeneration','stepKey',
        'taskId','transportProofJti'];
    actual_keys TEXT[];
    numeric_keys TEXT[] := ARRAY['acceptedAt','admissionEvaluatedAt','admissionExpiresAt','commandActorId','completedAt',
        'originalExpectedVersion','receiptGeneration','receiptPayloadRevision','receiptRequestVersion','sourceGeneration'];
    uuid_keys TEXT[] := ARRAY['admissionJti','commandActorPersonPublicId','ownerProofJti','roundId','taskId','transportProofJti'];
    receipt JSONB;
    canonical_receipt TEXT;
BEGIN
    IF TG_OP='DELETE' THEN
        IF NOT apr_retention_internal.authorized_delete(TG_RELID,to_jsonb(OLD)) THEN
            RAISE EXCEPTION 'Exact retention permit required' USING ERRCODE='42501';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'Verified information admission evidence is append-only'; END IF;
    SELECT array_agg(key ORDER BY key) INTO actual_keys FROM jsonb_object_keys(NEW.admission) key;
    IF actual_keys IS DISTINCT FROM expected_keys OR EXISTS
        (SELECT 1 FROM jsonb_each(NEW.admission) item WHERE item.value = 'null'::jsonb) THEN
        RAISE EXCEPTION 'Invalid information admission contract';
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_each(NEW.admission) item WHERE
        (item.key=ANY(numeric_keys) AND (jsonb_typeof(item.value)<>'number'
            OR NOT (item.value::text ~ '^(0|[1-9][0-9]*)$') OR (item.value::text)::numeric>9007199254740991))
        OR (item.key='materialChange' AND jsonb_typeof(item.value)<>'boolean')
        OR (NOT item.key=ANY(numeric_keys) AND item.key<>'materialChange' AND jsonb_typeof(item.value)<>'string')
        OR (item.key=ANY(uuid_keys) AND NOT ((item.value#>>'{}') ~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'))) THEN
        RAISE EXCEPTION 'Invalid information admission field types';
    END IF;
    SELECT * INTO STRICT command FROM apr_quorum_information_commands
        WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id AND idempotency_key=NEW.idempotency_key FOR SHARE;
    SELECT * INTO STRICT original FROM apr_quorum_information_rounds
        WHERE round_id=NEW.round_id AND tenant_id=NEW.tenant_id AND request_id=NEW.request_id
          AND source_generation=NEW.source_generation AND task_id=NEW.task_id;
    SELECT * INTO STRICT task FROM apr_tasks WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id
        AND step_id=original.step_id AND task_id=NEW.task_id;
    receipt := command.receipt;
    IF command.status <> 'COMPLETED' OR command.operation <> NEW.operation OR command.command_sha256 <> NEW.command_sha256
        OR NOT EXISTS (SELECT 1 FROM apr_quorum_information_completion_transactions WHERE tenant_id=NEW.tenant_id
            AND request_id=NEW.request_id AND idempotency_key=NEW.idempotency_key
            AND command_sha256=NEW.command_sha256 AND transaction_id=pg_current_xact_id())
        OR (NEW.operation='REQUEST_INFO' AND (NEW.actor_user_id,NEW.actor_person_id)
            IS DISTINCT FROM (original.actor_user_id,original.actor_person_id))
        OR (NEW.operation='REPLY' AND (NEW.actor_user_id,NEW.actor_person_id) IS DISTINCT FROM
            ((original.context->>'requesterUserId')::bigint,(original.context->>'requesterPersonId')::uuid))
        OR (task.decision_actor_user_id,task.decision_actor_person_public_id)
            IS DISTINCT FROM (original.actor_user_id,original.actor_person_id) THEN
        RAISE EXCEPTION 'Information admission is not bound to its completed command actor';
    END IF;
    SELECT array_agg(key ORDER BY key) INTO actual_keys FROM jsonb_object_keys(receipt) key;
    IF actual_keys IS DISTINCT FROM ARRAY['generation','materialChange','payloadRevision','payloadSha256','requestVersion','roundId','status']
        OR receipt->>'status' <> 'COMPLETED' OR (receipt->>'roundId')::uuid <> NEW.round_id
        OR (receipt->>'generation')::bigint <> (CASE NEW.operation WHEN 'REQUEST_INFO' THEN NEW.source_generation ELSE original.target_generation END)
        OR (receipt->>'requestVersion')::bigint < 1 OR (receipt->>'payloadRevision')::integer < 1
        OR NOT (receipt->>'payloadSha256' ~ '^[a-f0-9]{64}$')
        OR jsonb_typeof(receipt->'materialChange') <> 'boolean' THEN
        RAISE EXCEPTION 'Invalid completed information receipt';
    END IF;
    -- This closed seven-field JSON is the same compact, recursively sorted format used by both services.
    canonical_receipt := '{"generation":'||(receipt->>'generation')||',"materialChange":'||(receipt->>'materialChange')
        ||',"payloadRevision":'||(receipt->>'payloadRevision')||',"payloadSha256":"'||(receipt->>'payloadSha256')
        ||'","requestVersion":'||(receipt->>'requestVersion')||',"roundId":"'||NEW.round_id::text
        ||'","status":"COMPLETED"}';
    IF NEW.receipt_sha256 <> encode(sha256(convert_to(canonical_receipt,'UTF8')),'hex')
        OR NEW.admission->>'operation' <> NEW.operation OR NEW.admission->>'commandSha256' <> NEW.command_sha256
        OR NEW.admission->>'rawBodySha256' <> NEW.raw_body_sha256 OR NEW.admission->>'receiptSha256' <> NEW.receipt_sha256
        OR (NEW.admission->>'roundId')::uuid <> NEW.round_id OR (NEW.admission->>'taskId')::uuid <> NEW.task_id
        OR (NEW.admission->>'sourceGeneration')::bigint <> NEW.source_generation
        OR (NEW.admission->>'commandActorId')::bigint <> NEW.actor_user_id
        OR (NEW.admission->>'commandActorPersonPublicId')::uuid <> NEW.actor_person_id
        OR (NEW.admission->>'admissionJti')::uuid <> NEW.admission_jti
        OR NEW.admission->'receiptGeneration' <> receipt->'generation'
        OR NEW.admission->'receiptRequestVersion' <> receipt->'requestVersion'
        OR NEW.admission->'receiptPayloadRevision' <> receipt->'payloadRevision'
        OR NEW.admission->'receiptPayloadSha256' <> receipt->'payloadSha256'
        OR NEW.admission->'materialChange' <> receipt->'materialChange'
        OR NEW.admission->>'stepKey' <> (SELECT stage_key FROM apr_quorum_stage_runtime WHERE tenant_id=NEW.tenant_id
            AND request_id=NEW.request_id AND step_id=original.step_id AND generation=NEW.source_generation)
        OR (NEW.operation='REQUEST_INFO' AND (NEW.admission->>'originalExpectedVersion')::bigint <> task.version-1)
        OR (NEW.operation='REPLY' AND (NEW.admission->>'originalExpectedVersion')::bigint <> (receipt->>'requestVersion')::bigint-1)
        OR (NEW.admission->>'originalExpectedVersion')::bigint < 0 THEN
        RAISE EXCEPTION 'Information admission pins or digest do not match';
    END IF;
    IF NEW.admission->>'admissionIssuer' <> 'dwp-auth-server:workflow-runtime:information-admission:v1'
        OR NOT (NEW.admission->>'admissionSha256' ~ '^[a-f0-9]{64}$')
        OR NOT (NEW.admission->>'admissionSourceVectorSha256' ~ '^[a-f0-9]{64}$')
        OR NOT (NEW.admission->>'admissionSourceRevision' ~ '^awr-[a-f0-9]{64}$')
        OR NOT (NEW.admission->>'admissionKeyId' ~ '^[A-Za-z0-9._:-]{1,80}$')
        OR length(NEW.admission->>'admissionOwnerAuthRevision') NOT BETWEEN 1 AND 200
        OR length(NEW.admission->>'admissionOwnerPolicyRevision') NOT BETWEEN 1 AND 200
        OR (NEW.admission->>'acceptedAt')::bigint <> floor(extract(epoch FROM NEW.accepted_at))::bigint
        OR (NEW.admission->>'completedAt')::bigint <> floor(extract(epoch FROM command.completed_at))::bigint
        OR command.completed_at > NEW.accepted_at
        OR NEW.accepted_at > clock_timestamp()
        OR (NEW.admission->>'admissionEvaluatedAt')::bigint < 1
        OR (NEW.admission->>'admissionEvaluatedAt')::bigint > floor(extract(epoch FROM command.completed_at))::bigint
        OR (NEW.admission->>'admissionEvaluatedAt')::bigint > floor(extract(epoch FROM NEW.accepted_at))::bigint
        OR (NEW.admission->>'admissionExpiresAt')::bigint <= extract(epoch FROM NEW.accepted_at)
        OR (NEW.admission->>'admissionExpiresAt')::bigint <= extract(epoch FROM clock_timestamp())
        OR (NEW.admission->>'admissionExpiresAt')::bigint-(NEW.admission->>'admissionEvaluatedAt')::bigint > 30
        OR (NEW.admission->>'ownerProofJti')::uuid = (NEW.admission->>'transportProofJti')::uuid THEN
        RAISE EXCEPTION 'Information admission was not valid at its acceptance time';
    END IF;
    RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION public.guard_apr_self_attestation_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF NOT apr_retention_internal.authorized_delete(TG_RELID,to_jsonb(OLD)) THEN
            RAISE EXCEPTION 'Exact retention permit required' USING ERRCODE='42501';
        END IF;
        RETURN OLD;
    END IF;
 RAISE EXCEPTION 'Self-attestation artifacts, consents, evidence, events and private command receipts are append only'; END $$;
CREATE OR REPLACE FUNCTION public.guard_apr_self_attestation_head() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF NOT apr_retention_internal.authorized_delete(TG_RELID,to_jsonb(OLD)) THEN
            RAISE EXCEPTION 'Exact retention permit required' USING ERRCODE='42501';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Self-attestation head cannot be deleted'; END IF;
    IF NEW.source_digest::text IS DISTINCT FROM encode(sha256(convert_to(approval_typed_form_canonical_json(
        jsonb_build_object('source',NEW.source_pin,'terms',NEW.terms)),'UTF8')),'hex')
       OR (NEW.source_pin->>'requestId') IS DISTINCT FROM NEW.request_id::text
       OR (NEW.source_pin->>'ownerUserId')::bigint IS DISTINCT FROM NEW.owner_user_id
       OR (NEW.source_pin->>'resourceSetKey') IS DISTINCT FROM NEW.resource_set_key
       OR (NEW.source_pin->>'formVersionId') IS DISTINCT FROM NEW.form_version_id::text
       OR (NEW.source_pin->>'workflowVersionId') IS DISTINCT FROM NEW.workflow_version_id::text
       OR (NEW.source_pin->>'providerId') IS DISTINCT FROM NEW.provider_id::text
       OR (NEW.source_pin->>'payloadRevision')::integer IS DISTINCT FROM NEW.payload_revision
       OR (NEW.source_pin->>'documentPolicyId') IS DISTINCT FROM NEW.document_policy_id::text
       OR (NEW.source_pin->>'documentPolicyRevision')::integer IS DISTINCT FROM NEW.document_policy_revision
       OR (NEW.source_pin->>'attachmentPolicyId') IS DISTINCT FROM NEW.attachment_policy_id::text
       OR (NEW.source_pin->>'attachmentPolicyRevision')::integer IS DISTINCT FROM NEW.attachment_policy_revision
       OR NOT EXISTS(SELECT 1 FROM apr_self_attestation_artifacts a WHERE a.tenant_id=NEW.tenant_id AND a.request_id=NEW.request_id
             AND a.artifact_id=NEW.artifact_id AND a.artifact_sha256::text=NEW.source_pin->>'artifactSha256'
             AND a.renderer_version=NEW.source_pin->>'rendererVersion')
       THEN RAISE EXCEPTION 'Self-attestation source seal is invalid'; END IF;
    IF TG_OP='UPDATE' AND ((to_jsonb(NEW)-'version'-'state'-'consent_receipt_id') IS DISTINCT FROM (to_jsonb(OLD)-'version'-'state'-'consent_receipt_id')
       OR NEW.version<>OLD.version+1 OR NOT ((OLD.state='AWAITING_CONSENT' AND NEW.state IN('CONSENTED','CANCELLED'))
         OR (OLD.state='CONSENTED' AND NEW.state IN('ATTESTED','CANCELLED')))
       OR (OLD.consent_receipt_id IS NOT NULL AND NEW.consent_receipt_id IS DISTINCT FROM OLD.consent_receipt_id))
       THEN RAISE EXCEPTION 'Self-attestation state or source is immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_quorum_information_admissions FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_quorum_information_admissions FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_quorum_information_completion_transactions FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_quorum_information_completion_transactions FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_self_attestation_artifacts FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_self_attestation_artifacts FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_self_attestations FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_self_attestations FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_self_attestation_consents FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_self_attestation_consents FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_self_attestation_evidence FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_self_attestation_evidence FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_self_attestation_commands FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_self_attestation_commands FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_self_attestation_events FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete AFTER DELETE ON public.apr_self_attestation_events FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
GRANT SELECT,DELETE ON public.sys_audit_outbox,public.apr_recovery_auditor_assignment_events,public.apr_integration_outbox,public.apr_draft_commands,public.apr_quorum_information_admissions,public.apr_quorum_information_completion_transactions,public.apr_quorum_information_commands,public.apr_quorum_information_rounds,public.apr_self_attestation_commands,public.apr_self_attestation_evidence,public.apr_self_attestation_events,public.apr_self_attestation_consents,public.apr_self_attestations,public.apr_self_attestation_artifacts,public.apr_document_command_receipts,public.apr_document_comments,public.apr_document_hold_journal,public.apr_document_hold_proposals,public.apr_document_heads,public.apr_attachment_download_grants,public.apr_attachment_cleanup_journal,public.apr_attachment_preparations,public.apr_attachment_manifests,public.apr_attachment_selections,public.apr_attachment_uploads,public.apr_quorum_sla_timers,public.apr_quorum_prerequisites,public.apr_quorum_votes,public.apr_quorum_candidates,public.apr_quorum_stage_runtime,public.apr_tasks,public.apr_steps,public.apr_request_events,public.apr_request_payload_versions,public.apr_request_payloads,public.apr_requests TO dwp_approval_retention_owner;

ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb) OWNER TO dwp_approval_retention_owner;
GRANT SELECT,DELETE ON public.apr_attachment_command_receipts TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA apr_retention_internal FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) TO dwp_approval_retention_executor;
COMMENT ON FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) IS 'Local-only exact retention executor. No scheduler, provider activation, end-user permission grant or global erasure claim.';
