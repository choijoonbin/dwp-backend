-- BASE-TFR-HRM-012: immutable workforce-ingestion run and item evidence.
-- Connector, mapping, cursor, receipt, audit and outbox stores are reused.

CREATE TABLE ppl_workforce_ingestion_runs (
    workforce_ingestion_run_id UUID PRIMARY KEY
        DEFAULT pg_catalog.gen_random_uuid(),
    run_public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    command_receipt_id UUID NOT NULL,
    linked_replay_receipt_id UUID,
    linked_replay_run_id UUID,
    connector_instance_id UUID NOT NULL,
    connector_public_id UUID NOT NULL,
    connector_version BIGINT NOT NULL,
    cursor_type VARCHAR(30) NOT NULL DEFAULT 'OPAQUE_TOKEN',
    mapping_profile_id UUID NOT NULL,
    mapping_public_id UUID NOT NULL,
    mapping_version BIGINT NOT NULL,
    mapping_sha256 CHAR(64) NOT NULL,
    legal_entity_public_ids UUID[] NOT NULL DEFAULT '{}'::UUID[],
    population_scope_digest CHAR(64) NOT NULL,
    object_ref VARCHAR(132) NOT NULL,
    object_digest CHAR(64) NOT NULL,
    schema_key VARCHAR(128) NOT NULL,
    schema_version BIGINT NOT NULL,
    mode VARCHAR(16) NOT NULL,
    effective_date DATE NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    source_count BIGINT NOT NULL DEFAULT 0,
    accepted_count BIGINT NOT NULL DEFAULT 0,
    rejected_count BIGINT NOT NULL DEFAULT 0,
    duplicate_count BIGINT NOT NULL DEFAULT 0,
    quarantined_count BIGINT NOT NULL DEFAULT 0,
    applied_count BIGINT NOT NULL DEFAULT 0,
    result_digest CHAR(64),
    cursor_before_digest CHAR(64) NOT NULL,
    cursor_after_digest CHAR(64),
    cursor_expected_version BIGINT NOT NULL,
    cursor_committed_version BIGINT,
    failure_code VARCHAR(80),
    redacted_failure_summary VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_ppl_workforce_ingestion_runs_public
        UNIQUE (run_public_id),
    CONSTRAINT uk_ppl_workforce_ingestion_runs_id
        UNIQUE (tenant_id, workforce_ingestion_run_id),
    CONSTRAINT uk_ppl_workforce_ingestion_runs_receipt
        UNIQUE (tenant_id, command_receipt_id),
    CONSTRAINT fk_ppl_workforce_ingestion_runs_receipt FOREIGN KEY
        (tenant_id, command_receipt_id)
        REFERENCES ppl_command_receipts(tenant_id, command_receipt_id),
    CONSTRAINT fk_ppl_workforce_ingestion_runs_replay_receipt FOREIGN KEY
        (tenant_id, linked_replay_receipt_id)
        REFERENCES ppl_command_receipts(tenant_id, command_receipt_id),
    CONSTRAINT fk_ppl_workforce_ingestion_runs_replay_run FOREIGN KEY
        (tenant_id, linked_replay_run_id)
        REFERENCES ppl_workforce_ingestion_runs(
            tenant_id, workforce_ingestion_run_id),
    CONSTRAINT fk_ppl_workforce_ingestion_runs_connector FOREIGN KEY
        (tenant_id, connector_instance_id)
        REFERENCES int_connector_instances(tenant_id, connector_instance_id),
    CONSTRAINT fk_ppl_workforce_ingestion_runs_mapping FOREIGN KEY
        (tenant_id, mapping_profile_id)
        REFERENCES int_mapping_profiles(tenant_id, mapping_profile_id),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_public_snapshots CHECK (
        connector_public_id = connector_instance_id
        AND mapping_public_id = mapping_profile_id),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_versions CHECK (
        connector_version >= 0
        AND mapping_version > 0
        AND schema_version > 0
        AND cursor_expected_version >= 0
        AND (cursor_committed_version IS NULL
             OR cursor_committed_version > cursor_expected_version)
        AND version >= 0),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_digests CHECK (
        mapping_sha256 ~ '^[0-9a-f]{64}$'
        AND population_scope_digest ~ '^[0-9a-f]{64}$'
        AND object_digest ~ '^[0-9a-f]{64}$'
        AND cursor_before_digest ~ '^[0-9a-f]{64}$'
        AND (cursor_after_digest IS NULL
             OR cursor_after_digest ~ '^[0-9a-f]{64}$')
        AND (result_digest IS NULL
             OR result_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_object CHECK (
        object_ref ~ '^obj_[A-Za-z0-9_-]{16,128}$'
        AND char_length(schema_key) BETWEEN 1 AND 128),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_population CHECK (
        array_position(legal_entity_public_ids, NULL) IS NULL),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_mode CHECK (
        mode IN ('VALIDATE_ONLY','APPLY')),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_cursor_type CHECK (
        cursor_type IN ('WATERMARK','OPAQUE_TOKEN','OFFSET')),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_state CHECK (
        lifecycle_state IN (
            'RECEIVED','RUNNING','FAILED_RETRYABLE',
            'SUCCEEDED','REJECTED','DEAD')),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_counts CHECK (
        source_count >= 0
        AND accepted_count >= 0
        AND rejected_count >= 0
        AND duplicate_count >= 0
        AND quarantined_count >= 0
        AND applied_count >= 0),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_lease CHECK (
        (lifecycle_state = 'RUNNING'
         AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (lifecycle_state <> 'RUNNING'
            AND lease_owner IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_retry CHECK (
        (lifecycle_state = 'FAILED_RETRYABLE' AND next_attempt_at IS NOT NULL)
        OR (lifecycle_state <> 'FAILED_RETRYABLE'
            AND next_attempt_at IS NULL)),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_terminal CHECK (
        (lifecycle_state = 'SUCCEEDED'
         AND result_digest IS NOT NULL
         AND failure_code IS NULL
         AND redacted_failure_summary IS NULL
         AND completed_at IS NOT NULL)
        OR (lifecycle_state IN ('REJECTED','DEAD')
            AND result_digest IS NOT NULL
            AND failure_code IS NOT NULL
            AND redacted_failure_summary IS NOT NULL
            AND completed_at IS NOT NULL)
        OR (lifecycle_state = 'FAILED_RETRYABLE'
            AND result_digest IS NULL
            AND failure_code IS NOT NULL
            AND redacted_failure_summary IS NOT NULL
            AND completed_at IS NULL)
        OR (lifecycle_state IN ('RECEIVED','RUNNING')
            AND result_digest IS NULL
            AND failure_code IS NULL
            AND redacted_failure_summary IS NULL
            AND completed_at IS NULL)),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_cursor CHECK (
        (mode = 'VALIDATE_ONLY'
         AND cursor_after_digest IS NULL
         AND cursor_committed_version IS NULL)
        OR (mode = 'APPLY' AND lifecycle_state = 'SUCCEEDED'
            AND cursor_after_digest IS NOT NULL
            AND cursor_committed_version = cursor_expected_version + 1)
        OR (mode = 'APPLY' AND lifecycle_state <> 'SUCCEEDED'
            AND cursor_after_digest IS NULL
            AND cursor_committed_version IS NULL)),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_replay CHECK (
        ((linked_replay_receipt_id IS NULL)
         = (linked_replay_run_id IS NULL))
        AND linked_replay_receipt_id IS DISTINCT FROM command_receipt_id
        AND linked_replay_run_id IS DISTINCT FROM workforce_ingestion_run_id),
    CONSTRAINT ck_ppl_workforce_ingestion_runs_time CHECK (
        updated_at >= created_at
        AND (completed_at IS NULL OR completed_at >= created_at))
);

CREATE TABLE ppl_workforce_ingestion_items (
    workforce_ingestion_item_id UUID PRIMARY KEY
        DEFAULT pg_catalog.gen_random_uuid(),
    item_public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    workforce_ingestion_run_id UUID NOT NULL,
    connector_instance_id UUID NOT NULL,
    item_sequence BIGINT NOT NULL,
    source_record_key_digest CHAR(64) NOT NULL,
    source_version_digest CHAR(64) NOT NULL,
    input_digest CHAR(64) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    outcome_digest CHAR(64) NOT NULL,
    finding_code VARCHAR(80),
    redacted_summary VARCHAR(1000),
    applied_effect_digest CHAR(64),
    quarantine_ciphertext BYTEA,
    quarantine_key_reference VARCHAR(255),
    quarantine_envelope_digest CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT uk_ppl_workforce_ingestion_items_public
        UNIQUE (item_public_id),
    CONSTRAINT uk_ppl_workforce_ingestion_items_sequence
        UNIQUE (tenant_id, workforce_ingestion_run_id, item_sequence),
    CONSTRAINT fk_ppl_workforce_ingestion_items_run FOREIGN KEY
        (tenant_id, workforce_ingestion_run_id)
        REFERENCES ppl_workforce_ingestion_runs(
            tenant_id, workforce_ingestion_run_id),
    CONSTRAINT fk_ppl_workforce_ingestion_items_connector FOREIGN KEY
        (tenant_id, connector_instance_id)
        REFERENCES int_connector_instances(tenant_id, connector_instance_id),
    CONSTRAINT ck_ppl_workforce_ingestion_items_sequence CHECK (
        item_sequence > 0),
    CONSTRAINT ck_ppl_workforce_ingestion_items_digests CHECK (
        source_record_key_digest ~ '^[0-9a-f]{64}$'
        AND source_version_digest ~ '^[0-9a-f]{64}$'
        AND input_digest ~ '^[0-9a-f]{64}$'
        AND outcome_digest ~ '^[0-9a-f]{64}$'
        AND (applied_effect_digest IS NULL
             OR applied_effect_digest ~ '^[0-9a-f]{64}$')
        AND (quarantine_envelope_digest IS NULL
             OR quarantine_envelope_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ppl_workforce_ingestion_items_outcome CHECK (
        outcome IN (
            'VALIDATED','REJECTED','DUPLICATE',
            'APPLIED','QUARANTINED_AFTER_ACCEPTANCE')),
    CONSTRAINT ck_ppl_workforce_ingestion_items_evidence CHECK (
        (outcome = 'VALIDATED'
         AND finding_code IS NULL AND redacted_summary IS NULL
         AND applied_effect_digest IS NULL
         AND quarantine_ciphertext IS NULL
         AND quarantine_key_reference IS NULL
         AND quarantine_envelope_digest IS NULL)
        OR (outcome = 'REJECTED'
            AND finding_code IS NOT NULL AND redacted_summary IS NOT NULL
            AND applied_effect_digest IS NULL
            AND quarantine_ciphertext IS NULL
            AND quarantine_key_reference IS NULL
            AND quarantine_envelope_digest IS NULL)
        OR (outcome = 'DUPLICATE'
            AND finding_code IS NULL AND redacted_summary IS NULL
            AND applied_effect_digest IS NULL
            AND quarantine_ciphertext IS NULL
            AND quarantine_key_reference IS NULL
            AND quarantine_envelope_digest IS NULL)
        OR (outcome = 'APPLIED'
            AND finding_code IS NULL AND redacted_summary IS NULL
            AND applied_effect_digest IS NOT NULL
            AND quarantine_ciphertext IS NULL
            AND quarantine_key_reference IS NULL
            AND quarantine_envelope_digest IS NULL)
        OR (outcome = 'QUARANTINED_AFTER_ACCEPTANCE'
            AND finding_code IS NOT NULL AND redacted_summary IS NOT NULL
            AND applied_effect_digest IS NULL
            AND quarantine_ciphertext IS NOT NULL
            AND octet_length(quarantine_ciphertext) > 0
            AND quarantine_key_reference IS NOT NULL
            AND quarantine_envelope_digest IS NOT NULL))
);

CREATE INDEX idx_ppl_workforce_ingestion_runs_work_queue
    ON ppl_workforce_ingestion_runs(
        tenant_id, lifecycle_state, next_attempt_at, lease_until, created_at)
    WHERE lifecycle_state IN ('RECEIVED','FAILED_RETRYABLE','RUNNING');
CREATE INDEX idx_ppl_workforce_ingestion_runs_stable
    ON ppl_workforce_ingestion_runs(
        tenant_id, effective_date DESC, created_at DESC, run_public_id DESC);
CREATE INDEX idx_ppl_workforce_ingestion_runs_connector
    ON ppl_workforce_ingestion_runs(
        tenant_id, connector_instance_id, created_at DESC);
CREATE INDEX idx_ppl_workforce_ingestion_runs_replay
    ON ppl_workforce_ingestion_runs(
        tenant_id, linked_replay_receipt_id, linked_replay_run_id)
    WHERE linked_replay_run_id IS NOT NULL;
CREATE INDEX idx_ppl_workforce_ingestion_items_stable
    ON ppl_workforce_ingestion_items(
        tenant_id, workforce_ingestion_run_id,
        item_sequence, item_public_id);
CREATE INDEX idx_ppl_workforce_ingestion_items_duplicate
    ON ppl_workforce_ingestion_items(
        tenant_id, connector_instance_id, source_record_key_digest,
        source_version_digest, input_digest);
CREATE INDEX idx_ppl_workforce_ingestion_items_outcome
    ON ppl_workforce_ingestion_items(
        tenant_id, outcome, created_at DESC, item_public_id);
CREATE UNIQUE INDEX uk_sys_people_outbox_hrm012_reconciled
    ON sys_people_outbox_events(
        tenant_id, aggregate_type, aggregate_id, event_type)
    WHERE aggregate_type = 'WorkforceIngestionRun'
      AND event_type = 'WorkforceIngestionReconciled.v1';

CREATE FUNCTION public.ppl_guard_hrm012_receipt_authority()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    frozen JSONB;
    frozen_key_total BIGINT;
    authority JSONB;
    evidence JSONB;
    current_authority JSONB;
    previous_authority JSONB;
    old_authority JSONB;
    old_evidence JSONB;
    old_current_authority JSONB;
    old_previous_authority JSONB;
    authority_key_total BIGINT;
    current_key_total BIGINT;
    previous_key_total BIGINT;
    evidence_total INTEGER;
    old_evidence_total INTEGER;
    run_total BIGINT;
BEGIN
    IF NEW.command_type <> 'WORKFORCE_INGESTION' THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE'
       AND NEW.result_reference->'hrm012FrozenAuthority'
           IS DISTINCT FROM
           OLD.result_reference->'hrm012FrozenAuthority' THEN
        RAISE EXCEPTION 'HRM-012 frozen receipt authority is immutable'
            USING ERRCODE = '23514';
    END IF;

    frozen := NEW.result_reference->'hrm012FrozenAuthority';
    IF frozen IS NULL OR pg_catalog.jsonb_typeof(frozen) <> 'object' THEN
        RAISE EXCEPTION 'HRM-012 receipt requires frozen authority object'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO frozen_key_total
      FROM pg_catalog.jsonb_object_keys(frozen);
    IF frozen_key_total <> 18
       OR NOT (frozen ?& ARRAY[
           'tenantId','numericUserId','personPublicId','markerJti',
           'markerSha256','contextKey','scopeRef','populationScopeDigest',
           'authorizationRevision','populationRevision','fieldPolicyRevision',
           'postReceiptDecisionRevision','dutyEvidenceRevision',
           'featureDecisionRevision','purpose','correlationId',
           'idempotencyKeySha256','canonicalRequestHash']::TEXT[]) THEN
        RAISE EXCEPTION 'HRM-012 frozen receipt authority shape is not closed'
            USING ERRCODE = '23514';
    END IF;

    IF pg_catalog.jsonb_typeof(frozen->'tenantId') <> 'number'
       OR pg_catalog.jsonb_typeof(frozen->'numericUserId') <> 'number'
       OR pg_catalog.jsonb_typeof(frozen->'authorizationRevision') <> 'number'
       OR pg_catalog.jsonb_typeof(frozen->'populationRevision') <> 'number'
       OR pg_catalog.jsonb_typeof(frozen->'fieldPolicyRevision') <> 'number'
       OR frozen->>'tenantId' !~ '^[1-9][0-9]{0,18}$'
       OR frozen->>'numericUserId' !~ '^[1-9][0-9]{0,18}$'
       OR frozen->>'authorizationRevision' !~ '^[1-9][0-9]{0,18}$'
       OR frozen->>'populationRevision' !~ '^[1-9][0-9]{0,18}$'
       OR frozen->>'fieldPolicyRevision' !~ '^[1-9][0-9]{0,18}$' THEN
        RAISE EXCEPTION 'HRM-012 frozen receipt authority revisions are invalid'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_catalog.unnest(ARRAY[
              'personPublicId','markerJti','markerSha256','contextKey',
              'scopeRef','populationScopeDigest','postReceiptDecisionRevision',
              'dutyEvidenceRevision','featureDecisionRevision','purpose',
              'correlationId','idempotencyKeySha256',
              'canonicalRequestHash']::TEXT[]) AS required_string(key)
         WHERE pg_catalog.jsonb_typeof(frozen->required_string.key) <> 'string'
    ) THEN
        RAISE EXCEPTION 'HRM-012 frozen receipt authority value types are invalid'
            USING ERRCODE = '23514';
    END IF;

    IF frozen->>'personPublicId'
            !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR frozen->>'correlationId'
            !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR frozen->>'markerSha256' !~ '^[0-9a-f]{64}$'
       OR frozen->>'populationScopeDigest' !~ '^[0-9a-f]{64}$'
       OR frozen->>'idempotencyKeySha256' !~ '^[0-9a-f]{64}$'
       OR frozen->>'canonicalRequestHash' !~ '^[0-9a-f]{64}$'
       OR frozen->>'purpose' <> 'HRIS_INTEGRATION' THEN
        RAISE EXCEPTION 'HRM-012 frozen receipt authority values are invalid'
            USING ERRCODE = '23514';
    END IF;

    IF (frozen->>'tenantId')::NUMERIC <> NEW.tenant_id::NUMERIC
       OR frozen->>'personPublicId' <> NEW.actor_public_id::TEXT
       OR NEW.actor_public_id IS DISTINCT FROM NEW.subject_principal_public_id
       OR frozen->>'populationScopeDigest'
            <> NEW.population_scope_digest::TEXT
       OR (frozen->>'fieldPolicyRevision')::NUMERIC
            <> NEW.field_policy_revision::NUMERIC
       OR frozen->>'purpose' <> NEW.purpose_code
       OR (frozen->>'authorizationRevision')::NUMERIC
            <> NEW.authorization_revision::NUMERIC
       OR frozen->>'correlationId' <> NEW.correlation_id::TEXT
       OR frozen->>'canonicalRequestHash' <> NEW.request_hash::TEXT THEN
        RAISE EXCEPTION 'HRM-012 frozen receipt authority does not match receipt identity'
            USING ERRCODE = '23514';
    END IF;

    IF char_length(frozen->>'markerJti') NOT BETWEEN 1 AND 160
       OR char_length(frozen->>'contextKey') NOT BETWEEN 1 AND 256
       OR char_length(frozen->>'scopeRef') NOT BETWEEN 1 AND 512
       OR char_length(frozen->>'postReceiptDecisionRevision') NOT BETWEEN 1 AND 500
       OR char_length(frozen->>'dutyEvidenceRevision') NOT BETWEEN 1 AND 500
       OR char_length(frozen->>'featureDecisionRevision') NOT BETWEEN 1 AND 500
       OR EXISTS (
           SELECT 1
             FROM pg_catalog.unnest(ARRAY[
                 frozen->>'markerJti', frozen->>'contextKey',
                 frozen->>'scopeRef', frozen->>'postReceiptDecisionRevision',
                 frozen->>'dutyEvidenceRevision',
                 frozen->>'featureDecisionRevision']::TEXT[]) AS canonical(value)
            WHERE canonical.value <> pg_catalog.btrim(canonical.value)
               OR canonical.value ~ '[[:cntrl:],]'
       ) THEN
        RAISE EXCEPTION 'HRM-012 frozen receipt authority text is not canonical'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        old_authority := OLD.result_reference->'hrm012Authority';
    END IF;
    authority := NEW.result_reference->'hrm012Authority';
    IF authority IS NULL THEN
        IF old_authority IS NOT NULL THEN
            RAISE EXCEPTION 'HRM-012 marker authority evidence cannot be removed'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' THEN
        RAISE EXCEPTION 'HRM-012 marker authority must be bound after receipt reservation'
            USING ERRCODE = '23514';
    END IF;

    IF pg_catalog.jsonb_typeof(authority) <> 'object' THEN
        RAISE EXCEPTION 'HRM-012 marker authority shape is invalid'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO authority_key_total
      FROM pg_catalog.jsonb_object_keys(authority);
    IF authority_key_total <> 1
       OR NOT (authority ? 'authorityEvidence')
       OR pg_catalog.jsonb_typeof(authority->'authorityEvidence') <> 'array' THEN
        RAISE EXCEPTION 'HRM-012 marker authority shape is invalid'
            USING ERRCODE = '23514';
    END IF;

    evidence := authority->'authorityEvidence';
    evidence_total := pg_catalog.jsonb_array_length(evidence);
    IF evidence_total NOT BETWEEN 1 AND 2 THEN
        RAISE EXCEPTION 'HRM-012 marker authority evidence must contain one current and at most one previous entry'
            USING ERRCODE = '23514';
    END IF;

    current_authority := evidence->0;
    IF pg_catalog.jsonb_typeof(current_authority) <> 'object' THEN
        RAISE EXCEPTION 'HRM-012 marker current evidence shape is invalid'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO current_key_total
      FROM pg_catalog.jsonb_object_keys(current_authority);
    IF current_key_total <> 6
       OR NOT (current_authority ?& ARRAY[
           'jti','exp','markerSha256','state','boundAt',
           'authDecisionRevision']::TEXT[])
       OR EXISTS (
           SELECT 1
             FROM pg_catalog.unnest(ARRAY[
                 'jti','exp','markerSha256','state','boundAt']::TEXT[])
                  AS required_string(key)
            WHERE pg_catalog.jsonb_typeof(
                    current_authority->required_string.key) <> 'string')
       OR pg_catalog.jsonb_typeof(
              current_authority->'authDecisionRevision')
              NOT IN ('string','null') THEN
        RAISE EXCEPTION 'HRM-012 marker current evidence shape is invalid'
            USING ERRCODE = '23514';
    END IF;
    IF char_length(current_authority->>'jti') NOT BETWEEN 1 AND 512
       OR current_authority->>'jti'
            <> pg_catalog.btrim(current_authority->>'jti')
       OR current_authority->>'jti' ~ '[[:cntrl:],]'
       OR current_authority->>'markerSha256' !~ '^[0-9a-f]{64}$'
       OR current_authority->>'state' NOT IN (
            'UNVERIFIED','DENIED','TRANSIENT_UNAVAILABLE','VERIFIED_ALLOWED')
       OR current_authority->>'exp'
            !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$'
       OR current_authority->>'boundAt'
            !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$'
       OR (current_authority->>'exp')::TIMESTAMPTZ
            <= (current_authority->>'boundAt')::TIMESTAMPTZ
       OR (current_authority->>'state' = 'VERIFIED_ALLOWED'
           AND (pg_catalog.jsonb_typeof(
                    current_authority->'authDecisionRevision') <> 'string'
                OR char_length(
                    current_authority->>'authDecisionRevision')
                    NOT BETWEEN 1 AND 512
                OR current_authority->>'authDecisionRevision'
                    <> frozen->>'postReceiptDecisionRevision'))
       OR (current_authority->>'state' <> 'VERIFIED_ALLOWED'
           AND pg_catalog.jsonb_typeof(
                   current_authority->'authDecisionRevision') <> 'null') THEN
        RAISE EXCEPTION 'HRM-012 marker current evidence values are invalid'
            USING ERRCODE = '23514';
    END IF;

    IF evidence_total = 2 THEN
        previous_authority := evidence->1;
        IF pg_catalog.jsonb_typeof(previous_authority) <> 'object' THEN
            RAISE EXCEPTION 'HRM-012 marker previous evidence shape is invalid'
                USING ERRCODE = '23514';
        END IF;
        SELECT count(*) INTO previous_key_total
          FROM pg_catalog.jsonb_object_keys(previous_authority);
        IF previous_key_total <> 4
           OR NOT (previous_authority ?& ARRAY[
               'jtiSha256','exp','state','replacedAt']::TEXT[])
           OR EXISTS (
               SELECT 1
                 FROM pg_catalog.unnest(ARRAY[
                     'jtiSha256','exp','state','replacedAt']::TEXT[])
                      AS required_string(key)
                WHERE pg_catalog.jsonb_typeof(
                        previous_authority->required_string.key) <> 'string')
           OR previous_authority->>'jtiSha256' !~ '^[0-9a-f]{64}$'
           OR previous_authority->>'state' NOT IN (
                'UNVERIFIED','DENIED','TRANSIENT_UNAVAILABLE')
           OR previous_authority->>'exp'
                !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$'
           OR previous_authority->>'replacedAt'
                !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$' THEN
            RAISE EXCEPTION 'HRM-012 marker previous evidence shape is invalid'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF old_authority IS NULL THEN
        SELECT count(*) INTO run_total
          FROM public.ppl_workforce_ingestion_runs run
         WHERE run.tenant_id = NEW.tenant_id
           AND run.command_receipt_id = NEW.command_receipt_id;
        IF evidence_total <> 1
           OR current_authority->>'state' <> 'UNVERIFIED'
           OR (current_authority->>'exp')::TIMESTAMPTZ
                <= pg_catalog.clock_timestamp()
           OR run_total <> 0 THEN
            RAISE EXCEPTION 'HRM-012 initial marker authority binding is invalid'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF pg_catalog.jsonb_typeof(old_authority) <> 'object'
       OR pg_catalog.jsonb_typeof(
              old_authority->'authorityEvidence') <> 'array'
       OR pg_catalog.jsonb_array_length(
              old_authority->'authorityEvidence') NOT BETWEEN 1 AND 2 THEN
        RAISE EXCEPTION 'HRM-012 prior marker authority evidence is invalid'
            USING ERRCODE = '23514';
    END IF;
    old_evidence := old_authority->'authorityEvidence';
    old_evidence_total := pg_catalog.jsonb_array_length(old_evidence);
    old_current_authority := old_evidence->0;
    IF pg_catalog.jsonb_typeof(old_current_authority) <> 'object' THEN
        RAISE EXCEPTION 'HRM-012 prior marker authority evidence is invalid'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO current_key_total
      FROM pg_catalog.jsonb_object_keys(old_current_authority);
    IF current_key_total <> 6
       OR NOT (old_current_authority ?& ARRAY[
           'jti','exp','markerSha256','state','boundAt',
           'authDecisionRevision']::TEXT[])
       OR EXISTS (
           SELECT 1
             FROM pg_catalog.unnest(ARRAY[
                 'jti','exp','markerSha256','state','boundAt']::TEXT[])
                  AS required_string(key)
            WHERE pg_catalog.jsonb_typeof(
                    old_current_authority->required_string.key) <> 'string')
       OR pg_catalog.jsonb_typeof(
              old_current_authority->'authDecisionRevision')
              NOT IN ('string','null')
       OR char_length(old_current_authority->>'jti') NOT BETWEEN 1 AND 512
       OR old_current_authority->>'jti'
            <> pg_catalog.btrim(old_current_authority->>'jti')
       OR old_current_authority->>'jti' ~ '[[:cntrl:],]'
       OR old_current_authority->>'markerSha256' !~ '^[0-9a-f]{64}$'
       OR old_current_authority->>'state' NOT IN (
            'UNVERIFIED','DENIED','TRANSIENT_UNAVAILABLE','VERIFIED_ALLOWED')
       OR old_current_authority->>'exp'
            !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$'
       OR old_current_authority->>'boundAt'
            !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$'
       OR (old_current_authority->>'exp')::TIMESTAMPTZ
            <= (old_current_authority->>'boundAt')::TIMESTAMPTZ
       OR (old_current_authority->>'state' = 'VERIFIED_ALLOWED'
           AND old_current_authority->>'authDecisionRevision'
                IS DISTINCT FROM frozen->>'postReceiptDecisionRevision')
       OR (old_current_authority->>'state' <> 'VERIFIED_ALLOWED'
           AND pg_catalog.jsonb_typeof(
                   old_current_authority->'authDecisionRevision') <> 'null') THEN
        RAISE EXCEPTION 'HRM-012 prior marker authority evidence is invalid'
            USING ERRCODE = '23514';
    END IF;
    IF old_evidence_total = 2 THEN
        old_previous_authority := old_evidence->1;
        IF pg_catalog.jsonb_typeof(old_previous_authority) <> 'object' THEN
            RAISE EXCEPTION 'HRM-012 prior marker authority evidence is invalid'
                USING ERRCODE = '23514';
        END IF;
        SELECT count(*) INTO previous_key_total
          FROM pg_catalog.jsonb_object_keys(old_previous_authority);
        IF previous_key_total <> 4
           OR NOT (old_previous_authority ?& ARRAY[
               'jtiSha256','exp','state','replacedAt']::TEXT[])
           OR EXISTS (
               SELECT 1
                 FROM pg_catalog.unnest(ARRAY[
                     'jtiSha256','exp','state','replacedAt']::TEXT[])
                      AS required_string(key)
                WHERE pg_catalog.jsonb_typeof(
                        old_previous_authority->required_string.key) <> 'string')
           OR old_previous_authority->>'jtiSha256' !~ '^[0-9a-f]{64}$'
           OR old_previous_authority->>'state' NOT IN (
                'UNVERIFIED','DENIED','TRANSIENT_UNAVAILABLE')
           OR old_previous_authority->>'exp'
                !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$'
           OR old_previous_authority->>'replacedAt'
                !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$' THEN
            RAISE EXCEPTION 'HRM-012 prior marker authority evidence is invalid'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF authority = old_authority THEN
        RETURN NEW;
    END IF;

    IF current_authority->>'jti' = old_current_authority->>'jti' THEN
        IF old_current_authority->>'state' = 'VERIFIED_ALLOWED'
           OR current_authority->>'state' = 'UNVERIFIED'
           OR evidence_total <> old_evidence_total
           OR current_authority->>'exp'
                IS DISTINCT FROM old_current_authority->>'exp'
           OR current_authority->>'markerSha256'
                IS DISTINCT FROM old_current_authority->>'markerSha256'
           OR current_authority->>'boundAt'
                IS DISTINCT FROM old_current_authority->>'boundAt'
           OR evidence->1 IS DISTINCT FROM old_evidence->1 THEN
            RAISE EXCEPTION 'HRM-012 same-jti marker identity is immutable'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    SELECT count(*) INTO run_total
      FROM public.ppl_workforce_ingestion_runs run
     WHERE run.tenant_id = NEW.tenant_id
       AND run.command_receipt_id = NEW.command_receipt_id;
    IF old_current_authority->>'state' = 'VERIFIED_ALLOWED'
       OR old_current_authority->>'state' NOT IN (
            'UNVERIFIED','DENIED','TRANSIENT_UNAVAILABLE')
       OR evidence_total <> 2
       OR current_authority->>'state' <> 'UNVERIFIED'
       OR (current_authority->>'exp')::TIMESTAMPTZ
            <= pg_catalog.clock_timestamp()
       OR current_authority->>'markerSha256'
            = old_current_authority->>'markerSha256'
       OR previous_authority->>'jtiSha256'
            <> pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
                   old_current_authority->>'jti', 'UTF8')), 'hex')
       OR previous_authority->>'exp'
            IS DISTINCT FROM old_current_authority->>'exp'
       OR previous_authority->>'state'
            IS DISTINCT FROM old_current_authority->>'state'
       OR previous_authority->>'replacedAt'
            IS DISTINCT FROM current_authority->>'boundAt'
       OR (previous_authority->>'replacedAt')::TIMESTAMPTZ
            < (old_current_authority->>'boundAt')::TIMESTAMPTZ
       OR run_total <> 0 THEN
        RAISE EXCEPTION 'HRM-012 fresh marker replacement is not safe'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_hrm012_receipt_authority() FROM PUBLIC;

CREATE TRIGGER trg_ppl_command_receipts_hrm012_authority
    BEFORE INSERT OR UPDATE ON ppl_command_receipts
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_hrm012_receipt_authority();

CREATE FUNCTION public.ppl_guard_workforce_ingestion_item()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    run_mode VARCHAR(16);
    run_state VARCHAR(24);
    run_connector UUID;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'workforce ingestion item evidence is immutable'
            USING ERRCODE = '23514';
    END IF;

    SELECT mode, lifecycle_state, connector_instance_id
      INTO run_mode, run_state, run_connector
      FROM public.ppl_workforce_ingestion_runs
     WHERE tenant_id = NEW.tenant_id
       AND workforce_ingestion_run_id = NEW.workforce_ingestion_run_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'workforce ingestion item requires same-tenant run'
            USING ERRCODE = '23503';
    END IF;
    IF run_state <> 'RUNNING' THEN
        RAISE EXCEPTION 'workforce ingestion items require a RUNNING run'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.connector_instance_id <> run_connector THEN
        RAISE EXCEPTION 'workforce ingestion item connector must match run snapshot'
            USING ERRCODE = '23514';
    END IF;
    IF run_mode = 'VALIDATE_ONLY'
       AND NEW.outcome NOT IN ('VALIDATED','REJECTED','DUPLICATE') THEN
        RAISE EXCEPTION 'VALIDATE_ONLY item outcome is not allowed'
            USING ERRCODE = '23514';
    END IF;
    IF run_mode = 'APPLY' AND NEW.outcome = 'VALIDATED' THEN
        RAISE EXCEPTION 'APPLY item cannot use VALIDATED outcome'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_workforce_ingestion_item() FROM PUBLIC;

CREATE TRIGGER trg_ppl_workforce_ingestion_item_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ppl_workforce_ingestion_items
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_workforce_ingestion_item();

CREATE FUNCTION public.ppl_guard_workforce_ingestion_run()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    original_run public.ppl_workforce_ingestion_runs%ROWTYPE;
    receipt_command_type VARCHAR(100);
    receipt_originating_action VARCHAR(160);
    receipt_purpose_code VARCHAR(80);
    receipt_target_type VARCHAR(80);
    receipt_actor UUID;
    receipt_subject UUID;
    item_total BIGINT;
    validated_total BIGINT;
    rejected_total BIGINT;
    duplicate_total BIGINT;
    applied_total BIGINT;
    quarantined_total BIGINT;
    reconciled_event_total BIGINT;
    exact_event_total BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'workforce ingestion run evidence cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT receipt.command_type, receipt.originating_action,
               receipt.purpose_code, receipt.target_type,
               receipt.actor_public_id, receipt.subject_principal_public_id
          INTO receipt_command_type, receipt_originating_action,
               receipt_purpose_code, receipt_target_type,
               receipt_actor, receipt_subject
          FROM public.ppl_command_receipts receipt
         WHERE receipt.tenant_id = NEW.tenant_id
           AND receipt.command_receipt_id = NEW.command_receipt_id
         FOR SHARE;
        IF NOT FOUND
           OR receipt_command_type <> 'WORKFORCE_INGESTION'
           OR receipt_originating_action <> 'INITIATE_WORKFORCE_INGESTION'
           OR receipt_purpose_code <> 'HRIS_INTEGRATION'
           OR receipt_target_type <> 'WORKFORCE_INGESTION_OBJECT'
           OR receipt_actor IS DISTINCT FROM receipt_subject THEN
            RAISE EXCEPTION 'workforce ingestion run requires exact same-tenant HRM-012 receipt'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.lifecycle_state <> 'RECEIVED'
           OR NEW.version <> 0 OR NEW.attempt_count <> 0
           OR NEW.source_count <> 0 OR NEW.accepted_count <> 0
           OR NEW.rejected_count <> 0 OR NEW.duplicate_count <> 0
           OR NEW.quarantined_count <> 0 OR NEW.applied_count <> 0 THEN
            RAISE EXCEPTION 'workforce ingestion run must begin RECEIVED at zero'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.linked_replay_run_id IS NOT NULL THEN
            SELECT * INTO original_run
              FROM public.ppl_workforce_ingestion_runs
             WHERE tenant_id = NEW.tenant_id
               AND workforce_ingestion_run_id = NEW.linked_replay_run_id;
            IF NOT FOUND OR original_run.command_receipt_id
                    <> NEW.linked_replay_receipt_id THEN
                RAISE EXCEPTION 'linked replay lineage does not match original evidence'
                    USING ERRCODE = '23514';
            END IF;
            IF original_run.lifecycle_state NOT IN ('SUCCEEDED','REJECTED','DEAD')
               OR ROW(original_run.connector_instance_id,
                      original_run.connector_public_id,
                      original_run.connector_version,
                      original_run.cursor_type,
                      original_run.mapping_profile_id,
                      original_run.mapping_public_id,
                      original_run.mapping_version,
                      original_run.mapping_sha256,
                      original_run.legal_entity_public_ids,
                      original_run.population_scope_digest,
                      original_run.object_ref, original_run.object_digest,
                      original_run.schema_key, original_run.schema_version,
                      original_run.mode, original_run.effective_date)
                  IS DISTINCT FROM
                  ROW(NEW.connector_instance_id, NEW.connector_public_id,
                      NEW.connector_version, NEW.cursor_type,
                      NEW.mapping_profile_id,
                      NEW.mapping_public_id, NEW.mapping_version,
                      NEW.mapping_sha256, NEW.legal_entity_public_ids,
                      NEW.population_scope_digest,
                      NEW.object_ref, NEW.object_digest,
                      NEW.schema_key, NEW.schema_version,
                      NEW.mode, NEW.effective_date) THEN
                RAISE EXCEPTION 'linked replay must preserve immutable source lineage'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.lifecycle_state IN ('SUCCEEDED','REJECTED','DEAD') THEN
        RAISE EXCEPTION 'terminal workforce ingestion run is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.workforce_ingestion_run_id, NEW.run_public_id, NEW.tenant_id,
           NEW.command_receipt_id, NEW.linked_replay_receipt_id,
           NEW.linked_replay_run_id,
           NEW.connector_instance_id, NEW.connector_public_id,
           NEW.connector_version, NEW.cursor_type, NEW.mapping_profile_id,
           NEW.mapping_public_id, NEW.mapping_version, NEW.mapping_sha256,
           NEW.legal_entity_public_ids, NEW.population_scope_digest,
           NEW.object_ref, NEW.object_digest, NEW.schema_key,
           NEW.schema_version, NEW.mode, NEW.effective_date,
           NEW.cursor_before_digest, NEW.cursor_expected_version,
           NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.workforce_ingestion_run_id, OLD.run_public_id, OLD.tenant_id,
           OLD.command_receipt_id, OLD.linked_replay_receipt_id,
           OLD.linked_replay_run_id,
           OLD.connector_instance_id, OLD.connector_public_id,
           OLD.connector_version, OLD.cursor_type, OLD.mapping_profile_id,
           OLD.mapping_public_id, OLD.mapping_version, OLD.mapping_sha256,
           OLD.legal_entity_public_ids, OLD.population_scope_digest,
           OLD.object_ref, OLD.object_digest, OLD.schema_key,
           OLD.schema_version, OLD.mode, OLD.effective_date,
           OLD.cursor_before_digest, OLD.cursor_expected_version,
           OLD.created_at) THEN
        RAISE EXCEPTION 'workforce ingestion identity and source snapshot are immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (
        (OLD.lifecycle_state = 'RECEIVED'
         AND NEW.lifecycle_state IN ('RUNNING','REJECTED','DEAD'))
        OR (OLD.lifecycle_state = 'RUNNING'
            AND NEW.lifecycle_state IN (
                'FAILED_RETRYABLE','SUCCEEDED','REJECTED','DEAD'))
        OR (OLD.lifecycle_state = 'RUNNING'
            AND NEW.lifecycle_state = 'RUNNING'
            AND OLD.lease_until <= pg_catalog.clock_timestamp())
        OR (OLD.lifecycle_state = 'FAILED_RETRYABLE'
            AND NEW.lifecycle_state IN ('RUNNING','DEAD'))
    ) THEN
        RAISE EXCEPTION 'illegal workforce ingestion lifecycle transition'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'workforce ingestion version must advance exactly once'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state = 'RUNNING' THEN
        IF NEW.attempt_count <> OLD.attempt_count + 1 THEN
            RAISE EXCEPTION 'RUNNING transition must increment attempt exactly once'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.lease_owner IS NULL
           OR pg_catalog.btrim(NEW.lease_owner) = ''
           OR NEW.lease_until IS NULL
           OR NEW.lease_until <= pg_catalog.clock_timestamp() THEN
            RAISE EXCEPTION 'RUNNING transition requires a nonempty owner and future lease'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.lifecycle_state = 'RUNNING'
           AND NEW.lease_owner = OLD.lease_owner THEN
            RAISE EXCEPTION 'expired RUNNING lease takeover requires a new owner'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.attempt_count <> OLD.attempt_count THEN
        RAISE EXCEPTION 'attempt count changes only when entering RUNNING'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.lifecycle_state = 'SUCCEEDED' THEN
        SELECT count(*),
               count(*) FILTER (WHERE outcome = 'VALIDATED'),
               count(*) FILTER (WHERE outcome = 'REJECTED'),
               count(*) FILTER (WHERE outcome = 'DUPLICATE'),
               count(*) FILTER (WHERE outcome = 'APPLIED'),
               count(*) FILTER (
                   WHERE outcome = 'QUARANTINED_AFTER_ACCEPTANCE')
          INTO item_total, validated_total, rejected_total,
               duplicate_total, applied_total, quarantined_total
          FROM public.ppl_workforce_ingestion_items
         WHERE tenant_id = NEW.tenant_id
           AND workforce_ingestion_run_id = NEW.workforce_ingestion_run_id;

        IF NEW.source_count <> item_total
           OR NEW.rejected_count <> rejected_total
           OR NEW.duplicate_count <> duplicate_total
           OR NEW.applied_count <> applied_total
           OR NEW.quarantined_count <> quarantined_total THEN
            RAISE EXCEPTION 'run counts must equal immutable item evidence'
                USING ERRCODE = '23514';
        END IF;

        SELECT count(*),
               count(*) FILTER (
                   WHERE payload = pg_catalog.jsonb_build_object(
                       'runPublicId', NEW.run_public_id::TEXT,
                       'connectorPublicId', NEW.connector_public_id::TEXT,
                       'mappingVersion', NEW.mapping_version,
                       'sourceCount', NEW.source_count,
                       'acceptedCount', NEW.accepted_count,
                       'rejectedCount', NEW.rejected_count,
                       'duplicateCount', NEW.duplicate_count,
                       'quarantinedCount', NEW.quarantined_count,
                       'appliedCount', NEW.applied_count,
                       'resultDigest', NEW.result_digest))
          INTO reconciled_event_total, exact_event_total
          FROM public.sys_people_outbox_events
         WHERE tenant_id = NEW.tenant_id
           AND aggregate_type = 'WorkforceIngestionRun'
           AND aggregate_id = NEW.run_public_id::TEXT
           AND event_type = 'WorkforceIngestionReconciled.v1';

        IF NEW.mode = 'VALIDATE_ONLY' THEN
            IF applied_total <> 0 OR quarantined_total <> 0
               OR NEW.accepted_count <> 0 OR NEW.applied_count <> 0
               OR NEW.quarantined_count <> 0
               OR NEW.source_count
                    <> validated_total + rejected_total + duplicate_total THEN
                RAISE EXCEPTION 'VALIDATE_ONLY accounting is not conserved'
                    USING ERRCODE = '23514';
            END IF;
            IF reconciled_event_total <> 0 THEN
                RAISE EXCEPTION 'VALIDATE_ONLY cannot emit reconciled event'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            IF validated_total <> 0
               OR NEW.source_count
                    <> NEW.accepted_count
                       + NEW.rejected_count + NEW.duplicate_count
               OR NEW.accepted_count
                    <> NEW.applied_count + NEW.quarantined_count
               OR NEW.accepted_count <> applied_total + quarantined_total THEN
                RAISE EXCEPTION 'APPLY accounting is not conserved'
                    USING ERRCODE = '23514';
            END IF;
            IF reconciled_event_total <> 1 OR exact_event_total <> 1 THEN
                RAISE EXCEPTION 'APPLY success requires one exact reconciled event'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;

    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_workforce_ingestion_run() FROM PUBLIC;

CREATE TRIGGER trg_ppl_workforce_ingestion_run_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ppl_workforce_ingestion_runs
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_workforce_ingestion_run();

CREATE FUNCTION public.ppl_guard_workforce_ingestion_terminal_atomic()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    current_xid TEXT;
    receipt_actor UUID;
    receipt_correlation UUID;
    item_total BIGINT;
    validated_total BIGINT;
    rejected_total BIGINT;
    duplicate_total BIGINT;
    applied_total BIGINT;
    quarantined_total BIGINT;
    audit_total BIGINT;
    audit_transaction_total BIGINT;
    outbox_total BIGINT;
    outbox_transaction_total BIGINT;
    cursor_total BIGINT;
    cursor_transaction_total BIGINT;
BEGIN
    IF NEW.lifecycle_state <> 'SUCCEEDED' THEN
        RETURN NULL;
    END IF;

    current_xid := pg_catalog.pg_current_xact_id()::TEXT;

    SELECT receipt.actor_public_id, receipt.correlation_id
      INTO receipt_actor, receipt_correlation
      FROM public.ppl_command_receipts receipt
     WHERE receipt.tenant_id = NEW.tenant_id
       AND receipt.command_receipt_id = NEW.command_receipt_id
       AND receipt.lifecycle_state = 'SUCCEEDED'
       AND receipt.result_code = 'WORKFORCE_INGESTION_SUCCEEDED'
       AND receipt.completed_at IS NOT NULL
       AND receipt.result_reference @> pg_catalog.jsonb_build_object(
               'runPublicId', NEW.run_public_id::TEXT,
               'resultDigest', NEW.result_digest)
       AND receipt.xmin::TEXT = current_xid;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'terminal ingestion requires same-transaction successful receipt'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*),
           count(*) FILTER (WHERE audit.xmin::TEXT = current_xid)
      INTO audit_total, audit_transaction_total
      FROM public.sys_people_audit_events audit
     WHERE audit.tenant_id = NEW.tenant_id
       AND audit.actor_type = 'USER'
       AND audit.actor_id = receipt_actor::TEXT
       AND audit.action = 'workforce-ingestion.reconciled'
       AND audit.target_type = 'WORKFORCE_INGESTION_RUN'
       AND audit.target_id = NEW.run_public_id::TEXT
       AND audit.outcome = 'SUCCESS'
       AND audit.correlation_id = receipt_correlation::TEXT
       AND audit.after_snapshot = pg_catalog.jsonb_build_object(
               'mode', NEW.mode,
               'sourceCount', NEW.source_count,
               'resultDigest', NEW.result_digest)
       AND substring(audit.audit_event_id::TEXT FROM 15 FOR 1) = '5'
       AND substring(audit.audit_event_id::TEXT FROM 20 FOR 1) ~ '^[89ab]$';
    IF audit_total <> 1 OR audit_transaction_total <> 1 THEN
        RAISE EXCEPTION 'terminal ingestion requires one same-transaction exact audit event'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*),
           count(*) FILTER (WHERE item.outcome = 'VALIDATED'),
           count(*) FILTER (WHERE item.outcome = 'REJECTED'),
           count(*) FILTER (WHERE item.outcome = 'DUPLICATE'),
           count(*) FILTER (WHERE item.outcome = 'APPLIED'),
           count(*) FILTER (
               WHERE item.outcome = 'QUARANTINED_AFTER_ACCEPTANCE')
      INTO item_total, validated_total, rejected_total,
           duplicate_total, applied_total, quarantined_total
      FROM public.ppl_workforce_ingestion_items item
     WHERE item.tenant_id = NEW.tenant_id
       AND item.workforce_ingestion_run_id = NEW.workforce_ingestion_run_id;

    IF NEW.source_count <> item_total
       OR NEW.rejected_count <> rejected_total
       OR NEW.duplicate_count <> duplicate_total
       OR NEW.applied_count <> applied_total
       OR NEW.quarantined_count <> quarantined_total THEN
        RAISE EXCEPTION 'terminal run counts changed after reconciliation'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*),
           count(*) FILTER (WHERE event.xmin::TEXT = current_xid)
      INTO outbox_total, outbox_transaction_total
      FROM public.sys_people_outbox_events event
     WHERE event.tenant_id = NEW.tenant_id
       AND event.aggregate_type = 'WorkforceIngestionRun'
       AND event.aggregate_id = NEW.run_public_id::TEXT
       AND event.event_type = 'WorkforceIngestionReconciled.v1'
       AND event.payload = pg_catalog.jsonb_build_object(
               'runPublicId', NEW.run_public_id::TEXT,
               'connectorPublicId', NEW.connector_public_id::TEXT,
               'mappingVersion', NEW.mapping_version,
               'sourceCount', NEW.source_count,
               'acceptedCount', NEW.accepted_count,
               'rejectedCount', NEW.rejected_count,
               'duplicateCount', NEW.duplicate_count,
               'quarantinedCount', NEW.quarantined_count,
               'appliedCount', NEW.applied_count,
               'resultDigest', NEW.result_digest)
       AND event.correlation_id = receipt_correlation::TEXT
       AND substring(event.event_id::TEXT FROM 15 FOR 1) = '5'
       AND substring(event.event_id::TEXT FROM 20 FOR 1) ~ '^[89ab]$';

    IF NEW.mode = 'VALIDATE_ONLY' THEN
        IF NEW.accepted_count <> 0 OR NEW.applied_count <> 0
           OR NEW.quarantined_count <> 0 OR applied_total <> 0
           OR quarantined_total <> 0
           OR NEW.source_count
                <> validated_total + rejected_total + duplicate_total THEN
            RAISE EXCEPTION 'deferred VALIDATE_ONLY accounting is not conserved'
                USING ERRCODE = '23514';
        END IF;
        IF outbox_total <> 0 THEN
            RAISE EXCEPTION 'VALIDATE_ONLY cannot retain reconciled event'
                USING ERRCODE = '23514';
        END IF;
        SELECT count(*)
          INTO cursor_transaction_total
          FROM public.int_connector_cursors cursor_row
         WHERE cursor_row.tenant_id = NEW.tenant_id
           AND cursor_row.connector_instance_id = NEW.connector_instance_id
           AND cursor_row.cursor_type = NEW.cursor_type
           AND cursor_row.xmin::TEXT = current_xid;
        IF cursor_transaction_total <> 0 THEN
            RAISE EXCEPTION 'VALIDATE_ONLY cannot advance connector cursor'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF validated_total <> 0
           OR NEW.source_count
                <> NEW.accepted_count + NEW.rejected_count + NEW.duplicate_count
           OR NEW.accepted_count
                <> NEW.applied_count + NEW.quarantined_count
           OR NEW.accepted_count <> applied_total + quarantined_total THEN
            RAISE EXCEPTION 'deferred APPLY accounting is not conserved'
                USING ERRCODE = '23514';
        END IF;
        IF outbox_total <> 1 OR outbox_transaction_total <> 1 THEN
            RAISE EXCEPTION 'APPLY success requires one same-transaction exact event'
                USING ERRCODE = '23514';
        END IF;

        SELECT count(*),
               count(*) FILTER (WHERE cursor_row.xmin::TEXT = current_xid)
          INTO cursor_total, cursor_transaction_total
          FROM public.int_connector_cursors cursor_row
         WHERE cursor_row.tenant_id = NEW.tenant_id
           AND cursor_row.connector_instance_id = NEW.connector_instance_id
           AND cursor_row.cursor_type = NEW.cursor_type
           AND cursor_row.version = NEW.cursor_committed_version
           AND cursor_row.version = NEW.cursor_expected_version + 1
           AND cursor_row.committed_cursor IS NOT NULL
           AND cursor_row.committed_at IS NOT NULL
           AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
                   cursor_row.committed_cursor, 'UTF8')), 'hex')
               = NEW.cursor_after_digest;
        IF cursor_total <> 1 OR cursor_transaction_total <> 1 THEN
            RAISE EXCEPTION 'APPLY success requires exact same-transaction cursor CAS'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_workforce_ingestion_terminal_atomic() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER trg_ppl_workforce_ingestion_terminal_atomic
    AFTER UPDATE ON ppl_workforce_ingestion_runs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.lifecycle_state = 'SUCCEEDED')
    EXECUTE FUNCTION public.ppl_guard_workforce_ingestion_terminal_atomic();

CREATE FUNCTION public.ppl_guard_hrm012_reconciled_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    run_state VARCHAR(24);
    run_mode VARCHAR(16);
BEGIN
    IF TG_OP = 'DELETE'
       AND OLD.aggregate_type = 'WorkforceIngestionRun'
       AND OLD.event_type = 'WorkforceIngestionReconciled.v1' THEN
        RAISE EXCEPTION 'workforce ingestion reconciled event is immutable'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE'
       AND (ROW(OLD.aggregate_type, OLD.event_type)
            = ROW('WorkforceIngestionRun', 'WorkforceIngestionReconciled.v1')
            OR ROW(NEW.aggregate_type, NEW.event_type)
            = ROW('WorkforceIngestionRun', 'WorkforceIngestionReconciled.v1')) THEN
        IF ROW(NEW.event_id, NEW.tenant_id, NEW.aggregate_type,
               NEW.aggregate_id, NEW.event_type, NEW.payload,
               NEW.correlation_id, NEW.occurred_at)
           IS DISTINCT FROM
           ROW(OLD.event_id, OLD.tenant_id, OLD.aggregate_type,
               OLD.aggregate_id, OLD.event_type, OLD.payload,
               OLD.correlation_id, OLD.occurred_at) THEN
            RAISE EXCEPTION 'workforce ingestion reconciled event identity is immutable'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT'
       AND NEW.aggregate_type = 'WorkforceIngestionRun'
       AND NEW.event_type = 'WorkforceIngestionReconciled.v1' THEN
        IF NEW.aggregate_id !~
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR substring(NEW.event_id::TEXT FROM 15 FOR 1) <> '5'
           OR substring(NEW.event_id::TEXT FROM 20 FOR 1) !~ '^[89ab]$' THEN
            RAISE EXCEPTION 'workforce ingestion event identity is not canonical'
                USING ERRCODE = '23514';
        END IF;
        SELECT lifecycle_state, mode
          INTO run_state, run_mode
          FROM public.ppl_workforce_ingestion_runs
         WHERE tenant_id = NEW.tenant_id
           AND run_public_id = NEW.aggregate_id::UUID
         FOR SHARE;
        IF NOT FOUND OR run_state <> 'RUNNING' OR run_mode <> 'APPLY' THEN
            RAISE EXCEPTION 'reconciled event requires a RUNNING APPLY run'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_hrm012_reconciled_outbox() FROM PUBLIC;

CREATE TRIGGER trg_sys_people_outbox_hrm012_reconciled_guard
    BEFORE INSERT OR UPDATE OR DELETE ON sys_people_outbox_events
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_hrm012_reconciled_outbox();

CREATE FUNCTION public.ppl_guard_hrm012_shared_atomic()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    current_xid TEXT;
    related_run_total BIGINT;
    exact_terminal_total BIGINT;
BEGIN
    current_xid := pg_catalog.pg_current_xact_id()::TEXT;

    IF TG_TABLE_NAME = 'int_connector_cursors' THEN
        SELECT count(*),
               count(*) FILTER (
                   WHERE run.lifecycle_state = 'SUCCEEDED'
                     AND run.cursor_committed_version = NEW.version
                     AND run.cursor_after_digest = pg_catalog.encode(
                         pg_catalog.sha256(pg_catalog.convert_to(
                             NEW.committed_cursor, 'UTF8')), 'hex'))
          INTO related_run_total, exact_terminal_total
          FROM public.ppl_workforce_ingestion_runs run
         WHERE run.tenant_id = NEW.tenant_id
           AND run.connector_instance_id = NEW.connector_instance_id
           AND run.cursor_type = NEW.cursor_type
           AND run.cursor_expected_version + 1 = NEW.version
           AND run.lifecycle_state = 'SUCCEEDED'
           AND run.xmin::TEXT = current_xid;
        IF related_run_total > 0
           AND (related_run_total <> 1 OR exact_terminal_total <> 1) THEN
            RAISE EXCEPTION 'HRM-012 cursor mutation requires same-transaction successful terminal run'
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF TG_TABLE_NAME = 'ppl_command_receipts' THEN
        SELECT count(*),
               count(*) FILTER (
                   WHERE run.lifecycle_state = 'SUCCEEDED'
                     AND run.result_digest = NEW.result_reference->>'resultDigest'
                     AND run.run_public_id::TEXT
                         = NEW.result_reference->>'runPublicId'
                     AND run.xmin::TEXT = current_xid)
          INTO related_run_total, exact_terminal_total
          FROM public.ppl_workforce_ingestion_runs run
         WHERE run.tenant_id = NEW.tenant_id
           AND run.command_receipt_id = NEW.command_receipt_id;
        IF related_run_total <> 1 OR exact_terminal_total <> 1 THEN
            RAISE EXCEPTION 'HRM-012 successful receipt requires same-transaction terminal run'
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF TG_TABLE_NAME = 'sys_people_audit_events' THEN
        SELECT count(*),
               count(*) FILTER (
                   WHERE run.lifecycle_state = 'SUCCEEDED'
                     AND run.result_digest = NEW.after_snapshot->>'resultDigest'
                     AND run.xmin::TEXT = current_xid)
          INTO related_run_total, exact_terminal_total
          FROM public.ppl_workforce_ingestion_runs run
         WHERE run.tenant_id = NEW.tenant_id
           AND run.run_public_id::TEXT = NEW.target_id;
        IF related_run_total <> 1 OR exact_terminal_total <> 1 THEN
            RAISE EXCEPTION 'HRM-012 audit event requires same-transaction terminal run'
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF TG_TABLE_NAME = 'sys_people_outbox_events' THEN
        SELECT count(*),
               count(*) FILTER (
                   WHERE run.lifecycle_state = 'SUCCEEDED'
                     AND run.result_digest = NEW.payload->>'resultDigest'
                     AND run.xmin::TEXT = current_xid)
          INTO related_run_total, exact_terminal_total
          FROM public.ppl_workforce_ingestion_runs run
         WHERE run.tenant_id = NEW.tenant_id
           AND run.run_public_id::TEXT = NEW.aggregate_id;
        IF related_run_total <> 1 OR exact_terminal_total <> 1 THEN
            RAISE EXCEPTION 'HRM-012 reconciled event requires same-transaction terminal run'
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    RAISE EXCEPTION 'unsupported HRM-012 shared atomic trigger target'
        USING ERRCODE = '23514';
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_hrm012_shared_atomic() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER trg_int_connector_cursors_hrm012_atomic
    AFTER INSERT OR UPDATE ON int_connector_cursors
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.ppl_guard_hrm012_shared_atomic();

CREATE CONSTRAINT TRIGGER trg_ppl_command_receipts_hrm012_atomic
    AFTER INSERT OR UPDATE ON ppl_command_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.command_type = 'WORKFORCE_INGESTION'
          AND NEW.lifecycle_state = 'SUCCEEDED')
    EXECUTE FUNCTION public.ppl_guard_hrm012_shared_atomic();

CREATE CONSTRAINT TRIGGER trg_sys_people_audit_events_hrm012_atomic
    AFTER INSERT ON sys_people_audit_events
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.action = 'workforce-ingestion.reconciled'
          AND NEW.target_type = 'WORKFORCE_INGESTION_RUN')
    EXECUTE FUNCTION public.ppl_guard_hrm012_shared_atomic();

CREATE CONSTRAINT TRIGGER trg_sys_people_outbox_hrm012_atomic
    AFTER INSERT ON sys_people_outbox_events
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.aggregate_type = 'WorkforceIngestionRun'
          AND NEW.event_type = 'WorkforceIngestionReconciled.v1')
    EXECUTE FUNCTION public.ppl_guard_hrm012_shared_atomic();

ALTER TABLE ppl_workforce_ingestion_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_workforce_ingestion_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_workforce_ingestion_runs_tenant_policy
    ON ppl_workforce_ingestion_runs
    USING (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_workforce_ingestion_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_workforce_ingestion_items FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_workforce_ingestion_items_tenant_policy
    ON ppl_workforce_ingestion_items
    USING (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

COMMENT ON TABLE ppl_workforce_ingestion_runs IS
    'Immutable tenant-scoped HRM-012 receipt, source snapshot and reconciliation ledger.';
COMMENT ON TABLE ppl_workforce_ingestion_items IS
    'Immutable redacted HRM-012 validation, duplicate, apply and quarantine evidence.';
