-- BASE-TFR-HRM-013 Payroll task-owner lane.  This migration is schema-only:
-- it creates no tenant, grant, credential, feature activation or business seed.

CREATE TABLE pay_hrm013_dispatch_inbox (
    tenant_id BIGINT NOT NULL,
    dispatch_event_id UUID NOT NULL,
    case_public_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    task_owner_revision BIGINT NOT NULL,
    task_version BIGINT NOT NULL,
    dispatched_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    worker_public_id UUID NOT NULL,
    relationship_public_id UUID NOT NULL,
    separation_type VARCHAR(64) NOT NULL,
    effective_date DATE NOT NULL,
    last_working_date DATE NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    exact_request_bytes BYTEA NOT NULL,
    proof_sha256 CHAR(64) NOT NULL,
    grant_set_id UUID NOT NULL,
    grant_revision BIGINT NOT NULL,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    grant_use_mode VARCHAR(24) NOT NULL,
    drain_root_work_item_id UUID,
    drain_root_created_at TIMESTAMPTZ,
    drain_root_payload_sha256 CHAR(64),
    admitted_at TIMESTAMPTZ NOT NULL,
    exact_admission_receipt_bytes BYTEA NOT NULL,
    admission_receipt_sha256 CHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'ADMITTED',
    completed_at TIMESTAMPTZ,
    CONSTRAINT pay_hrm013_dispatch_inbox_pk PRIMARY KEY (tenant_id, dispatch_event_id),
    CONSTRAINT pay_hrm013_dispatch_task_unique UNIQUE (tenant_id, task_public_id, task_version),
    CONSTRAINT pay_hrm013_dispatch_positive CHECK (
        tenant_id > 0 AND task_owner_revision > 0 AND task_version > 0
        AND grant_revision > 0 AND tenant_grant_revision > 0
        AND authorization_revision > 0),
    CONSTRAINT pay_hrm013_dispatch_closed CHECK (
        task_type = 'PAYROLL_SETTLEMENT'
        AND grant_use_mode IN ('NEW_WORK', 'DRAIN_EXISTING')
        AND state IN ('ADMITTED', 'COMPLETED', 'FAILED')),
    CONSTRAINT pay_hrm013_dispatch_digest_shape CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$'
        AND request_body_sha256 ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'
        AND proof_sha256 ~ '^[0-9a-f]{64}$'
        AND admission_receipt_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_hrm013_dispatch_bytes_present CHECK (
        octet_length(exact_request_bytes) BETWEEN 1 AND 32768
        AND octet_length(exact_admission_receipt_bytes) BETWEEN 1 AND 16384),
    CONSTRAINT pay_hrm013_dispatch_dates CHECK (
        last_working_date <= effective_date
        AND admitted_at >= dispatched_at
        AND ((state = 'ADMITTED' AND completed_at IS NULL)
             OR (state IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)))
    ,CONSTRAINT pay_hrm013_dispatch_drain_shape CHECK (
        (grant_use_mode = 'NEW_WORK'
            AND drain_root_work_item_id IS NULL
            AND drain_root_created_at IS NULL
            AND drain_root_payload_sha256 IS NULL)
        OR (grant_use_mode = 'DRAIN_EXISTING'
            AND drain_root_work_item_id IS NOT NULL
            AND drain_root_created_at IS NOT NULL
            AND drain_root_payload_sha256 ~ '^[0-9a-f]{64}$'))
);

CREATE TABLE pay_hrm013_proof_replay (
    tenant_id BIGINT NOT NULL,
    binding_id VARCHAR(32) NOT NULL,
    caller_workload_id VARCHAR(96) NOT NULL,
    proof_id_sha256 CHAR(64) NOT NULL,
    request_nonce_sha256 CHAR(64) NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_hrm013_proof_replay_pk PRIMARY KEY (
        tenant_id, binding_id, caller_workload_id, proof_id_sha256),
    CONSTRAINT pay_hrm013_proof_replay_nonce_unique UNIQUE (
        tenant_id, binding_id, caller_workload_id, request_nonce_sha256),
    CONSTRAINT pay_hrm013_proof_replay_positive CHECK (tenant_id > 0),
    CONSTRAINT pay_hrm013_proof_replay_digest_shape CHECK (
        proof_id_sha256 ~ '^[0-9a-f]{64}$'
        AND request_nonce_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX pay_hrm013_proof_replay_expiry_idx
    ON pay_hrm013_proof_replay (tenant_id, retain_until);

CREATE OR REPLACE FUNCTION pay_v56_purge_expired_replays(
    requested_tenant_id BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO pg_catalog, public, pg_temp
AS $pay_v56_purge_replays$
DECLARE
    configured_tenant TEXT := current_setting('dwp.payroll_tenant_id', true);
    purged BIGINT;
BEGIN
    IF requested_tenant_id < 1
       OR configured_tenant IS NULL
       OR configured_tenant = ''
       OR configured_tenant !~ '^[1-9][0-9]*$'
       OR configured_tenant::BIGINT <> requested_tenant_id THEN
        RAISE EXCEPTION 'HRM-013 replay purge tenant mismatch';
    END IF;
    DELETE FROM public.pay_hrm013_proof_replay
     WHERE tenant_id = requested_tenant_id
       AND retain_until < clock_timestamp();
    GET DIAGNOSTICS purged = ROW_COUNT;
    RETURN purged;
END
$pay_v56_purge_replays$;

REVOKE ALL ON FUNCTION pay_v56_purge_expired_replays(BIGINT) FROM PUBLIC;

CREATE TABLE pay_hrm013_dispatch_quarantine (
    quarantine_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    dispatch_event_id UUID NOT NULL,
    stored_request_body_sha256 CHAR(64) NOT NULL,
    rejected_request_body_sha256 CHAR(64) NOT NULL,
    rejected_exact_request_bytes BYTEA NOT NULL,
    correlation_id UUID NOT NULL,
    reason VARCHAR(64) NOT NULL,
    quarantined_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_hrm013_dispatch_quarantine_pk PRIMARY KEY (tenant_id, quarantine_id),
    CONSTRAINT pay_hrm013_dispatch_quarantine_positive CHECK (tenant_id > 0),
    CONSTRAINT pay_hrm013_dispatch_quarantine_reason CHECK (
        reason = 'DISPATCH_DIGEST_CONFLICT'),
    CONSTRAINT pay_hrm013_dispatch_quarantine_digest_shape CHECK (
        stored_request_body_sha256 ~ '^[0-9a-f]{64}$'
        AND rejected_request_body_sha256 ~ '^[0-9a-f]{64}$'
        AND stored_request_body_sha256 <> rejected_request_body_sha256),
    CONSTRAINT pay_hrm013_dispatch_quarantine_bytes CHECK (
        octet_length(rejected_exact_request_bytes) BETWEEN 1 AND 32768)
);

CREATE TABLE pay_hrm013_callback_quarantine (
    quarantine_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    callback_event_id UUID NOT NULL,
    stored_dispatch_event_id UUID NOT NULL,
    rejected_dispatch_event_id UUID NOT NULL,
    stored_callback_payload_sha256 CHAR(64) NOT NULL,
    rejected_callback_payload_sha256 CHAR(64) NOT NULL,
    stored_exact_callback_bytes BYTEA NOT NULL,
    rejected_exact_callback_bytes BYTEA NOT NULL,
    correlation_id UUID NOT NULL,
    reason VARCHAR(64) NOT NULL,
    quarantined_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_hrm013_callback_quarantine_pk PRIMARY KEY (
        tenant_id, quarantine_id),
    CONSTRAINT pay_hrm013_callback_quarantine_positive CHECK (tenant_id > 0),
    CONSTRAINT pay_hrm013_callback_quarantine_reason CHECK (
        reason = 'CALLBACK_IDENTITY_CONFLICT'),
    CONSTRAINT pay_hrm013_callback_quarantine_digest_shape CHECK (
        stored_callback_payload_sha256 ~ '^[0-9a-f]{64}$'
        AND rejected_callback_payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_hrm013_callback_quarantine_bytes CHECK (
        octet_length(stored_exact_callback_bytes) BETWEEN 1 AND 32768
        AND octet_length(rejected_exact_callback_bytes) BETWEEN 1 AND 32768)
);

CREATE TABLE pay_hrm013_receipt_outbox (
    tenant_id BIGINT NOT NULL,
    callback_event_id UUID NOT NULL,
    dispatch_event_id UUID NOT NULL,
    case_public_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    exact_callback_bytes BYTEA NOT NULL,
    callback_payload_sha256 CHAR(64) NOT NULL,
    originating_payload_sha256 CHAR(64) NOT NULL,
    grant_use_mode VARCHAR(24) NOT NULL,
    drain_root_work_item_id UUID,
    drain_root_created_at TIMESTAMPTZ,
    drain_root_payload_sha256 CHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_hrm013_receipt_outbox_pk PRIMARY KEY (tenant_id, callback_event_id),
    CONSTRAINT pay_hrm013_receipt_outbox_dispatch_fk FOREIGN KEY (
        tenant_id, dispatch_event_id) REFERENCES pay_hrm013_dispatch_inbox (
        tenant_id, dispatch_event_id),
    CONSTRAINT pay_hrm013_receipt_outbox_positive CHECK (
        tenant_id > 0 AND attempt_count BETWEEN 0 AND 10),
    CONSTRAINT pay_hrm013_receipt_outbox_closed CHECK (
        grant_use_mode IN ('NEW_WORK', 'DRAIN_EXISTING')
        AND status IN ('PENDING', 'DELIVERING', 'RETRY', 'DELIVERED', 'TERMINAL')),
    CONSTRAINT pay_hrm013_receipt_outbox_digest_shape CHECK (
        callback_payload_sha256 ~ '^[0-9a-f]{64}$'
        AND originating_payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_hrm013_receipt_outbox_bytes CHECK (
        octet_length(exact_callback_bytes) BETWEEN 1 AND 32768),
    CONSTRAINT pay_hrm013_receipt_outbox_drain_shape CHECK (
        drain_root_work_item_id IS NOT NULL
        AND drain_root_created_at IS NOT NULL
        AND drain_root_payload_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX pay_hrm013_receipt_outbox_delivery_idx
    ON pay_hrm013_receipt_outbox (tenant_id, status, available_at, created_at)
    WHERE status IN ('PENDING', 'RETRY', 'DELIVERING');

-- Extension-free RFC 4122 UUIDv5 support for the frozen terminal-child identity.
CREATE OR REPLACE FUNCTION pay_v56_sha1(input_bytes BYTEA)
RETURNS BYTEA
LANGUAGE plpgsql
IMMUTABLE STRICT
AS $pay_v56_sha1$
DECLARE
    message BYTEA := input_bytes || decode('80', 'hex');
    bit_length BIGINT := octet_length(input_bytes)::BIGINT * 8;
    length_bytes BYTEA := decode(repeat('00', 8), 'hex');
    words BIGINT[] := array_fill(0::BIGINT, ARRAY[80], ARRAY[0]);
    h0 BIGINT := 1732584193;
    h1 BIGINT := 4023233417;
    h2 BIGINT := 2562383102;
    h3 BIGINT := 271733878;
    h4 BIGINT := 3285377520;
    a BIGINT; b BIGINT; c BIGINT; d BIGINT; e BIGINT;
    f BIGINT; k BIGINT; temp BIGINT;
    block_offset INTEGER; index INTEGER; byte_index INTEGER;
    hashes BIGINT[];
    result BYTEA := decode(repeat('00', 20), 'hex');
    mask CONSTANT BIGINT := 4294967295;
BEGIN
    WHILE octet_length(message) % 64 <> 56 LOOP
        message := message || decode('00', 'hex');
    END LOOP;
    FOR index IN 0..7 LOOP
        length_bytes := set_byte(length_bytes, 7 - index,
                ((bit_length >> (index * 8)) & 255)::INTEGER);
    END LOOP;
    message := message || length_bytes;

    FOR block_offset IN 0..(octet_length(message) - 1) BY 64 LOOP
        FOR index IN 0..15 LOOP
            byte_index := block_offset + index * 4;
            words[index] := ((get_byte(message, byte_index)::BIGINT << 24)
                    | (get_byte(message, byte_index + 1)::BIGINT << 16)
                    | (get_byte(message, byte_index + 2)::BIGINT << 8)
                    | get_byte(message, byte_index + 3)::BIGINT) & mask;
        END LOOP;
        FOR index IN 16..79 LOOP
            temp := words[index - 3] # words[index - 8]
                    # words[index - 14] # words[index - 16];
            words[index] := (((temp << 1) & mask) | (temp >> 31)) & mask;
        END LOOP;

        a := h0; b := h1; c := h2; d := h3; e := h4;
        FOR index IN 0..79 LOOP
            IF index < 20 THEN
                f := ((b & c) | ((~b) & d)) & mask;
                k := 1518500249;
            ELSIF index < 40 THEN
                f := (b # c # d) & mask;
                k := 1859775393;
            ELSIF index < 60 THEN
                f := ((b & c) | (b & d) | (c & d)) & mask;
                k := 2400959708;
            ELSE
                f := (b # c # d) & mask;
                k := 3395469782;
            END IF;
            temp := (((((a << 5) & mask) | (a >> 27))
                    + f + e + k + words[index])) & mask;
            e := d;
            d := c;
            c := (((b << 30) & mask) | (b >> 2)) & mask;
            b := a;
            a := temp;
        END LOOP;
        h0 := (h0 + a) & mask;
        h1 := (h1 + b) & mask;
        h2 := (h2 + c) & mask;
        h3 := (h3 + d) & mask;
        h4 := (h4 + e) & mask;
    END LOOP;

    hashes := ARRAY[h0, h1, h2, h3, h4];
    FOR index IN 0..4 LOOP
        FOR byte_index IN 0..3 LOOP
            result := set_byte(result, index * 4 + byte_index,
                    ((hashes[index + 1] >> ((3 - byte_index) * 8)) & 255)::INTEGER);
        END LOOP;
    END LOOP;
    RETURN result;
END
$pay_v56_sha1$;

CREATE OR REPLACE FUNCTION pay_v56_uuid_v5(namespace_id UUID, exact_name TEXT)
RETURNS UUID
LANGUAGE plpgsql
IMMUTABLE STRICT
AS $pay_v56_uuid_v5$
DECLARE
    hashed BYTEA := substring(pay_v56_sha1(
            uuid_send(namespace_id) || convert_to(exact_name, 'UTF8')) FROM 1 FOR 16);
    encoded TEXT;
BEGIN
    hashed := set_byte(hashed, 6, (get_byte(hashed, 6) & 15) | 80);
    hashed := set_byte(hashed, 8, (get_byte(hashed, 8) & 63) | 128);
    encoded := encode(hashed, 'hex');
    RETURN (substring(encoded, 1, 8) || '-' || substring(encoded, 9, 4)
            || '-' || substring(encoded, 13, 4) || '-'
            || substring(encoded, 17, 4) || '-'
            || substring(encoded, 21, 12))::UUID;
END
$pay_v56_uuid_v5$;

CREATE OR REPLACE FUNCTION pay_v56_reject_immutable_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $pay_v56_immutable$
BEGIN
    RAISE EXCEPTION 'HRM-013 immutable evidence cannot be updated or deleted';
END
$pay_v56_immutable$;

CREATE OR REPLACE FUNCTION pay_v56_guard_dispatch_inbox_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $pay_v56_inbox_transition$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'ADMITTED' OR NEW.completed_at IS NOT NULL THEN
            RAISE EXCEPTION 'HRM-013 dispatch must begin ADMITTED';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM-013 dispatch evidence cannot be deleted';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['state', 'completed_at'])
            IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['state', 'completed_at']) THEN
        RAISE EXCEPTION 'HRM-013 dispatch snapshot is immutable';
    END IF;
    IF NOT (OLD.state = 'ADMITTED'
            AND OLD.completed_at IS NULL
            AND NEW.state IN ('COMPLETED', 'FAILED')
            AND NEW.completed_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Illegal HRM-013 dispatch transition';
    END IF;
    RETURN NEW;
END
$pay_v56_inbox_transition$;

CREATE OR REPLACE FUNCTION pay_v56_guard_receipt_outbox_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $pay_v56_outbox_transition$
DECLARE
    parent RECORD;
    callback_body JSONB;
    expected_root_id UUID;
    expected_root_created_at TIMESTAMPTZ;
    expected_root_payload_sha256 TEXT;
    expected_callback_event_id UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING'
           OR NEW.attempt_count <> 0
           OR NEW.available_at <> NEW.created_at
           OR NEW.lease_owner IS NOT NULL
           OR NEW.lease_until IS NOT NULL
           OR NEW.delivered_at IS NOT NULL
           OR NEW.last_failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'HRM-013 callback must begin pending and unleased';
        END IF;
        SELECT inbox.* INTO parent
          FROM public.pay_hrm013_dispatch_inbox inbox
         WHERE inbox.tenant_id = NEW.tenant_id
           AND inbox.dispatch_event_id = NEW.dispatch_event_id;
        IF NOT FOUND OR parent.state NOT IN ('COMPLETED', 'FAILED') THEN
            RAISE EXCEPTION 'HRM-013 callback requires its exact terminal dispatch';
        END IF;
        BEGIN
            callback_body := convert_from(NEW.exact_callback_bytes, 'UTF8')::JSONB;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'HRM-013 callback exact body is invalid';
        END;
        IF jsonb_typeof(callback_body) <> 'object'
           OR NOT (callback_body ?& ARRAY[
                'callbackEventId', 'casePublicId', 'taskPublicId', 'taskType',
                'status', 'ownerRevision', 'externalReceiptPublicId',
                'originatingPayloadSha256', 'occurredAt', 'payloadSha256',
                'correlationId'])
           OR (SELECT count(*) FROM jsonb_object_keys(callback_body)) <> 11 THEN
            RAISE EXCEPTION 'HRM-013 callback exact body is invalid';
        END IF;
        IF parent.grant_use_mode = 'NEW_WORK' THEN
            expected_root_id := parent.dispatch_event_id;
            expected_root_created_at := parent.dispatched_at;
            expected_root_payload_sha256 := parent.payload_sha256;
        ELSE
            expected_root_id := parent.drain_root_work_item_id;
            expected_root_created_at := parent.drain_root_created_at;
            expected_root_payload_sha256 := parent.drain_root_payload_sha256;
        END IF;
        expected_callback_event_id := pay_v56_uuid_v5(
                '8ca8c32b-c8f7-5ab4-a9a5-18166dc32128'::UUID,
                NEW.tenant_id::TEXT || chr(10) || expected_root_id::TEXT
                || chr(10) || parent.task_owner_revision::TEXT || chr(10)
                || 'SVC-PEP-HRM-008' || chr(10) || 'TERMINAL_CHILD');
        IF NEW.case_public_id IS DISTINCT FROM parent.case_public_id
           OR NEW.task_public_id IS DISTINCT FROM parent.task_public_id
           OR NEW.correlation_id IS DISTINCT FROM parent.correlation_id
           OR NEW.originating_payload_sha256 IS DISTINCT FROM parent.payload_sha256
           OR NEW.grant_use_mode IS DISTINCT FROM parent.grant_use_mode
           OR NEW.drain_root_work_item_id IS DISTINCT FROM expected_root_id
           OR NEW.drain_root_created_at IS DISTINCT FROM expected_root_created_at
           OR NEW.drain_root_payload_sha256
                IS DISTINCT FROM expected_root_payload_sha256
           OR NEW.created_at IS DISTINCT FROM parent.completed_at
           OR NEW.callback_event_id IS DISTINCT FROM expected_callback_event_id
           OR callback_body->>'callbackEventId'
                IS DISTINCT FROM NEW.callback_event_id::TEXT
           OR callback_body->>'casePublicId'
                IS DISTINCT FROM NEW.case_public_id::TEXT
           OR callback_body->>'taskPublicId'
                IS DISTINCT FROM NEW.task_public_id::TEXT
           OR callback_body->>'taskType' IS DISTINCT FROM parent.task_type
           OR callback_body->>'status' IS DISTINCT FROM parent.state
           OR (callback_body->>'ownerRevision')::BIGINT
                IS DISTINCT FROM parent.task_owner_revision
           OR (callback_body->>'occurredAt')::TIMESTAMPTZ
                IS DISTINCT FROM parent.completed_at
           OR callback_body->>'correlationId'
                IS DISTINCT FROM NEW.correlation_id::TEXT
           OR callback_body->>'originatingPayloadSha256'
                IS DISTINCT FROM NEW.originating_payload_sha256
           OR callback_body->>'payloadSha256'
                IS DISTINCT FROM NEW.callback_payload_sha256 THEN
            RAISE EXCEPTION 'HRM-013 callback requires its exact terminal dispatch';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM-013 callback evidence cannot be deleted';
    END IF;
    IF (to_jsonb(NEW) - ARRAY[
            'status', 'attempt_count', 'available_at', 'lease_owner',
            'lease_until', 'delivered_at', 'last_failure_code'])
            IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY[
            'status', 'attempt_count', 'available_at', 'lease_owner',
            'lease_until', 'delivered_at', 'last_failure_code']) THEN
        RAISE EXCEPTION 'HRM-013 callback snapshot is immutable';
    END IF;
    IF OLD.status IN ('PENDING', 'RETRY')
       AND NEW.status = 'DELIVERING'
       AND OLD.available_at <= CURRENT_TIMESTAMP
       AND OLD.attempt_count < 10
       AND NEW.attempt_count = OLD.attempt_count + 1
       AND NEW.available_at = OLD.available_at
       AND NEW.lease_owner IS NOT NULL
       AND NEW.lease_until > CURRENT_TIMESTAMP
       AND NEW.lease_until <= CURRENT_TIMESTAMP + INTERVAL '300 seconds'
       AND NEW.delivered_at IS NOT DISTINCT FROM OLD.delivered_at
       AND NEW.last_failure_code IS NOT DISTINCT FROM OLD.last_failure_code THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'DELIVERING'
       AND NEW.status = 'DELIVERING'
       AND OLD.lease_until <= CURRENT_TIMESTAMP
       AND OLD.attempt_count < 10
       AND NEW.attempt_count = OLD.attempt_count + 1
       AND NEW.available_at = OLD.available_at
       AND NEW.lease_owner IS NOT NULL
       AND NEW.lease_until > CURRENT_TIMESTAMP
       AND NEW.lease_until <= CURRENT_TIMESTAMP + INTERVAL '300 seconds'
       AND NEW.delivered_at IS NOT DISTINCT FROM OLD.delivered_at
       AND NEW.last_failure_code IS NOT DISTINCT FROM OLD.last_failure_code THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'DELIVERING'
       AND NEW.status = 'DELIVERED'
       AND OLD.lease_until > CURRENT_TIMESTAMP
       AND NEW.attempt_count = OLD.attempt_count
       AND NEW.available_at = OLD.available_at
       AND NEW.lease_owner IS NULL
       AND NEW.lease_until IS NULL
       AND NEW.delivered_at IS NOT DISTINCT FROM CURRENT_TIMESTAMP
       AND NEW.last_failure_code IS NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'DELIVERING'
       AND OLD.lease_until > CURRENT_TIMESTAMP
       AND OLD.attempt_count < 10
       AND NEW.status = 'RETRY'
       AND NEW.attempt_count = OLD.attempt_count
       AND NEW.available_at >= CURRENT_TIMESTAMP
       AND NEW.available_at <= CURRENT_TIMESTAMP + INTERVAL '300 seconds'
       AND NEW.lease_owner IS NULL
       AND NEW.lease_until IS NULL
       AND NEW.delivered_at IS NULL
       AND NEW.last_failure_code IS NOT NULL
       AND NEW.last_failure_code <> 'RESULT_UNKNOWN' THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'DELIVERING'
       AND OLD.lease_until > CURRENT_TIMESTAMP
       AND OLD.attempt_count = 10
       AND NEW.status = 'TERMINAL'
       AND NEW.attempt_count = OLD.attempt_count
       AND NEW.available_at = OLD.available_at
       AND NEW.lease_owner IS NULL
       AND NEW.lease_until IS NULL
       AND NEW.delivered_at IS NULL
       AND NEW.last_failure_code = 'RESULT_UNKNOWN' THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'DELIVERING'
       AND OLD.lease_until <= CURRENT_TIMESTAMP
       AND OLD.attempt_count = 10
       AND NEW.status = 'TERMINAL'
       AND NEW.attempt_count = OLD.attempt_count
       AND NEW.available_at = OLD.available_at
       AND NEW.lease_owner IS NULL
       AND NEW.lease_until IS NULL
       AND NEW.delivered_at IS NULL
       AND NEW.last_failure_code = 'RESULT_UNKNOWN' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Illegal HRM-013 callback transition';
END
$pay_v56_outbox_transition$;

DO $pay_v56_security$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'pay_hrm013_dispatch_inbox',
        'pay_hrm013_proof_replay',
        'pay_hrm013_dispatch_quarantine',
        'pay_hrm013_callback_quarantine',
        'pay_hrm013_receipt_outbox']
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC', table_name);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (tenant_id = NULLIF(current_setting('
            || '''dwp.payroll_tenant_id'', true), '''')::BIGINT) '
            || 'WITH CHECK (tenant_id = NULLIF(current_setting('
            || '''dwp.payroll_tenant_id'', true), '''')::BIGINT)',
            table_name || '_tenant_policy', table_name);
    END LOOP;

    CREATE TRIGGER pay_hrm013_dispatch_inbox_transition
        BEFORE INSERT OR UPDATE OR DELETE ON pay_hrm013_dispatch_inbox
        FOR EACH ROW EXECUTE FUNCTION pay_v56_guard_dispatch_inbox_transition();
    CREATE TRIGGER pay_hrm013_dispatch_quarantine_immutable
        BEFORE UPDATE OR DELETE ON pay_hrm013_dispatch_quarantine
        FOR EACH ROW EXECUTE FUNCTION pay_v56_reject_immutable_mutation();
    CREATE TRIGGER pay_hrm013_callback_quarantine_immutable
        BEFORE UPDATE OR DELETE ON pay_hrm013_callback_quarantine
        FOR EACH ROW EXECUTE FUNCTION pay_v56_reject_immutable_mutation();
    CREATE TRIGGER pay_hrm013_receipt_outbox_transition
        BEFORE INSERT OR UPDATE OR DELETE ON pay_hrm013_receipt_outbox
        FOR EACH ROW EXECUTE FUNCTION pay_v56_guard_receipt_outbox_transition();
END
$pay_v56_security$;

ALTER FUNCTION pay_v56_reject_immutable_mutation() SECURITY DEFINER;
ALTER FUNCTION pay_v56_reject_immutable_mutation()
    SET search_path TO pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION pay_v56_reject_immutable_mutation() FROM PUBLIC;
ALTER FUNCTION pay_v56_guard_dispatch_inbox_transition() SECURITY DEFINER;
ALTER FUNCTION pay_v56_guard_dispatch_inbox_transition()
    SET search_path TO pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION pay_v56_guard_dispatch_inbox_transition() FROM PUBLIC;
ALTER FUNCTION pay_v56_guard_receipt_outbox_transition() SECURITY DEFINER;
ALTER FUNCTION pay_v56_guard_receipt_outbox_transition()
    SET search_path TO pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION pay_v56_guard_receipt_outbox_transition() FROM PUBLIC;
ALTER FUNCTION pay_v56_sha1(BYTEA) SECURITY DEFINER;
ALTER FUNCTION pay_v56_sha1(BYTEA)
    SET search_path TO pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION pay_v56_sha1(BYTEA) FROM PUBLIC;
ALTER FUNCTION pay_v56_uuid_v5(UUID, TEXT) SECURITY DEFINER;
ALTER FUNCTION pay_v56_uuid_v5(UUID, TEXT)
    SET search_path TO pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION pay_v56_uuid_v5(UUID, TEXT) FROM PUBLIC;

DO $pay_v56_runtime_access$
DECLARE
    runtime_role_name TEXT := '${payrollRuntimeRole}';
    runtime_role RECORD;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid Payroll runtime role name';
    END IF;
    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
      INTO runtime_role FROM pg_roles WHERE rolname = runtime_role_name;
    IF NOT FOUND OR runtime_role.rolsuper OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION 'Payroll runtime role is missing or unsafe';
    END IF;

    EXECUTE format('GRANT SELECT ON TABLE pay_hrm013_dispatch_inbox TO %I', runtime_role_name);
    EXECUTE format('GRANT INSERT (tenant_id, dispatch_event_id, case_public_id, task_public_id, task_type, task_owner_revision, task_version, dispatched_at, correlation_id, worker_public_id, relationship_public_id, separation_type, effective_date, last_working_date, payload_sha256, request_body_sha256, command_digest, exact_request_bytes, proof_sha256, grant_set_id, grant_revision, tenant_grant_revision, authorization_revision, grant_use_mode, drain_root_work_item_id, drain_root_created_at, drain_root_payload_sha256, admitted_at, exact_admission_receipt_bytes, admission_receipt_sha256) ON TABLE pay_hrm013_dispatch_inbox TO %I', runtime_role_name);
    EXECUTE format('GRANT UPDATE (state, completed_at) ON TABLE pay_hrm013_dispatch_inbox TO %I', runtime_role_name);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE pay_hrm013_proof_replay TO %I', runtime_role_name);
    EXECUTE format('GRANT EXECUTE ON FUNCTION pay_v56_purge_expired_replays(BIGINT) TO %I', runtime_role_name);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE pay_hrm013_dispatch_quarantine TO %I', runtime_role_name);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE pay_hrm013_callback_quarantine TO %I', runtime_role_name);
    EXECUTE format('GRANT SELECT ON TABLE pay_hrm013_receipt_outbox TO %I', runtime_role_name);
    EXECUTE format('GRANT INSERT (tenant_id, callback_event_id, dispatch_event_id, case_public_id, task_public_id, correlation_id, exact_callback_bytes, callback_payload_sha256, originating_payload_sha256, grant_use_mode, drain_root_work_item_id, drain_root_created_at, drain_root_payload_sha256, available_at, created_at) ON TABLE pay_hrm013_receipt_outbox TO %I', runtime_role_name);
    EXECUTE format('GRANT UPDATE (status, attempt_count, available_at, lease_owner, lease_until, delivered_at, last_failure_code) ON TABLE pay_hrm013_receipt_outbox TO %I', runtime_role_name);
END
$pay_v56_runtime_access$;

COMMENT ON TABLE pay_hrm013_dispatch_inbox IS
    'Durable receipt-first HRM-013 Payroll task dispatch inbox; same-digest replay returns the exact persisted admission receipt.';
COMMENT ON TABLE pay_hrm013_dispatch_quarantine IS
    'Append-only evidence for dispatch identity reuse with a different exact request digest.';
COMMENT ON TABLE pay_hrm013_callback_quarantine IS
    'Append-only evidence for callback identity reuse with divergent immutable content.';
COMMENT ON TABLE pay_hrm013_receipt_outbox IS
    'Durable HRM-008 completion/failure callback outbox, including drain continuation lineage.';
