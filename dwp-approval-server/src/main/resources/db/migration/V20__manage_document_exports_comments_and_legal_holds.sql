CREATE TABLE apr_document_policy_heads (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL UNIQUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    published_revision INTEGER NOT NULL DEFAULT 0 CHECK (published_revision >= 0),
    pending_revision INTEGER,
    PRIMARY KEY (tenant_id, resource_set_key),
    UNIQUE (tenant_id, policy_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (pending_revision IS NULL OR pending_revision > published_revision)
);

CREATE TABLE apr_document_policy_versions (
    tenant_id BIGINT NOT NULL,
    policy_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 0),
    rules JSONB NOT NULL CHECK (jsonb_typeof(rules) = 'object'),
    rules_sha256 CHAR(64) NOT NULL CHECK (rules_sha256 ~ '^[a-f0-9]{64}$'),
    maker_user_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, policy_id, revision),
    FOREIGN KEY (tenant_id, policy_id) REFERENCES apr_document_policy_heads(tenant_id, policy_id),
    CHECK ((revision = 0 AND maker_user_id IS NULL) OR (revision > 0 AND maker_user_id > 0))
);
ALTER TABLE apr_document_policy_heads ADD CONSTRAINT fk_apr_document_policy_published
    FOREIGN KEY (tenant_id, policy_id, published_revision)
    REFERENCES apr_document_policy_versions(tenant_id, policy_id, revision)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE apr_document_policy_heads ADD CONSTRAINT fk_apr_document_policy_pending
    FOREIGN KEY (tenant_id, policy_id, pending_revision)
    REFERENCES apr_document_policy_versions(tenant_id, policy_id, revision)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE apr_document_policy_publications (
    publication_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    policy_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    maker_user_id BIGINT NOT NULL,
    checker_user_id BIGINT NOT NULL,
    review_comment VARCHAR(1000) NOT NULL CHECK (length(btrim(review_comment)) >= 10),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, policy_id, revision),
    FOREIGN KEY (tenant_id, policy_id, revision)
        REFERENCES apr_document_policy_versions(tenant_id, policy_id, revision),
    CHECK (maker_user_id > 0 AND checker_user_id > 0 AND maker_user_id <> checker_user_id)
);

CREATE TABLE apr_document_heads (
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    comments_version BIGINT NOT NULL DEFAULT 0 CHECK (comments_version >= 0),
    hold_version BIGINT NOT NULL DEFAULT 0 CHECK (hold_version >= 0),
    hold_active BOOLEAN NOT NULL DEFAULT FALSE,
    pending_hold_id UUID,
    retain_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, request_id) REFERENCES apr_requests(tenant_id, request_id)
);
ALTER TABLE apr_tasks ADD CONSTRAINT uk_apr_document_task_request_scope UNIQUE (tenant_id, request_id, task_id);
CREATE TABLE apr_document_comments (
    comment_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    source_task_id UUID,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    author_user_id BIGINT NOT NULL CHECK (author_user_id > 0),
    comment_text VARCHAR(2000) NOT NULL CHECK (length(btrim(comment_text)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    retain_until TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, request_id, sequence),
    FOREIGN KEY (tenant_id, request_id) REFERENCES apr_document_heads(tenant_id, request_id),
    FOREIGN KEY (tenant_id, request_id, source_task_id) REFERENCES apr_tasks(tenant_id, request_id, task_id),
    CHECK (retain_until > created_at)
);
CREATE TABLE apr_document_hold_proposals (
    proposal_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    operation VARCHAR(10) NOT NULL CHECK (operation IN ('PLACE', 'RELEASE')),
    reason VARCHAR(1000) NOT NULL CHECK (length(btrim(reason)) >= 10),
    maker_user_id BIGINT NOT NULL CHECK (maker_user_id > 0),
    base_version BIGINT NOT NULL CHECK (base_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, request_id, proposal_id),
    FOREIGN KEY (tenant_id, request_id) REFERENCES apr_document_heads(tenant_id, request_id)
);
ALTER TABLE apr_document_heads ADD CONSTRAINT fk_apr_document_pending_hold
    FOREIGN KEY (tenant_id, request_id, pending_hold_id)
    REFERENCES apr_document_hold_proposals(tenant_id, request_id, proposal_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE apr_document_hold_journal (
    entry_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    checker_user_id BIGINT NOT NULL CHECK (checker_user_id > 0),
    review_comment VARCHAR(1000) NOT NULL CHECK (length(btrim(review_comment)) >= 10),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, request_id, version),
    UNIQUE (tenant_id, request_id, proposal_id),
    FOREIGN KEY (tenant_id, request_id, proposal_id)
        REFERENCES apr_document_hold_proposals(tenant_id, request_id, proposal_id)
);

CREATE TABLE apr_document_command_receipts (
    receipt_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    route_key VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,120}$'),
    fingerprint CHAR(64) NOT NULL CHECK (fingerprint ~ '^[a-f0-9]{64}$'),
    metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    retain_until TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, actor_user_id, route_key, idempotency_key),
    CHECK (retain_until > created_at),
    CHECK (NOT (metadata ?| ARRAY['payload', 'content', 'text', 'comments', 'evidence']))
);

CREATE FUNCTION apr_document_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Approval document journal entries are append-only' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER trg_apr_document_policy_versions_immutable BEFORE UPDATE OR DELETE ON apr_document_policy_versions
    FOR EACH ROW EXECUTE FUNCTION apr_document_immutable();
CREATE TRIGGER trg_apr_document_publications_immutable BEFORE UPDATE OR DELETE ON apr_document_policy_publications
    FOR EACH ROW EXECUTE FUNCTION apr_document_immutable();
CREATE TRIGGER trg_apr_document_comments_immutable BEFORE UPDATE OR DELETE ON apr_document_comments
    FOR EACH ROW EXECUTE FUNCTION apr_document_immutable();
CREATE TRIGGER trg_apr_document_hold_proposals_immutable BEFORE UPDATE OR DELETE ON apr_document_hold_proposals
    FOR EACH ROW EXECUTE FUNCTION apr_document_immutable();
CREATE TRIGGER trg_apr_document_hold_journal_immutable BEFORE UPDATE OR DELETE ON apr_document_hold_journal
    FOR EACH ROW EXECUTE FUNCTION apr_document_immutable();
CREATE TRIGGER trg_apr_document_receipts_immutable BEFORE UPDATE OR DELETE ON apr_document_command_receipts
    FOR EACH ROW EXECUTE FUNCTION apr_document_immutable();

CREATE FUNCTION apr_document_check_hold_checker() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM apr_document_hold_proposals
        WHERE tenant_id = NEW.tenant_id AND request_id = NEW.request_id
          AND proposal_id = NEW.proposal_id AND maker_user_id = NEW.checker_user_id) THEN
        RAISE EXCEPTION 'Legal hold requires an independent checker' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_apr_document_independent_hold_checker BEFORE INSERT ON apr_document_hold_journal
    FOR EACH ROW EXECUTE FUNCTION apr_document_check_hold_checker();

COMMENT ON TABLE apr_document_command_receipts IS
    'Metadata-only replay references; never a plaintext document or a substitute for current entitlement.';
COMMENT ON TABLE apr_document_heads IS
    'Retention deadline and dual-control hold head. No purge worker or WORM/KMS assurance is implied.';
