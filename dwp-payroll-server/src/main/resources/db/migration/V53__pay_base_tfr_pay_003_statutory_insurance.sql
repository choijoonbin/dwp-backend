-- BASE-TFR-PAY-003. Signed country-pack snapshots, activation history and
-- typed social-insurance outcomes. The five tables below are the exhaustive
-- PAY-003 V53 lease. No rate, formula, trusted key or customer pack is seeded.

CREATE TABLE pay_country_pack_version_snapshots (
    country_pack_snapshot_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    pack_id UUID NOT NULL,
    pack_key VARCHAR(128) NOT NULL,
    pack_version VARCHAR(64) NOT NULL,
    snapshot_revision BIGINT NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    jurisdiction_code VARCHAR(32) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    artifact_digest CHAR(64) NOT NULL,
    signature_digest CHAR(64) NOT NULL,
    signature_algorithm VARCHAR(32) NOT NULL,
    signing_key_id VARCHAR(160) NOT NULL,
    compatibility_version VARCHAR(64) NOT NULL,
    policy_schema_version VARCHAR(64) NOT NULL,
    official_reference_set VARCHAR(240) NOT NULL,
    golden_pack_digest CHAR(64) NOT NULL,
    statutory_approval_receipt_id UUID NOT NULL,
    installer_subject_public_id UUID NOT NULL,
    statutory_approver_subject_public_id UUID NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_country_pack_snapshot_public_unique
        UNIQUE (tenant_id, pack_id),
    CONSTRAINT pay_country_pack_snapshot_revision_unique
        UNIQUE (tenant_id, pack_id, snapshot_revision),
    CONSTRAINT pay_country_pack_snapshot_scope_unique
        UNIQUE (tenant_id, pack_id, legal_entity_id, payroll_group_id),
    CONSTRAINT pay_country_pack_snapshot_version_scope_unique UNIQUE (
        tenant_id, legal_entity_id, payroll_group_id,
        jurisdiction_code, pack_key, pack_version),
    CONSTRAINT pay_country_pack_snapshot_positive CHECK (
        tenant_id > 0 AND snapshot_revision >= 0),
    CONSTRAINT pay_country_pack_snapshot_identity_shape CHECK (
        pack_key ~ '^[A-Z0-9][A-Z0-9._:-]{0,127}$'
        AND pack_version ~ '^[A-Za-z0-9][A-Za-z0-9._:+-]{0,63}$'
        AND jurisdiction_code ~ '^[A-Z]{2}(-[A-Z0-9]{1,8})?$'),
    CONSTRAINT pay_country_pack_snapshot_lifecycle_valid CHECK (
        lifecycle_state = 'AVAILABLE'),
    CONSTRAINT pay_country_pack_snapshot_digest_shape CHECK (
        payload_digest ~ '^[0-9a-f]{64}$'
        AND artifact_digest ~ '^[0-9a-f]{64}$'
        AND signature_digest ~ '^[0-9a-f]{64}$'
        AND golden_pack_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_country_pack_snapshot_signature_shape CHECK (
        signature_algorithm IN (
            'ED25519', 'ECDSA_P256_SHA256', 'RSA_PSS_SHA256')
        AND signing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$'),
    CONSTRAINT pay_country_pack_snapshot_version_shape CHECK (
        compatibility_version ~ '^[A-Za-z0-9][A-Za-z0-9._:+-]{0,63}$'
        AND policy_schema_version ~ '^[A-Za-z0-9][A-Za-z0-9._:+-]{0,63}$'
        AND btrim(official_reference_set) <> ''),
    CONSTRAINT pay_country_pack_snapshot_sod CHECK (
        installer_subject_public_id <> statutory_approver_subject_public_id),
    CONSTRAINT pay_country_pack_snapshot_period CHECK (
        effective_to IS NULL),
    CONSTRAINT pay_country_pack_snapshot_recorded CHECK (
        recorded_at IS NOT NULL)
);

CREATE TABLE pay_country_pack_activation_versions (
    country_pack_activation_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    pack_id UUID NOT NULL,
    previous_revision BIGINT NOT NULL,
    activation_revision BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    activated_by_subject_public_id UUID NOT NULL,
    receipt_id UUID,
    audit_event_id UUID,
    correlation_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_country_pack_activation_snapshot_fk FOREIGN KEY (
        tenant_id, pack_id)
        REFERENCES pay_country_pack_version_snapshots (tenant_id, pack_id),
    CONSTRAINT pay_country_pack_activation_revision_unique
        UNIQUE (tenant_id, pack_id, activation_revision),
    CONSTRAINT pay_country_pack_activation_receipt_unique
        UNIQUE (tenant_id, receipt_id),
    CONSTRAINT pay_country_pack_activation_event_unique
        UNIQUE (tenant_id, audit_event_id),
    CONSTRAINT pay_country_pack_activation_positive CHECK (
        tenant_id > 0 AND previous_revision >= 0
        AND activation_revision = previous_revision + 1),
    CONSTRAINT pay_country_pack_activation_lifecycle_valid CHECK (
        lifecycle_state IN (
            'INSTALLED', 'VALIDATED', 'APPROVED',
            'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT pay_country_pack_activation_command_shape CHECK (
        (lifecycle_state = 'ACTIVE' AND receipt_id IS NOT NULL
            AND audit_event_id IS NOT NULL AND correlation_id IS NOT NULL)
        OR (lifecycle_state <> 'ACTIVE' AND receipt_id IS NULL
            AND audit_event_id IS NULL AND correlation_id IS NULL)),
    CONSTRAINT pay_country_pack_activation_period CHECK (
        effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX pay_country_pack_activation_head_idx
    ON pay_country_pack_activation_versions (
        tenant_id, pack_id, activation_revision DESC);

CREATE TABLE pay_country_pack_command_receipts (
    country_pack_receipt_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    originating_action VARCHAR(48) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    pack_id UUID NOT NULL,
    expected_revision BIGINT NOT NULL,
    activation_revision BIGINT NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    purpose_code VARCHAR(64) NOT NULL,
    field_policy_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    receipt_status VARCHAR(24) NOT NULL,
    correlation_id UUID NOT NULL,
    result_ref VARCHAR(240) NOT NULL,
    step_up_challenge_id VARCHAR(160) NOT NULL,
    step_up_nonce VARCHAR(160) NOT NULL,
    step_up_issuer VARCHAR(300) NOT NULL,
    step_up_expires_at TIMESTAMPTZ NOT NULL,
    step_up_payload_sha256 CHAR(64) NOT NULL,
    step_up_decision_revision VARCHAR(200) NOT NULL,
    audit_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_country_pack_receipt_public_unique
        UNIQUE (tenant_id, receipt_id),
    CONSTRAINT pay_country_pack_receipt_idempotency_unique UNIQUE (
        tenant_id, subject_principal_public_id,
        originating_action, idempotency_key),
    CONSTRAINT pay_country_pack_receipt_activation_fk FOREIGN KEY (
        tenant_id, pack_id, activation_revision)
        REFERENCES pay_country_pack_activation_versions (
            tenant_id, pack_id, activation_revision)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT pay_country_pack_receipt_scope_fk FOREIGN KEY (
        tenant_id, pack_id, legal_entity_id, payroll_group_id)
        REFERENCES pay_country_pack_version_snapshots (
            tenant_id, pack_id, legal_entity_id, payroll_group_id),
    CONSTRAINT pay_country_pack_receipt_positive CHECK (
        tenant_id > 0 AND expected_revision >= 0
        AND activation_revision = expected_revision + 1
        AND field_policy_revision > 0 AND authorization_revision > 0),
    CONSTRAINT pay_country_pack_receipt_contract CHECK (
        originating_action = 'PAY_COUNTRY_PACK_ACTIVATE'
        AND purpose_code = 'STATUTORY_CONFIGURATION'
        AND receipt_status IN ('ACCEPTED', 'SUCCEEDED')),
    CONSTRAINT pay_country_pack_receipt_idempotency_shape CHECK (
        char_length(idempotency_key) BETWEEN 16 AND 128
        AND idempotency_key = btrim(idempotency_key)),
    CONSTRAINT pay_country_pack_receipt_digest_shape CHECK (
        request_digest ~ '^[0-9a-f]{64}$'
        AND population_scope_digest ~ '^[0-9a-f]{64}$'
        AND step_up_payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_country_pack_receipt_step_up_shape CHECK (
        btrim(step_up_challenge_id) <> ''
        AND btrim(step_up_nonce) <> ''
        AND btrim(step_up_issuer) <> ''
        AND btrim(step_up_decision_revision) <> ''
        AND created_at < step_up_expires_at),
    CONSTRAINT pay_country_pack_receipt_result_shape CHECK (
        result_ref = 'country-pack:' || pack_id::TEXT
        AND jsonb_typeof(audit_payload) = 'object')
);

CREATE TABLE pay_country_pack_audit_outbox_events (
    country_pack_outbox_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    receipt_id UUID NOT NULL,
    pack_id UUID NOT NULL,
    activation_revision BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    event_payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_country_pack_outbox_public_unique
        UNIQUE (tenant_id, event_id),
    CONSTRAINT pay_country_pack_outbox_effect_unique
        UNIQUE (tenant_id, pack_id, activation_revision, event_type),
    CONSTRAINT pay_country_pack_outbox_sequence_unique
        UNIQUE (tenant_id, pack_id, aggregate_sequence),
    CONSTRAINT pay_country_pack_outbox_receipt_fk FOREIGN KEY (
        tenant_id, receipt_id)
        REFERENCES pay_country_pack_command_receipts (tenant_id, receipt_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT pay_country_pack_outbox_activation_fk FOREIGN KEY (
        tenant_id, pack_id, activation_revision)
        REFERENCES pay_country_pack_activation_versions (
            tenant_id, pack_id, activation_revision)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT pay_country_pack_outbox_positive CHECK (
        tenant_id > 0 AND activation_revision > 0
        AND aggregate_sequence = activation_revision),
    CONSTRAINT pay_country_pack_outbox_contract CHECK (
        event_type = 'CountryPackActivated'
        AND schema_version = 1
        AND jsonb_typeof(event_payload) = 'object'
        AND event_payload ?& ARRAY[
            'specVersion', 'id', 'source', 'type', 'schemaVersion', 'time',
            'tenantId', 'aggregateType', 'aggregateId', 'aggregateSequence',
            'correlationId', 'data'])
);

ALTER TABLE pay_country_pack_activation_versions
    ADD CONSTRAINT pay_country_pack_activation_receipt_fk FOREIGN KEY (
        tenant_id, receipt_id)
        REFERENCES pay_country_pack_command_receipts (tenant_id, receipt_id)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT pay_country_pack_activation_outbox_fk FOREIGN KEY (
        tenant_id, audit_event_id)
        REFERENCES pay_country_pack_audit_outbox_events (tenant_id, event_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE pay_social_insurance_result_facts (
    social_insurance_fact_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    social_insurance_fact_id UUID NOT NULL,
    run_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    worker_result_id UUID NOT NULL,
    pack_id UUID NOT NULL,
    activation_revision BIGINT NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    provider_result_type VARCHAR(32) NOT NULL,
    provider_source_semantics VARCHAR(48) NOT NULL,
    provider_outcome_digest CHAR(64) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    source_result_line_ids UUID[] NOT NULL,
    source_line_set_digest CHAR(64) NOT NULL,
    result_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_social_insurance_fact_public_unique
        UNIQUE (tenant_id, social_insurance_fact_id),
    CONSTRAINT pay_social_insurance_fact_worker_unique
        UNIQUE (tenant_id, worker_result_id),
    CONSTRAINT pay_social_insurance_fact_worker_fk FOREIGN KEY (
        tenant_id, run_id, attempt_no, worker_result_id)
        REFERENCES pay_worker_results (
            tenant_id, run_id, attempt_no, worker_result_id),
    CONSTRAINT pay_social_insurance_fact_activation_fk FOREIGN KEY (
        tenant_id, pack_id, activation_revision)
        REFERENCES pay_country_pack_activation_versions (
            tenant_id, pack_id, activation_revision),
    CONSTRAINT pay_social_insurance_fact_positive CHECK (
        tenant_id > 0 AND attempt_no > 0
        AND activation_revision > 0 AND amount >= 0),
    CONSTRAINT pay_social_insurance_fact_money_shape CHECK (
        currency ~ '^[A-Z]{3}$'),
    CONSTRAINT pay_social_insurance_fact_provider_shape CHECK (
        provider_result_type = 'SOCIAL_INSURANCE'
        AND provider_source_semantics = 'TYPED_COUNTRY_RULE_OUTPUT'
        AND policy_version ~ '^[A-Za-z0-9][A-Za-z0-9._:+-]{0,63}$'),
    CONSTRAINT pay_social_insurance_fact_digest_shape CHECK (
        provider_outcome_digest ~ '^[0-9a-f]{64}$'
        AND source_line_set_digest ~ '^[0-9a-f]{64}$'
        AND result_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_social_insurance_fact_source_shape CHECK (
        cardinality(source_result_line_ids) > 0
        AND array_position(source_result_line_ids, NULL) IS NULL)
);

CREATE FUNCTION pay_v53_reject_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v53_immutable$
BEGIN
    RAISE EXCEPTION 'PAY-003 V53 facts are append-only';
END
$pay_v53_immutable$;

CREATE FUNCTION pay_v53_guard_activation_version()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v53_activation$
DECLARE
    snapshot pay_country_pack_version_snapshots%ROWTYPE;
    prior pay_country_pack_activation_versions%ROWTYPE;
    conflicting_pack UUID;
BEGIN
    IF session_user = '${payrollRuntimeRole}'
       AND NEW.lifecycle_state <> 'ACTIVE' THEN
        RAISE EXCEPTION 'Payroll runtime cannot publish pack lifecycle authority';
    END IF;
    IF session_user = '${payrollProjectionPublisherRole}'
       AND NEW.lifecycle_state = 'ACTIVE' THEN
        RAISE EXCEPTION 'Projection publisher cannot activate a country pack';
    END IF;
    SELECT * INTO snapshot
      FROM pay_country_pack_version_snapshots source
     WHERE source.tenant_id = NEW.tenant_id
       AND source.pack_id = NEW.pack_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Country-pack snapshot is unavailable';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.tenant_id::TEXT || ':' || snapshot.legal_entity_id::TEXT || ':'
        || snapshot.payroll_group_id::TEXT || ':'
        || snapshot.jurisdiction_code || ':' || snapshot.pack_key, 0));

    SELECT * INTO prior
      FROM pay_country_pack_activation_versions history
     WHERE history.tenant_id = NEW.tenant_id
       AND history.pack_id = NEW.pack_id
     ORDER BY history.activation_revision DESC
     LIMIT 1
     FOR UPDATE;

    IF NOT FOUND THEN
        IF NEW.previous_revision <> snapshot.snapshot_revision
           OR NEW.activation_revision <> snapshot.snapshot_revision + 1
           OR snapshot.lifecycle_state <> 'AVAILABLE'
           OR NEW.lifecycle_state <> 'INSTALLED'
           OR NEW.occurred_at < snapshot.recorded_at THEN
            RAISE EXCEPTION 'Illegal initial country-pack lifecycle transition';
        END IF;
    ELSE
        IF NEW.previous_revision <> prior.activation_revision
           OR NEW.activation_revision <> prior.activation_revision + 1
           OR NEW.occurred_at <= prior.occurred_at
           OR NOT ((prior.lifecycle_state = 'INSTALLED'
                    AND NEW.lifecycle_state = 'VALIDATED')
                OR (prior.lifecycle_state = 'VALIDATED'
                    AND NEW.lifecycle_state = 'APPROVED')
                OR (prior.lifecycle_state = 'APPROVED'
                    AND NEW.lifecycle_state = 'ACTIVE')
                OR (prior.lifecycle_state = 'ACTIVE'
                    AND NEW.lifecycle_state IN ('SUSPENDED', 'RETIRED'))
                OR (prior.lifecycle_state = 'SUSPENDED'
                    AND NEW.lifecycle_state IN ('ACTIVE', 'RETIRED'))) THEN
            RAISE EXCEPTION 'Illegal country-pack lifecycle transition';
        END IF;
        IF NEW.effective_from IS DISTINCT FROM prior.effective_from
           OR (NEW.lifecycle_state <> 'RETIRED'
               AND NEW.effective_to IS DISTINCT FROM prior.effective_to)
           OR (NEW.lifecycle_state = 'RETIRED'
               AND (prior.effective_to IS NOT NULL
                    OR NEW.effective_to IS NULL
                    OR NEW.effective_to <= prior.effective_from)) THEN
            RAISE EXCEPTION 'Country-pack effective period changed illegally';
        END IF;
    END IF;

    IF NEW.effective_from IS DISTINCT FROM snapshot.effective_from
       OR (NEW.lifecycle_state <> 'RETIRED'
           AND NEW.effective_to IS DISTINCT FROM snapshot.effective_to) THEN
        RAISE EXCEPTION 'Country-pack activation period differs from snapshot';
    END IF;

    IF NEW.lifecycle_state = 'ACTIVE' THEN
        SELECT other.pack_id INTO conflicting_pack
          FROM (
                SELECT DISTINCT ON (history.tenant_id, history.pack_id)
                       history.tenant_id, history.pack_id,
                       history.lifecycle_state,
                       history.effective_from, history.effective_to
                  FROM pay_country_pack_activation_versions history
                 WHERE history.tenant_id = NEW.tenant_id
                 ORDER BY history.tenant_id, history.pack_id,
                          history.activation_revision DESC
          ) other
          JOIN pay_country_pack_version_snapshots other_snapshot
            ON other_snapshot.tenant_id = other.tenant_id
           AND other_snapshot.pack_id = other.pack_id
         WHERE other.tenant_id = NEW.tenant_id
           AND other.pack_id <> NEW.pack_id
           AND other.lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED')
           AND other_snapshot.legal_entity_id = snapshot.legal_entity_id
           AND other_snapshot.payroll_group_id = snapshot.payroll_group_id
           AND other_snapshot.jurisdiction_code = snapshot.jurisdiction_code
           AND other_snapshot.pack_key = snapshot.pack_key
           AND daterange(other.effective_from, other.effective_to, '[)')
               && daterange(NEW.effective_from, NEW.effective_to, '[)')
         LIMIT 1;
        IF conflicting_pack IS NOT NULL THEN
            RAISE EXCEPTION 'Country-pack effective period overlaps an activated winner';
        END IF;
    END IF;
    RETURN NEW;
END
$pay_v53_activation$;

CREATE FUNCTION pay_v53_guard_social_insurance_fact()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v53_social_insurance$
DECLARE
    source_count INTEGER;
    source_distinct_count INTEGER;
    source_amount NUMERIC(19,4);
    source_currency_count INTEGER;
    canonical_ids UUID[];
    worker_currency TEXT;
    activation_state TEXT;
    activation_policy_version TEXT;
    reused_source UUID;
BEGIN
    SELECT worker.currency INTO worker_currency
      FROM pay_worker_results worker
     WHERE worker.tenant_id = NEW.tenant_id
       AND worker.run_id = NEW.run_id
       AND worker.attempt_no = NEW.attempt_no
       AND worker.worker_result_id = NEW.worker_result_id;
    SELECT activation.lifecycle_state, snapshot.policy_schema_version
      INTO activation_state, activation_policy_version
      FROM pay_country_pack_activation_versions activation
      JOIN pay_country_pack_version_snapshots snapshot
        ON snapshot.tenant_id = activation.tenant_id
       AND snapshot.pack_id = activation.pack_id
     WHERE activation.tenant_id = NEW.tenant_id
       AND activation.pack_id = NEW.pack_id
       AND activation.activation_revision = NEW.activation_revision;

    SELECT COUNT(*), COUNT(DISTINCT line.result_line_id),
           COALESCE(SUM(line.amount), 0)::NUMERIC(19,4),
           COUNT(DISTINCT line.currency),
           array_agg(line.result_line_id ORDER BY line.result_line_id::TEXT)
      INTO source_count, source_distinct_count, source_amount,
           source_currency_count, canonical_ids
      FROM pay_result_lines line
     WHERE line.tenant_id = NEW.tenant_id
       AND line.run_id = NEW.run_id
       AND line.attempt_no = NEW.attempt_no
       AND line.worker_result_id = NEW.worker_result_id
       AND line.currency = NEW.currency
       AND line.result_line_id = ANY(NEW.source_result_line_ids);

    SELECT source.candidate_id INTO reused_source
      FROM unnest(NEW.source_result_line_ids) AS source(candidate_id)
     WHERE EXISTS (
            SELECT 1
              FROM pay_social_insurance_result_facts existing
             WHERE existing.tenant_id = NEW.tenant_id
               AND existing.social_insurance_fact_id
                   <> NEW.social_insurance_fact_id
               AND candidate_id = ANY(existing.source_result_line_ids))
     LIMIT 1;

    IF worker_currency IS DISTINCT FROM NEW.currency
       OR activation_state IS DISTINCT FROM 'ACTIVE'
       OR activation_policy_version IS DISTINCT FROM NEW.policy_version
       OR reused_source IS NOT NULL
       OR cardinality(NEW.source_result_line_ids) < 1
       OR source_count <> cardinality(NEW.source_result_line_ids)
       OR source_distinct_count <> source_count
       OR canonical_ids IS DISTINCT FROM NEW.source_result_line_ids
       OR source_currency_count <> 1
       OR source_amount IS DISTINCT FROM NEW.amount THEN
        RAISE EXCEPTION 'Social-insurance source lines do not reconcile exactly';
    END IF;
    RETURN NEW;
END
$pay_v53_social_insurance$;

CREATE FUNCTION pay_v53_guard_atomic_activation_closure()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v53_atomic_closure$
DECLARE coherent BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'pay_country_pack_activation_versions' THEN
        IF NEW.lifecycle_state <> 'ACTIVE' THEN
            IF NEW.receipt_id IS NOT NULL OR NEW.audit_event_id IS NOT NULL
               OR NEW.correlation_id IS NOT NULL THEN
                RAISE EXCEPTION 'Non-activation lifecycle row carries command closure';
            END IF;
            RETURN NULL;
        END IF;
        SELECT EXISTS (
            SELECT 1
              FROM pay_country_pack_command_receipts receipt
              JOIN pay_country_pack_audit_outbox_events event
                ON event.tenant_id = receipt.tenant_id
               AND event.receipt_id = receipt.receipt_id
               AND event.pack_id = receipt.pack_id
               AND event.activation_revision = receipt.activation_revision
               AND event.correlation_id = receipt.correlation_id
             WHERE receipt.tenant_id = NEW.tenant_id
               AND receipt.receipt_id = NEW.receipt_id
               AND receipt.pack_id = NEW.pack_id
               AND receipt.activation_revision = NEW.activation_revision
               AND receipt.correlation_id = NEW.correlation_id
               AND event.event_id = NEW.audit_event_id)
          INTO coherent;
    ELSIF TG_TABLE_NAME = 'pay_country_pack_command_receipts' THEN
        SELECT EXISTS (
            SELECT 1
              FROM pay_country_pack_activation_versions activation
              JOIN pay_country_pack_audit_outbox_events event
                ON event.tenant_id = activation.tenant_id
               AND event.event_id = activation.audit_event_id
               AND event.receipt_id = activation.receipt_id
               AND event.pack_id = activation.pack_id
               AND event.activation_revision = activation.activation_revision
               AND event.correlation_id = activation.correlation_id
             WHERE activation.tenant_id = NEW.tenant_id
               AND activation.receipt_id = NEW.receipt_id
               AND activation.pack_id = NEW.pack_id
               AND activation.activation_revision = NEW.activation_revision
               AND activation.correlation_id = NEW.correlation_id)
          INTO coherent;
    ELSE
        SELECT EXISTS (
            SELECT 1
              FROM pay_country_pack_activation_versions activation
              JOIN pay_country_pack_command_receipts receipt
                ON receipt.tenant_id = activation.tenant_id
               AND receipt.receipt_id = activation.receipt_id
               AND receipt.pack_id = activation.pack_id
               AND receipt.activation_revision = activation.activation_revision
               AND receipt.correlation_id = activation.correlation_id
             WHERE activation.tenant_id = NEW.tenant_id
               AND activation.audit_event_id = NEW.event_id
               AND activation.receipt_id = NEW.receipt_id
               AND activation.pack_id = NEW.pack_id
               AND activation.activation_revision = NEW.activation_revision
               AND activation.correlation_id = NEW.correlation_id)
          INTO coherent;
    END IF;
    IF NOT coherent THEN
        RAISE EXCEPTION 'Activation receipt and audit outbox closure is not coherent';
    END IF;
    RETURN NULL;
END
$pay_v53_atomic_closure$;

CREATE TRIGGER pay_country_pack_activation_insert_guard
    BEFORE INSERT ON pay_country_pack_activation_versions
    FOR EACH ROW EXECUTE FUNCTION pay_v53_guard_activation_version();

CREATE TRIGGER pay_social_insurance_fact_insert_guard
    BEFORE INSERT ON pay_social_insurance_result_facts
    FOR EACH ROW EXECUTE FUNCTION pay_v53_guard_social_insurance_fact();

CREATE CONSTRAINT TRIGGER pay_country_pack_activation_atomic_closure
    AFTER INSERT ON pay_country_pack_activation_versions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION pay_v53_guard_atomic_activation_closure();
CREATE CONSTRAINT TRIGGER pay_country_pack_receipt_atomic_closure
    AFTER INSERT ON pay_country_pack_command_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION pay_v53_guard_atomic_activation_closure();
CREATE CONSTRAINT TRIGGER pay_country_pack_outbox_atomic_closure
    AFTER INSERT ON pay_country_pack_audit_outbox_events
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION pay_v53_guard_atomic_activation_closure();

DO $pay_v53_security$
DECLARE pay_table_name TEXT;
BEGIN
    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_country_pack_version_snapshots',
        'pay_country_pack_activation_versions',
        'pay_country_pack_command_receipts',
        'pay_country_pack_audit_outbox_events',
        'pay_social_insurance_result_facts']
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I '
            || 'FOR EACH ROW EXECUTE FUNCTION pay_v53_reject_mutation()',
            pay_table_name || '_immutable', pay_table_name);
        EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC', pay_table_name);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', pay_table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', pay_table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I '
            || 'USING (tenant_id = NULLIF(current_setting('
            || '''dwp.payroll_tenant_id'', true), '''')::BIGINT) '
            || 'WITH CHECK (tenant_id = NULLIF(current_setting('
            || '''dwp.payroll_tenant_id'', true), '''')::BIGINT)',
            pay_table_name || '_tenant_policy', pay_table_name);
    END LOOP;
END
$pay_v53_security$;

ALTER FUNCTION pay_v53_reject_mutation() SECURITY DEFINER;
ALTER FUNCTION pay_v53_reject_mutation()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v53_guard_activation_version() SECURITY DEFINER;
ALTER FUNCTION pay_v53_guard_activation_version()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v53_guard_social_insurance_fact() SECURITY DEFINER;
ALTER FUNCTION pay_v53_guard_social_insurance_fact()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v53_guard_atomic_activation_closure() SECURITY DEFINER;
ALTER FUNCTION pay_v53_guard_atomic_activation_closure()
    SET search_path TO pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION pay_v53_reject_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v53_guard_activation_version() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v53_guard_social_insurance_fact() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v53_guard_atomic_activation_closure() FROM PUBLIC;

DO $pay_v53_runtime_access$
DECLARE
    runtime_role_name TEXT := '${payrollRuntimeRole}';
    projection_role_name TEXT := '${payrollProjectionPublisherRole}';
    runtime_role RECORD;
    projection_role RECORD;
    pay_table_name TEXT;
    sequence_binding RECORD;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$'
       OR projection_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid PAY-003 database role name';
    END IF;
    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb,
           rolreplication, rolbypassrls
      INTO runtime_role
      FROM pg_roles WHERE rolname = runtime_role_name;
    IF NOT FOUND OR runtime_role.rolsuper OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION 'Payroll runtime role is missing or unsafe';
    END IF;
    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb,
           rolreplication, rolbypassrls
      INTO projection_role
      FROM pg_roles WHERE rolname = projection_role_name;
    IF NOT FOUND OR projection_role.rolsuper OR projection_role.rolcreaterole
       OR projection_role.rolcreatedb OR projection_role.rolreplication
       OR projection_role.rolbypassrls
       OR projection_role_name = runtime_role_name THEN
        RAISE EXCEPTION 'Payroll projection publisher role is missing or unsafe';
    END IF;

    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_country_pack_version_snapshots',
        'pay_country_pack_activation_versions',
        'pay_country_pack_command_receipts',
        'pay_country_pack_audit_outbox_events',
        'pay_social_insurance_result_facts']
    LOOP
        EXECUTE format('GRANT SELECT ON TABLE %I TO %I',
            pay_table_name, runtime_role_name);
        EXECUTE format('GRANT SELECT ON TABLE %I TO %I',
            pay_table_name, projection_role_name);
    END LOOP;
    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_country_pack_activation_versions',
        'pay_country_pack_command_receipts',
        'pay_country_pack_audit_outbox_events']
    LOOP
        EXECUTE format('GRANT INSERT ON TABLE %I TO %I',
            pay_table_name, runtime_role_name);
    END LOOP;
    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_country_pack_version_snapshots',
        'pay_country_pack_activation_versions',
        'pay_social_insurance_result_facts']
    LOOP
        EXECUTE format('GRANT INSERT ON TABLE %I TO %I',
            pay_table_name, projection_role_name);
    END LOOP;
    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_country_pack_version_snapshots',
        'pay_country_pack_activation_versions',
        'pay_country_pack_command_receipts',
        'pay_country_pack_audit_outbox_events',
        'pay_social_insurance_result_facts']
    LOOP
        FOR sequence_binding IN
            SELECT pg_get_serial_sequence(
                       'public.' || pay_table_name, columns.column_name)
                       AS sequence_name
              FROM information_schema.columns columns
             WHERE columns.table_schema = 'public'
               AND columns.table_name = pay_table_name
               AND columns.column_default LIKE 'nextval(%'
        LOOP
            IF sequence_binding.sequence_name IS NOT NULL THEN
                EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC',
                    sequence_binding.sequence_name);
                IF pay_table_name IN (
                    'pay_country_pack_activation_versions',
                    'pay_country_pack_command_receipts',
                    'pay_country_pack_audit_outbox_events') THEN
                    EXECUTE format(
                        'GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
                        sequence_binding.sequence_name, runtime_role_name);
                END IF;
                IF pay_table_name IN (
                    'pay_country_pack_version_snapshots',
                    'pay_country_pack_activation_versions',
                    'pay_social_insurance_result_facts') THEN
                    EXECUTE format(
                        'GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
                        sequence_binding.sequence_name, projection_role_name);
                END IF;
            END IF;
        END LOOP;
    END LOOP;
    EXECUTE format(
        'REVOKE ALL ON FUNCTION pay_v53_reject_mutation(), '
        || 'pay_v53_guard_activation_version(), '
        || 'pay_v53_guard_social_insurance_fact(), '
        || 'pay_v53_guard_atomic_activation_closure() FROM %I, %I',
        runtime_role_name, projection_role_name);
END
$pay_v53_runtime_access$;

COMMENT ON TABLE pay_country_pack_version_snapshots IS
    'Immutable signed pack metadata and exact legal-entity/pay-group binding; contains no executable legal formula or rate.';
COMMENT ON TABLE pay_country_pack_activation_versions IS
    'Append-only full CAS lifecycle history after immutable AVAILABLE snapshot; SUSPENDED to ACTIVE is the only reactivation path.';
COMMENT ON TABLE pay_country_pack_command_receipts IS
    'Subject-and-action scoped durable PEP-PAY-028 idempotency receipts.';
COMMENT ON TABLE pay_country_pack_audit_outbox_events IS
    'Atomic append-only CountryPackActivated audit outbox; delivery retries reuse the same event.';
COMMENT ON TABLE pay_social_insurance_result_facts IS
    'Typed provider outcome linked to exact V50 worker result and canonical source-line set; generic deductions are never inferred.';
