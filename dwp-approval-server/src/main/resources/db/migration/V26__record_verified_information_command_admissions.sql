-- Historical provenance is not a reusable credential. No old commands are backfilled.
CREATE TABLE apr_quorum_information_completion_transactions (
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    command_sha256 CHAR(64) NOT NULL CHECK (command_sha256 ~ '^[a-f0-9]{64}$'),
    transaction_id XID8 NOT NULL,
    PRIMARY KEY (tenant_id,request_id,idempotency_key),
    UNIQUE (tenant_id,request_id,idempotency_key,command_sha256),
    FOREIGN KEY (tenant_id,request_id,idempotency_key)
        REFERENCES apr_quorum_information_commands(tenant_id,request_id,idempotency_key)
);

CREATE FUNCTION protect_apr_information_completion_transaction() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' OR pg_trigger_depth() <> 2 OR NEW.transaction_id <> pg_current_xact_id()
        OR NOT EXISTS (SELECT 1 FROM apr_quorum_information_commands WHERE tenant_id=NEW.tenant_id
            AND request_id=NEW.request_id AND idempotency_key=NEW.idempotency_key
            AND status='COMPLETED' AND command_sha256=NEW.command_sha256) THEN
        RAISE EXCEPTION 'A completion transaction marker can only be recorded by the completed command transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apr_information_completion_transaction_frozen BEFORE INSERT OR UPDATE OR DELETE
    ON apr_quorum_information_completion_transactions FOR EACH ROW EXECUTE FUNCTION protect_apr_information_completion_transaction();

CREATE FUNCTION record_apr_information_completion_transaction() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO apr_quorum_information_completion_transactions(tenant_id,request_id,idempotency_key,command_sha256,transaction_id)
        VALUES(NEW.tenant_id,NEW.request_id,NEW.idempotency_key,NEW.command_sha256,pg_current_xact_id());
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apr_information_command_completion_transaction AFTER UPDATE ON apr_quorum_information_commands
    FOR EACH ROW WHEN (OLD.status='UNKNOWN' AND NEW.status='COMPLETED')
    EXECUTE FUNCTION record_apr_information_completion_transaction();

CREATE TABLE apr_quorum_information_admissions (
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,120}$'),
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('REQUEST_INFO', 'REPLY')),
    command_sha256 CHAR(64) NOT NULL CHECK (command_sha256 ~ '^[a-f0-9]{64}$'),
    raw_body_sha256 CHAR(64) NOT NULL CHECK (raw_body_sha256 ~ '^[a-f0-9]{64}$'),
    receipt_sha256 CHAR(64) NOT NULL CHECK (receipt_sha256 ~ '^[a-f0-9]{64}$'),
    round_id UUID NOT NULL REFERENCES apr_quorum_information_rounds(round_id),
    source_generation BIGINT NOT NULL CHECK (source_generation > 0),
    task_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    actor_person_id UUID NOT NULL,
    admission_jti UUID NOT NULL UNIQUE,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    admission JSONB NOT NULL CHECK (jsonb_typeof(admission) = 'object'),
    PRIMARY KEY (tenant_id, request_id, idempotency_key),
    FOREIGN KEY (tenant_id, request_id, idempotency_key)
        REFERENCES apr_quorum_information_commands(tenant_id, request_id, idempotency_key),
    FOREIGN KEY (tenant_id,request_id,idempotency_key,command_sha256)
        REFERENCES apr_quorum_information_completion_transactions(tenant_id,request_id,idempotency_key,command_sha256)
);

CREATE FUNCTION validate_apr_quorum_information_admission() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
CREATE TRIGGER trg_apr_quorum_information_admission_provenance BEFORE INSERT OR UPDATE OR DELETE
    ON apr_quorum_information_admissions FOR EACH ROW EXECUTE FUNCTION validate_apr_quorum_information_admission();
