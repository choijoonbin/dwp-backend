CREATE TABLE apr_form_categories (
    category_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    category_key VARCHAR(100) NOT NULL,
    parent_category_id UUID,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    description_ko VARCHAR(600) NOT NULL DEFAULT '',
    description_en VARCHAR(600) NOT NULL DEFAULT '',
    icon_key VARCHAR(80) NOT NULL DEFAULT 'files',
    sort_order INTEGER NOT NULL DEFAULT 100,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_apr_form_category_key UNIQUE (tenant_id, category_key),
    CONSTRAINT uk_apr_form_category_scope UNIQUE (tenant_id, category_id),
    CONSTRAINT fk_apr_form_category_parent FOREIGN KEY (tenant_id, parent_category_id)
        REFERENCES apr_form_categories(tenant_id, category_id),
    CONSTRAINT ck_apr_form_category_key CHECK (category_key ~ '^[A-Z][A-Z0-9_]{1,99}$'),
    CONSTRAINT ck_apr_form_category_state CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_apr_form_category_order CHECK (sort_order BETWEEN 0 AND 10000),
    CONSTRAINT ck_apr_form_category_not_self CHECK (
        parent_category_id IS NULL OR parent_category_id <> category_id)
);

ALTER TABLE apr_forms
    ADD COLUMN category_id UUID,
    ADD COLUMN description_ko VARCHAR(1000) NOT NULL DEFAULT '',
    ADD COLUMN description_en VARCHAR(1000) NOT NULL DEFAULT '',
    ADD COLUMN owner_group_ref VARCHAR(160),
    ADD COLUMN form_kind VARCHAR(24) NOT NULL DEFAULT 'REQUEST';

ALTER TABLE apr_forms
    ADD CONSTRAINT fk_apr_form_category FOREIGN KEY (tenant_id, category_id)
        REFERENCES apr_form_categories(tenant_id, category_id),
    ADD CONSTRAINT ck_apr_form_kind CHECK (form_kind IN ('REQUEST', 'DOCUMENT', 'SIGNATURE'));

CREATE TABLE apr_form_workflow_bindings (
    binding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    form_id UUID NOT NULL,
    workflow_id UUID NOT NULL,
    binding_type VARCHAR(20) NOT NULL DEFAULT 'DEFAULT',
    condition_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    priority INTEGER NOT NULL DEFAULT 100,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_apr_form_workflow_binding UNIQUE (tenant_id, form_id, workflow_id),
    CONSTRAINT fk_apr_form_binding_form FOREIGN KEY (tenant_id, form_id)
        REFERENCES apr_forms(tenant_id, form_id),
    CONSTRAINT fk_apr_form_binding_workflow FOREIGN KEY (tenant_id, workflow_id)
        REFERENCES apr_workflow_definitions(tenant_id, workflow_id),
    CONSTRAINT ck_apr_form_binding_type CHECK (binding_type IN ('DEFAULT', 'CONDITIONAL')),
    CONSTRAINT ck_apr_form_binding_state CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_apr_form_binding_condition CHECK (jsonb_typeof(condition_payload) = 'object'),
    CONSTRAINT ck_apr_form_binding_priority CHECK (priority BETWEEN 0 AND 10000),
    CONSTRAINT ck_apr_form_binding_effective CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX uk_apr_form_default_route
    ON apr_form_workflow_bindings (tenant_id, form_id)
    WHERE binding_type = 'DEFAULT' AND lifecycle_state = 'ACTIVE';

CREATE INDEX idx_apr_form_category_catalog
    ON apr_forms (tenant_id, category_id, lifecycle_state, updated_at DESC);

CREATE INDEX idx_apr_form_route_catalog
    ON apr_form_workflow_bindings (tenant_id, workflow_id, lifecycle_state, priority);

CREATE OR REPLACE FUNCTION seed_approval_form_catalog(p_tenant_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO apr_form_categories (
        category_id, tenant_id, category_key, name_ko, name_en,
        description_ko, description_en, icon_key, sort_order, created_by, updated_by)
    SELECT md5('approval-form-category:' || p_tenant_id || ':' || seed.category_key)::uuid,
           p_tenant_id, seed.category_key, seed.name_ko, seed.name_en,
           seed.description_ko, seed.description_en, seed.icon_key, seed.sort_order, 1, 1
      FROM (VALUES
        ('GENERAL', '공통 업무', 'General operations',
         '조직 전반에서 사용하는 일반 의사결정 양식입니다.',
         'General decision forms used across the organization.', 'files', 10),
        ('PEOPLE', '인사·구성원', 'People and workforce',
         '구성원 생애주기와 인사 운영을 위한 양식입니다.',
         'Forms for workforce lifecycle and people operations.', 'users', 20),
        ('FINANCE', '재무·투자', 'Finance and investment',
         '예산, 비용, 투자 의사결정을 위한 통제 양식입니다.',
         'Governed forms for budget, expense, and investment decisions.', 'landmark', 30),
        ('PROCUREMENT', '구매·협력사', 'Procurement and suppliers',
         '구매 요청과 협력사 온보딩을 위한 양식입니다.',
         'Forms for purchasing and supplier onboarding.', 'package-check', 40),
        ('ACCESS', '접근·보안', 'Access and security',
         '권한 요청과 보안 예외를 위한 제한 양식입니다.',
         'Restricted forms for access requests and security exceptions.', 'shield-check', 50)
      ) seed(category_key, name_ko, name_en, description_ko, description_en, icon_key, sort_order)
    ON CONFLICT (tenant_id, category_key) DO NOTHING;

    UPDATE apr_forms form
       SET category_id = category.category_id,
           description_ko = CASE WHEN form.description_ko = '' THEN workflow.description_ko
                                 ELSE form.description_ko END,
           description_en = CASE WHEN form.description_en = '' THEN workflow.description_en
                                 ELSE form.description_en END,
           owner_group_ref = COALESCE(form.owner_group_ref, workflow.owner_group_ref)
      FROM apr_workflow_definitions workflow
      JOIN apr_form_categories category
        ON category.tenant_id = workflow.tenant_id
       AND category.category_key = workflow.category
     WHERE form.tenant_id = p_tenant_id
       AND workflow.tenant_id = form.tenant_id
       AND form.form_key = workflow.workflow_key || '_FORM'
       AND (form.category_id IS NULL OR form.description_ko = '' OR form.description_en = '');

    UPDATE apr_forms form
       SET category_id = category.category_id
      FROM apr_form_categories category
     WHERE form.tenant_id = p_tenant_id
       AND category.tenant_id = form.tenant_id
       AND category.category_key = 'GENERAL'
       AND form.category_id IS NULL;

    INSERT INTO apr_form_workflow_bindings (
        binding_id, tenant_id, form_id, workflow_id, binding_type,
        condition_payload, priority, lifecycle_state, effective_from, created_by, updated_by)
    SELECT md5('approval-form-route:' || p_tenant_id || ':' || form.form_key || ':' || workflow.workflow_key)::uuid,
           p_tenant_id, form.form_id, workflow.workflow_id, 'DEFAULT',
           '{}'::jsonb, 100, 'ACTIVE',
           CASE WHEN form.lifecycle_state = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
           1, 1
      FROM apr_forms form
      JOIN apr_workflow_definitions workflow
        ON workflow.tenant_id = form.tenant_id
       AND form.form_key = workflow.workflow_key || '_FORM'
     WHERE form.tenant_id = p_tenant_id
    ON CONFLICT (tenant_id, form_id, workflow_id) DO NOTHING;
END;
$$;

SELECT seed_approval_form_catalog(tenant_id) FROM apr_tenants;

COMMENT ON TABLE apr_form_categories IS
    'Tenant-scoped hierarchical taxonomy for governed approval form discovery.';
COMMENT ON TABLE apr_form_workflow_bindings IS
    'Version-safe routing bindings that connect reusable forms to approval processes.';
