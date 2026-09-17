CREATE TABLE apr_form_v3_workspaces (
    form_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    form_key VARCHAR(100) NOT NULL,
    name_ko VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    description_ko VARCHAR(1000) NOT NULL,
    description_en VARCHAR(1000) NOT NULL,
    owner_group_ref VARCHAR(160) NOT NULL,
    category_id UUID NOT NULL,
    default_workflow_id UUID NOT NULL,
    source_template_version_id UUID REFERENCES apr_form_template_versions(template_version_id),
    source_form_id UUID REFERENCES apr_form_v3_workspaces(form_id),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    current_version_number INTEGER NOT NULL DEFAULT 0,
    workspace_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_apr_form_v3_workspace_identity UNIQUE(tenant_id, form_id),
    CONSTRAINT uk_apr_form_v3_workspace_scoped_identity UNIQUE(
        tenant_id, management_resource_set_key, form_id),
    CONSTRAINT uk_apr_form_v3_workspace_key UNIQUE(
        tenant_id, management_resource_set_key, form_key),
    CONSTRAINT fk_apr_form_v3_workspace_category FOREIGN KEY(tenant_id, category_id)
        REFERENCES apr_form_categories(tenant_id, category_id),
    CONSTRAINT fk_apr_form_v3_workspace_workflow FOREIGN KEY(tenant_id, default_workflow_id)
        REFERENCES apr_workflow_definitions(tenant_id, workflow_id),
    CONSTRAINT fk_apr_form_v3_workspace_source_scope FOREIGN KEY(
        tenant_id, management_resource_set_key, source_form_id)
        REFERENCES apr_form_v3_workspaces(tenant_id, management_resource_set_key, form_id),
    CONSTRAINT ck_apr_form_v3_workspace_scope CHECK (
        management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_apr_form_v3_workspace_key CHECK (form_key ~ '^[A-Z][A-Z0-9_]{1,99}$'),
    CONSTRAINT ck_apr_form_v3_workspace_state CHECK (lifecycle_state IN ('DRAFT','ARCHIVED')),
    CONSTRAINT ck_apr_form_v3_workspace_versions CHECK (
        current_version_number >= 0 AND workspace_version BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_apr_form_v3_workspace_source CHECK (source_form_id IS NULL OR source_form_id <> form_id)
);

CREATE INDEX idx_apr_form_v3_workspace_catalog
    ON apr_form_v3_workspaces(
        tenant_id, management_resource_set_key, lifecycle_state, updated_at DESC, form_id);
CREATE INDEX idx_apr_form_v3_workspace_keyset
    ON apr_form_v3_workspaces(
        tenant_id, management_resource_set_key, form_key, form_id);

CREATE TABLE apr_form_v3_versions (
    form_version_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    form_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    schema_contract VARCHAR(80) NOT NULL,
    schema_payload JSONB NOT NULL,
    schema_sha256 CHAR(64) NOT NULL,
    compatibility_mode VARCHAR(32) NOT NULL,
    base_schema_sha256 CHAR(64),
    source_template_version_id UUID REFERENCES apr_form_template_versions(template_version_id),
    source_form_version_id UUID REFERENCES apr_form_v3_versions(form_version_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_by BIGINT NOT NULL,
    CONSTRAINT uk_apr_form_v3_version UNIQUE(tenant_id, form_id, version_number),
    CONSTRAINT uk_apr_form_v3_version_identity UNIQUE(tenant_id, form_id, form_version_id),
    CONSTRAINT uk_apr_form_v3_version_tenant_identity UNIQUE(tenant_id, form_version_id),
    CONSTRAINT fk_apr_form_v3_version_workspace FOREIGN KEY(tenant_id, form_id)
        REFERENCES apr_form_v3_workspaces(tenant_id, form_id),
    CONSTRAINT fk_apr_form_v3_version_source_tenant FOREIGN KEY(tenant_id, source_form_version_id)
        REFERENCES apr_form_v3_versions(tenant_id, form_version_id),
    CONSTRAINT ck_apr_form_v3_version_number CHECK (version_number > 0),
    CONSTRAINT ck_apr_form_v3_version_contract CHECK (
        schema_contract = 'DWP_APPROVAL_FORM_TYPED_V3'
        AND schema_payload->>'schemaContract' = schema_contract
        AND schema_payload->>'schemaVersion' = '3'),
    CONSTRAINT ck_apr_form_v3_version_hash CHECK (
        schema_sha256 ~ '^[a-f0-9]{64}$'
        AND schema_sha256 = encode(sha256(convert_to(
            approval_typed_form_canonical_json(schema_payload), 'UTF8')), 'hex')),
    CONSTRAINT ck_apr_form_v3_version_compatibility CHECK (
        compatibility_mode IN ('INITIAL','STRICT','BACKWARD_COMPATIBLE','BREAKING')
        AND ((compatibility_mode = 'INITIAL' AND base_schema_sha256 IS NULL)
             OR (compatibility_mode <> 'INITIAL' AND base_schema_sha256 ~ '^[a-f0-9]{64}$'))),
    CONSTRAINT ck_apr_form_v3_version_payload_size CHECK (octet_length(schema_payload::text) <= 262144),
    CONSTRAINT ck_apr_form_v3_version_source CHECK (
        source_form_version_id IS NULL OR source_form_version_id <> form_version_id)
);

CREATE INDEX idx_apr_form_v3_version_history
    ON apr_form_v3_versions(tenant_id, form_id, version_number DESC);
CREATE INDEX idx_apr_form_v3_version_hash
    ON apr_form_v3_versions(tenant_id, schema_sha256);

ALTER TABLE apr_form_v3_workspaces
    ADD CONSTRAINT fk_apr_form_v3_workspace_current_version
    FOREIGN KEY(tenant_id, form_id, current_version_number)
    REFERENCES apr_form_v3_versions(tenant_id, form_id, version_number)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE apr_form_v3_lifecycle_events (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    form_id UUID NOT NULL,
    form_version_id UUID NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    workspace_version BIGINT NOT NULL,
    material JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(tenant_id, form_id, form_version_id)
        REFERENCES apr_form_v3_versions(tenant_id, form_id, form_version_id),
    FOREIGN KEY(tenant_id, management_resource_set_key, form_id)
        REFERENCES apr_form_v3_workspaces(tenant_id, management_resource_set_key, form_id),
    CONSTRAINT ck_apr_form_v3_event_scope CHECK (
        management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_apr_form_v3_event_action CHECK (
        action IN ('INSTALL_TEMPLATE','CLONE_DRAFT','UPDATE_DRAFT','ARCHIVE_DRAFT')),
    CONSTRAINT ck_apr_form_v3_event_version CHECK (
        workspace_version BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_apr_form_v3_event_material CHECK (jsonb_typeof(material) = 'object')
);

CREATE INDEX idx_apr_form_v3_event_history
    ON apr_form_v3_lifecycle_events(tenant_id, form_id, occurred_at DESC, event_id);

CREATE TABLE apr_form_v3_command_receipts (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_kind VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    result_form_id UUID NOT NULL,
    result_form_version_id UUID NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id, management_resource_set_key, actor_user_id, command_kind, idempotency_key),
    FOREIGN KEY(tenant_id, result_form_id, result_form_version_id)
        REFERENCES apr_form_v3_versions(tenant_id, form_id, form_version_id),
    FOREIGN KEY(tenant_id, management_resource_set_key, result_form_id)
        REFERENCES apr_form_v3_workspaces(tenant_id, management_resource_set_key, form_id),
    CONSTRAINT ck_apr_form_v3_receipt_scope CHECK (
        management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_apr_form_v3_receipt_kind CHECK (
        command_kind IN ('INSTALL_TEMPLATE','CLONE_DRAFT','UPDATE_DRAFT','ARCHIVE_DRAFT')),
    CONSTRAINT ck_apr_form_v3_receipt_key CHECK (
        idempotency_key ~ '^[A-Za-z0-9._:-]{1,200}$'),
    CONSTRAINT ck_apr_form_v3_receipt_hash CHECK (request_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE FUNCTION approval_form_v3_contains_secret(value JSONB)
RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    entry RECORD;
    item JSONB;
BEGIN
    IF jsonb_typeof(value) = 'object' THEN
        FOR entry IN SELECT key, val FROM jsonb_each(value) pair(key,val) LOOP
            IF lower(entry.key) ~ '(^|_)(secret|password|token|api[_-]?key|apikey|private[_-]?key|privatekey|access[_-]?token|accesstoken)($|_)' THEN
                RETURN TRUE;
            END IF;
            IF entry.key = 'credentialRef'
               AND (jsonb_typeof(entry.val) <> 'string'
                    OR trim(both '"' from entry.val::text) !~ '^vault://[A-Za-z0-9][A-Za-z0-9._/-]{2,239}$') THEN
                RETURN TRUE;
            END IF;
            IF approval_form_v3_contains_secret(entry.val) THEN RETURN TRUE; END IF;
        END LOOP;
    ELSIF jsonb_typeof(value) = 'array' THEN
        FOR item IN SELECT element FROM jsonb_array_elements(value) element LOOP
            IF approval_form_v3_contains_secret(item) THEN RETURN TRUE; END IF;
        END LOOP;
    END IF;
    RETURN FALSE;
END $$;

CREATE FUNCTION enforce_approval_form_v3_workspace_scope()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    category_scope VARCHAR(80);
    workflow_scope VARCHAR(80);
    source_scope VARCHAR(16);
    source_tenant BIGINT;
    source_resource_set VARCHAR(80);
    source_state VARCHAR(20);
    source_version_state VARCHAR(20);
BEGIN
    SELECT management_resource_set_key INTO category_scope
      FROM apr_form_categories
     WHERE tenant_id = NEW.tenant_id AND category_id = NEW.category_id;
    SELECT management_resource_set_key INTO workflow_scope
      FROM apr_workflow_definitions
     WHERE tenant_id = NEW.tenant_id AND workflow_id = NEW.default_workflow_id;
    IF category_scope IS DISTINCT FROM NEW.management_resource_set_key
       OR workflow_scope IS DISTINCT FROM NEW.management_resource_set_key THEN
        RAISE EXCEPTION 'Form V3 category and workflow must belong to the exact management scope';
    END IF;
    IF NEW.source_template_version_id IS NOT NULL THEN
        SELECT template.scope_kind, template.tenant_id, template.management_resource_set_key,
               template.lifecycle_state, version.lifecycle_state
          INTO source_scope, source_tenant, source_resource_set, source_state, source_version_state
          FROM apr_form_template_versions version
          JOIN apr_form_templates template ON template.template_id = version.template_id
         WHERE version.template_version_id = NEW.source_template_version_id;
        IF NOT FOUND OR source_state <> 'ACTIVE' OR source_version_state <> 'RELEASED'
           OR NOT (source_scope = 'GLOBAL'
                   OR (source_scope = 'TENANT'
                       AND source_tenant = NEW.tenant_id
                       AND source_resource_set = NEW.management_resource_set_key)) THEN
            RAISE EXCEPTION 'Form V3 template source must be released and visible in the exact scope';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_enforce_approval_form_v3_workspace_scope
    BEFORE INSERT OR UPDATE OF tenant_id, management_resource_set_key, category_id,
        default_workflow_id, source_template_version_id, source_form_id
    ON apr_form_v3_workspaces FOR EACH ROW
    EXECUTE FUNCTION enforce_approval_form_v3_workspace_scope();

CREATE FUNCTION protect_approval_form_v3_workspace_provenance()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.source_template_version_id IS DISTINCT FROM NEW.source_template_version_id
       OR OLD.source_form_id IS DISTINCT FROM NEW.source_form_id THEN
        RAISE EXCEPTION 'Approval Form V3 source provenance is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_protect_approval_form_v3_workspace_provenance
    BEFORE UPDATE OF source_template_version_id, source_form_id
    ON apr_form_v3_workspaces FOR EACH ROW
    EXECUTE FUNCTION protect_approval_form_v3_workspace_provenance();

CREATE FUNCTION validate_approval_form_v3_version()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF approval_form_v3_contains_secret(NEW.schema_payload) THEN
        RAISE EXCEPTION 'Form V3 material contains a secret or an invalid credential reference';
    END IF;
    IF NEW.schema_payload->'locales' IS NULL
       OR jsonb_typeof(NEW.schema_payload->'locales') <> 'array'
       OR NOT (NEW.schema_payload->'locales' @> '["ko","en"]'::jsonb)
       OR jsonb_typeof(NEW.schema_payload->'pages') <> 'array'
       OR jsonb_array_length(NEW.schema_payload->'pages') < 1 THEN
        RAISE EXCEPTION 'Form V3 material must contain KO/EN locales and at least one page';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_validate_approval_form_v3_version
    BEFORE INSERT ON apr_form_v3_versions
    FOR EACH ROW EXECUTE FUNCTION validate_approval_form_v3_version();

CREATE FUNCTION enforce_approval_form_v3_version_lineage()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    workspace_template UUID;
    workspace_source_form UUID;
    predecessor_form UUID;
    predecessor_number INTEGER;
BEGIN
    SELECT source_template_version_id, source_form_id
      INTO workspace_template, workspace_source_form
      FROM apr_form_v3_workspaces
     WHERE tenant_id = NEW.tenant_id AND form_id = NEW.form_id;
    IF NOT FOUND OR workspace_template IS DISTINCT FROM NEW.source_template_version_id THEN
        RAISE EXCEPTION 'Form V3 version source template does not match its workspace';
    END IF;
    IF NEW.version_number = 1 THEN
        IF workspace_source_form IS NULL AND NEW.source_form_version_id IS NOT NULL THEN
            RAISE EXCEPTION 'An installed Form V3 root cannot claim a source form version';
        END IF;
        IF workspace_source_form IS NOT NULL THEN
            SELECT form_id INTO predecessor_form FROM apr_form_v3_versions
             WHERE tenant_id = NEW.tenant_id AND form_version_id = NEW.source_form_version_id;
            IF NOT FOUND OR predecessor_form <> workspace_source_form THEN
                RAISE EXCEPTION 'A cloned Form V3 root must bind the exact source form version';
            END IF;
        END IF;
    ELSE
        SELECT form_id, version_number INTO predecessor_form, predecessor_number
          FROM apr_form_v3_versions
         WHERE tenant_id = NEW.tenant_id AND form_version_id = NEW.source_form_version_id;
        IF NOT FOUND OR predecessor_form <> NEW.form_id
           OR predecessor_number <> NEW.version_number - 1 THEN
            RAISE EXCEPTION 'A Form V3 version must bind its immediate immutable predecessor';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_enforce_approval_form_v3_version_lineage
    BEFORE INSERT ON apr_form_v3_versions
    FOR EACH ROW EXECUTE FUNCTION enforce_approval_form_v3_version_lineage();

CREATE FUNCTION protect_approval_form_v3_immutable_evidence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Approval Form V3 versions, events, and receipts are append only'; END $$;
CREATE TRIGGER trg_protect_approval_form_v3_version
    BEFORE UPDATE OR DELETE ON apr_form_v3_versions
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_v3_immutable_evidence();
CREATE TRIGGER trg_protect_approval_form_v3_event
    BEFORE UPDATE OR DELETE ON apr_form_v3_lifecycle_events
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_v3_immutable_evidence();
CREATE TRIGGER trg_protect_approval_form_v3_receipt
    BEFORE UPDATE OR DELETE ON apr_form_v3_command_receipts
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_v3_immutable_evidence();

COMMENT ON TABLE apr_form_v3_workspaces IS
    'Draft-only Form Schema V3 workspaces. Publication is deliberately absent from this bounded package.';
COMMENT ON TABLE apr_form_v3_versions IS
    'Append-only immutable Form Schema V3 snapshots with explicit compatibility lineage.';
COMMENT ON COLUMN apr_form_v3_versions.schema_payload IS
    'Canonical schema material; data-source credentials may only appear as vault:// references.';
