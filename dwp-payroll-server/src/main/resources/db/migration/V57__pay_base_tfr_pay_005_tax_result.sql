-- BASE-TFR-PAY-005. One immutable typed TAX_RESULT fact linked to the exact
-- V50 worker result and V53 active signed country-pack lineage. No legal rate,
-- formula, provider credential, receipt, outbox, event, seed or backfill exists.

CREATE TABLE pay_tax_result_facts (
    tax_result_fact_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    tax_result_fact_id UUID NOT NULL,
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
    CONSTRAINT pay_tax_result_fact_public_unique
        UNIQUE (tenant_id, tax_result_fact_id),
    CONSTRAINT pay_tax_result_fact_worker_unique
        UNIQUE (tenant_id, worker_result_id),
    CONSTRAINT pay_tax_result_fact_worker_fk FOREIGN KEY (
        tenant_id, run_id, attempt_no, worker_result_id)
        REFERENCES pay_worker_results (
            tenant_id, run_id, attempt_no, worker_result_id),
    CONSTRAINT pay_tax_result_fact_activation_fk FOREIGN KEY (
        tenant_id, pack_id, activation_revision)
        REFERENCES pay_country_pack_activation_versions (
            tenant_id, pack_id, activation_revision),
    CONSTRAINT pay_tax_result_fact_positive CHECK (
        tenant_id > 0 AND attempt_no > 0
        AND activation_revision > 0 AND amount >= 0),
    CONSTRAINT pay_tax_result_fact_money_shape CHECK (
        currency ~ '^[A-Z]{3}$'),
    CONSTRAINT pay_tax_result_fact_provider_shape CHECK (
        provider_result_type = 'TAX_RESULT'
        AND provider_source_semantics = 'TYPED_COUNTRY_RULE_OUTPUT'
        AND policy_version ~ '^[A-Za-z0-9][A-Za-z0-9._:+-]{0,63}$'),
    CONSTRAINT pay_tax_result_fact_digest_shape CHECK (
        provider_outcome_digest ~ '^[0-9a-f]{64}$'
        AND source_line_set_digest ~ '^[0-9a-f]{64}$'
        AND result_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_tax_result_fact_source_shape CHECK (
        cardinality(source_result_line_ids) > 0
        AND array_position(source_result_line_ids, NULL) IS NULL)
);

CREATE INDEX pay_tax_result_fact_query_idx
    ON pay_tax_result_facts (
        tenant_id, recorded_at DESC, worker_result_id);

CREATE FUNCTION pay_v57_reject_tax_result_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v57_immutable$
BEGIN
    RAISE EXCEPTION 'PAY-005 V57 tax-result facts are append-only';
END
$pay_v57_immutable$;

CREATE FUNCTION pay_v57_guard_tax_result_fact()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v57_tax_result$
DECLARE
    source_count INTEGER;
    source_distinct_count INTEGER;
    source_amount NUMERIC(19,4);
    source_currency_count INTEGER;
    source_tax_component_count INTEGER;
    canonical_ids UUID[];
    worker_currency TEXT;
    worker_recorded_at TIMESTAMPTZ;
    activation_state TEXT;
    activation_policy_version TEXT;
    activation_effective_from DATE;
    activation_effective_to DATE;
    reused_source UUID;
    social_currency TEXT;
    social_policy_version TEXT;
    social_pack_id UUID;
    social_activation_revision BIGINT;
BEGIN
    SELECT worker.currency, worker.recorded_at
      INTO worker_currency, worker_recorded_at
      FROM pay_worker_results worker
     WHERE worker.tenant_id = NEW.tenant_id
       AND worker.run_id = NEW.run_id
       AND worker.attempt_no = NEW.attempt_no
       AND worker.worker_result_id = NEW.worker_result_id;

    SELECT activation.lifecycle_state, snapshot.policy_schema_version,
           activation.effective_from, activation.effective_to
      INTO activation_state, activation_policy_version,
           activation_effective_from, activation_effective_to
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
           COUNT(*) FILTER (
               WHERE line.line_category = 'DEDUCTION'
                 AND line.component_code LIKE 'TAX.%'),
           array_agg(line.result_line_id ORDER BY line.result_line_id::TEXT)
      INTO source_count, source_distinct_count, source_amount,
           source_currency_count, source_tax_component_count, canonical_ids
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
              FROM pay_tax_result_facts existing
             WHERE existing.tenant_id = NEW.tenant_id
               AND existing.tax_result_fact_id <> NEW.tax_result_fact_id
               AND candidate_id = ANY(existing.source_result_line_ids))
     LIMIT 1;

    SELECT social.currency, social.policy_version, social.pack_id,
           social.activation_revision
      INTO social_currency, social_policy_version, social_pack_id,
           social_activation_revision
      FROM pay_social_insurance_result_facts social
     WHERE social.tenant_id = NEW.tenant_id
       AND social.worker_result_id = NEW.worker_result_id;

    IF worker_currency IS DISTINCT FROM NEW.currency
       OR activation_state IS DISTINCT FROM 'ACTIVE'
       OR activation_policy_version IS DISTINCT FROM NEW.policy_version
       OR worker_recorded_at IS NULL
       OR worker_recorded_at::DATE < activation_effective_from
       OR (activation_effective_to IS NOT NULL
           AND worker_recorded_at::DATE >= activation_effective_to)
       OR reused_source IS NOT NULL
       OR cardinality(NEW.source_result_line_ids) < 1
       OR source_count <> cardinality(NEW.source_result_line_ids)
       OR source_distinct_count <> source_count
       OR source_tax_component_count <> source_count
       OR canonical_ids IS DISTINCT FROM NEW.source_result_line_ids
       OR source_currency_count <> 1
       OR source_amount IS DISTINCT FROM NEW.amount
       OR (social_currency IS NOT NULL AND (
           social_currency IS DISTINCT FROM NEW.currency
           OR social_policy_version IS DISTINCT FROM NEW.policy_version
           OR social_pack_id IS DISTINCT FROM NEW.pack_id
           OR social_activation_revision IS DISTINCT FROM NEW.activation_revision)) THEN
        RAISE EXCEPTION 'Tax-result source and statutory lineage do not reconcile exactly';
    END IF;
    RETURN NEW;
END
$pay_v57_tax_result$;

CREATE FUNCTION pay_v57_guard_social_tax_lineage()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v57_social_tax$
DECLARE
    tax_currency TEXT;
    tax_policy_version TEXT;
    tax_pack_id UUID;
    tax_activation_revision BIGINT;
BEGIN
    SELECT tax.currency, tax.policy_version, tax.pack_id,
           tax.activation_revision
      INTO tax_currency, tax_policy_version, tax_pack_id,
           tax_activation_revision
      FROM pay_tax_result_facts tax
     WHERE tax.tenant_id = NEW.tenant_id
       AND tax.worker_result_id = NEW.worker_result_id;
    IF tax_currency IS NOT NULL AND (
       tax_currency IS DISTINCT FROM NEW.currency
       OR tax_policy_version IS DISTINCT FROM NEW.policy_version
       OR tax_pack_id IS DISTINCT FROM NEW.pack_id
       OR tax_activation_revision IS DISTINCT FROM NEW.activation_revision) THEN
        RAISE EXCEPTION 'Tax and social-insurance statutory lineage differs';
    END IF;
    RETURN NEW;
END
$pay_v57_social_tax$;

CREATE TRIGGER pay_tax_result_fact_insert_guard
    BEFORE INSERT ON pay_tax_result_facts
    FOR EACH ROW EXECUTE FUNCTION pay_v57_guard_tax_result_fact();
CREATE TRIGGER pay_tax_result_fact_immutable
    BEFORE UPDATE OR DELETE ON pay_tax_result_facts
    FOR EACH ROW EXECUTE FUNCTION pay_v57_reject_tax_result_mutation();
CREATE TRIGGER pay_social_insurance_tax_lineage_guard
    BEFORE INSERT ON pay_social_insurance_result_facts
    FOR EACH ROW EXECUTE FUNCTION pay_v57_guard_social_tax_lineage();

REVOKE ALL ON TABLE pay_tax_result_facts FROM PUBLIC;
ALTER TABLE pay_tax_result_facts ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_tax_result_facts FORCE ROW LEVEL SECURITY;
CREATE POLICY pay_tax_result_facts_tenant_policy ON pay_tax_result_facts
    USING (tenant_id = NULLIF(current_setting(
        'dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting(
        'dwp.payroll_tenant_id', true), '')::BIGINT);

ALTER FUNCTION pay_v57_reject_tax_result_mutation() SECURITY DEFINER;
ALTER FUNCTION pay_v57_reject_tax_result_mutation()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v57_guard_tax_result_fact() SECURITY DEFINER;
ALTER FUNCTION pay_v57_guard_tax_result_fact()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v57_guard_social_tax_lineage() SECURITY DEFINER;
ALTER FUNCTION pay_v57_guard_social_tax_lineage()
    SET search_path TO pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION pay_v57_reject_tax_result_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v57_guard_tax_result_fact() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v57_guard_social_tax_lineage() FROM PUBLIC;

DO $pay_v57_runtime_access$
DECLARE
    runtime_role_name TEXT := '${payrollRuntimeRole}';
    projection_role_name TEXT := '${payrollProjectionPublisherRole}';
    runtime_role RECORD;
    projection_role RECORD;
    sequence_name TEXT;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$'
       OR projection_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid PAY-005 database role name';
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

    EXECUTE format('GRANT SELECT ON TABLE pay_tax_result_facts TO %I',
        runtime_role_name);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE pay_tax_result_facts TO %I',
        projection_role_name);
    sequence_name := pg_get_serial_sequence(
        'public.pay_tax_result_facts', 'tax_result_fact_internal_id');
    IF sequence_name IS NULL THEN
        RAISE EXCEPTION 'PAY-005 identity sequence is missing';
    END IF;
    EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC', sequence_name);
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
        sequence_name, projection_role_name);
    EXECUTE format(
        'REVOKE ALL ON FUNCTION pay_v57_reject_tax_result_mutation(), '
        || 'pay_v57_guard_tax_result_fact(), '
        || 'pay_v57_guard_social_tax_lineage() FROM %I, %I',
        runtime_role_name, projection_role_name);
END
$pay_v57_runtime_access$;

COMMENT ON TABLE pay_tax_result_facts IS
    'Immutable typed TAX_RESULT linked to exact V50 source lines and V53 active signed-pack lineage; generic totals are never inferred.';
