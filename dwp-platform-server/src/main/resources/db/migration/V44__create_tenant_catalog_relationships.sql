ALTER TABLE adm_registry_entries
    DROP CONSTRAINT ck_adm_registry_entries_type;

ALTER TABLE adm_registry_entries
    ADD CONSTRAINT ck_adm_registry_entries_type
        CHECK (registry_type IN (
            'APP', 'CONNECTOR', 'AGENT', 'TOOL', 'POLICY', 'API', 'DATA_PRODUCT'
        ));

CREATE TABLE adm_catalog_relations (
    catalog_relation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    source_ref VARCHAR(260) NOT NULL,
    target_ref VARCHAR(260) NOT NULL,
    relation_type VARCHAR(40) NOT NULL,
    relation_origin VARCHAR(24) NOT NULL DEFAULT 'DECLARED',
    criticality VARCHAR(24) NOT NULL DEFAULT 'OPERATIONAL',
    evidence_ref VARCHAR(500),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_catalog_relation
        UNIQUE (tenant_id, source_ref, target_ref, relation_type),
    CONSTRAINT ck_adm_catalog_relation_refs
        CHECK (
            source_ref = UPPER(BTRIM(source_ref))
            AND target_ref = UPPER(BTRIM(target_ref))
            AND source_ref ~ '^[A-Z][A-Z0-9_]*:[A-Z0-9_.:/-]+$'
            AND target_ref ~ '^[A-Z][A-Z0-9_]*:[A-Z0-9_.:/-]+$'
            AND source_ref <> target_ref
        ),
    CONSTRAINT ck_adm_catalog_relation_type
        CHECK (relation_type IN (
            'DEPENDS_ON', 'CONSUMES', 'PRODUCES', 'EXPOSES', 'GOVERNS',
            'NAVIGATES_TO', 'REQUIRES_PERMISSION', 'SYNCHRONIZES'
        )),
    CONSTRAINT ck_adm_catalog_relation_origin
        CHECK (relation_origin IN ('DECLARED', 'DISCOVERED', 'SYSTEM')),
    CONSTRAINT ck_adm_catalog_relation_criticality
        CHECK (criticality IN ('INFORMATIONAL', 'OPERATIONAL', 'CRITICAL')),
    CONSTRAINT ck_adm_catalog_relation_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_adm_catalog_relation_metadata
        CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX idx_adm_catalog_relations_source
    ON adm_catalog_relations(tenant_id, source_ref, lifecycle_state);
CREATE INDEX idx_adm_catalog_relations_target
    ON adm_catalog_relations(tenant_id, target_ref, lifecycle_state);
CREATE INDEX idx_adm_catalog_relations_risk
    ON adm_catalog_relations(tenant_id, criticality, lifecycle_state);

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.REGISTRY_TYPE', 'API', 'API contract',
     '{"ko":"API 계약","en":"API contract"}', 60, '{}'),
    ('PLATFORM.REGISTRY_TYPE', 'DATA_PRODUCT', 'Data product',
     '{"ko":"데이터 제품","en":"Data product"}', 70, '{}');

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.CATALOG.RELATION_TYPE', 'dwp-platform-server',
     'Catalog relation type', 'Directed relationship between governed catalog entities.',
     'EXTENSIBLE', 'CHECK', 'adm_catalog_relations.relation_type', 'REFERENCE'),
    ('PLATFORM.CATALOG.RELATION_ORIGIN', 'dwp-platform-server',
     'Catalog relation origin', 'Evidence source for a catalog relationship.',
     'SYSTEM', 'CHECK', 'adm_catalog_relations.relation_origin', 'PROTOCOL'),
    ('PLATFORM.CATALOG.CRITICALITY', 'dwp-platform-server',
     'Catalog relation criticality', 'Operational impact carried by a catalog relationship.',
     'SYSTEM', 'CHECK', 'adm_catalog_relations.criticality', 'SECURITY'),
    ('PLATFORM.CATALOG.LIFECYCLE', 'dwp-platform-server',
     'Catalog relation lifecycle', 'Lifecycle of an explicitly governed catalog relationship.',
     'SYSTEM', 'CHECK', 'adm_catalog_relations.lifecycle_state', 'STATE_MACHINE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.CATALOG.RELATION_TYPE', 'DEPENDS_ON', 'Depends on',
     '{"ko":"의존","en":"Depends on"}', 10, '{"direction":"OUTBOUND"}'),
    ('PLATFORM.CATALOG.RELATION_TYPE', 'CONSUMES', 'Consumes',
     '{"ko":"소비","en":"Consumes"}', 20, '{"direction":"OUTBOUND"}'),
    ('PLATFORM.CATALOG.RELATION_TYPE', 'PRODUCES', 'Produces',
     '{"ko":"생산","en":"Produces"}', 30, '{"direction":"OUTBOUND"}'),
    ('PLATFORM.CATALOG.RELATION_TYPE', 'EXPOSES', 'Exposes',
     '{"ko":"노출","en":"Exposes"}', 40, '{"direction":"OUTBOUND"}'),
    ('PLATFORM.CATALOG.RELATION_TYPE', 'GOVERNS', 'Governs',
     '{"ko":"관리","en":"Governs"}', 50, '{"direction":"OUTBOUND"}'),
    ('PLATFORM.CATALOG.RELATION_TYPE', 'NAVIGATES_TO', 'Navigates to',
     '{"ko":"메뉴 연결","en":"Navigates to"}', 60, '{"direction":"OUTBOUND"}'),
    ('PLATFORM.CATALOG.RELATION_TYPE', 'REQUIRES_PERMISSION', 'Requires permission',
     '{"ko":"권한 필요","en":"Requires permission"}', 70, '{"direction":"OUTBOUND"}'),
    ('PLATFORM.CATALOG.RELATION_TYPE', 'SYNCHRONIZES', 'Synchronizes',
     '{"ko":"동기화","en":"Synchronizes"}', 80, '{"direction":"BIDIRECTIONAL"}'),
    ('PLATFORM.CATALOG.RELATION_ORIGIN', 'DECLARED', 'Declared',
     '{"ko":"명시됨","en":"Declared"}', 10, '{}'),
    ('PLATFORM.CATALOG.RELATION_ORIGIN', 'DISCOVERED', 'Discovered',
     '{"ko":"자동 탐지","en":"Discovered"}', 20, '{}'),
    ('PLATFORM.CATALOG.RELATION_ORIGIN', 'SYSTEM', 'System generated',
     '{"ko":"시스템 생성","en":"System generated"}', 30, '{}'),
    ('PLATFORM.CATALOG.CRITICALITY', 'INFORMATIONAL', 'Informational',
     '{"ko":"정보","en":"Informational"}', 10, '{"weight":1}'),
    ('PLATFORM.CATALOG.CRITICALITY', 'OPERATIONAL', 'Operational',
     '{"ko":"운영","en":"Operational"}', 20, '{"weight":2}'),
    ('PLATFORM.CATALOG.CRITICALITY', 'CRITICAL', 'Critical',
     '{"ko":"핵심","en":"Critical"}', 30, '{"weight":4}'),
    ('PLATFORM.CATALOG.LIFECYCLE', 'ACTIVE', 'Active',
     '{"ko":"활성","en":"Active"}', 10, '{}'),
    ('PLATFORM.CATALOG.LIFECYCLE', 'RETIRED', 'Retired',
     '{"ko":"종료","en":"Retired"}', 20, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
SELECT code_set_key, owner_service, 'DATABASE_COLUMN', source_reference, 'CHECK'
  FROM sys_code_sets
 WHERE code_set_key IN (
    'PLATFORM.CATALOG.RELATION_TYPE',
    'PLATFORM.CATALOG.RELATION_ORIGIN',
    'PLATFORM.CATALOG.CRITICALITY',
    'PLATFORM.CATALOG.LIFECYCLE'
 );

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES (
    'PLATFORM.REGISTRY_TYPE', 'dwp-platform-server', 'DATABASE_COLUMN',
    'adm_registry_entries.registry_type', 'CHECK'
)
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET enforcement_type = EXCLUDED.enforcement_type,
              lifecycle_state = 'ACTIVE',
              updated_at = CURRENT_TIMESTAMP;

COMMENT ON TABLE adm_catalog_relations IS
    'Tenant-declared catalog edges only. Domain tables remain authoritative and discovered edges are projected at read time.';
COMMENT ON COLUMN adm_catalog_relations.source_ref IS
    'Canonical reference KIND:KEY. References are validated against the live catalog before activation.';
COMMENT ON COLUMN adm_catalog_relations.metadata IS
    'Non-secret relation metadata. Credentials, personal data, and payload samples are prohibited.';
