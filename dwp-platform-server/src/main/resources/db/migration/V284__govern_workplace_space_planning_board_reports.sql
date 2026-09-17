CREATE TABLE wp_space_planning_report_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    scenario_id UUID NOT NULL,
    site_id UUID NOT NULL,
    floor_id UUID,
    report_format VARCHAR(12) NOT NULL,
    scenario_version BIGINT NOT NULL,
    preview_version BIGINT NOT NULL DEFAULT 1,
    report_snapshot JSONB NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    confirmation_token VARCHAR(80) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, preview_id),
    UNIQUE (tenant_id, actor_user_id, preview_id),
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, scenario_id)
        REFERENCES wp_space_planning_scenarios(tenant_id, scenario_id),
    FOREIGN KEY (tenant_id, site_id)
        REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, site_id, floor_id)
        REFERENCES wp_floors(tenant_id, site_id, floor_id),
    CONSTRAINT ck_wp_plan_report_preview_actor CHECK (actor_user_id > 0),
    CONSTRAINT ck_wp_plan_report_preview_format CHECK (report_format IN ('PDF','XLSX')),
    CONSTRAINT ck_wp_plan_report_preview_versions CHECK
        (scenario_version > 0 AND preview_version > 0),
    CONSTRAINT ck_wp_plan_report_preview_snapshot CHECK
        (jsonb_typeof(report_snapshot) = 'object'
         AND report_snapshot @> '{"personLevelDataIncluded":false}'::jsonb),
    CONSTRAINT ck_wp_plan_report_preview_hashes CHECK
        (snapshot_sha256 ~ '^[0-9a-f]{64}$'
         AND request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_plan_report_preview_confirmation CHECK
        (confirmation_token ~ '^[0-9a-f-]{36}$'),
    CONSTRAINT ck_wp_plan_report_preview_reason CHECK
        (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_plan_report_preview_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_plan_report_preview_expiry CHECK (expires_at > created_at)
);

CREATE TABLE wp_space_planning_report_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    preview_id UUID NOT NULL,
    scenario_id UUID NOT NULL,
    report_format VARCHAR(12) NOT NULL,
    scenario_version BIGINT NOT NULL,
    expected_preview_version BIGINT NOT NULL,
    command_state VARCHAR(24) NOT NULL,
    command_version BIGINT NOT NULL DEFAULT 1,
    reason VARCHAR(500) NOT NULL,
    explicit_confirmation BOOLEAN NOT NULL,
    decision_revision VARCHAR(68) NOT NULL,
    document_mime VARCHAR(100) NOT NULL,
    file_name VARCHAR(220) NOT NULL,
    document_content BYTEA NOT NULL,
    document_size BIGINT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, command_id),
    UNIQUE (tenant_id, actor_user_id, command_id),
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, actor_user_id, preview_id)
        REFERENCES wp_space_planning_report_previews(tenant_id, actor_user_id, preview_id),
    FOREIGN KEY (tenant_id, scenario_id)
        REFERENCES wp_space_planning_scenarios(tenant_id, scenario_id),
    CONSTRAINT ck_wp_plan_report_command_actor CHECK (actor_user_id > 0),
    CONSTRAINT ck_wp_plan_report_command_format CHECK (report_format IN ('PDF','XLSX')),
    CONSTRAINT ck_wp_plan_report_command_versions CHECK
        (scenario_version > 0 AND expected_preview_version > 0 AND command_version > 0),
    CONSTRAINT ck_wp_plan_report_command_state CHECK
        (command_state IN ('SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_plan_report_command_confirmation CHECK (explicit_confirmation),
    CONSTRAINT ck_wp_plan_report_command_revision CHECK
        (decision_revision ~ '^psr-[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_plan_report_command_document CHECK
        (document_size BETWEEN 1 AND 10485760
         AND document_size = octet_length(document_content)
         AND ((report_format='PDF'
               AND document_mime='application/pdf'
               AND file_name ~ '\.pdf$'
               AND substring(document_content FROM 1 FOR 4)=decode('25504446','hex'))
              OR (report_format='XLSX'
                  AND document_mime='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
                  AND file_name ~ '\.xlsx$'
                  AND substring(document_content FROM 1 FOR 2)=decode('504b','hex')))),
    CONSTRAINT ck_wp_plan_report_command_hashes CHECK
        (content_sha256 ~ '^[0-9a-f]{64}$'
         AND request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_plan_report_command_reason CHECK
        (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_plan_report_command_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_plan_report_command_clock CHECK
        (completed_at >= accepted_at AND expires_at > completed_at)
);

CREATE TABLE wp_space_planning_report_audit_events (
    report_audit_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    scenario_id UUID NOT NULL,
    preview_id UUID,
    command_id UUID,
    event_type VARCHAR(48) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    evidence JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (tenant_id, scenario_id)
        REFERENCES wp_space_planning_scenarios(tenant_id, scenario_id),
    FOREIGN KEY (tenant_id, preview_id)
        REFERENCES wp_space_planning_report_previews(tenant_id, preview_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_space_planning_report_commands(tenant_id, command_id),
    CONSTRAINT ck_wp_plan_report_audit_actor CHECK (actor_user_id > 0),
    CONSTRAINT ck_wp_plan_report_audit_type CHECK
        (event_type IN ('PREVIEW_CREATED','REPORT_EXPORTED','CONTENT_ACCESSED')),
    CONSTRAINT ck_wp_plan_report_audit_evidence CHECK (jsonb_typeof(evidence)='object')
);

CREATE INDEX idx_wp_plan_report_previews_expiry
    ON wp_space_planning_report_previews(tenant_id, expires_at);
CREATE INDEX idx_wp_plan_report_commands_expiry
    ON wp_space_planning_report_commands(tenant_id, expires_at);
CREATE INDEX idx_wp_plan_report_audit_scenario
    ON wp_space_planning_report_audit_events(tenant_id, scenario_id, occurred_at DESC);

COMMENT ON TABLE wp_space_planning_report_previews IS
    'Short-lived actor, tenant and planning-scope bound previews of aggregate board reports. Person and booking rows are prohibited.';
COMMENT ON TABLE wp_space_planning_report_commands IS
    'Exactly-once guarded PDF/XLSX board-report artifacts with CAS, step-up evidence and integrity metadata.';
COMMENT ON TABLE wp_space_planning_report_audit_events IS
    'Append-only evidence for preview, export and content access without raw person-level data.';
