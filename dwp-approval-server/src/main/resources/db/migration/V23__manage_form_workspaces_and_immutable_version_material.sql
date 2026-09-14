ALTER TABLE apr_form_versions ADD CONSTRAINT uk_apr_form_version_full_identity
    UNIQUE (tenant_id,form_id,form_version_id);

CREATE TABLE apr_form_workspaces (
    tenant_id BIGINT NOT NULL, form_id UUID NOT NULL,
    published_form_version_id UUID, draft_form_version_id UUID,
    catalog_availability VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    workspace_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_by BIGINT NOT NULL,
    PRIMARY KEY(tenant_id,form_id),
    FOREIGN KEY(tenant_id,form_id) REFERENCES apr_forms(tenant_id,form_id),
    FOREIGN KEY(tenant_id,form_id,published_form_version_id) REFERENCES apr_form_versions(tenant_id,form_id,form_version_id),
    FOREIGN KEY(tenant_id,form_id,draft_form_version_id) REFERENCES apr_form_versions(tenant_id,form_id,form_version_id),
    CHECK(catalog_availability IN('ACTIVE','RETIRED')),
    CHECK(workspace_version BETWEEN 0 AND 9007199254740991),
    CHECK(published_form_version_id IS NOT NULL OR draft_form_version_id IS NOT NULL),
    CHECK(published_form_version_id IS NULL OR draft_form_version_id IS NULL OR published_form_version_id<>draft_form_version_id)
);
CREATE TABLE apr_form_version_material (
    tenant_id BIGINT NOT NULL, form_id UUID NOT NULL, form_version_id UUID NOT NULL,
    schema_sha256 CHAR(64) NOT NULL, metadata_payload JSONB NOT NULL, route_payload JSONB NOT NULL,
    material_sha256 CHAR(64) NOT NULL, provenance VARCHAR(40) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), captured_by BIGINT NOT NULL,
    PRIMARY KEY(tenant_id,form_version_id),
    FOREIGN KEY(tenant_id,form_id,form_version_id) REFERENCES apr_form_versions(tenant_id,form_id,form_version_id),
    CHECK(schema_sha256 ~ '^[a-f0-9]{64}$' AND material_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK(jsonb_typeof(metadata_payload)='object' AND jsonb_typeof(route_payload)='object'),
    CHECK(octet_length(metadata_payload::text)+octet_length(route_payload::text)<=262144),
    CHECK(provenance IN('AUTHORING_SNAPSHOT','PUBLISH_SNAPSHOT','LEGACY_CAPTURE_TIME'))
);
CREATE TABLE apr_form_version_lineage (
    tenant_id BIGINT NOT NULL, form_id UUID NOT NULL, form_version_id UUID NOT NULL,
    source_form_version_id UUID NOT NULL, base_published_form_version_id UUID,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), recorded_by BIGINT NOT NULL,
    PRIMARY KEY(tenant_id,form_version_id),
    FOREIGN KEY(tenant_id,form_id,form_version_id) REFERENCES apr_form_versions(tenant_id,form_id,form_version_id),
    FOREIGN KEY(tenant_id,form_id,source_form_version_id) REFERENCES apr_form_versions(tenant_id,form_id,form_version_id),
    FOREIGN KEY(tenant_id,form_id,base_published_form_version_id) REFERENCES apr_form_versions(tenant_id,form_id,form_version_id),
    CHECK(form_version_id<>source_form_version_id)
);
CREATE TABLE apr_form_lifecycle_events (
    event_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, form_id UUID NOT NULL,
    form_version_id UUID, actor_user_id BIGINT NOT NULL, management_resource_set_key VARCHAR(80) NOT NULL,
    action VARCHAR(30) NOT NULL, form_revision BIGINT NOT NULL, workspace_revision BIGINT NOT NULL,
    material JSONB NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(tenant_id,form_id) REFERENCES apr_forms(tenant_id,form_id),
    FOREIGN KEY(tenant_id,form_id,form_version_id) REFERENCES apr_form_versions(tenant_id,form_id,form_version_id),
    CHECK(action IN('BRANCH','UPDATE_DRAFT','PUBLISH','RETIRE','REINSTATE')),
    CHECK(jsonb_typeof(material)='object')
);
CREATE TABLE apr_form_command_receipts (
    tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL, context_scope_key VARCHAR(500) NOT NULL,
    route_key VARCHAR(200) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
    form_id UUID NOT NULL, management_resource_set_key VARCHAR(80) NOT NULL,
    request_sha256 CHAR(64) NOT NULL, result JSONB NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,actor_user_id,context_scope_key,route_key,idempotency_key),
    FOREIGN KEY(tenant_id,form_id) REFERENCES apr_forms(tenant_id,form_id),
    CHECK(length(idempotency_key)>0 AND request_sha256 ~ '^[a-f0-9]{64}$' AND jsonb_typeof(result)='object')
);
CREATE FUNCTION protect_approval_form_version_material() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' AND EXISTS(SELECT 1 FROM apr_form_versions v
         WHERE v.tenant_id=OLD.tenant_id AND v.form_version_id=OLD.form_version_id AND v.lifecycle_state='PUBLISHED') THEN
        RAISE EXCEPTION 'Published form material is immutable';
    END IF;
    IF TG_OP<>'DELETE' AND NEW.material_sha256::text IS DISTINCT FROM encode(sha256(convert_to(
       approval_typed_form_canonical_json(jsonb_build_object('schemaSha256',NEW.schema_sha256::text,
          'metadata',NEW.metadata_payload,'route',NEW.route_payload)),'UTF8')),'hex') THEN
        RAISE EXCEPTION 'Form material canonical digest is invalid';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_approval_form_material BEFORE INSERT OR UPDATE OR DELETE ON apr_form_version_material
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_version_material();
CREATE FUNCTION protect_new_published_form_version() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_state='PUBLISHED' AND EXISTS(SELECT 1 FROM apr_form_version_material m
         WHERE m.tenant_id=OLD.tenant_id AND m.form_version_id=OLD.form_version_id AND m.provenance IN('PUBLISH_SNAPSHOT','LEGACY_CAPTURE_TIME')) THEN
        RAISE EXCEPTION 'New managed published form version is immutable';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_new_approval_form_version BEFORE UPDATE OR DELETE ON apr_form_versions
    FOR EACH ROW EXECUTE FUNCTION protect_new_published_form_version();
CREATE FUNCTION protect_approval_form_journal() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Form lineage, events and receipts are append only'; END $$;
CREATE TRIGGER trg_approval_form_lineage BEFORE UPDATE OR DELETE ON apr_form_version_lineage
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_journal();
CREATE TRIGGER trg_approval_form_events BEFORE UPDATE OR DELETE ON apr_form_lifecycle_events
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_journal();
CREATE TRIGGER trg_approval_form_receipts BEFORE UPDATE OR DELETE ON apr_form_command_receipts
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_journal();
-- No legacy backfill: adoption is explicit and never invents capture-time historical metadata.
