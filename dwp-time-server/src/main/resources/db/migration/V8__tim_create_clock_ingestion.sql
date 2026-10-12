-- BASE-TFR-TIM-006 / G7-LEASE-TIM-TIME-V8-R1
-- Immutable clock facts are isolated from the mutable work-regime aggregate. The runtime role can
-- append and read tenant-scoped evidence; it cannot update or delete facts, receipts, or envelopes.

CREATE TABLE tme_clock_command_receipts (
    command_receipt_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    command_type VARCHAR(40) NOT NULL,
    actor_id BIGINT NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    population_public_id UUID NOT NULL,
    population_revision BIGINT NOT NULL,
    grant_revision BIGINT NOT NULL,
    membership_revision BIGINT NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    field_policy_revision BIGINT NOT NULL,
    purpose_code VARCHAR(120) NOT NULL,
    authorization_revision BIGINT NOT NULL,
    request_digest CHAR(64) NOT NULL,
    result_event_public_id UUID NOT NULL,
    result_digest CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_clock_receipt_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_clock_receipt_idempotency UNIQUE (
        tenant_id, subject_principal_public_id, command_type, idempotency_key),
    CONSTRAINT ck_tme_clock_receipt_command
        CHECK (command_type IN ('CLOCK_RECORD', 'CLOCK_VOID')),
    CONSTRAINT ck_tme_clock_receipt_actor CHECK (actor_id > 0),
    CONSTRAINT ck_tme_clock_receipt_revisions CHECK (
        population_revision > 0 AND grant_revision > 0 AND membership_revision > 0
        AND field_policy_revision > 0 AND authorization_revision > 0),
    CONSTRAINT ck_tme_clock_receipt_status CHECK (status = 'SUCCEEDED'),
    CONSTRAINT ck_tme_clock_receipt_digests CHECK (
        population_scope_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^[0-9a-f]{64}$'
        AND result_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE tme_clock_events (
    event_order BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    command_receipt_public_id UUID NOT NULL,
    source_public_id UUID NOT NULL,
    source_event_key VARCHAR(200) NOT NULL,
    source_sequence BIGINT,
    source_type VARCHAR(20) NOT NULL,
    device_public_id UUID,
    capture_mode VARCHAR(12) NOT NULL,
    offline_batch_public_id UUID,
    worker_public_id UUID NOT NULL,
    people_assignment_public_id UUID NOT NULL,
    people_assignment_revision BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    source_local_datetime TIMESTAMP NOT NULL,
    source_time_zone VARCHAR(80) NOT NULL,
    source_utc_offset_minutes SMALLINT NOT NULL,
    dst_resolution VARCHAR(40) NOT NULL,
    instant_authority VARCHAR(32) NOT NULL,
    tzdb_version VARCHAR(40) NOT NULL,
    time_zone_rule_version VARCHAR(80) NOT NULL,
    source_provenance_digest CHAR(64) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    trust_level VARCHAR(20) NOT NULL,
    voids_event_public_id UUID,
    correction_reason_code VARCHAR(80),
    correlation_id UUID NOT NULL,
    recorded_by BIGINT NOT NULL,
    CONSTRAINT uk_tme_clock_event_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_clock_source_event UNIQUE (
        tenant_id, source_public_id, source_event_key),
    CONSTRAINT fk_tme_clock_event_receipt FOREIGN KEY (
        tenant_id, command_receipt_public_id)
        REFERENCES tme_clock_command_receipts (tenant_id, public_id),
    CONSTRAINT fk_tme_clock_event_voids FOREIGN KEY (
        tenant_id, voids_event_public_id)
        REFERENCES tme_clock_events (tenant_id, public_id),
    CONSTRAINT ck_tme_clock_event_assignment_revision
        CHECK (people_assignment_revision >= 0),
    CONSTRAINT ck_tme_clock_event_actor CHECK (recorded_by > 0),
    CONSTRAINT ck_tme_clock_event_type CHECK (
        event_type IN ('IN', 'OUT', 'BREAK_START', 'BREAK_END', 'VOID')),
    CONSTRAINT ck_tme_clock_source_type CHECK (
        source_type IN ('WEB', 'MOBILE', 'KIOSK', 'DEVICE', 'IMPORT', 'API')),
    CONSTRAINT ck_tme_clock_capture_mode CHECK (capture_mode IN ('ONLINE', 'OFFLINE')),
    CONSTRAINT ck_tme_clock_capture_evidence CHECK (
        (capture_mode = 'ONLINE' AND offline_batch_public_id IS NULL)
        OR (capture_mode = 'OFFLINE' AND offline_batch_public_id IS NOT NULL)),
    CONSTRAINT ck_tme_clock_device_evidence CHECK (
        (source_type IN ('MOBILE', 'KIOSK', 'DEVICE')) = (device_public_id IS NOT NULL)),
    CONSTRAINT ck_tme_clock_event_offset
        CHECK (source_utc_offset_minutes BETWEEN -840 AND 840),
    CONSTRAINT ck_tme_clock_event_dst_resolution CHECK (dst_resolution IN (
        'EXACT_OFFSET_SUPPLIED', 'EARLIER_OFFSET_SELECTED',
        'LATER_OFFSET_SELECTED', 'GAP_SHIFT_FORWARD')),
    CONSTRAINT ck_tme_clock_event_instant_authority CHECK (instant_authority IN (
        'SOURCE_INSTANT', 'LOCAL_WITH_OFFSET', 'SERVER_RESOLVED')),
    CONSTRAINT ck_tme_clock_event_trust
        CHECK (trust_level IN ('LOW', 'STANDARD', 'VERIFIED')),
    CONSTRAINT ck_tme_clock_event_digests CHECK (
        source_provenance_digest ~ '^[0-9a-f]{64}$'
        AND payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_clock_event_void CHECK (
        (event_type = 'VOID' AND voids_event_public_id IS NOT NULL
            AND correction_reason_code ~ '^[A-Z][A-Z0-9_]{1,79}$'
            AND source_sequence IS NULL)
        OR (event_type <> 'VOID' AND voids_event_public_id IS NULL
            AND correction_reason_code IS NULL AND source_sequence > 0)),
    CONSTRAINT ck_tme_clock_event_not_self_void
        CHECK (voids_event_public_id IS NULL OR voids_event_public_id <> public_id)
);

CREATE UNIQUE INDEX uk_tme_clock_source_sequence
    ON tme_clock_events (tenant_id, source_public_id, source_sequence)
    WHERE source_sequence IS NOT NULL;
CREATE UNIQUE INDEX uk_tme_clock_event_single_void
    ON tme_clock_events (tenant_id, voids_event_public_id)
    WHERE event_type = 'VOID';
CREATE INDEX ix_tme_clock_worker_order
    ON tme_clock_events (tenant_id, worker_public_id, event_order DESC);
ALTER TABLE tme_clock_events
    ADD CONSTRAINT uk_tme_clock_event_tenant_order UNIQUE (tenant_id, event_order);

CREATE TABLE tme_clock_outbox_events (
    outbox_event_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_public_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    schema_name VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_tme_clock_outbox_event UNIQUE (tenant_id, event_id),
    CONSTRAINT fk_tme_clock_outbox_fact FOREIGN KEY (
        tenant_id, aggregate_sequence)
        REFERENCES tme_clock_events (tenant_id, event_order),
    CONSTRAINT ck_tme_clock_outbox_aggregate CHECK (aggregate_type = 'ClockStream'),
    CONSTRAINT ck_tme_clock_outbox_schema CHECK (
        schema_name = 'ClockEventRecorded' AND schema_version = 1),
    CONSTRAINT ck_tme_clock_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_tme_clock_outbox_digest
        CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_clock_outbox_attempts CHECK (publish_attempts >= 0)
);
CREATE INDEX ix_tme_clock_outbox_unpublished
    ON tme_clock_outbox_events (occurred_at, outbox_event_id)
    WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION tme_guard_clock_source_order()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE previous_sequence BIGINT;
BEGIN
    IF NEW.source_sequence IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(
        hashtextextended(NEW.tenant_id::TEXT || ':' || NEW.source_public_id::TEXT, 0));
    SELECT MAX(source_sequence)
      INTO previous_sequence
      FROM public.tme_clock_events
     WHERE tenant_id = NEW.tenant_id
       AND source_public_id = NEW.source_public_id
       AND source_sequence IS NOT NULL;
    IF previous_sequence IS NOT NULL
       AND NEW.source_sequence <= previous_sequence
       AND NEW.capture_mode <> 'OFFLINE' THEN
        RAISE EXCEPTION 'online clock source sequence must advance monotonically'
            USING ERRCODE = '23514';
    END IF;
    -- A unique delayed offline fact is preserved. Snapshot projections derive its typed
    -- OUT_OF_ORDER_OFFLINE_SEQUENCE quarantine without rewriting this immutable row.
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION tme_reject_clock_fact_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION 'clock facts and receipts are append-only' USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION tme_guard_clock_outbox_delivery()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'clock outbox events cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF ROW(NEW.tenant_id, NEW.event_id, NEW.aggregate_type, NEW.aggregate_public_id,
           NEW.aggregate_sequence, NEW.schema_name, NEW.schema_version, NEW.payload,
           NEW.payload_digest, NEW.correlation_id, NEW.causation_id, NEW.occurred_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.event_id, OLD.aggregate_type, OLD.aggregate_public_id,
           OLD.aggregate_sequence, OLD.schema_name, OLD.schema_version, OLD.payload,
           OLD.payload_digest, OLD.correlation_id, OLD.causation_id, OLD.occurred_at) THEN
        RAISE EXCEPTION 'clock outbox envelope is immutable' USING ERRCODE = '55000';
    END IF;
    IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION 'clock outbox publication is immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.publish_attempts < OLD.publish_attempts THEN
        RAISE EXCEPTION 'clock outbox attempts must be monotonic' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_tme_clock_source_order
    BEFORE INSERT ON tme_clock_events
    FOR EACH ROW EXECUTE FUNCTION tme_guard_clock_source_order();
CREATE TRIGGER trg_tme_clock_events_immutable
    BEFORE UPDATE OR DELETE ON tme_clock_events
    FOR EACH ROW EXECUTE FUNCTION tme_reject_clock_fact_mutation();
CREATE TRIGGER trg_tme_clock_receipts_immutable
    BEFORE UPDATE OR DELETE ON tme_clock_command_receipts
    FOR EACH ROW EXECUTE FUNCTION tme_reject_clock_fact_mutation();
CREATE TRIGGER trg_tme_clock_outbox_delivery
    BEFORE UPDATE OR DELETE ON tme_clock_outbox_events
    FOR EACH ROW EXECUTE FUNCTION tme_guard_clock_outbox_delivery();

DO $block$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'tme_clock_command_receipts', 'tme_clock_events', 'tme_clock_outbox_events'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING '
            || '(tenant_id = NULLIF(current_setting(''dwp.tenant_id'', true), '''')::BIGINT) '
            || 'WITH CHECK '
            || '(tenant_id = NULLIF(current_setting(''dwp.tenant_id'', true), '''')::BIGINT)',
            table_name || '_tenant_isolation', table_name);
    END LOOP;
END;
$block$;

REVOKE ALL ON TABLE
    tme_clock_command_receipts,
    tme_clock_events,
    tme_clock_outbox_events
FROM PUBLIC;
REVOKE ALL ON SEQUENCE
    tme_clock_command_receipts_command_receipt_id_seq,
    tme_clock_events_event_order_seq,
    tme_clock_outbox_events_outbox_event_id_seq
FROM PUBLIC;

GRANT SELECT, INSERT ON TABLE
    tme_clock_command_receipts,
    tme_clock_events,
    tme_clock_outbox_events
TO "${timeRuntimeRole}";
GRANT USAGE, SELECT ON SEQUENCE
    tme_clock_command_receipts_command_receipt_id_seq,
    tme_clock_events_event_order_seq,
    tme_clock_outbox_events_outbox_event_id_seq
TO "${timeRuntimeRole}";

GRANT SELECT ON TABLE tme_clock_outbox_events
TO "${timeProjectionPublisherRole}";
GRANT UPDATE (published_at, publish_attempts) ON TABLE tme_clock_outbox_events
TO "${timeProjectionPublisherRole}";

REVOKE ALL ON FUNCTION
    tme_guard_clock_source_order(),
    tme_reject_clock_fact_mutation(),
    tme_guard_clock_outbox_delivery()
FROM PUBLIC;
REVOKE ALL ON FUNCTION
    tme_guard_clock_source_order(),
    tme_reject_clock_fact_mutation(),
    tme_guard_clock_outbox_delivery()
FROM "${timeRuntimeRole}";
REVOKE ALL ON FUNCTION
    tme_guard_clock_source_order(),
    tme_reject_clock_fact_mutation(),
    tme_guard_clock_outbox_delivery()
FROM "${timeProjectionPublisherRole}";
