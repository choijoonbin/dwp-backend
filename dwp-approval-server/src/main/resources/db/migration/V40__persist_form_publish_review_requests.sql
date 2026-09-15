CREATE TABLE apr_form_publish_review_requests (
    tenant_id BIGINT NOT NULL,
    review_request_id UUID NOT NULL,
    form_id UUID NOT NULL,
    draft_form_version_id UUID NOT NULL,
    base_published_form_version_id UUID,
    management_resource_set_key VARCHAR(80) NOT NULL,
    request_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    request_version BIGINT NOT NULL DEFAULT 0,
    maker_user_id BIGINT NOT NULL,
    last_editor_user_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NOT NULL,
    reviewer_person_public_id UUID NOT NULL,
    form_revision BIGINT NOT NULL,
    workspace_revision BIGINT NOT NULL,
    schema_sha256 CHAR(64) NOT NULL,
    review_content_sha256 CHAR(64) NOT NULL,
    request_reason VARCHAR(1000) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    decided_at TIMESTAMPTZ,
    decided_by BIGINT,
    decision_reason VARCHAR(1000),
    PRIMARY KEY (tenant_id, review_request_id),
    FOREIGN KEY (tenant_id, form_id)
        REFERENCES apr_forms (tenant_id, form_id),
    FOREIGN KEY (tenant_id, form_id, draft_form_version_id)
        REFERENCES apr_form_versions (tenant_id, form_id, form_version_id),
    FOREIGN KEY (tenant_id, form_id, base_published_form_version_id)
        REFERENCES apr_form_versions (tenant_id, form_id, form_version_id),
    CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CHECK (request_status IN ('PENDING', 'PUBLISHED', 'REJECTED', 'SUPERSEDED')),
    CHECK (request_version BETWEEN 0 AND 9007199254740991),
    CHECK (form_revision BETWEEN 0 AND 9007199254740991),
    CHECK (workspace_revision BETWEEN 0 AND 9007199254740991),
    CHECK (schema_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (review_content_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (length(btrim(request_reason)) BETWEEN 10 AND 1000
        AND request_reason = btrim(request_reason)),
    CHECK (maker_user_id > 0 AND last_editor_user_id > 0 AND reviewer_user_id > 0),
    CHECK (reviewer_user_id <> maker_user_id AND reviewer_user_id <> last_editor_user_id),
    CHECK ((request_status = 'PENDING' AND decided_at IS NULL AND decided_by IS NULL
            AND decision_reason IS NULL)
        OR (request_status <> 'PENDING' AND decided_at IS NOT NULL AND decided_by IS NOT NULL)),
    CHECK (decision_reason IS NULL OR (length(btrim(decision_reason)) BETWEEN 10 AND 1000
        AND decision_reason = btrim(decision_reason)))
);

CREATE UNIQUE INDEX uk_apr_form_publish_review_pending
    ON apr_form_publish_review_requests (tenant_id, form_id)
    WHERE request_status = 'PENDING';

CREATE INDEX idx_apr_form_publish_review_assignee
    ON apr_form_publish_review_requests (
        tenant_id, management_resource_set_key, reviewer_user_id, request_status, requested_at DESC);

ALTER TABLE apr_form_lifecycle_events
    DROP CONSTRAINT apr_form_lifecycle_events_action_check;
ALTER TABLE apr_form_lifecycle_events
    ADD CONSTRAINT apr_form_lifecycle_events_action_check
    CHECK (action IN ('BRANCH', 'UPDATE_DRAFT', 'PUBLISH', 'RETIRE', 'REINSTATE',
        'REVIEW_REQUEST', 'REVIEW_REJECT'));

COMMENT ON TABLE apr_form_publish_review_requests IS
    'Durable APR-12 maker-checker assignments. PENDING rows pin the exact draft and designated publisher.';

