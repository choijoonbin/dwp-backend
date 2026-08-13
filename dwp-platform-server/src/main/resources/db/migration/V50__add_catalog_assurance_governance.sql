CREATE TABLE sys_catalog_compatibility_rules (
    catalog_rule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_key VARCHAR(120) NOT NULL,
    rule_version BIGINT NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    rule_definition JSONB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_by VARCHAR(160) NOT NULL DEFAULT 'system',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_catalog_rule_version UNIQUE (rule_key, rule_version),
    CONSTRAINT ck_sys_catalog_rule_state
        CHECK (lifecycle_state IN ('ACTIVE', 'SUPERSEDED', 'RETIRED')),
    CONSTRAINT ck_sys_catalog_rule_definition
        CHECK (jsonb_typeof(rule_definition) = 'object'),
    CONSTRAINT ck_sys_catalog_rule_hash
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uk_sys_catalog_rule_active
    ON sys_catalog_compatibility_rules(rule_key)
    WHERE lifecycle_state = 'ACTIVE';

INSERT INTO sys_catalog_compatibility_rules (
    rule_key, rule_version, lifecycle_state, rule_definition, content_sha256)
VALUES (
    'DWP_CATALOG_IMPACT',
    1,
    'ACTIVE',
    '{
      "maximumTraversalDepth": 8,
      "criticalDirectBlocks": true,
      "retireWithDirectDependentsBlocks": true,
      "criticalityWeights": {
        "INFORMATIONAL": 1,
        "OPERATIONAL": 2,
        "CRITICAL": 4
      }
    }'::jsonb,
    'fc95a76e184134a08d3ac22c8aec34423916d5517143531d9b287bd05cc88a34'
);

CREATE OR REPLACE FUNCTION sys_guard_catalog_compatibility_rule_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Catalog compatibility rules cannot be deleted';
    END IF;
    IF OLD.rule_key IS DISTINCT FROM NEW.rule_key
       OR OLD.rule_version IS DISTINCT FROM NEW.rule_version
       OR OLD.rule_definition IS DISTINCT FROM NEW.rule_definition
       OR OLD.content_sha256 IS DISTINCT FROM NEW.content_sha256
       OR OLD.created_by IS DISTINCT FROM NEW.created_by
       OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Published catalog compatibility rule content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_catalog_compatibility_rule_immutable
BEFORE UPDATE OR DELETE ON sys_catalog_compatibility_rules
FOR EACH ROW EXECUTE FUNCTION sys_guard_catalog_compatibility_rule_mutation();

CREATE TABLE adm_catalog_assurance_findings (
    catalog_finding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    entity_ref VARCHAR(260) NOT NULL,
    finding_code VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    rule_key VARCHAR(120) NOT NULL,
    rule_version BIGINT NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    evidence_sha256 CHAR(64) NOT NULL,
    first_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disposition_reason VARCHAR(1000),
    disposition_evidence_ref VARCHAR(500),
    disposed_by BIGINT,
    disposed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_adm_catalog_finding_identity
        UNIQUE (tenant_id, entity_ref, finding_code, rule_key, rule_version),
    CONSTRAINT fk_adm_catalog_finding_rule
        FOREIGN KEY (rule_key, rule_version)
        REFERENCES sys_catalog_compatibility_rules(rule_key, rule_version),
    CONSTRAINT ck_adm_catalog_finding_code
        CHECK (finding_code IN ('OWNER_MISSING', 'ORPHAN_ASSET', 'DEPRECATION_IMPACT')),
    CONSTRAINT ck_adm_catalog_finding_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_adm_catalog_finding_state
        CHECK (lifecycle_state IN ('OPEN', 'ACKNOWLEDGED', 'FALSE_POSITIVE', 'ACCEPTED_RISK', 'RESOLVED')),
    CONSTRAINT ck_adm_catalog_finding_evidence
        CHECK (jsonb_typeof(evidence) = 'object'),
    CONSTRAINT ck_adm_catalog_finding_hash
        CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_adm_catalog_finding_disposition
        CHECK (lifecycle_state = 'OPEN'
            OR (disposition_reason IS NOT NULL AND disposed_at IS NOT NULL))
);

CREATE TABLE adm_catalog_finding_dispositions (
    catalog_finding_disposition_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_finding_id UUID NOT NULL
        REFERENCES adm_catalog_assurance_findings(catalog_finding_id),
    tenant_id BIGINT NOT NULL,
    previous_state VARCHAR(24) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    evidence_ref VARCHAR(500),
    actor_type VARCHAR(16) NOT NULL,
    decided_by BIGINT,
    content_sha256 CHAR(64) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_adm_catalog_finding_disposition_decision
        CHECK (decision IN ('OPEN', 'ACKNOWLEDGED', 'FALSE_POSITIVE', 'ACCEPTED_RISK', 'RESOLVED')),
    CONSTRAINT ck_adm_catalog_finding_disposition_actor
        CHECK (actor_type IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_adm_catalog_finding_disposition_user
        CHECK ((actor_type = 'USER' AND decided_by IS NOT NULL) OR actor_type = 'SYSTEM'),
    CONSTRAINT ck_adm_catalog_finding_disposition_hash
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_adm_catalog_finding_queue
    ON adm_catalog_assurance_findings(tenant_id, lifecycle_state, severity, last_detected_at DESC);

CREATE OR REPLACE FUNCTION sys_reject_catalog_finding_disposition_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Catalog finding dispositions are append-only';
END;
$$;

CREATE TRIGGER trg_adm_catalog_finding_dispositions_immutable
BEFORE UPDATE OR DELETE ON adm_catalog_finding_dispositions
FOR EACH ROW EXECUTE FUNCTION sys_reject_catalog_finding_disposition_mutation();

COMMENT ON TABLE sys_catalog_compatibility_rules IS
    'Versioned decision rules. Published content is immutable; lifecycle state may advance.';
COMMENT ON TABLE adm_catalog_assurance_findings IS
    'Tenant-scoped automated catalog findings and immutable operator disposition evidence.';
COMMENT ON TABLE adm_catalog_finding_dispositions IS
    'Append-only disposition evidence for catalog assurance findings.';
