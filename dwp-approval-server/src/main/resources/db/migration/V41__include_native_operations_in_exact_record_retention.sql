-- Bring V37 native operation evidence under the exact request-retention protocol.
-- A batch spanning more than one request is deliberately not partially purged.

DO $audit_relay_role$
DECLARE application_user NAME:=session_user;
BEGIN
    IF NOT EXISTS(
        SELECT 1 FROM pg_catalog.pg_roles
         WHERE rolname='dwp_approval_audit_relay'
    ) THEN
        CREATE ROLE dwp_approval_audit_relay
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    ALTER ROLE dwp_approval_audit_relay
        NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    IF application_user<>'dwp_approval_audit_relay' THEN
        EXECUTE format('GRANT dwp_approval_audit_relay TO %I',application_user);
    END IF;
END
$audit_relay_role$;

LOCK TABLE public.apr_requests, public.apr_record_purge_claims,
    public.apr_operation_batches, public.apr_operation_items, public.sys_audit_outbox
    IN SHARE ROW EXCLUSIVE MODE;

-- The V37 trigger rejects every UPDATE. Remove it transactionally before the
-- one-time audit identity backfill; the UPDATE-only V41 guard is installed below.
DROP TRIGGER trg_apr_operation_batches_append_only
    ON public.apr_operation_batches;

ALTER TABLE public.apr_operation_batches
    ADD COLUMN audit_event_id UUID,
    ADD COLUMN audit_occurred_at TIMESTAMPTZ;

CREATE FUNCTION apr_retention_internal.audit_event_occurred_at(p_value JSONB)
RETURNS TIMESTAMPTZ
LANGUAGE sql IMMUTABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
SELECT CASE jsonb_typeof(p_value)
    WHEN 'number' THEN pg_catalog.to_timestamp((p_value#>>'{}')::DOUBLE PRECISION)
    WHEN 'string' THEN (p_value#>>'{}')::TIMESTAMPTZ
    ELSE NULL
END
$$;

CREATE FUNCTION apr_retention_internal.operation_audit_matches(
    p_operation UUID,p_event UUID)
RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
SELECT EXISTS(
    SELECT 1
      FROM public.apr_operation_batches batch
      JOIN public.sys_audit_outbox audit
        ON audit.tenant_id=batch.tenant_id AND audit.event_id=p_event
     WHERE batch.operation_id=p_operation
       AND audit.event_id::TEXT=audit.payload->>'eventId'
       AND audit.payload->>'tenantId'=batch.tenant_id::TEXT
       AND audit.payload->>'sourceService'='dwp-approval-server'
       AND audit.payload->>'sourceModule'='approval-native-operations'
       AND audit.payload->>'category'='ADMIN_CHANGE'
       AND audit.payload->>'action'='approval.operations.'||lower(batch.operation_type)
       AND audit.payload->>'outcome'='SUCCESS'
       AND audit.payload->>'severity'='HIGH'
       AND audit.payload->>'actorType'='USER'
       AND audit.payload->>'actorId'=batch.actor_user_id::TEXT
       AND audit.payload->>'targetType'='APPROVAL_OPERATION_BATCH'
       AND audit.payload->>'targetId'=batch.operation_id::TEXT
       AND audit.payload->>'correlationId'=batch.idempotency_key
       AND audit.payload->>'retentionClass'='EXTENDED'
       AND (batch.audit_occurred_at IS NULL OR
            apr_retention_internal.audit_event_occurred_at(
                audit.payload->'occurredAt')=batch.audit_occurred_at)
       AND audit.payload->'afterState'=(
           SELECT jsonb_build_object(
               'operation',batch.operation_type,
               'commandMode',batch.command_mode,
               'managementResourceSetKey',batch.management_resource_set_key,
               'itemCount',batch.item_count,
               'targetIds',COALESCE(
                   jsonb_agg(to_jsonb(item.target_id::TEXT) ORDER BY item.item_sequence),
                   '[]'::jsonb))
             FROM public.apr_operation_items item
            WHERE item.operation_id=batch.operation_id)
)
$$;

-- An intermediate V37 deployment may already have rows. Only a unique, still-live
-- producer event is acceptable backfill evidence; cleaned or ambiguous history
-- cannot be reconstructed and therefore stops the forward migration.
DO $$
BEGIN
    IF EXISTS(
        SELECT 1
          FROM public.apr_operation_batches batch
         WHERE (
             SELECT count(*)
               FROM public.sys_audit_outbox audit
              WHERE apr_retention_internal.operation_audit_matches(
                  batch.operation_id,audit.event_id)
         )<>1
    ) THEN
        RAISE EXCEPTION 'Existing approval operation audit identity is unavailable or ambiguous'
            USING ERRCODE='23514';
    END IF;
END
$$;

UPDATE public.apr_operation_batches batch
   SET audit_event_id=audit.event_id,
       audit_occurred_at=apr_retention_internal.audit_event_occurred_at(
           audit.payload->'occurredAt')
  FROM public.sys_audit_outbox audit
 WHERE apr_retention_internal.operation_audit_matches(
     batch.operation_id,audit.event_id);

ALTER TABLE public.apr_operation_batches
    ALTER COLUMN audit_event_id SET NOT NULL,
    ALTER COLUMN audit_occurred_at SET NOT NULL,
    ADD CONSTRAINT uk_apr_operation_audit_event UNIQUE(audit_event_id);

CREATE FUNCTION apr_retention_internal.operation_batch_is_exact(p_operation UUID)
RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
SELECT EXISTS(
    SELECT 1
      FROM public.apr_operation_batches batch
     WHERE batch.operation_id=p_operation
       AND batch.item_count=(
           SELECT count(*) FROM public.apr_operation_items item
            WHERE item.operation_id=batch.operation_id)
       AND (batch.command_mode<>'SINGLE' OR batch.item_count=1)
       AND (SELECT min(item.item_sequence) FROM public.apr_operation_items item
             WHERE item.operation_id=batch.operation_id)=1
       AND (SELECT max(item.item_sequence) FROM public.apr_operation_items item
             WHERE item.operation_id=batch.operation_id)=batch.item_count
       AND NOT EXISTS(
           SELECT 1
             FROM public.apr_operation_items item
             JOIN public.apr_record_retention_heads retention
               ON retention.tenant_id=item.tenant_id
              AND retention.request_id=item.request_id
            WHERE item.operation_id=batch.operation_id
              AND retention.state<>'LIVE')
       AND NOT EXISTS(
           SELECT 1
             FROM public.apr_operation_items item
            WHERE item.operation_id=batch.operation_id
              AND (item.tenant_id<>batch.tenant_id
                OR item.management_resource_set_key<>batch.management_resource_set_key
                OR item.actor_user_id<>batch.actor_user_id
                OR item.committed_at IS DISTINCT FROM batch.committed_at
                OR (batch.operation_type='TASK_REASSIGN'
                    AND item.target_type<>'APPROVAL_TASK')
                OR (batch.operation_type<>'TASK_REASSIGN'
                    AND item.target_type<>'OUTBOX_EVENT')
                OR (item.target_type='OUTBOX_EVENT' AND NOT EXISTS(
                    SELECT 1
                      FROM public.apr_integration_outbox target
                     WHERE target.tenant_id=item.tenant_id
                       AND target.request_id=item.request_id
                       AND target.management_resource_set_key=
                           item.management_resource_set_key
                       AND target.outbox_id=item.target_id))
                OR (item.target_type='APPROVAL_TASK' AND NOT EXISTS(
                    SELECT 1
                      FROM public.apr_tasks target
                      JOIN public.apr_requests request
                        ON request.tenant_id=target.tenant_id
                       AND request.request_id=target.request_id
                     WHERE target.tenant_id=item.tenant_id
                       AND target.request_id=item.request_id
                       AND request.management_resource_set_key=
                           item.management_resource_set_key
                       AND target.task_id=item.target_id))))
)
$$;

CREATE FUNCTION apr_retention_internal.operation_audit_is_exact(p_operation UUID)
RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
SELECT apr_retention_internal.operation_audit_matches(
           batch.operation_id,batch.audit_event_id)
       OR NOT EXISTS(
           SELECT 1 FROM public.sys_audit_outbox audit
            WHERE audit.event_id=batch.audit_event_id)
  FROM public.apr_operation_batches batch
 WHERE batch.operation_id=p_operation
$$;

CREATE FUNCTION apr_retention_internal.audit_cleanup_eligible(p_row JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE
    operation UUID;
    expected_requests INTEGER;
    locked_requests INTEGER;
BEGIN
    IF p_row->>'status'<>'PUBLISHED' THEN RETURN FALSE; END IF;
    IF p_row->'payload'->>'targetType'='APPROVAL_OPERATION_BATCH' THEN
        SELECT batch.operation_id INTO operation
          FROM public.apr_operation_batches batch
         WHERE batch.tenant_id=(p_row->>'tenant_id')::BIGINT
           AND batch.audit_event_id=(p_row->>'event_id')::UUID
           AND batch.operation_id::TEXT=p_row->'payload'->>'targetId';
        IF operation IS NULL
           OR NOT apr_retention_internal.operation_audit_matches(
               operation,(p_row->>'event_id')::UUID) THEN
            RETURN FALSE;
        END IF;
        SELECT count(DISTINCT item.request_id) INTO expected_requests
          FROM public.apr_operation_items item
         WHERE item.operation_id=operation;
        PERFORM request.request_id
          FROM public.apr_requests request
         WHERE request.tenant_id=(p_row->>'tenant_id')::BIGINT
           AND request.request_id IN(
               SELECT item.request_id FROM public.apr_operation_items item
                WHERE item.operation_id=operation)
         ORDER BY request.request_id
         FOR SHARE OF request;
        GET DIAGNOSTICS locked_requests=ROW_COUNT;
        IF locked_requests<>expected_requests
           OR NOT apr_retention_internal.operation_batch_is_exact(operation) THEN
            RETURN FALSE;
        END IF;
        RETURN TRUE;
    END IF;

    -- Request-bound audit evidence has no separate durable producer-event witness
    -- yet and therefore remains in the exact request inventory. Unbound published
    -- operational audit rows may follow the core relay's bounded cleanup policy.
    IF EXISTS(
        SELECT 1
          FROM public.apr_requests request
         WHERE request.tenant_id=(p_row->>'tenant_id')::BIGINT
           AND (p_row->'payload'->>'approvalId'=request.request_id::TEXT
             OR p_row->'payload'->'afterState'->>'requestId'=request.request_id::TEXT
             OR (p_row->'payload'->>'targetType' IN('APPROVAL_REQUEST','APPROVAL_DOCUMENT')
                 AND p_row->'payload'->>'targetId'=request.request_id::TEXT)
             OR (p_row->'payload'->>'targetType'='APPROVAL_TASK' AND EXISTS(
                 SELECT 1 FROM public.apr_tasks task
                  WHERE task.tenant_id=request.tenant_id
                    AND task.request_id=request.request_id
                    AND task.task_id::TEXT=p_row->'payload'->>'targetId')))
    ) THEN
        RETURN FALSE;
    END IF;
    RETURN TRUE;
END
$$;

CREATE FUNCTION apr_retention_internal.guard_audit_outbox_delete()
RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF current_user='dwp_approval_audit_relay' THEN
        IF apr_retention_internal.audit_cleanup_eligible(to_jsonb(OLD)) THEN
            RETURN OLD;
        END IF;
        RETURN NULL;
    END IF;
    IF NOT apr_retention_internal.authorized_delete(TG_RELID::regclass,to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'Exact retention deletion permit required' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
END
$$;

CREATE FUNCTION apr_retention_internal.consume_audit_outbox_delete()
RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE affected INTEGER;
BEGIN
    IF current_user='dwp_approval_audit_relay' THEN RETURN OLD; END IF;
    IF NOT apr_retention_internal.authorized_delete(TG_RELID::regclass,to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'Retention deletion proof changed' USING ERRCODE='23514';
    END IF;
    DELETE FROM apr_retention_internal.delete_permits
     WHERE backend_pid=pg_backend_pid() AND transaction_id=txid_current()
       AND table_oid=TG_RELID::regclass
       AND primary_key=apr_retention_internal.exact_primary_key(
           TG_RELID::regclass,to_jsonb(OLD))
       AND row_sha256=encode(sha256(convert_to(to_jsonb(OLD)::TEXT,'UTF8')),'hex');
    GET DIAGNOSTICS affected=ROW_COUNT;
    IF affected<>1 THEN
        RAISE EXCEPTION 'Retention permit consumption mismatch' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
END
$$;

DROP TRIGGER trg_retention_deny_delete ON public.sys_audit_outbox;
DROP TRIGGER trg_retention_consume_delete ON public.sys_audit_outbox;
CREATE TRIGGER trg_retention_deny_delete
    BEFORE DELETE ON public.sys_audit_outbox
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.guard_audit_outbox_delete();
CREATE TRIGGER trg_retention_consume_delete
    AFTER DELETE ON public.sys_audit_outbox
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_audit_outbox_delete();

CREATE FUNCTION apr_retention_internal.assert_operation_inventory_integrity()
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF EXISTS(
        SELECT 1 FROM public.apr_operation_batches batch
         WHERE NOT apr_retention_internal.operation_batch_is_exact(batch.operation_id)
            OR NOT apr_retention_internal.operation_audit_is_exact(batch.operation_id)
    ) OR EXISTS(
        SELECT 1
          FROM pg_catalog.pg_constraint constraint_row
         WHERE constraint_row.conrelid IN (
                   'public.apr_operation_batches'::regclass,
                   'public.apr_operation_items'::regclass)
           AND constraint_row.contype='f' AND NOT constraint_row.convalidated
    ) THEN
        RAISE EXCEPTION 'Existing approval operation evidence is not exactly request-bound'
            USING ERRCODE='23514';
    END IF;
END
$$;

SELECT apr_retention_internal.assert_operation_inventory_integrity();

CREATE FUNCTION apr_retention_internal.enforce_operation_batch_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF NOT apr_retention_internal.operation_batch_is_exact(NEW.operation_id)
       OR NOT COALESCE((
           SELECT apr_retention_internal.operation_audit_matches(
               batch.operation_id,batch.audit_event_id)
             FROM public.apr_operation_batches batch
            WHERE batch.operation_id=NEW.operation_id),FALSE) THEN
        RAISE EXCEPTION 'Approval operation batch is incomplete or inconsistent'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER trg_apr_operation_batch_exact
    AFTER INSERT ON public.apr_operation_batches
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.enforce_operation_batch_integrity();
CREATE CONSTRAINT TRIGGER trg_apr_operation_item_exact
    AFTER INSERT ON public.apr_operation_items
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.enforce_operation_batch_integrity();

CREATE FUNCTION apr_retention_internal.guard_operation_retention_claim()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF EXISTS(
        SELECT 1
          FROM public.apr_operation_items target
          JOIN public.apr_operation_items other
            ON other.operation_id=target.operation_id
         WHERE target.tenant_id=NEW.tenant_id AND target.request_id=NEW.request_id
           AND (other.tenant_id<>NEW.tenant_id OR other.request_id<>NEW.request_id)
    ) THEN
        RAISE EXCEPTION 'BLOCKED_SHARED_LINK' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_apr_retention_claim_operation_scope
    BEFORE INSERT ON public.apr_record_purge_claims
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.guard_operation_retention_claim();

ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb)
    RENAME TO exact_primary_key_v36;
CREATE FUNCTION apr_retention_internal.exact_primary_key(p_table REGCLASS,p_row JSONB)
RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE keys TEXT[]; result JSONB;
BEGIN
    IF p_table='public.apr_operation_batches'::regclass THEN
        keys:=ARRAY['operation_id'];
    ELSIF p_table='public.apr_operation_items'::regclass THEN
        keys:=ARRAY['operation_id','item_sequence'];
    ELSE
        RETURN apr_retention_internal.exact_primary_key_v36(p_table,p_row);
    END IF;
    IF EXISTS(SELECT 1 FROM unnest(keys) key
              WHERE p_row->key IS NULL OR p_row->key='null'::jsonb) THEN
        RAISE EXCEPTION 'Incomplete approval operation retention identity'
            USING ERRCODE='23514';
    END IF;
    SELECT jsonb_object_agg(key,p_row->key) INTO result FROM unnest(keys) key;
    RETURN result;
END
$$;

ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid)
    RENAME TO catalog_rows_v36;
CREATE FUNCTION apr_retention_internal.catalog_rows(p_tenant BIGINT,p_request UUID)
RETURNS TABLE(table_oid REGCLASS,primary_key JSONB,row_sha256 TEXT,row_data JSONB)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
WITH base AS (
    SELECT * FROM apr_retention_internal.catalog_rows_v36(p_tenant,p_request)
)
SELECT * FROM base
UNION ALL
SELECT 'public.apr_operation_batches'::regclass,
       apr_retention_internal.exact_primary_key(
           'public.apr_operation_batches'::regclass,to_jsonb(batch)),
       encode(sha256(convert_to(to_jsonb(batch)::text,'UTF8')),'hex'),to_jsonb(batch)
  FROM public.apr_operation_batches batch
 WHERE batch.tenant_id=p_tenant
   AND EXISTS(
       SELECT 1 FROM public.apr_operation_items item
        WHERE item.operation_id=batch.operation_id
          AND item.tenant_id=p_tenant AND item.request_id=p_request)
UNION ALL
SELECT 'public.apr_operation_items'::regclass,
       apr_retention_internal.exact_primary_key(
           'public.apr_operation_items'::regclass,to_jsonb(item)),
       encode(sha256(convert_to(to_jsonb(item)::text,'UTF8')),'hex'),to_jsonb(item)
  FROM public.apr_operation_items item
 WHERE item.tenant_id=p_tenant AND item.request_id=p_request
UNION ALL
SELECT 'public.sys_audit_outbox'::regclass,
       apr_retention_internal.exact_primary_key(
           'public.sys_audit_outbox'::regclass,to_jsonb(audit)),
       encode(sha256(convert_to(to_jsonb(audit)::text,'UTF8')),'hex'),to_jsonb(audit)
  FROM public.sys_audit_outbox audit
 WHERE audit.tenant_id=p_tenant
   AND audit.payload->>'targetType'='APPROVAL_OPERATION_BATCH'
   AND EXISTS(
       SELECT 1
         FROM public.apr_operation_batches batch
         JOIN public.apr_operation_items item USING(operation_id)
        WHERE batch.tenant_id=p_tenant AND item.tenant_id=p_tenant
          AND item.request_id=p_request
          AND audit.event_id=batch.audit_event_id
          AND audit.payload->>'targetId'=batch.operation_id::TEXT)
   AND NOT EXISTS(
       SELECT 1 FROM base existing
        WHERE existing.table_oid='public.sys_audit_outbox'::regclass
          AND existing.primary_key=apr_retention_internal.exact_primary_key(
              'public.sys_audit_outbox'::regclass,to_jsonb(audit)));
$$;

-- V37 operation batches carry their own explicit retention deadline. Replace the
-- claim preparation function so both the pinned deadline and the pre-claim gate
-- consume it directly; the existing owner and executor-only ACL are preserved.
CREATE OR REPLACE FUNCTION apr_retention_internal.prepare_record(
    p_tenant BIGINT,p_request UUID,p_version BIGINT)
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
       GREATEST(deadline,doc.retain_until,max(GREATEST((row_data->>'retain_until')::timestamptz,
         (row_data->>'retention_until')::timestamptz,(row_data->>'expires_at')::timestamptz,
         GREATEST(CASE WHEN table_oid='public.sys_audit_outbox'::regclass
                  AND row_data->'payload'->>'targetType'='APPROVAL_OPERATION_BATCH'
                THEN NULL ELSE (row_data->>'created_at')::timestamptz END,
           (row_data->>'completed_at')::timestamptz,
           (row_data->>'accepted_at')::timestamptz,(row_data->>'consented_at')::timestamptz,
           (row_data->>'occurred_at')::timestamptz,
           (row_data->>'audit_occurred_at')::timestamptz,
           CASE jsonb_typeof(row_data->'payload'->'occurredAt')
             WHEN 'number' THEN pg_catalog.to_timestamp(
                 (row_data->'payload'->>'occurredAt')::DOUBLE PRECISION)
             WHEN 'string' THEN (row_data->'payload'->>'occurredAt')::TIMESTAMPTZ
             ELSE NULL END)+make_interval(days=>CASE WHEN table_oid IN('public.apr_document_hold_journal'::regclass,'public.apr_document_hold_proposals'::regclass) THEN (rules->>'holdEvidenceRetentionDays')::int
           WHEN table_oid='public.sys_audit_outbox'::regclass THEN (rules->>'auditEvidenceRetentionDays')::int
           WHEN table_oid='public.apr_operation_batches'::regclass THEN (rules->>'auditEvidenceRetentionDays')::int
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

-- Keep V37 updates immutable while allowing only exact, permit-backed retention deletes.
CREATE TRIGGER trg_apr_operation_batches_append_only
    BEFORE UPDATE ON public.apr_operation_batches
    FOR EACH ROW EXECUTE FUNCTION public.reject_apr_operation_ledger_mutation();
DROP TRIGGER trg_apr_operation_items_append_only ON public.apr_operation_items;
CREATE TRIGGER trg_apr_operation_items_append_only
    BEFORE UPDATE ON public.apr_operation_items
    FOR EACH ROW EXECUTE FUNCTION public.reject_apr_operation_ledger_mutation();

GRANT SELECT,DELETE ON public.apr_operation_batches,public.apr_operation_items
    TO dwp_approval_retention_owner;
REVOKE ALL ON public.apr_operation_batches,public.apr_operation_items FROM PUBLIC;
REVOKE ALL ON public.sys_audit_outbox FROM dwp_approval_audit_relay;
GRANT SELECT,DELETE ON public.sys_audit_outbox TO dwp_approval_audit_relay;
GRANT UPDATE(status,attempt_count,available_at,locked_by,locked_until,last_error,
    published_at,updated_at) ON public.sys_audit_outbox TO dwp_approval_audit_relay;
GRANT USAGE ON SCHEMA apr_retention_internal TO dwp_approval_audit_relay;

CREATE TRIGGER trg_retention_deny_delete
    BEFORE DELETE ON public.apr_operation_batches
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete
    AFTER DELETE ON public.apr_operation_batches
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
CREATE TRIGGER trg_retention_deny_delete
    BEFORE DELETE ON public.apr_operation_items
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.deny_unpermitted_delete();
CREATE TRIGGER trg_retention_consume_delete
    AFTER DELETE ON public.apr_operation_items
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();

CREATE FUNCTION apr_retention_internal.delete_operation_record_rows()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE
    exact_claim UUID;
    permit_count INTEGER;
    linked_operations UUID[];
BEGIN
    IF TG_OP<>'DELETE'
       OR NOT apr_retention_internal.authorized_delete(TG_RELID::regclass,to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'Exact request retention permit required before operation evidence deletion'
            USING ERRCODE='23514';
    END IF;
    SELECT count(*),(array_agg(permit.claim_id ORDER BY permit.claim_id::TEXT))[1]
      INTO permit_count,exact_claim
      FROM apr_retention_internal.delete_permits permit
     WHERE permit.backend_pid=pg_backend_pid() AND permit.transaction_id=txid_current()
       AND permit.table_oid=TG_RELID::regclass
       AND permit.primary_key=apr_retention_internal.exact_primary_key(
           TG_RELID::regclass,to_jsonb(OLD))
       AND permit.row_sha256=encode(sha256(convert_to(to_jsonb(OLD)::text,'UTF8')),'hex');
    IF permit_count<>1 OR exact_claim IS NULL THEN
        RAISE EXCEPTION 'Exact request retention claim is ambiguous' USING ERRCODE='23514';
    END IF;

    IF EXISTS(
        SELECT 1
          FROM public.apr_operation_items target
          JOIN public.apr_operation_items other
            ON other.operation_id=target.operation_id
         WHERE target.tenant_id=OLD.tenant_id AND target.request_id=OLD.request_id
           AND (other.tenant_id<>OLD.tenant_id OR other.request_id<>OLD.request_id)
    ) THEN
        RAISE EXCEPTION 'BLOCKED_SHARED_LINK' USING ERRCODE='23514';
    END IF;

    SELECT COALESCE(array_agg(DISTINCT item.operation_id ORDER BY item.operation_id),
                    ARRAY[]::UUID[])
      INTO linked_operations
      FROM public.apr_operation_items item
     WHERE item.tenant_id=OLD.tenant_id AND item.request_id=OLD.request_id;

    DELETE FROM public.apr_operation_items AS row
    USING public.apr_record_purge_rows AS pin
     WHERE row.operation_id=ANY(linked_operations)
       AND row.tenant_id=OLD.tenant_id AND row.request_id=OLD.request_id
       AND pin.claim_id=exact_claim
       AND pin.table_oid='public.apr_operation_items'::regclass
       AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
       AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');

    DELETE FROM public.apr_operation_batches AS row
    USING public.apr_record_purge_rows AS pin
     WHERE row.operation_id=ANY(linked_operations)
       AND NOT EXISTS(
           SELECT 1 FROM public.apr_operation_items item
            WHERE item.operation_id=row.operation_id)
       AND pin.claim_id=exact_claim
       AND pin.table_oid='public.apr_operation_batches'::regclass
       AND pin.primary_key=apr_retention_internal.exact_primary_key(pin.table_oid,to_jsonb(row))
       AND pin.row_sha256=encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex');

    IF EXISTS(
        SELECT 1 FROM apr_retention_internal.delete_permits permit
         WHERE permit.backend_pid=pg_backend_pid() AND permit.transaction_id=txid_current()
           AND permit.claim_id=exact_claim
           AND permit.table_oid IN ('public.apr_operation_batches'::regclass,
                                    'public.apr_operation_items'::regclass)
    ) OR EXISTS(
        SELECT 1 FROM public.apr_operation_items item
         WHERE item.tenant_id=OLD.tenant_id AND item.request_id=OLD.request_id
    ) OR EXISTS(
        SELECT 1 FROM public.apr_operation_batches batch
         WHERE batch.operation_id=ANY(linked_operations)
    ) THEN
        RAISE EXCEPTION 'Incomplete approval operation evidence deletion'
            USING ERRCODE='40001';
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER trg_retention_operation_children
    BEFORE DELETE ON public.apr_requests
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.delete_operation_record_rows();

ALTER FUNCTION apr_retention_internal.operation_batch_is_exact(uuid)
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.audit_event_occurred_at(jsonb)
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.operation_audit_matches(uuid,uuid)
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.operation_audit_is_exact(uuid)
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.audit_cleanup_eligible(jsonb)
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.guard_audit_outbox_delete()
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.consume_audit_outbox_delete()
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.assert_operation_inventory_integrity()
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.enforce_operation_batch_integrity()
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.guard_operation_retention_claim()
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.exact_primary_key(regclass,jsonb)
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.catalog_rows(bigint,uuid)
    OWNER TO dwp_approval_retention_owner;
ALTER FUNCTION apr_retention_internal.delete_operation_record_rows()
    OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA apr_retention_internal FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apr_retention_internal.audit_cleanup_eligible(jsonb)
    TO dwp_approval_audit_relay;
COMMENT ON FUNCTION apr_retention_internal.purge_local_record(uuid,bigint) IS
    'Exact 44-table local-only purge, including V37 native operation batch/item evidence. Multi-request batches fail closed; single-request evidence is deleted in FK-safe order. No runtime activation or global erasure claim.';
