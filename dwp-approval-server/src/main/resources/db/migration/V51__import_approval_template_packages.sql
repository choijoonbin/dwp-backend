ALTER TABLE apr_form_template_command_receipts
    DROP CONSTRAINT ck_apr_form_template_receipt_kind,
    ADD CONSTRAINT ck_apr_form_template_receipt_kind CHECK (
        command_kind IN ('CLONE_TEMPLATE', 'IMPORT_PACKAGE'));

CREATE TABLE apr_form_template_package_imports (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    package_id UUID NOT NULL,
    package_version VARCHAR(80) NOT NULL,
    package_sha256 CHAR(64) NOT NULL,
    package_metadata JSONB NOT NULL,
    template_id UUID NOT NULL,
    template_version_id UUID NOT NULL,
    imported_by BIGINT NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, management_resource_set_key, package_id, package_version),
    UNIQUE (tenant_id, management_resource_set_key, template_version_id),
    FOREIGN KEY (tenant_id, management_resource_set_key, template_id)
        REFERENCES apr_form_templates(tenant_id, management_resource_set_key, template_id),
    FOREIGN KEY (template_id, template_version_id)
        REFERENCES apr_form_template_versions(template_id, template_version_id),
    CONSTRAINT ck_apr_form_template_package_scope CHECK (
        management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_apr_form_template_package_version CHECK (
        package_version ~ '^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$'),
    CONSTRAINT ck_apr_form_template_package_hash CHECK (
        package_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_apr_form_template_package_metadata CHECK (
        jsonb_typeof(package_metadata) = 'object'
        AND octet_length(package_metadata::text) <= 262144),
    CONSTRAINT ck_apr_form_template_package_actor CHECK (imported_by > 0)
);

CREATE INDEX idx_apr_form_template_package_template
    ON apr_form_template_package_imports(
        tenant_id, management_resource_set_key, template_id, imported_at DESC);

CREATE TRIGGER trg_protect_approval_form_template_package_import
    BEFORE UPDATE OR DELETE ON apr_form_template_package_imports
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_template_evidence();

COMMENT ON TABLE apr_form_template_package_imports IS
    'Immutable tenant-scoped provenance for validated Approval template package imports.';
