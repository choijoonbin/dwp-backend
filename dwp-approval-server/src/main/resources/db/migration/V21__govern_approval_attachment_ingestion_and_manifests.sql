CREATE TABLE apr_attachment_policy_heads (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL CHECK(resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    policy_id UUID NOT NULL UNIQUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    published_revision INTEGER NOT NULL DEFAULT 0,
    pending_revision INTEGER,
    PRIMARY KEY(tenant_id,resource_set_key), UNIQUE(tenant_id,policy_id)
);
CREATE TABLE apr_attachment_policy_versions (
    tenant_id BIGINT NOT NULL, policy_id UUID NOT NULL, revision INTEGER NOT NULL CHECK(revision>=0),
    rules JSONB NOT NULL CHECK(jsonb_typeof(rules)='object'), rules_sha256 CHAR(64) NOT NULL CHECK(rules_sha256 ~ '^[a-f0-9]{64}$'),
    maker_user_id BIGINT, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,policy_id,revision), FOREIGN KEY(tenant_id,policy_id) REFERENCES apr_attachment_policy_heads(tenant_id,policy_id),
    CHECK((revision=0 AND maker_user_id IS NULL) OR (revision>0 AND maker_user_id>0))
);
ALTER TABLE apr_attachment_policy_heads ADD CONSTRAINT fk_apr_attachment_published FOREIGN KEY(tenant_id,policy_id,published_revision)
    REFERENCES apr_attachment_policy_versions(tenant_id,policy_id,revision) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE apr_attachment_policy_heads ADD CONSTRAINT fk_apr_attachment_pending FOREIGN KEY(tenant_id,policy_id,pending_revision)
    REFERENCES apr_attachment_policy_versions(tenant_id,policy_id,revision) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE apr_attachment_policy_publications (
    publication_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, policy_id UUID NOT NULL, revision INTEGER NOT NULL,
    maker_user_id BIGINT NOT NULL, checker_user_id BIGINT NOT NULL CHECK(checker_user_id<>maker_user_id),
    review_comment VARCHAR(1000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,policy_id,revision), FOREIGN KEY(tenant_id,policy_id,revision) REFERENCES apr_attachment_policy_versions(tenant_id,policy_id,revision)
);
CREATE TABLE apr_attachment_uploads (
    upload_id UUID PRIMARY KEY, attachment_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL, request_id UUID NOT NULL, uploader_user_id BIGINT NOT NULL CHECK(uploader_user_id>0),
    request_version BIGINT NOT NULL, payload_revision INTEGER NOT NULL, policy_id UUID NOT NULL, policy_version BIGINT NOT NULL,
    file_name VARCHAR(160) NOT NULL, media_type VARCHAR(160) NOT NULL, size_bytes BIGINT NOT NULL CHECK(size_bytes BETWEEN 1 AND 26214400),
    content_sha256 CHAR(64) NOT NULL CHECK(content_sha256 ~ '^[a-f0-9]{64}$'),
    object_key VARCHAR(200) NOT NULL UNIQUE, object_version VARCHAR(1024),
    state VARCHAR(32) NOT NULL CHECK(state IN('RESERVED','UPLOADING','STORAGE_RECONCILING','QUARANTINED','SCANNING','AVAILABLE','REJECTED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0, generation BIGINT NOT NULL DEFAULT 0, lease_token UUID, lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5), reason VARCHAR(120),
    av_state VARCHAR(32) NOT NULL DEFAULT 'NOT_CHECKED', passive_content_state VARCHAR(32) NOT NULL DEFAULT 'NOT_CHECKED',
    engine_version VARCHAR(120), definitions_at TIMESTAMPTZ, scanned_at TIMESTAMPTZ,
    parser_version VARCHAR(120), expires_at TIMESTAMPTZ NOT NULL, retain_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,request_id,attachment_id), UNIQUE(tenant_id,request_id,upload_id), UNIQUE(tenant_id,upload_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES apr_requests(tenant_id,request_id),
    FOREIGN KEY(tenant_id,policy_id) REFERENCES apr_attachment_policy_heads(tenant_id,policy_id),
    CHECK(state<>'AVAILABLE' OR (av_state='AV_CLEAR' AND passive_content_state='PASSIVE_ALLOWED' AND object_version IS NOT NULL)),
    CHECK(state NOT IN('UPLOADING','SCANNING') OR (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_apr_attachment_scan ON apr_attachment_uploads(state,lease_until,created_at) WHERE state IN('QUARANTINED','SCANNING','STORAGE_RECONCILING');
CREATE TABLE apr_attachment_selections (
    tenant_id BIGINT NOT NULL, request_id UUID NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    attachment_ids JSONB NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(attachment_ids)='array'),
    PRIMARY KEY(tenant_id,request_id), FOREIGN KEY(tenant_id,request_id) REFERENCES apr_requests(tenant_id,request_id)
);
CREATE TABLE apr_attachment_preparations (
    preparation_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, request_id UUID NOT NULL,
    source_request_version BIGINT NOT NULL, source_payload_revision INTEGER NOT NULL, source_payload_sha256 CHAR(64) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL, selection_version BIGINT NOT NULL, policy_id UUID NOT NULL, policy_version BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL, items JSONB NOT NULL CHECK(jsonb_typeof(items)='array'),
    expires_at TIMESTAMPTZ NOT NULL, consumed_revision INTEGER,
    FOREIGN KEY(tenant_id,request_id) REFERENCES apr_requests(tenant_id,request_id),
    FOREIGN KEY(tenant_id,policy_id) REFERENCES apr_attachment_policy_heads(tenant_id,policy_id)
);
CREATE TABLE apr_attachment_manifests (
    tenant_id BIGINT NOT NULL, request_id UUID NOT NULL, payload_revision INTEGER NOT NULL,
    payload_sha256 CHAR(64) NOT NULL, manifest_sha256 CHAR(64) NOT NULL,
    items JSONB NOT NULL CHECK(jsonb_typeof(items)='array'), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,request_id,payload_revision),
    FOREIGN KEY(tenant_id,request_id,payload_revision) REFERENCES apr_request_payload_versions(tenant_id,request_id,revision_number)
);
INSERT INTO apr_attachment_manifests(tenant_id,request_id,payload_revision,payload_sha256,manifest_sha256,items)
SELECT tenant_id,request_id,revision_number,payload_sha256,encode(sha256(convert_to('[]','UTF8')),'hex'),'[]'::jsonb FROM apr_request_payload_versions;
CREATE TABLE apr_attachment_download_grants (
    grant_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, request_id UUID NOT NULL, attachment_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL, owner_type VARCHAR(8) NOT NULL CHECK(owner_type IN('REQUEST','TASK')), owner_id UUID NOT NULL,
    payload_revision INTEGER NOT NULL, expected_owner_version BIGINT NOT NULL, payload_sha256 CHAR(64) NOT NULL, manifest_sha256 CHAR(64) NOT NULL,
    policy_id UUID NOT NULL, policy_version BIGINT NOT NULL,
    content_sha256 CHAR(64) NOT NULL, object_version VARCHAR(1024) NOT NULL, expires_at TIMESTAMPTZ NOT NULL, consumed_at TIMESTAMPTZ,
    generation BIGINT NOT NULL DEFAULT 0, lease_token UUID, lease_until TIMESTAMPTZ,
    FOREIGN KEY(tenant_id,request_id,attachment_id) REFERENCES apr_attachment_uploads(tenant_id,request_id,attachment_id)
);
CREATE TABLE apr_attachment_command_receipts (
    tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL, route VARCHAR(400) NOT NULL, idempotency_key VARCHAR(120) NOT NULL,
    fingerprint CHAR(64) NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'), metadata JSONB NOT NULL CHECK(jsonb_typeof(metadata)='object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), retain_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(tenant_id,actor_user_id,route,idempotency_key)
);
CREATE TABLE apr_attachment_cleanup_journal (
    cleanup_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, upload_id UUID NOT NULL, object_key VARCHAR(200) NOT NULL, object_version VARCHAR(1024),
    reason VARCHAR(120) NOT NULL, state VARCHAR(32) NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ,
    FOREIGN KEY(tenant_id,upload_id) REFERENCES apr_attachment_uploads(tenant_id,upload_id)
);
CREATE FUNCTION protect_approval_attachment_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Approval attachment evidence is immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER protect_apr_attachment_manifests BEFORE UPDATE OR DELETE ON apr_attachment_manifests FOR EACH ROW EXECUTE FUNCTION protect_approval_attachment_evidence();
CREATE TRIGGER protect_apr_attachment_receipts BEFORE UPDATE OR DELETE ON apr_attachment_command_receipts FOR EACH ROW EXECUTE FUNCTION protect_approval_attachment_evidence();
CREATE TRIGGER protect_apr_attachment_policy_versions BEFORE UPDATE OR DELETE ON apr_attachment_policy_versions FOR EACH ROW EXECUTE FUNCTION protect_approval_attachment_evidence();
CREATE TRIGGER protect_apr_attachment_policy_publications BEFORE UPDATE OR DELETE ON apr_attachment_policy_publications FOR EACH ROW EXECUTE FUNCTION protect_approval_attachment_evidence();
CREATE FUNCTION protect_approval_attachment_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (to_jsonb(NEW)-'consumed_revision') IS DISTINCT FROM (to_jsonb(OLD)-'consumed_revision')
       OR (OLD.consumed_revision IS NOT NULL AND NEW.consumed_revision IS DISTINCT FROM OLD.consumed_revision) THEN
        RAISE EXCEPTION 'Approval attachment preparation bindings are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_apr_attachment_preparations BEFORE UPDATE OR DELETE ON apr_attachment_preparations FOR EACH ROW EXECUTE FUNCTION protect_approval_attachment_preparation();
CREATE FUNCTION protect_approval_attachment_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.tenant_id,NEW.request_id,NEW.uploader_user_id,NEW.attachment_id,NEW.upload_id,NEW.file_name,NEW.media_type,NEW.size_bytes,NEW.content_sha256,NEW.object_key,NEW.policy_id,NEW.policy_version)
       IS DISTINCT FROM ROW(OLD.tenant_id,OLD.request_id,OLD.uploader_user_id,OLD.attachment_id,OLD.upload_id,OLD.file_name,OLD.media_type,OLD.size_bytes,OLD.content_sha256,OLD.object_key,OLD.policy_id,OLD.policy_version)
       OR (OLD.object_version IS NOT NULL AND NEW.object_version IS DISTINCT FROM OLD.object_version) THEN
        RAISE EXCEPTION 'Approval attachment content identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_apr_attachment_identity BEFORE UPDATE ON apr_attachment_uploads FOR EACH ROW EXECUTE FUNCTION protect_approval_attachment_identity();
