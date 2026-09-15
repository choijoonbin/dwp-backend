CREATE SCHEMA apr_signature_native;
REVOKE ALL ON SCHEMA apr_signature_native FROM PUBLIC;

CREATE TABLE apr_signature_provider_policy_heads (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    draft_version_id UUID,
    published_version_id UUID,
    PRIMARY KEY (tenant_id, resource_set_key),
    UNIQUE (tenant_id, resource_set_key, policy_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (version BETWEEN 0 AND 9007199254740991),
    CHECK (draft_version_id IS NOT NULL OR published_version_id IS NOT NULL),
    CHECK (draft_version_id IS NULL OR published_version_id IS NULL
           OR draft_version_id <> published_version_id)
);

CREATE TABLE apr_signature_provider_policy_versions (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    version_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    rules JSONB NOT NULL,
    rules_sha256 CHAR(64) NOT NULL,
    maker_user_id BIGINT NOT NULL,
    maker_person_public_id UUID NOT NULL,
    editor_user_id BIGINT NOT NULL,
    editor_person_public_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, policy_id, version_id),
    UNIQUE (tenant_id, resource_set_key, policy_id, revision),
    FOREIGN KEY (tenant_id, resource_set_key, policy_id)
        REFERENCES apr_signature_provider_policy_heads(tenant_id, resource_set_key, policy_id),
    CHECK (revision BETWEEN 0 AND 9007199254740991),
    CHECK (rules_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (maker_user_id > 0 AND editor_user_id > 0),
    CHECK (jsonb_typeof(rules) = 'object')
);

CREATE TABLE apr_signature_provider_policy_publications (
    publication_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    version_id UUID NOT NULL,
    maker_user_id BIGINT NOT NULL,
    maker_person_public_id UUID NOT NULL,
    editor_user_id BIGINT NOT NULL,
    editor_person_public_id UUID NOT NULL,
    checker_user_id BIGINT NOT NULL,
    checker_person_public_id UUID NOT NULL,
    review_evidence_id UUID NOT NULL,
    review_content_sha256 CHAR(64) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, resource_set_key, policy_id, version_id),
    FOREIGN KEY (tenant_id, resource_set_key, policy_id, version_id)
        REFERENCES apr_signature_provider_policy_versions(tenant_id, resource_set_key, policy_id, version_id),
    CHECK (maker_user_id > 0 AND editor_user_id > 0 AND checker_user_id > 0),
    CHECK (checker_person_public_id <> maker_person_public_id
           AND checker_person_public_id <> editor_person_public_id),
    CHECK (review_content_sha256 ~ '^[a-f0-9]{64}$')
);

ALTER TABLE apr_signature_provider_policy_heads
    ADD CONSTRAINT fk_apr_signature_provider_draft
    FOREIGN KEY (tenant_id, resource_set_key, policy_id, draft_version_id)
    REFERENCES apr_signature_provider_policy_versions(tenant_id, resource_set_key, policy_id, version_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE apr_signature_provider_policy_heads
    ADD CONSTRAINT fk_apr_signature_provider_published
    FOREIGN KEY (tenant_id, resource_set_key, policy_id, published_version_id)
    REFERENCES apr_signature_provider_policy_versions(tenant_id, resource_set_key, policy_id, version_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE apr_signature_native_commands (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    route_contract_key VARCHAR(180) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    context_scope_key VARCHAR(512) NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    command_target_id UUID,
    body_sha256 CHAR(64) NOT NULL,
    private_body JSONB NOT NULL,
    target_id UUID NOT NULL,
    result_version BIGINT NOT NULL,
    result_type VARCHAR(512) NOT NULL,
    result JSONB NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, actor_user_id, route_contract_key, idempotency_key),
    CHECK (actor_user_id > 0),
    CHECK (route_contract_key ~ '^route[.][A-Za-z0-9._:-]{1,173}$'),
    CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,120}$'),
    CHECK (length(context_scope_key) BETWEEN 1 AND 512),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (body_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (result_version BETWEEN 0 AND 9007199254740991),
    CHECK (jsonb_typeof(private_body) = 'object' AND jsonb_typeof(result) = 'object')
);

CREATE TABLE apr_signature_provider_probe_runs (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    probe_run_id UUID NOT NULL,
    operation VARCHAR(32) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    source_revision VARCHAR(69) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    body_sha256 CHAR(64) NOT NULL,
    original_targets JSONB NOT NULL,
    result JSONB NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, resource_set_key, probe_run_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (operation IN ('PROBE', 'KMS_PROBE', 'WORM_INSPECTION')),
    CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETE', 'PARTIAL', 'UNKNOWN_REMOTE_OUTCOME')),
    CHECK (source_revision = 'sigp-' || source_sha256),
    CHECK (source_sha256 ~ '^[a-f0-9]{64}$' AND body_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (actor_user_id > 0 AND completed_at >= started_at),
    CHECK (jsonb_typeof(original_targets) = 'array' AND jsonb_typeof(result) = 'object')
);

CREATE TABLE apr_signature_provider_probe_observations (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    probe_run_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    provider_version BIGINT NOT NULL,
    provider_sha256 CHAR(64) NOT NULL,
    result JSONB NOT NULL,
    evidence_id UUID,
    evidence_sha256 CHAR(64),
    observed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, resource_set_key, probe_run_id, provider_id),
    FOREIGN KEY (tenant_id, resource_set_key, probe_run_id)
        REFERENCES apr_signature_provider_probe_runs(tenant_id, resource_set_key, probe_run_id),
    FOREIGN KEY (tenant_id, provider_id) REFERENCES apr_signature_providers(tenant_id, provider_id),
    CHECK (provider_version BETWEEN 0 AND 9007199254740991),
    CHECK (provider_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (jsonb_typeof(result) = 'object'),
    CHECK ((evidence_id IS NULL) = (evidence_sha256 IS NULL)),
    CHECK (evidence_sha256 IS NULL OR evidence_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE TABLE apr_signature_provider_inspections (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    probe_run_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    provider_version BIGINT NOT NULL,
    provider_sha256 CHAR(64) NOT NULL,
    inspection_kind VARCHAR(16) NOT NULL,
    result JSONB NOT NULL,
    evidence_id UUID,
    evidence_sha256 CHAR(64),
    observed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, resource_set_key, probe_run_id, inspection_kind),
    FOREIGN KEY (tenant_id, resource_set_key, probe_run_id)
        REFERENCES apr_signature_provider_probe_runs(tenant_id, resource_set_key, probe_run_id),
    FOREIGN KEY (tenant_id, provider_id) REFERENCES apr_signature_providers(tenant_id, provider_id),
    CHECK (provider_version BETWEEN 0 AND 9007199254740991),
    CHECK (provider_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (inspection_kind IN ('KMS', 'WORM')),
    CHECK (jsonb_typeof(result) = 'object'),
    CHECK ((evidence_id IS NULL) = (evidence_sha256 IS NULL)),
    CHECK (evidence_sha256 IS NULL OR evidence_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE TABLE apr_external_signature_requests (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    signature_request_id UUID NOT NULL,
    request_id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    provider_id UUID NOT NULL,
    provider_version BIGINT NOT NULL,
    provider_sha256 CHAR(64) NOT NULL,
    configuration_id UUID NOT NULL,
    configuration_version BIGINT NOT NULL,
    configuration_sha256 CHAR(64) NOT NULL,
    policy_id UUID NOT NULL,
    policy_version_id UUID NOT NULL,
    policy_sha256 CHAR(64) NOT NULL,
    source JSONB NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    remote_reference_sha256 CHAR(64),
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, signature_request_id),
    UNIQUE (tenant_id, resource_set_key, signature_request_id, owner_user_id),
    CONSTRAINT fk_apr_external_signature_request_approval
        FOREIGN KEY (tenant_id, request_id) REFERENCES apr_requests(tenant_id, request_id),
    CONSTRAINT fk_apr_external_signature_request_provider
        FOREIGN KEY (tenant_id, provider_id) REFERENCES apr_signature_providers(tenant_id, provider_id),
    CONSTRAINT fk_apr_external_signature_request_policy_version
        FOREIGN KEY (tenant_id, resource_set_key, policy_id, policy_version_id)
        REFERENCES apr_signature_provider_policy_versions(tenant_id, resource_set_key, policy_id, version_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$' AND owner_user_id > 0),
    CHECK (provider_version BETWEEN 0 AND 9007199254740991
           AND configuration_version BETWEEN 0 AND 9007199254740991
           AND version BETWEEN 0 AND 9007199254740991),
    CHECK (provider_sha256 ~ '^[a-f0-9]{64}$'
           AND configuration_sha256 ~ '^[a-f0-9]{64}$'
           AND policy_sha256 ~ '^[a-f0-9]{64}$'
           AND source_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (provider_id = configuration_id AND provider_version = configuration_version
           AND provider_sha256 = configuration_sha256),
    CHECK (state IN ('PREPARED', 'HANDOVER_PENDING', 'OUT_FOR_SIGNATURE',
           'COMPLETION_PENDING', 'COMPLETED_VERIFIED', 'CANCEL_PENDING',
           'CANCELLED', 'FAILED', 'UNKNOWN_REMOTE_OUTCOME')),
    CHECK (jsonb_typeof(source) = 'object' AND jsonb_typeof(reason_codes) = 'array'),
    CHECK (remote_reference_sha256 IS NULL OR remote_reference_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (state <> 'COMPLETED_VERIFIED' OR remote_reference_sha256 IS NOT NULL),
    CHECK (updated_at >= created_at)
);

CREATE TABLE apr_external_signature_events (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    signature_request_id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    action VARCHAR(120) NOT NULL,
    state VARCHAR(32) NOT NULL,
    reason_codes JSONB NOT NULL,
    evidence_id UUID,
    evidence_sha256 CHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, resource_set_key, signature_request_id, event_id),
    UNIQUE (tenant_id, resource_set_key, signature_request_id, sequence),
    CONSTRAINT fk_apr_external_signature_event_request
        FOREIGN KEY (tenant_id, resource_set_key, signature_request_id, owner_user_id)
        REFERENCES apr_external_signature_requests(tenant_id, resource_set_key, signature_request_id, owner_user_id),
    CHECK (sequence BETWEEN 0 AND 9007199254740991),
    CHECK (action ~ '^[A-Z][A-Z0-9_]{0,119}$'),
    CHECK (state IN ('PREPARED', 'HANDOVER_PENDING', 'OUT_FOR_SIGNATURE',
           'COMPLETION_PENDING', 'COMPLETED_VERIFIED', 'CANCEL_PENDING',
           'CANCELLED', 'FAILED', 'UNKNOWN_REMOTE_OUTCOME')),
    CHECK (jsonb_typeof(reason_codes) = 'array'),
    CHECK ((evidence_id IS NULL) = (evidence_sha256 IS NULL)),
    CHECK (evidence_sha256 IS NULL OR evidence_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE TABLE apr_external_signature_artifacts (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    signature_request_id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    artifact_id UUID NOT NULL,
    artifact_kind VARCHAR(24) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_locator_sha256 CHAR(64) NOT NULL,
    object_version_sha256 CHAR(64) NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    evidence_id UUID NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, resource_set_key, signature_request_id, artifact_id),
    CONSTRAINT fk_apr_external_signature_artifact_request
        FOREIGN KEY (tenant_id, resource_set_key, signature_request_id, owner_user_id)
        REFERENCES apr_external_signature_requests(tenant_id, resource_set_key, signature_request_id, owner_user_id),
    CHECK (artifact_kind IN ('UNSIGNED_PDF', 'SIGNED_PDF', 'CERTIFICATE',
           'AUDIT_TRAIL', 'TSA')),
    CHECK (length(btrim(media_type)) BETWEEN 1 AND 120),
    CHECK (content_sha256 ~ '^[a-f0-9]{64}$'
           AND storage_locator_sha256 ~ '^[a-f0-9]{64}$'
           AND object_version_sha256 ~ '^[a-f0-9]{64}$'
           AND evidence_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (size_bytes BETWEEN 1 AND 52428800),
    CHECK (retain_until > recorded_at)
);

CREATE FUNCTION apr_signature_native.immutable() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'Native signature evidence is immutable' USING ERRCODE = '23514';
END $$;

CREATE FUNCTION apr_signature_native.validate_rules() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    keys TEXT[];
BEGIN
    SELECT array_agg(key ORDER BY key) INTO keys FROM jsonb_object_keys(NEW.rules) key;
    IF keys IS DISTINCT FROM ARRAY[
        'allowedClassifications', 'configurationBinding', 'minimumRetentionDays',
        'probeMaxAgeSeconds', 'requireAuthenticatedWebhook',
        'requireComplianceWormStorage', 'requireFreshRevocationEvidence',
        'requireTrustedCertificateChain', 'requireTrustedTimestamp',
        'requireVerifiedProviderAccount', 'requiredProviderKinds', 'signingEnabled',
        'trustBundleId']::TEXT[]
       OR jsonb_typeof(NEW.rules->'signingEnabled') <> 'boolean'
       OR jsonb_typeof(NEW.rules->'requiredProviderKinds') <> 'array'
       OR jsonb_typeof(NEW.rules->'allowedClassifications') <> 'array'
       OR jsonb_typeof(NEW.rules->'requireVerifiedProviderAccount') <> 'boolean'
       OR jsonb_typeof(NEW.rules->'requireAuthenticatedWebhook') <> 'boolean'
       OR jsonb_typeof(NEW.rules->'requireTrustedCertificateChain') <> 'boolean'
       OR jsonb_typeof(NEW.rules->'requireFreshRevocationEvidence') <> 'boolean'
       OR jsonb_typeof(NEW.rules->'requireTrustedTimestamp') <> 'boolean'
       OR jsonb_typeof(NEW.rules->'requireComplianceWormStorage') <> 'boolean'
       OR jsonb_typeof(NEW.rules->'minimumRetentionDays') <> 'number'
       OR jsonb_typeof(NEW.rules->'probeMaxAgeSeconds') <> 'number'
       OR jsonb_typeof(NEW.rules->'configurationBinding') NOT IN ('object', 'null')
       OR jsonb_typeof(NEW.rules->'trustBundleId') NOT IN ('string', 'null') THEN
        RAISE EXCEPTION 'Malformed native signature policy' USING ERRCODE = '23514';
    END IF;
    IF jsonb_array_length(NEW.rules->'requiredProviderKinds') > 3
       OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(NEW.rules->'requiredProviderKinds') value
                  WHERE value NOT IN ('DOCUSIGN', 'ADOBE_SIGN', 'CUSTOM'))
       OR (SELECT count(DISTINCT value) FROM jsonb_array_elements_text(
              NEW.rules->'requiredProviderKinds') value)
          <> jsonb_array_length(NEW.rules->'requiredProviderKinds')
       OR jsonb_array_length(NEW.rules->'allowedClassifications') > 3
       OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(NEW.rules->'allowedClassifications') value
                  WHERE value NOT IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
       OR (SELECT count(DISTINCT value) FROM jsonb_array_elements_text(
              NEW.rules->'allowedClassifications') value)
          <> jsonb_array_length(NEW.rules->'allowedClassifications')
       OR (NEW.rules->>'minimumRetentionDays') !~ '^[0-9]+$'
       OR (NEW.rules->>'minimumRetentionDays')::NUMERIC NOT BETWEEN 1 AND 36500
       OR (NEW.rules->>'probeMaxAgeSeconds') !~ '^[0-9]+$'
       OR (NEW.rules->>'probeMaxAgeSeconds')::NUMERIC NOT BETWEEN 60 AND 86400
       OR (NEW.rules->>'requireVerifiedProviderAccount') <> 'true'
       OR (NEW.rules->>'requireAuthenticatedWebhook') <> 'true' THEN
        RAISE EXCEPTION 'Invalid native signature policy values' USING ERRCODE = '23514';
    END IF;
    IF (NEW.rules->>'signingEnabled') = 'true'
       AND (jsonb_array_length(NEW.rules->'requiredProviderKinds') = 0
            OR jsonb_array_length(NEW.rules->'allowedClassifications') = 0
            OR jsonb_typeof(NEW.rules->'configurationBinding') <> 'object') THEN
        RAISE EXCEPTION 'Enabled native signature policy lacks explicit bindings' USING ERRCODE = '23514';
    END IF;
    IF (NEW.rules->>'signingEnabled') = 'true'
       AND ((NEW.rules->>'requireTrustedCertificateChain') = 'true'
        OR (NEW.rules->>'requireFreshRevocationEvidence') = 'true'
        OR (NEW.rules->>'requireTrustedTimestamp') = 'true')
       AND COALESCE(NEW.rules->>'trustBundleId', '')
           !~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$' THEN
        RAISE EXCEPTION 'Trust-bound policy lacks a canonical trust bundle' USING ERRCODE = '23514';
    END IF;
    IF jsonb_typeof(NEW.rules->'configurationBinding') = 'object'
       AND ((SELECT array_agg(key ORDER BY key)
             FROM jsonb_object_keys(NEW.rules->'configurationBinding') key)
            IS DISTINCT FROM ARRAY['sha256', 'sourceId', 'version']::TEXT[]
            OR COALESCE(NEW.rules->'configurationBinding'->>'sourceId', '')
               !~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
            OR COALESCE(NEW.rules->'configurationBinding'->>'sha256', '') !~ '^[a-f0-9]{64}$'
            OR COALESCE(NEW.rules->'configurationBinding'->>'version', '') !~ '^[0-9]+$'
            OR (NEW.rules->'configurationBinding'->>'version')::NUMERIC
               NOT BETWEEN 0 AND 9007199254740991) THEN
        RAISE EXCEPTION 'Invalid native signature configuration binding' USING ERRCODE = '23514';
    END IF;
    IF jsonb_typeof(NEW.rules->'trustBundleId') = 'string'
       AND (NEW.rules->>'trustBundleId')
           !~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$' THEN
        RAISE EXCEPTION 'Invalid native signature trust bundle binding' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION apr_signature_native.validate_publication() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE
    version_row apr_signature_provider_policy_versions%ROWTYPE;
BEGIN
    SELECT * INTO STRICT version_row FROM apr_signature_provider_policy_versions
     WHERE tenant_id = NEW.tenant_id AND resource_set_key = NEW.resource_set_key
       AND policy_id = NEW.policy_id AND version_id = NEW.version_id;
    IF (NEW.maker_user_id, NEW.maker_person_public_id,
        NEW.editor_user_id, NEW.editor_person_public_id)
       IS DISTINCT FROM
       (version_row.maker_user_id, version_row.maker_person_public_id,
        version_row.editor_user_id, version_row.editor_person_public_id) THEN
        RAISE EXCEPTION 'Publication identities changed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION apr_signature_native.guard_policy_head() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Native signature policy heads cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 0 OR NEW.draft_version_id IS NULL
           OR NEW.published_version_id IS NOT NULL THEN
            RAISE EXCEPTION 'Invalid native signature policy initialization' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id, NEW.resource_set_key, NEW.policy_id)
       IS DISTINCT FROM (OLD.tenant_id, OLD.resource_set_key, OLD.policy_id)
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Native signature policy CAS changed' USING ERRCODE = '23514';
    END IF;
    IF NEW.published_version_id IS NOT DISTINCT FROM OLD.published_version_id
       AND NEW.draft_version_id IS NOT NULL
       AND NEW.draft_version_id IS DISTINCT FROM OLD.draft_version_id THEN
        RETURN NEW;
    END IF;
    IF OLD.draft_version_id IS NOT NULL AND NEW.draft_version_id IS NULL
       AND NEW.published_version_id = OLD.draft_version_id
       AND EXISTS (SELECT 1 FROM apr_signature_provider_policy_publications p
                   WHERE p.tenant_id = NEW.tenant_id
                     AND p.resource_set_key = NEW.resource_set_key
                     AND p.policy_id = NEW.policy_id
                     AND p.version_id = NEW.published_version_id) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Invalid native signature policy transition' USING ERRCODE = '23514';
END $$;

CREATE FUNCTION apr_signature_native.guard_external_request() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE
    request_row apr_requests%ROWTYPE;
    payload_row apr_request_payloads%ROWTYPE;
    provider_row apr_signature_providers%ROWTYPE;
    expected_source_sha256 TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'External signature requests cannot be deleted' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT request_row FROM apr_requests
     WHERE tenant_id = NEW.tenant_id AND request_id = NEW.request_id;
    SELECT * INTO STRICT payload_row FROM apr_request_payloads
     WHERE tenant_id = NEW.tenant_id AND request_id = NEW.request_id;
    SELECT * INTO STRICT provider_row FROM apr_signature_providers
     WHERE tenant_id = NEW.tenant_id AND provider_id = NEW.provider_id;
    expected_source_sha256 := encode(sha256(convert_to(
        '{"contract":"DWP_EXTERNAL_SIGNATURE_SOURCE_V1"'
        || ',"dataClassification":"' || request_row.data_classification || '"'
        || ',"formVersionId":"' || request_row.form_version_id::TEXT || '"'
        || ',"payloadRevision":' || payload_row.schema_version::TEXT
        || ',"payloadSha256":"' || payload_row.payload_sha256 || '"'
        || ',"requestId":"' || request_row.request_id::TEXT || '"'
        || ',"requestVersion":' || request_row.version::TEXT
        || ',"resourceSetKey":"' || request_row.management_resource_set_key || '"'
        || ',"workflowVersionId":"' || request_row.workflow_version_id::TEXT || '"}',
        'UTF8')), 'hex');
    IF request_row.requester_user_id <> NEW.owner_user_id
       OR request_row.status <> 'APPROVED' OR request_row.deleted_at IS NOT NULL
       OR request_row.management_resource_set_key <> NEW.resource_set_key
       OR provider_row.management_resource_set_key <> NEW.resource_set_key
       OR provider_row.lifecycle_state <> 'ACTIVE'
       OR provider_row.version <> NEW.provider_version
       OR (SELECT array_agg(key ORDER BY key) FROM jsonb_object_keys(NEW.source) key)
          IS DISTINCT FROM ARRAY['dataClassification', 'formVersionId',
             'payloadRevision', 'payloadSha256', 'requestId', 'requestVersion',
             'resourceSetKey', 'sourceSha256', 'workflowVersionId']::TEXT[]
       OR NEW.source->>'requestId' <> NEW.request_id::TEXT
       OR NEW.source->>'requestVersion' <> request_row.version::TEXT
       OR NEW.source->>'resourceSetKey' <> NEW.resource_set_key
       OR NEW.source->>'dataClassification' <> request_row.data_classification
       OR NEW.source->>'workflowVersionId' <> request_row.workflow_version_id::TEXT
       OR NEW.source->>'formVersionId' <> request_row.form_version_id::TEXT
       OR NEW.source->>'payloadRevision' <> payload_row.schema_version::TEXT
       OR NEW.source->>'payloadSha256' <> payload_row.payload_sha256
       OR NEW.source->>'sourceSha256' <> NEW.source_sha256
       OR NEW.source_sha256 <> expected_source_sha256 THEN
        RAISE EXCEPTION 'External signature source is not current' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.tenant_id, NEW.resource_set_key, NEW.signature_request_id,
            NEW.request_id, NEW.owner_user_id, NEW.provider_id, NEW.provider_version,
            NEW.provider_sha256, NEW.configuration_id, NEW.configuration_version,
            NEW.configuration_sha256, NEW.policy_id, NEW.policy_version_id,
            NEW.policy_sha256, NEW.source, NEW.source_sha256, NEW.created_at)
           IS DISTINCT FROM
           (OLD.tenant_id, OLD.resource_set_key, OLD.signature_request_id,
            OLD.request_id, OLD.owner_user_id, OLD.provider_id, OLD.provider_version,
            OLD.provider_sha256, OLD.configuration_id, OLD.configuration_version,
            OLD.configuration_sha256, OLD.policy_id, OLD.policy_version_id,
            OLD.policy_sha256, OLD.source, OLD.source_sha256, OLD.created_at)
           OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
            RAISE EXCEPTION 'External signature request fence changed' USING ERRCODE = '23514';
        END IF;
        IF NOT (
              (OLD.state = 'PREPARED' AND NEW.state IN (
                  'HANDOVER_PENDING', 'OUT_FOR_SIGNATURE', 'UNKNOWN_REMOTE_OUTCOME',
                  'FAILED', 'CANCEL_PENDING', 'CANCELLED'))
           OR (OLD.state IN ('HANDOVER_PENDING', 'OUT_FOR_SIGNATURE',
                  'COMPLETION_PENDING', 'UNKNOWN_REMOTE_OUTCOME')
               AND NEW.state <> 'PREPARED')
           OR (OLD.state IN ('FAILED', 'CANCEL_PENDING')
               AND NEW.state IN ('CANCEL_PENDING', 'CANCELLED',
                  'UNKNOWN_REMOTE_OUTCOME', 'FAILED'))
        ) THEN
            RAISE EXCEPTION 'Invalid external signature state transition' USING ERRCODE = '23514';
        END IF;
        IF NEW.state IN ('FAILED', 'UNKNOWN_REMOTE_OUTCOME')
           AND jsonb_array_length(NEW.reason_codes) = 0 THEN
            RAISE EXCEPTION 'External signature failure lacks an exact reason' USING ERRCODE = '23514';
        END IF;
        IF OLD.remote_reference_sha256 IS NOT NULL
           AND NEW.remote_reference_sha256 IS DISTINCT FROM OLD.remote_reference_sha256 THEN
            RAISE EXCEPTION 'External signature provider reference changed' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.version <> 0 OR NEW.state <> 'PREPARED'
       OR NEW.remote_reference_sha256 IS NOT NULL OR NEW.reason_codes <> '[]'::jsonb
       THEN
        RAISE EXCEPTION 'Invalid external signature initialization' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION apr_signature_native.guard_external_event() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE
    request_row apr_external_signature_requests%ROWTYPE;
    previous_state VARCHAR(32);
BEGIN
    SELECT * INTO STRICT request_row FROM apr_external_signature_requests
     WHERE tenant_id = NEW.tenant_id AND resource_set_key = NEW.resource_set_key
       AND signature_request_id = NEW.signature_request_id
       AND owner_user_id = NEW.owner_user_id;
    IF NEW.sequence <> request_row.version OR NEW.state <> request_row.state
       OR NEW.occurred_at <> request_row.updated_at
       OR (NEW.state IN ('FAILED', 'UNKNOWN_REMOTE_OUTCOME')
           AND jsonb_array_length(NEW.reason_codes) = 0) THEN
        RAISE EXCEPTION 'External signature event does not match its fenced head'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.sequence = 0 THEN
        IF NEW.action <> 'CREATE' OR NEW.state <> 'PREPARED'
           OR NEW.evidence_id IS NOT NULL OR NEW.evidence_sha256 IS NOT NULL THEN
            RAISE EXCEPTION 'Invalid external signature creation event' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT state INTO STRICT previous_state FROM apr_external_signature_events
     WHERE tenant_id = NEW.tenant_id AND resource_set_key = NEW.resource_set_key
       AND signature_request_id = NEW.signature_request_id
       AND sequence = NEW.sequence - 1;
    IF NOT (
          (NEW.action = 'HANDOVER' AND previous_state = 'PREPARED')
       OR (NEW.action = 'REFRESH' AND previous_state IN (
              'HANDOVER_PENDING', 'OUT_FOR_SIGNATURE',
              'COMPLETION_PENDING', 'UNKNOWN_REMOTE_OUTCOME'))
       OR (NEW.action = 'CANCEL' AND previous_state NOT IN (
              'COMPLETED_VERIFIED', 'CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'External signature event action changed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION apr_signature_native.guard_external_artifact() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM apr_external_signature_requests request
         WHERE request.tenant_id = NEW.tenant_id
           AND request.resource_set_key = NEW.resource_set_key
           AND request.signature_request_id = NEW.signature_request_id
           AND request.owner_user_id = NEW.owner_user_id
           AND request.state = 'COMPLETED_VERIFIED'
    ) THEN
        RAISE EXCEPTION 'External signature artifact lacks a verified completion head'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_apr_signature_policy_rules
    BEFORE INSERT ON apr_signature_provider_policy_versions
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.validate_rules();
CREATE TRIGGER trg_apr_signature_policy_version_immutable
    BEFORE UPDATE OR DELETE ON apr_signature_provider_policy_versions
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();
CREATE TRIGGER trg_apr_signature_policy_publication_validate
    BEFORE INSERT ON apr_signature_provider_policy_publications
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.validate_publication();
CREATE TRIGGER trg_apr_signature_policy_publication_immutable
    BEFORE UPDATE OR DELETE ON apr_signature_provider_policy_publications
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();
CREATE TRIGGER trg_apr_signature_policy_head
    BEFORE INSERT OR UPDATE OR DELETE ON apr_signature_provider_policy_heads
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.guard_policy_head();
CREATE TRIGGER trg_apr_signature_command_immutable
    BEFORE UPDATE OR DELETE ON apr_signature_native_commands
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();
CREATE TRIGGER trg_apr_signature_probe_run_immutable
    BEFORE UPDATE OR DELETE ON apr_signature_provider_probe_runs
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();
CREATE TRIGGER trg_apr_signature_probe_observation_immutable
    BEFORE UPDATE OR DELETE ON apr_signature_provider_probe_observations
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();
CREATE TRIGGER trg_apr_signature_inspection_immutable
    BEFORE UPDATE OR DELETE ON apr_signature_provider_inspections
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();
CREATE TRIGGER trg_apr_external_signature_request
    BEFORE INSERT OR UPDATE OR DELETE ON apr_external_signature_requests
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.guard_external_request();
CREATE TRIGGER trg_apr_external_signature_event_guard
    BEFORE INSERT ON apr_external_signature_events
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.guard_external_event();
CREATE TRIGGER trg_apr_external_signature_event_immutable
    BEFORE UPDATE OR DELETE ON apr_external_signature_events
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();
CREATE TRIGGER trg_apr_external_signature_artifact_guard
    BEFORE INSERT ON apr_external_signature_artifacts
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.guard_external_artifact();
CREATE TRIGGER trg_apr_external_signature_artifact_immutable
    BEFORE UPDATE OR DELETE ON apr_external_signature_artifacts
    FOR EACH ROW EXECUTE FUNCTION apr_signature_native.immutable();

ALTER TABLE apr_signature_provider_policy_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_policy_heads FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_policy_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_policy_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_policy_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_policy_publications FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_native_commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_native_commands FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_probe_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_probe_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_probe_observations ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_probe_observations FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_inspections ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_signature_provider_inspections FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_external_signature_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_external_signature_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_external_signature_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_external_signature_events FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_external_signature_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_external_signature_artifacts FORCE ROW LEVEL SECURITY;

CREATE POLICY apr_signature_policy_head_scope ON apr_signature_provider_policy_heads
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));
CREATE POLICY apr_signature_policy_version_scope ON apr_signature_provider_policy_versions
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));
CREATE POLICY apr_signature_policy_publication_scope ON apr_signature_provider_policy_publications
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));
CREATE POLICY apr_signature_command_actor ON apr_signature_native_commands
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND actor_user_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.actor', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND actor_user_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.actor', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));
CREATE POLICY apr_signature_probe_run_scope ON apr_signature_provider_probe_runs
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));
CREATE POLICY apr_signature_probe_observation_scope ON apr_signature_provider_probe_observations
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));
CREATE POLICY apr_signature_inspection_scope ON apr_signature_provider_inspections
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));
CREATE POLICY apr_external_signature_request_actor ON apr_external_signature_requests
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND owner_user_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.actor', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND owner_user_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.actor', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));
CREATE POLICY apr_external_signature_event_actor ON apr_external_signature_events
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND owner_user_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.actor', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND owner_user_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.actor', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));
CREATE POLICY apr_external_signature_artifact_actor ON apr_external_signature_artifacts
    USING (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND owner_user_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.actor', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true))
    WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.tenant', true), ''), '0')::BIGINT
       AND owner_user_id = COALESCE(NULLIF(current_setting('dwp.approval.signature.actor', true), ''), '0')::BIGINT
       AND resource_set_key = current_setting('dwp.approval.signature.scope', true));

CREATE INDEX ix_apr_signature_probe_history
    ON apr_signature_provider_probe_runs(tenant_id, resource_set_key, completed_at DESC, probe_run_id DESC);
CREATE INDEX ix_apr_signature_provider_observation_latest
    ON apr_signature_provider_probe_observations(tenant_id, resource_set_key, provider_id, observed_at DESC);
CREATE INDEX ix_apr_signature_inspection_latest
    ON apr_signature_provider_inspections(tenant_id, resource_set_key, inspection_kind, observed_at DESC);
CREATE INDEX ix_apr_external_signature_request_owner
    ON apr_external_signature_requests(tenant_id, owner_user_id, updated_at DESC);

COMMENT ON SCHEMA apr_signature_native IS
    'Private trigger functions for native external-signature governance; no provider secret material.';
COMMENT ON TABLE apr_signature_provider_policy_heads IS
    'Tenant/resource-set policy CAS head. No policy or readiness seed is installed by this migration.';
COMMENT ON TABLE apr_signature_native_commands IS
    'Actor-isolated exact idempotency receipts; provider credentials and remote payloads are prohibited.';
COMMENT ON TABLE apr_external_signature_artifacts IS
    'Metadata-only retained provider artifacts. Bytes remain in configured WORM storage and are never fabricated locally.';
