-- Extend the exact request-retention inventory with V35 external-signature evidence.
-- Provider governance remains owned by V35; this migration only installs retention fences.

LOCK TABLE public.apr_requests, public.apr_request_payloads,
    public.apr_external_signature_requests, public.apr_external_signature_events,
    public.apr_external_signature_artifacts IN SHARE ROW EXCLUSIVE MODE;

CREATE FUNCTION apr_retention_internal.assert_external_signature_inventory_integrity() RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM public.apr_external_signature_requests signature
          LEFT JOIN public.apr_requests request
            ON request.tenant_id=signature.tenant_id AND request.request_id=signature.request_id
          LEFT JOIN public.apr_request_payloads payload
            ON payload.tenant_id=signature.tenant_id AND payload.request_id=signature.request_id
         WHERE request.request_id IS NULL OR payload.request_id IS NULL
            OR request.requester_user_id<>signature.owner_user_id
            OR request.management_resource_set_key<>signature.resource_set_key
            OR (SELECT array_agg(key ORDER BY key) FROM jsonb_object_keys(signature.source) key)
               IS DISTINCT FROM ARRAY['dataClassification','formVersionId','payloadRevision',
                   'payloadSha256','requestId','requestVersion','resourceSetKey','sourceSha256',
                   'workflowVersionId']::TEXT[]
            OR signature.source->>'requestId'<>signature.request_id::TEXT
            OR signature.source->>'requestVersion'<>request.version::TEXT
            OR signature.source->>'resourceSetKey'<>signature.resource_set_key
            OR signature.source->>'dataClassification'<>request.data_classification
            OR signature.source->>'workflowVersionId'<>request.workflow_version_id::TEXT
            OR signature.source->>'formVersionId'<>request.form_version_id::TEXT
            OR signature.source->>'payloadRevision'<>payload.schema_version::TEXT
            OR signature.source->>'payloadSha256'<>payload.payload_sha256
            OR signature.source->>'sourceSha256'<>signature.source_sha256
            OR signature.source_sha256<>encode(sha256(convert_to(
                '{"contract":"DWP_EXTERNAL_SIGNATURE_SOURCE_V1"'
                || ',"dataClassification":"' || request.data_classification || '"'
                || ',"formVersionId":"' || request.form_version_id::TEXT || '"'
                || ',"payloadRevision":' || payload.schema_version::TEXT
                || ',"payloadSha256":"' || payload.payload_sha256 || '"'
                || ',"requestId":"' || request.request_id::TEXT || '"'
                || ',"requestVersion":' || request.version::TEXT
                || ',"resourceSetKey":"' || request.management_resource_set_key || '"'
                || ',"workflowVersionId":"' || request.workflow_version_id::TEXT || '"}',
                'UTF8')),'hex')
    ) OR EXISTS (
        SELECT 1
          FROM public.apr_external_signature_events event
          LEFT JOIN public.apr_external_signature_requests signature
            ON signature.tenant_id=event.tenant_id
           AND signature.resource_set_key=event.resource_set_key
           AND signature.signature_request_id=event.signature_request_id
           AND signature.owner_user_id=event.owner_user_id
         WHERE signature.signature_request_id IS NULL
    ) OR EXISTS (
        SELECT 1
          FROM public.apr_external_signature_artifacts artifact
          LEFT JOIN public.apr_external_signature_requests signature
            ON signature.tenant_id=artifact.tenant_id
           AND signature.resource_set_key=artifact.resource_set_key
           AND signature.signature_request_id=artifact.signature_request_id
           AND signature.owner_user_id=artifact.owner_user_id
         WHERE signature.signature_request_id IS NULL
    ) OR EXISTS (
        SELECT 1
          FROM pg_catalog.pg_constraint constraint_row
         WHERE constraint_row.conrelid IN (
                   'public.apr_external_signature_requests'::regclass,
                   'public.apr_external_signature_events'::regclass,
                   'public.apr_external_signature_artifacts'::regclass)
           AND constraint_row.contype='f' AND NOT constraint_row.convalidated
    ) THEN
        RAISE EXCEPTION 'Existing external signature evidence is not exactly request-bound'
            USING ERRCODE='23514';
    END IF;
END $$;

SELECT apr_retention_internal.assert_external_signature_inventory_integrity();

ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb)
    RENAME TO exact_primary_key_v33;
CREATE FUNCTION apr_retention_internal.exact_primary_key(p_table REGCLASS,p_row JSONB) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE keys TEXT[]; result JSONB;
BEGIN
    IF p_table='public.apr_external_signature_requests'::regclass THEN
        keys:=ARRAY['tenant_id','resource_set_key','signature_request_id'];
    ELSIF p_table='public.apr_external_signature_events'::regclass THEN
        keys:=ARRAY['tenant_id','resource_set_key','signature_request_id','event_id'];
    ELSIF p_table='public.apr_external_signature_artifacts'::regclass THEN
        keys:=ARRAY['tenant_id','resource_set_key','signature_request_id','artifact_id'];
    ELSE
        RETURN apr_retention_internal.exact_primary_key_v33(p_table,p_row);
    END IF;
    IF EXISTS(SELECT 1 FROM unnest(keys) key
              WHERE p_row->key IS NULL OR p_row->key='null'::jsonb) THEN
        RAISE EXCEPTION 'Incomplete external signature retention identity' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_object_agg(key,p_row->key) INTO result FROM unnest(keys) key;
    RETURN result;
END $$;

ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid) RENAME TO catalog_rows_v33;
CREATE FUNCTION apr_retention_internal.catalog_rows(p_tenant BIGINT,p_request UUID)
RETURNS TABLE(table_oid REGCLASS,primary_key JSONB,row_sha256 TEXT,row_data JSONB)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
SELECT * FROM apr_retention_internal.catalog_rows_v33(p_tenant,p_request)
UNION ALL
SELECT 'public.apr_external_signature_requests'::regclass,
       apr_retention_internal.exact_primary_key(
           'public.apr_external_signature_requests'::regclass,to_jsonb(signature)),
       encode(sha256(convert_to(to_jsonb(signature)::text,'UTF8')),'hex'),to_jsonb(signature)
  FROM public.apr_external_signature_requests signature
 WHERE signature.tenant_id=p_tenant AND signature.request_id=p_request
UNION ALL
SELECT 'public.apr_external_signature_events'::regclass,
       apr_retention_internal.exact_primary_key(
           'public.apr_external_signature_events'::regclass,to_jsonb(event)),
       encode(sha256(convert_to(to_jsonb(event)::text,'UTF8')),'hex'),to_jsonb(event)
  FROM public.apr_external_signature_events event
  JOIN public.apr_external_signature_requests signature
    ON signature.tenant_id=event.tenant_id
   AND signature.resource_set_key=event.resource_set_key
   AND signature.signature_request_id=event.signature_request_id
   AND signature.owner_user_id=event.owner_user_id
 WHERE signature.tenant_id=p_tenant AND signature.request_id=p_request
UNION ALL
SELECT 'public.apr_external_signature_artifacts'::regclass,
       apr_retention_internal.exact_primary_key(
           'public.apr_external_signature_artifacts'::regclass,to_jsonb(artifact)),
       encode(sha256(convert_to(to_jsonb(artifact)::text,'UTF8')),'hex'),to_jsonb(artifact)
  FROM public.apr_external_signature_artifacts artifact
  JOIN public.apr_external_signature_requests signature
    ON signature.tenant_id=artifact.tenant_id
   AND signature.resource_set_key=artifact.resource_set_key
   AND signature.signature_request_id=artifact.signature_request_id
   AND signature.owner_user_id=artifact.owner_user_id
 WHERE signature.tenant_id=p_tenant AND signature.request_id=p_request;
$$;

-- Preserve every V35 INSERT/UPDATE invariant while routing DELETE through the exact permit guard.
DROP TRIGGER trg_apr_external_signature_request ON public.apr_external_signature_requests;
CREATE TRIGGER trg_apr_external_signature_request
    BEFORE INSERT OR UPDATE ON public.apr_external_signature_requests
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.guard_external_request();
DROP TRIGGER trg_apr_external_signature_event_immutable ON public.apr_external_signature_events;
CREATE TRIGGER trg_apr_external_signature_event_immutable
    BEFORE UPDATE ON public.apr_external_signature_events
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();
DROP TRIGGER trg_apr_external_signature_artifact_immutable ON public.apr_external_signature_artifacts;
CREATE TRIGGER trg_apr_external_signature_artifact_immutable
    BEFORE UPDATE ON public.apr_external_signature_artifacts
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();

CREATE POLICY apr_external_signature_request_retention_read
    ON public.apr_external_signature_requests FOR SELECT TO dwp_approval_retention_owner USING(true);
CREATE POLICY apr_external_signature_request_retention_delete
    ON public.apr_external_signature_requests FOR DELETE TO dwp_approval_retention_owner
    USING(apr_retention_internal.authorized_delete(
        'public.apr_external_signature_requests'::regclass,to_jsonb(apr_external_signature_requests)));
CREATE POLICY apr_external_signature_event_retention_read
    ON public.apr_external_signature_events FOR SELECT TO dwp_approval_retention_owner USING(true);
CREATE POLICY apr_external_signature_event_retention_delete
    ON public.apr_external_signature_events FOR DELETE TO dwp_approval_retention_owner
    USING(apr_retention_internal.authorized_delete(
        'public.apr_external_signature_events'::regclass,to_jsonb(apr_external_signature_events)));
CREATE POLICY apr_external_signature_artifact_retention_read
    ON public.apr_external_signature_artifacts FOR SELECT TO dwp_approval_retention_owner USING(true);
CREATE POLICY apr_external_signature_artifact_retention_delete
    ON public.apr_external_signature_artifacts FOR DELETE TO dwp_approval_retention_owner
    USING(apr_retention_internal.authorized_delete(
        'public.apr_external_signature_artifacts'::regclass,to_jsonb(apr_external_signature_artifacts)));

GRANT SELECT,DELETE ON public.apr_external_signature_requests,
    public.apr_external_signature_events,public.apr_external_signature_artifacts
    TO dwp_approval_retention_owner;
REVOKE ALL ON public.apr_external_signature_requests,
    public.apr_external_signature_events,public.apr_external_signature_artifacts FROM PUBLIC;

CREATE TRIGGER trg_retention_deny_delete
    BEFORE DELETE ON public.apr_external_signature_requests
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete
    AFTER DELETE ON public.apr_external_signature_requests
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete
    BEFORE DELETE ON public.apr_external_signature_events
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete
    AFTER DELETE ON public.apr_external_signature_events
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete
    BEFORE DELETE ON public.apr_external_signature_artifacts
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete
    AFTER DELETE ON public.apr_external_signature_artifacts
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();

-- V33 installs every row permit before deleting apr_requests. This trigger consumes the
-- three V35 families in FK-safe child-to-parent order inside that same purge transaction.
CREATE FUNCTION apr_retention_internal.delete_external_signature_record_rows() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE exact_claim UUID; permit_count INTEGER;
BEGIN
    IF TG_OP<>'DELETE' OR NOT apr_retention_internal.authorized_delete(TG_RELID::regclass,to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'Exact request retention permit required before signature evidence deletion'
            USING ERRCODE='23514';
    END IF;
    SELECT count(*),(array_agg(permit.claim_id ORDER BY permit.claim_id::TEXT))[1]
      INTO permit_count,exact_claim
      FROM apr_retention_internal.delete_permits permit
     WHERE permit.backend_pid=pg_backend_pid() AND permit.transaction_id=txid_current()
       AND permit.table_oid=TG_RELID::regclass
       AND permit.primary_key=apr_retention_internal.exact_primary_key(TG_RELID::regclass,to_jsonb(OLD))
       AND permit.row_sha256=encode(sha256(convert_to(to_jsonb(OLD)::text,'UTF8')),'hex');
    IF permit_count<>1 OR exact_claim IS NULL THEN
        RAISE EXCEPTION 'Exact request retention claim is ambiguous' USING ERRCODE='23514';
    END IF;

    DELETE FROM public.apr_external_signature_artifacts AS row
    USING public.apr_external_signature_requests AS signature,
          public.apr_record_purge_rows AS pin
     WHERE signature.tenant_id=OLD.tenant_id AND signature.request_id=OLD.request_id
       AND row.tenant_id=signature.tenant_id
       AND row.resource_set_key=signature.resource_set_key
       AND row.signature_request_id=signature.signature_request_id
       AND row.owner_user_id=signature.owner_user_id
       AND pin.claim_id=exact_claim
       AND pin.table_oid='public.apr_external_signature_artifacts'::regclass
       AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
       AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_external_signature_events AS row
    USING public.apr_external_signature_requests AS signature,
          public.apr_record_purge_rows AS pin
     WHERE signature.tenant_id=OLD.tenant_id AND signature.request_id=OLD.request_id
       AND row.tenant_id=signature.tenant_id
       AND row.resource_set_key=signature.resource_set_key
       AND row.signature_request_id=signature.signature_request_id
       AND row.owner_user_id=signature.owner_user_id
       AND pin.claim_id=exact_claim
       AND pin.table_oid='public.apr_external_signature_events'::regclass
       AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
       AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');
    DELETE FROM public.apr_external_signature_requests AS row
    USING public.apr_record_purge_rows AS pin
     WHERE row.tenant_id=OLD.tenant_id AND row.request_id=OLD.request_id
       AND pin.claim_id=exact_claim
       AND pin.table_oid='public.apr_external_signature_requests'::regclass
       AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
       AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');

    IF EXISTS(
        SELECT 1 FROM apr_retention_internal.delete_permits permit
         WHERE permit.backend_pid=pg_backend_pid() AND permit.transaction_id=txid_current()
           AND permit.claim_id=exact_claim
           AND permit.table_oid IN ('public.apr_external_signature_requests'::regclass,
               'public.apr_external_signature_events'::regclass,
               'public.apr_external_signature_artifacts'::regclass)
    ) OR EXISTS(
        SELECT 1 FROM public.apr_external_signature_requests signature
         WHERE signature.tenant_id=OLD.tenant_id AND signature.request_id=OLD.request_id
    ) THEN
        RAISE EXCEPTION 'Incomplete external signature evidence deletion' USING ERRCODE='40001';
    END IF;
    RETURN OLD;
END $$;

CREATE TRIGGER trg_retention_external_signature_children
    BEFORE DELETE ON public.apr_requests
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.delete_external_signature_record_rows();

ALTER FUNCTION apr_retention_internal.assert_external_signature_inventory_integrity()
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb)
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid)
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.delete_external_signature_record_rows()
    OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA apr_retention_internal FROM PUBLIC;
COMMENT ON FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) IS
    'Exact 42-table local-only purge, including V35 external-signature request/event/artifact evidence in FK-safe order; current policy/hold/source/lease/private permit fences preserved. No runtime activation or global erasure claim.';
