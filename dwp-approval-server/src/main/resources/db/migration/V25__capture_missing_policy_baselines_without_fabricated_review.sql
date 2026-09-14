CREATE TABLE apr_policy_version_source_records (
    policy_version_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    policy_id UUID NOT NULL,
    source_kind VARCHAR(48) NOT NULL,
    captured_at TIMESTAMPTZ,
    CONSTRAINT fk_apr_policy_version_source_tenant FOREIGN KEY (tenant_id, policy_id)
        REFERENCES apr_policy_rules(tenant_id, policy_id),
    CONSTRAINT ck_apr_policy_version_source_kind CHECK (
        (source_kind = 'UNRECORDED_HISTORICAL_METADATA' AND captured_at IS NULL)
        OR (source_kind = 'LEGACY_CAPTURE_TIME' AND captured_at IS NOT NULL)
    )
);

-- This journal does not disappear if a source version is corrupted or removed.
-- It never supplies a missing version to a query or acts as publication authority.
INSERT INTO apr_policy_version_source_records(policy_version_id, tenant_id, policy_id, source_kind)
SELECT policy_version_id, tenant_id, policy_id, 'UNRECORDED_HISTORICAL_METADATA'
FROM apr_policy_rule_versions;

CREATE FUNCTION preserve_apr_policy_version_source_record()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Policy version source records are immutable' USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER preserve_apr_policy_version_source_record
BEFORE UPDATE OR DELETE ON apr_policy_version_source_records
FOR EACH ROW EXECUTE FUNCTION preserve_apr_policy_version_source_record();

CREATE FUNCTION capture_approval_root_policy_baselines(p_tenant_id BIGINT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    v_policy apr_policy_rules%ROWTYPE;
    v_version_id UUID;
    v_capture_at TIMESTAMPTZ;
BEGIN
    FOR v_policy IN
        SELECT policy.* FROM apr_policy_rules policy
        JOIN apr_tenants tenant ON tenant.tenant_id = policy.tenant_id
        WHERE policy.tenant_id = p_tenant_id
          AND policy.management_resource_set_key = 'RS_APPROVALS'
          AND tenant.lifecycle_state = 'ACTIVE'
        ORDER BY policy.policy_id FOR UPDATE OF policy
    LOOP
        IF NOT EXISTS (SELECT 1 FROM apr_policy_rule_versions version
                WHERE version.tenant_id = v_policy.tenant_id
                  AND version.policy_id = v_policy.policy_id) THEN
            IF EXISTS (SELECT 1 FROM apr_policy_version_source_records source
                    WHERE source.tenant_id = v_policy.tenant_id
                      AND source.policy_id = v_policy.policy_id) THEN
                RAISE EXCEPTION 'Recorded policy history is missing; initialization cannot replace it'
                    USING ERRCODE = '23514';
            END IF;
            v_version_id := gen_random_uuid();
            v_capture_at := clock_timestamp();
            INSERT INTO apr_policy_rule_versions (
                policy_version_id, tenant_id, policy_id, version_number,
                enforcement_mode, severity, lifecycle_state, rule_payload,
                change_reason, submitted_by, submitted_at, published_by,
                published_at, review_comment)
            VALUES (v_version_id, v_policy.tenant_id, v_policy.policy_id, 1,
                v_policy.enforcement_mode, v_policy.severity, v_policy.lifecycle_state,
                v_policy.rule_payload, 'Legacy policy baseline captured at initialization',
                NULL, NULL, NULL, v_capture_at,
                'Capture only: historical maker-checker publication was not recorded');
            INSERT INTO apr_policy_version_source_records (
                policy_version_id, tenant_id, policy_id, source_kind, captured_at)
            VALUES (v_version_id, v_policy.tenant_id, v_policy.policy_id,
                'LEGACY_CAPTURE_TIME', v_capture_at);
        END IF;
    END LOOP;
END
$$;

DO $$
DECLARE v_tenant_id BIGINT;
BEGIN
    FOR v_tenant_id IN SELECT tenant_id FROM apr_tenants
        WHERE lifecycle_state = 'ACTIVE' ORDER BY tenant_id
    LOOP
        PERFORM capture_approval_root_policy_baselines(v_tenant_id);
    END LOOP;
END
$$;

-- Keep the V14 seed body intact. Only initialization, never a read repository,
-- captures missing baselines after the existing seed has completed.
ALTER FUNCTION seed_approval_tenant(BIGINT)
RENAME TO seed_approval_tenant_before_policy_history_v25;

CREATE FUNCTION seed_approval_tenant(p_tenant_id BIGINT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM seed_approval_tenant_before_policy_history_v25(p_tenant_id);
    PERFORM capture_approval_root_policy_baselines(p_tenant_id);
END
$$;

COMMENT ON TABLE apr_policy_version_source_records IS
    'Immutable capture provenance, not fabricated historical publication or authorization evidence.';
