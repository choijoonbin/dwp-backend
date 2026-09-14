-- Original command evidence only. Legacy command rows are never backfilled on reads or replays.
CREATE FUNCTION public.retention_original_command_profile_safe(value JSONB,depth INTEGER DEFAULT 0) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
BEGIN
    IF depth<0 OR depth>5 OR jsonb_typeof(value) IN('array','boolean') THEN RETURN false; END IF;
    IF jsonb_typeof(value)='number' THEN
        RETURN value::text ~ '^(0|[1-9][0-9]{0,15})$' AND value::numeric<=9007199254740991;
    END IF;
    IF jsonb_typeof(value)='string' THEN RETURN length(value#>>'{}')<=512; END IF;
    IF jsonb_typeof(value)='object' THEN
        RETURN NOT EXISTS(SELECT 1 FROM jsonb_each(value) item WHERE NOT public.retention_original_command_profile_safe(item.value,depth+1));
    END IF;
    RETURN jsonb_typeof(value)='null';
END $$;
ALTER FUNCTION public.retention_original_command_profile_safe(jsonb,integer) OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON FUNCTION public.retention_original_command_profile_safe(jsonb,integer) FROM PUBLIC;
CREATE TABLE apr_retention_original_command_witnesses (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL CHECK(actor_user_id>0 AND actor_user_id<=9007199254740991),
    operation VARCHAR(24) NOT NULL CHECK(operation IN('INITIALIZE_POLICY','SAVE_POLICY','PUBLISH_POLICY','CLAIM_RECORD')),
    route VARCHAR(300) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK(idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    resource_set_key VARCHAR(80) NOT NULL CHECK(resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    original_target_id UUID,
    result_reference_id UUID NOT NULL,
    request_id UUID,
    policy_id UUID,
    claim_intent_id UUID GENERATED ALWAYS AS(CASE WHEN operation='CLAIM_RECORD' THEN result_reference_id ELSE NULL END) STORED,
    request_body_sha256 CHAR(64) NOT NULL CHECK(request_body_sha256 ~ '^[a-f0-9]{64}$'),
    canonical_algorithm VARCHAR(64) NOT NULL CHECK(canonical_algorithm='APPROVAL_STEP_UP_TYPED_JSON_SHA256_V1'),
    parent_command_fingerprint CHAR(64) NOT NULL CHECK(parent_command_fingerprint ~ '^[a-f0-9]{64}$'),
    original_expected_version BIGINT CHECK(original_expected_version>=0 AND original_expected_version<=9007199254740991),
    result_version BIGINT NOT NULL CHECK(result_version>=0 AND result_version<=9007199254740991),
    origin_authority_profile JSONB NOT NULL CHECK(jsonb_typeof(origin_authority_profile)='object' AND octet_length(origin_authority_profile::text)<=65536),
    source_profile_sha256 CHAR(64) NOT NULL CHECK(source_profile_sha256 ~ '^[a-f0-9]{64}$'),
    committed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    retain_until TIMESTAMPTZ NOT NULL,
    UNIQUE(tenant_id,actor_user_id,route,idempotency_key),
    FOREIGN KEY(tenant_id,actor_user_id,route,idempotency_key)
        REFERENCES apr_retention_management_commands(tenant_id,actor_user_id,route,idempotency_key),
    FOREIGN KEY(tenant_id,request_id) REFERENCES apr_requests(tenant_id,request_id),
    FOREIGN KEY(tenant_id,policy_id) REFERENCES apr_retention_policy_heads(tenant_id,policy_id),
    FOREIGN KEY(tenant_id,claim_intent_id) REFERENCES apr_retention_dispatch_intents(tenant_id,intent_id),
    CHECK((operation='INITIALIZE_POLICY' AND original_target_id IS NULL AND original_expected_version IS NULL AND request_id IS NULL AND policy_id=result_reference_id)
       OR (operation IN('SAVE_POLICY','PUBLISH_POLICY') AND policy_id IS NOT NULL AND original_target_id IS NOT NULL AND original_target_id=policy_id AND result_reference_id=policy_id AND request_id IS NULL AND original_expected_version IS NOT NULL)
       OR (operation='CLAIM_RECORD' AND original_target_id IS NOT NULL AND original_target_id=request_id AND request_id IS NOT NULL AND original_expected_version IS NOT NULL)),
    CHECK(retain_until>=committed_at AND retain_until<=committed_at+interval '3650 days')
);

CREATE FUNCTION validate_apr_retention_command_witness() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE parent public.apr_retention_management_commands; source JSONB; expected_route TEXT; permission TEXT; capability TEXT;
BEGIN
    IF TG_OP='DELETE' THEN
        IF NOT apr_retention_internal.authorized_delete(TG_RELID::regclass,to_jsonb(OLD)) THEN
            RAISE EXCEPTION 'Exact private retention command permit required' USING ERRCODE='42501';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Original command witness is immutable' USING ERRCODE='23514'; END IF;
    SELECT * INTO parent FROM public.apr_retention_management_commands WHERE tenant_id=NEW.tenant_id
        AND actor_user_id=NEW.actor_user_id AND route=NEW.route AND idempotency_key=NEW.idempotency_key;
    source:=NEW.origin_authority_profile;
    expected_route:=CASE NEW.operation
        WHEN 'INITIALIZE_POLICY' THEN '/v1/admin/retention/policies'
        WHEN 'SAVE_POLICY' THEN '/v1/admin/retention/policies/'||NEW.original_target_id||'/draft'
        WHEN 'PUBLISH_POLICY' THEN '/v1/admin/retention/policies/'||NEW.original_target_id||'/publish'
        WHEN 'CLAIM_RECORD' THEN '/v1/admin/retention/records/'||NEW.original_target_id||'/claims' END;
    permission:=CASE NEW.operation WHEN 'PUBLISH_POLICY' THEN 'ADMIN.APPROVAL_POLICY:PUBLISH'
        WHEN 'CLAIM_RECORD' THEN 'ADMIN.APPROVAL_OPERATIONS:EXECUTE' ELSE 'ADMIN.APPROVAL_POLICY:UPDATE' END;
    capability:=CASE NEW.operation WHEN 'PUBLISH_POLICY' THEN 'approvals.policy.publish'
        WHEN 'CLAIM_RECORD' THEN 'approvals.operations.execute' ELSE 'approvals.policy.update' END;
    IF COALESCE(parent.tenant_id IS NULL OR NEW.route<>expected_route OR parent.resource_set_key<>NEW.resource_set_key
       OR parent.fingerprint<>NEW.parent_command_fingerprint OR parent.target_id<>NEW.result_reference_id
       OR parent.result_version<>NEW.result_version
       OR (SELECT xmin::text::bigint FROM public.apr_retention_management_commands WHERE tenant_id=NEW.tenant_id
           AND actor_user_id=NEW.actor_user_id AND route=NEW.route AND idempotency_key=NEW.idempotency_key)<>txid_current()%4294967296
       OR NEW.committed_at<parent.created_at OR NEW.committed_at>clock_timestamp()
       OR source->>'algorithm'<>NEW.canonical_algorithm OR source->>'operation'<>NEW.operation
       OR source->>'permission'<>permission OR source->>'capability'<>capability
       OR (source->>'tenantId')::bigint<>NEW.tenant_id OR (source->>'actorUserId')::bigint<>NEW.actor_user_id
       OR source->>'resourceSetKey'<>NEW.resource_set_key OR source->>'idempotencyKey'<>NEW.idempotency_key
       OR source->>'requestBodySha256'<>NEW.request_body_sha256
       OR source->'originalTargetId' IS DISTINCT FROM COALESCE(to_jsonb(NEW.original_target_id),'null'::jsonb)
       OR source->'originalExpectedVersion' IS DISTINCT FROM COALESCE(to_jsonb(NEW.original_expected_version),'null'::jsonb)
       OR NEW.source_profile_sha256<>encode(sha256(convert_to(source::text,'UTF8')),'hex'),true) THEN
        RAISE EXCEPTION 'Original command witness binding changed' USING ERRCODE='23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(source))<>15 OR NOT(source ?& ARRAY[
        'algorithm','operation','permission','capability','tenantId','actorUserId','resourceSetKey','idempotencyKey',
        'requestBodySha256','originalTargetId','originalExpectedVersion','originPlane','decisionRevision','originalPublication','verifiedHighBinding']) THEN
        RAISE EXCEPTION 'Closed original command profile required' USING ERRCODE='23514';
    END IF;
    IF NOT public.retention_original_command_profile_safe(source) OR source->>'originPlane' NOT IN('TRUSTED_COMPAT','000','100','110','111')
       OR (source->'decisionRevision'<>'null'::jsonb AND source->>'decisionRevision' !~ '^psr-[a-f0-9]{64}$')
       OR (NEW.operation IN('INITIALIZE_POLICY','CLAIM_RECORD') AND NEW.result_version<>0)
       OR (NEW.operation IN('SAVE_POLICY','PUBLISH_POLICY') AND NEW.result_version<>NEW.original_expected_version+1)
       OR (NEW.operation IN('INITIALIZE_POLICY','SAVE_POLICY') AND (source->'verifiedHighBinding'<>'null'::jsonb OR source->'originalPublication'<>'null'::jsonb)) THEN
        RAISE EXCEPTION 'Exact typed original command profile required' USING ERRCODE='23514';
    END IF;
    IF NEW.operation='PUBLISH_POLICY' AND NOT EXISTS(
        SELECT 1 FROM public.apr_retention_policy_publications p WHERE p.tenant_id=NEW.tenant_id AND p.policy_id=NEW.policy_id
          AND p.revision=(source->'originalPublication'->>'revision')::integer
          AND p.maker_user_id=(source->'originalPublication'->>'makerUserId')::bigint
          AND p.checker_user_id=NEW.actor_user_id AND p.maker_user_id<>p.checker_user_id) THEN
        RAISE EXCEPTION 'Original independent checker witness required' USING ERRCODE='23514';
    END IF;
    IF NEW.operation='CLAIM_RECORD' AND NOT EXISTS(
        SELECT 1 FROM public.apr_retention_dispatch_intents i WHERE i.intent_id=NEW.result_reference_id
          AND i.tenant_id=NEW.tenant_id AND i.actor_user_id=NEW.actor_user_id AND i.resource_set_key=NEW.resource_set_key
          AND i.request_id=NEW.request_id AND i.request_version=NEW.original_expected_version
          AND i.command_fingerprint=NEW.parent_command_fingerprint
          AND (NEW.policy_id IS NULL OR NEW.policy_id=i.policy_id)) THEN
        RAISE EXCEPTION 'Original record intent binding required' USING ERRCODE='23514';
    END IF;
    IF NEW.operation IN('PUBLISH_POLICY','CLAIM_RECORD') AND NOT EXISTS(
        SELECT 1 FROM public.apr_step_up_replay_ledger l WHERE l.tenant_id=NEW.tenant_id AND l.actor_user_id=NEW.actor_user_id
          AND l.capability_contract_key=capability AND l.target_id=NEW.original_target_id::text
          AND l.target_version=NEW.original_expected_version AND l.command_method='POST'
          AND l.command_path='/api/approvals'||NEW.route AND l.idempotency_key=NEW.idempotency_key
          AND l.payload_sha256=NEW.request_body_sha256 AND l.decision_revision=source->>'decisionRevision'
          AND (source->'verifiedHighBinding'->>'actorUserId')::bigint=l.actor_user_id
          AND (source->'verifiedHighBinding'->>'tenantId')::bigint=l.tenant_id
          AND source->'verifiedHighBinding'->>'activationPolicy'=l.activation_policy
          AND source->'verifiedHighBinding'->>'capabilityContractKey'=l.capability_contract_key
          AND source->'verifiedHighBinding'->>'scopeRef'=l.scope_ref
          AND source->'verifiedHighBinding'->>'targetType'=l.target_type
          AND source->'verifiedHighBinding'->>'targetId'=l.target_id
          AND (source->'verifiedHighBinding'->>'targetVersion')::bigint=l.target_version
          AND source->'verifiedHighBinding'->>'commandMethod'=l.command_method
          AND source->'verifiedHighBinding'->>'commandPath'=l.command_path
          AND source->'verifiedHighBinding'->>'idempotencyKey'=l.idempotency_key
          AND source->'verifiedHighBinding'->>'decisionRevision'=l.decision_revision
          AND source->'verifiedHighBinding'->>'commandContractKey'=CASE NEW.operation WHEN 'PUBLISH_POLICY'
              THEN 'route.approvals.admin.retention-policy-publish.action' ELSE 'route.approvals.admin.retention-record-claim.action' END
          AND l.xmin::text::bigint=txid_current()%4294967296) THEN
        RAISE EXCEPTION 'Same transaction consumed high authority required' USING ERRCODE='23514';
    END IF;
    IF NEW.operation IN('PUBLISH_POLICY','CLAIM_RECORD') AND COALESCE(
        source->>'originPlane' NOT IN('110','111') OR jsonb_typeof(source->'verifiedHighBinding')<>'object'
        OR source->'verifiedHighBinding'->>'payloadSha256'<>NEW.request_body_sha256,true) THEN
        RAISE EXCEPTION 'Original verified high binding required' USING ERRCODE='23514';
    END IF;
    IF NEW.operation IN('PUBLISH_POLICY','CLAIM_RECORD') AND (
        (SELECT count(*) FROM jsonb_object_keys(source->'verifiedHighBinding'))<>15
        OR NOT(source->'verifiedHighBinding' ?& ARRAY['actorUserId','tenantId','commandContractKey','contextKey',
            'activationPolicy','capabilityContractKey','scopeRef','targetType','targetId','targetVersion',
            'commandMethod','commandPath','idempotencyKey','payloadSha256','decisionRevision'])) THEN
        RAISE EXCEPTION 'Closed verified binding required' USING ERRCODE='23514';
    END IF;
    IF NEW.operation='PUBLISH_POLICY' AND ((SELECT count(*) FROM jsonb_object_keys(source->'originalPublication'))<>2
        OR NOT(source->'originalPublication' ?& ARRAY['makerUserId','revision'])) THEN
        RAISE EXCEPTION 'Closed original publication required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
ALTER FUNCTION validate_apr_retention_command_witness() OWNER TO dwp_approval_retention_owner;
REVOKE EXECUTE ON FUNCTION validate_apr_retention_command_witness() FROM PUBLIC;
CREATE TRIGGER trg_retention_original_command BEFORE INSERT OR UPDATE OR DELETE ON apr_retention_original_command_witnesses
    FOR EACH ROW EXECUTE FUNCTION validate_apr_retention_command_witness();
CREATE TRIGGER trg_retention_original_command_consume AFTER DELETE ON apr_retention_original_command_witnesses
    FOR EACH ROW EXECUTE FUNCTION apr_retention_internal.consume_delete_permit();
ALTER TABLE apr_retention_original_command_witnesses ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_retention_original_command_witnesses FORCE ROW LEVEL SECURITY;
CREATE POLICY retention_original_command_actor_read ON apr_retention_original_command_witnesses FOR SELECT
    USING(tenant_id=COALESCE(NULLIF(current_setting('dwp.approval.retention.receipt.tenant',true),''),'0')::bigint
      AND actor_user_id=COALESCE(NULLIF(current_setting('dwp.approval.retention.receipt.actor',true),''),'0')::bigint);
CREATE POLICY retention_original_command_actor_insert ON apr_retention_original_command_witnesses FOR INSERT
    WITH CHECK(tenant_id=COALESCE(NULLIF(current_setting('dwp.approval.retention.receipt.tenant',true),''),'0')::bigint
      AND actor_user_id=COALESCE(NULLIF(current_setting('dwp.approval.retention.receipt.actor',true),''),'0')::bigint);
CREATE POLICY retention_original_command_owner_read ON apr_retention_original_command_witnesses FOR SELECT TO dwp_approval_retention_owner USING(true);
CREATE POLICY retention_original_command_owner_delete ON apr_retention_original_command_witnesses FOR DELETE TO dwp_approval_retention_owner
    USING(apr_retention_internal.authorized_delete('public.apr_retention_original_command_witnesses'::regclass,to_jsonb(apr_retention_original_command_witnesses)));
GRANT SELECT,DELETE ON apr_retention_original_command_witnesses TO dwp_approval_retention_owner;
GRANT SELECT ON apr_retention_management_commands,apr_retention_policy_publications,apr_retention_dispatch_intents,apr_step_up_replay_ledger TO dwp_approval_retention_owner;
COMMENT ON TABLE apr_retention_original_command_witnesses IS 'Original immutable typed command evidence. Actor GUCs isolate rows but never replace current Java Auth/PEP. No legacy backfill, public grant, direct executor grant or global-erasure claim. V33 exact inventory adapter required before record purge.';
