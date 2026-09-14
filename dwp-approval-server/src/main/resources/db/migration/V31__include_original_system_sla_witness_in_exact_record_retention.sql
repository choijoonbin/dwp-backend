-- Forward-only exact owner inventory. No historical body, runtime grant or global erasure claim changes.
ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb) RENAME TO exact_primary_key_v28;
CREATE FUNCTION apr_retention_internal.exact_primary_key(p_table REGCLASS,p_row JSONB) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE keys TEXT[]:=ARRAY['tenant_id','event_id']; result JSONB;
BEGIN
    IF p_table<>'public.apr_system_sla_source_witnesses'::regclass THEN RETURN apr_retention_internal.exact_primary_key_v28(p_table,p_row); END IF;
    IF EXISTS(SELECT 1 FROM unnest(keys) k WHERE p_row->k IS NULL OR p_row->k='null'::jsonb) THEN
        RAISE EXCEPTION 'Incomplete retention catalog identity' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_object_agg(k,p_row->k) INTO result FROM unnest(keys) k;
    RETURN result;
END $$;
ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid) RENAME TO catalog_rows_v28;
CREATE FUNCTION apr_retention_internal.catalog_rows(p_tenant BIGINT,p_request UUID)
RETURNS TABLE(table_oid REGCLASS,primary_key JSONB,row_sha256 TEXT,row_data JSONB)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
SELECT * FROM apr_retention_internal.catalog_rows_v28(p_tenant,p_request)
UNION ALL SELECT 'public.apr_system_sla_source_witnesses'::regclass,
    apr_retention_internal.exact_primary_key('public.apr_system_sla_source_witnesses'::regclass,to_jsonb(r)),
    encode(sha256(convert_to(to_jsonb(r)::text,'UTF8')),'hex'),to_jsonb(r)
FROM public.apr_system_sla_source_witnesses r WHERE r.tenant_id=p_tenant AND r.request_id=p_request;
$$;
ALTER FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) RENAME TO purge_local_record_v28;
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
    DELETE FROM public.apr_system_sla_source_witnesses row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_system_sla_source_witnesses'::regclass
        AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
        AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
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
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_system_sla_source_witnesses
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON FUNCTION apr_retention_internal.purge_local_record_v28(uuid,bigint) FROM dwp_approval_retention_executor;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA apr_retention_internal FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) TO dwp_approval_retention_executor;
COMMENT ON FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) IS 'Exact 38-table local-only purge; current policy/hold/source/lease/private permit fences preserved. No runtime activation or global erasure claim.';
