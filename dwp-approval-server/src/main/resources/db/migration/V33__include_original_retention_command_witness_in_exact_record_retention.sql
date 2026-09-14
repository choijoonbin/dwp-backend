-- Forward-only exact owner inventory. No historical body, runtime grant or global erasure claim changes.
ALTER TABLE public.apr_retention_dispatch_intents ADD COLUMN original_inventory_sha256 CHAR(64)
    CHECK(original_inventory_sha256 IS NULL OR original_inventory_sha256 ~ '^[a-f0-9]{64}$');
ALTER TABLE public.apr_retention_dispatch_intents ADD COLUMN receipt_command_id UUID;
CREATE TABLE public.apr_retention_inventory_closures (
    tenant_id BIGINT NOT NULL, intent_id UUID NOT NULL, command_id UUID NOT NULL,
    original_inventory_sha256 CHAR(64) NOT NULL CHECK(original_inventory_sha256 ~ '^[a-f0-9]{64}$'),
    dispatch_inventory_sha256 CHAR(64) NOT NULL CHECK(dispatch_inventory_sha256 ~ '^[a-f0-9]{64}$'),
    witness_sha256 CHAR(64) NOT NULL CHECK(witness_sha256 ~ '^[a-f0-9]{64}$'),
    closed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,intent_id), UNIQUE(tenant_id,command_id),
    FOREIGN KEY(tenant_id,intent_id) REFERENCES public.apr_retention_dispatch_intents(tenant_id,intent_id)
);
ALTER TABLE public.apr_retention_dispatch_intents ADD CONSTRAINT fk_retention_intent_receipt_closure
    FOREIGN KEY(tenant_id,receipt_command_id) REFERENCES public.apr_retention_inventory_closures(tenant_id,command_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.apr_retention_original_command_witnesses ADD COLUMN receipt_closure_id UUID
    GENERATED ALWAYS AS(CASE WHEN operation='CLAIM_RECORD' THEN command_id ELSE NULL END) STORED;
ALTER TABLE public.apr_retention_original_command_witnesses ADD CONSTRAINT fk_retention_witness_closure
    FOREIGN KEY(tenant_id,receipt_closure_id) REFERENCES public.apr_retention_inventory_closures(tenant_id,command_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.apr_retention_inventory_closures ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.apr_retention_inventory_closures FORCE ROW LEVEL SECURITY;
CREATE POLICY retention_inventory_closure_owner_read ON public.apr_retention_inventory_closures
    FOR SELECT TO dwp_approval_retention_owner USING(true);
CREATE POLICY retention_inventory_closure_owner_insert ON public.apr_retention_inventory_closures
    FOR INSERT TO dwp_approval_retention_owner WITH CHECK(true);
CREATE POLICY retention_inventory_closure_executor_read ON public.apr_retention_inventory_closures
    FOR SELECT TO dwp_approval_retention_executor USING(true);
GRANT SELECT,INSERT ON public.apr_retention_inventory_closures TO dwp_approval_retention_owner;
GRANT SELECT ON public.apr_retention_inventory_closures TO dwp_approval_retention_executor;
REVOKE ALL ON public.apr_retention_inventory_closures FROM PUBLIC;
CREATE FUNCTION apr_retention_internal.validate_inventory_closure_insert() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE i public.apr_retention_dispatch_intents; w public.apr_retention_original_command_witnesses;
BEGIN
    IF TG_OP<>'INSERT' OR pg_trigger_depth()<>2 THEN
        RAISE EXCEPTION 'Only the original same transaction source closure may write' USING ERRCODE='42501';
    END IF;
    SELECT * INTO i FROM public.apr_retention_dispatch_intents WHERE tenant_id=NEW.tenant_id AND intent_id=NEW.intent_id;
    SELECT * INTO w FROM public.apr_retention_original_command_witnesses WHERE tenant_id=NEW.tenant_id AND command_id=NEW.command_id;
    IF COALESCE(i.intent_id IS NULL OR w.command_id IS NULL OR i.receipt_command_id IS NOT NULL
       OR i.version<>0 OR i.state<>'QUEUED' OR w.operation<>'CLAIM_RECORD' OR w.result_reference_id<>i.intent_id
       OR w.actor_user_id<>i.actor_user_id OR w.request_id<>i.request_id OR w.resource_set_key<>i.resource_set_key
       OR i.original_inventory_sha256<>NEW.original_inventory_sha256 OR i.inventory_sha256<>NEW.original_inventory_sha256
       OR NEW.witness_sha256<>encode(sha256(convert_to(to_jsonb(w)::text,'UTF8')),'hex')
       OR (SELECT xmin::text::bigint FROM public.apr_retention_dispatch_intents WHERE tenant_id=NEW.tenant_id AND intent_id=NEW.intent_id)<>txid_current()%4294967296
       OR (SELECT xmin::text::bigint FROM public.apr_retention_original_command_witnesses WHERE tenant_id=NEW.tenant_id AND command_id=NEW.command_id)<>txid_current()%4294967296,true) THEN
        RAISE EXCEPTION 'Original same transaction source closure changed' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_retention_closure_insert BEFORE INSERT ON public.apr_retention_inventory_closures
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.validate_inventory_closure_insert();
CREATE TRIGGER trg_retention_closure_immutable BEFORE UPDATE OR DELETE ON public.apr_retention_inventory_closures
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.immutable();
CREATE FUNCTION apr_retention_internal.close_intent_receipt_inventory(p_old public.apr_retention_dispatch_intents,p_new public.apr_retention_dispatch_intents)
RETURNS public.apr_retention_dispatch_intents LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE w public.apr_retention_original_command_witnesses; reviewed TEXT; sealed TEXT; witness_sha TEXT;
BEGIN
    IF pg_trigger_depth()<>1 OR p_old.receipt_command_id IS NOT NULL OR p_new.receipt_command_id IS NULL
       OR p_old.original_inventory_sha256 IS NULL OR p_old.original_inventory_sha256<>p_old.inventory_sha256
       OR p_old.version<>0 OR p_old.state<>'QUEUED'
       OR (to_jsonb(p_old)-'receipt_command_id') IS DISTINCT FROM (to_jsonb(p_new)-'receipt_command_id') THEN
        RAISE EXCEPTION 'Original receipt inventory can only close once before commit' USING ERRCODE='23514';
    END IF;
    SELECT * INTO w FROM public.apr_retention_original_command_witnesses WHERE command_id=p_new.receipt_command_id
        AND tenant_id=p_old.tenant_id AND actor_user_id=p_old.actor_user_id AND result_reference_id=p_old.intent_id
        AND request_id=p_old.request_id AND operation='CLAIM_RECORD' AND resource_set_key=p_old.resource_set_key
        AND original_expected_version=p_old.request_version AND parent_command_fingerprint=p_old.command_fingerprint;
    IF w.command_id IS NULL OR NOT EXISTS(SELECT 1 FROM public.apr_retention_dispatch_intents i
        WHERE i.tenant_id=p_old.tenant_id AND i.intent_id=p_old.intent_id AND i.xmin::text::bigint=txid_current()%4294967296)
       OR NOT EXISTS(SELECT 1 FROM public.apr_retention_original_command_witnesses row
        WHERE row.command_id=w.command_id AND row.xmin::text::bigint=txid_current()%4294967296)
       OR NOT EXISTS(SELECT 1 FROM public.apr_retention_management_commands parent
        WHERE parent.tenant_id=w.tenant_id AND parent.actor_user_id=w.actor_user_id AND parent.route=w.route
          AND parent.idempotency_key=w.idempotency_key AND parent.target_id=p_old.intent_id
          AND parent.fingerprint=p_old.command_fingerprint AND parent.xmin::text::bigint=txid_current()%4294967296)
       OR w.source_profile_sha256<>encode(sha256(convert_to(w.origin_authority_profile::text,'UTF8')),'hex') THEN
        RAISE EXCEPTION 'Exact original command transaction provenance required' USING ERRCODE='23514';
    END IF;
    SELECT encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex')
        INTO reviewed FROM apr_retention_internal.catalog_rows(p_old.tenant_id,p_old.request_id)
        WHERE NOT(table_oid='public.apr_retention_original_command_witnesses'::regclass AND primary_key=jsonb_build_object('command_id',w.command_id));
    IF reviewed IS DISTINCT FROM p_old.original_inventory_sha256 THEN
        RAISE EXCEPTION 'Unreviewed row or source version drift' USING ERRCODE='40001';
    END IF;
    SELECT encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex')
        INTO sealed FROM apr_retention_internal.catalog_rows(p_old.tenant_id,p_old.request_id);
    witness_sha:=encode(sha256(convert_to(to_jsonb(w)::text,'UTF8')),'hex');
    INSERT INTO public.apr_retention_inventory_closures(tenant_id,intent_id,command_id,original_inventory_sha256,dispatch_inventory_sha256,witness_sha256)
        VALUES(p_old.tenant_id,p_old.intent_id,w.command_id,reviewed,sealed,witness_sha);
    p_new.inventory_sha256:=sealed;
    RETURN p_new;
END $$;
CREATE OR REPLACE FUNCTION public.preserve_retention_dispatch_source() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP='UPDATE' AND OLD.receipt_command_id IS NULL AND NEW.receipt_command_id IS NOT NULL THEN
        RETURN apr_retention_internal.close_intent_receipt_inventory(OLD,NEW);
    END IF;
    IF TG_OP='DELETE' OR (to_jsonb(NEW)-ARRAY['version','state','execution_claim_id','reason_code'])
        IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['version','state','execution_claim_id','reason_code'])
        OR NEW.version<>OLD.version+1 THEN
        RAISE EXCEPTION 'Retention dispatch source is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
ALTER FUNCTION public.preserve_retention_dispatch_source() OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON FUNCTION public.preserve_retention_dispatch_source() FROM PUBLIC;
ALTER FUNCTION apr_retention_internal.close_intent_receipt_inventory(public.apr_retention_dispatch_intents,public.apr_retention_dispatch_intents) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.validate_inventory_closure_insert() OWNER TO dwp_approval_retention_owner;
COMMENT ON TABLE public.apr_retention_inventory_closures IS 'Retained legal/control evidence: opaque command/intent IDs and original/full sealed/witness hashes only, no body/profile/secrets/person/email. Excluded from request payload erasure like retained intents. Native FKs remain valid after witness/request purge. No global erasure claim.';
ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb) RENAME TO exact_primary_key_v31;
CREATE FUNCTION apr_retention_internal.exact_primary_key(p_table REGCLASS,p_row JSONB) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE keys TEXT[]:=ARRAY['command_id']; result JSONB;
BEGIN
    IF p_table<>'public.apr_retention_original_command_witnesses'::regclass THEN RETURN apr_retention_internal.exact_primary_key_v31(p_table,p_row); END IF;
    IF EXISTS(SELECT 1 FROM unnest(keys) k WHERE p_row->k IS NULL OR p_row->k='null'::jsonb) THEN
        RAISE EXCEPTION 'Incomplete retention catalog identity' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_object_agg(k,p_row->k) INTO result FROM unnest(keys) k;
    RETURN result;
END $$;
ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid) RENAME TO catalog_rows_v31;
CREATE FUNCTION apr_retention_internal.catalog_rows(p_tenant BIGINT,p_request UUID)
RETURNS TABLE(table_oid REGCLASS,primary_key JSONB,row_sha256 TEXT,row_data JSONB)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
SELECT * FROM apr_retention_internal.catalog_rows_v31(p_tenant,p_request)
UNION ALL SELECT 'public.apr_retention_original_command_witnesses'::regclass,
    apr_retention_internal.exact_primary_key('public.apr_retention_original_command_witnesses'::regclass,to_jsonb(r)),
    encode(sha256(convert_to(to_jsonb(r)::text,'UTF8')),'hex'),to_jsonb(r)
FROM public.apr_retention_original_command_witnesses r WHERE r.tenant_id=p_tenant AND r.request_id=p_request;
$$;
ALTER FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) RENAME TO purge_local_record_v31;
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
    DELETE FROM public.apr_retention_original_command_witnesses row USING public.apr_record_purge_rows pin
      WHERE pin.claim_id=p_claim AND pin.table_oid='public.apr_retention_original_command_witnesses'::regclass
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
CREATE TRIGGER trg_retention_deny_delete BEFORE DELETE ON public.apr_retention_original_command_witnesses
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
ALTER TRIGGER trg_retention_original_command_consume ON public.apr_retention_original_command_witnesses RENAME TO trg_retention_consume_delete;
ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid) OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON FUNCTION apr_retention_internal.purge_local_record_v31(uuid,bigint) FROM dwp_approval_retention_executor;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA apr_retention_internal FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) TO dwp_approval_retention_executor;
COMMENT ON FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) IS 'Exact 39-table local-only purge; current policy/hold/source/lease/private permit fences preserved. No runtime activation or global erasure claim.';
