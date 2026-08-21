CREATE TABLE ntf_tenant_template_revisions (
    template_revision_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    type_version_id UUID NOT NULL REFERENCES ntf_notification_type_versions(type_version_id),
    channel VARCHAR(30) NOT NULL CHECK (
        channel IN ('IN_APP', 'EMAIL', 'WEB_PUSH', 'MOBILE_PUSH', 'TEAMS', 'SLACK')
    ),
    locale VARCHAR(35) NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    title_template VARCHAR(300) NOT NULL,
    preview_template VARCHAR(600) NOT NULL,
    body_template TEXT NOT NULL CHECK (char_length(body_template) <= 4000),
    action_label VARCHAR(100) NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    checksum VARCHAR(64) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by BIGINT NOT NULL,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    approval_reason VARCHAR(500),
    supersedes_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_tenant_template_revision
        UNIQUE (tenant_id, type_version_id, channel, locale, revision),
    CONSTRAINT uq_ntf_tenant_template_identity
        UNIQUE (tenant_id, template_revision_id),
    CONSTRAINT fk_ntf_tenant_template_supersedes
        FOREIGN KEY (tenant_id, supersedes_revision_id)
        REFERENCES ntf_tenant_template_revisions (tenant_id, template_revision_id),
    CONSTRAINT ck_ntf_tenant_template_approval CHECK (
        (state = 'PUBLISHED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND approval_reason IS NOT NULL)
        OR (state <> 'PUBLISHED' AND approved_by IS NULL AND approved_at IS NULL
            AND approval_reason IS NULL)
    ),
    CONSTRAINT ck_ntf_tenant_template_four_eyes CHECK (
        approved_by IS NULL OR approved_by <> created_by
    )
);

CREATE UNIQUE INDEX uq_ntf_tenant_template_open_draft
    ON ntf_tenant_template_revisions (tenant_id, type_version_id, channel, locale)
    WHERE state = 'DRAFT';

CREATE INDEX ix_ntf_tenant_template_effective
    ON ntf_tenant_template_revisions (
        tenant_id, type_version_id, channel, locale, state, revision DESC
    );

CREATE OR REPLACE FUNCTION ntf_guard_tenant_template_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Tenant notification template revisions are immutable';
    END IF;
    IF OLD.state <> 'DRAFT' THEN
        RAISE EXCEPTION 'Published or retired notification template revisions are immutable';
    END IF;
    IF NEW.template_revision_id IS DISTINCT FROM OLD.template_revision_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.type_version_id IS DISTINCT FROM OLD.type_version_id
       OR NEW.channel IS DISTINCT FROM OLD.channel
       OR NEW.locale IS DISTINCT FROM OLD.locale
       OR NEW.revision IS DISTINCT FROM OLD.revision
       OR NEW.title_template IS DISTINCT FROM OLD.title_template
       OR NEW.preview_template IS DISTINCT FROM OLD.preview_template
       OR NEW.body_template IS DISTINCT FROM OLD.body_template
       OR NEW.action_label IS DISTINCT FROM OLD.action_label
       OR NEW.checksum IS DISTINCT FROM OLD.checksum
       OR NEW.change_reason IS DISTINCT FROM OLD.change_reason
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.supersedes_revision_id IS DISTINCT FROM OLD.supersedes_revision_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.state NOT IN ('PUBLISHED', 'RETIRED') THEN
        RAISE EXCEPTION 'Notification template draft content cannot be mutated';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_ntf_tenant_template_revision_guard
BEFORE UPDATE OR DELETE ON ntf_tenant_template_revisions
FOR EACH ROW EXECUTE FUNCTION ntf_guard_tenant_template_revision();

ALTER TABLE ntf_tenant_template_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_tenant_template_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_tenant_template_worker_scope ON ntf_tenant_template_revisions
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

ALTER TABLE ntf_user_notifications
    ADD COLUMN template_override_revision_id UUID,
    ADD CONSTRAINT fk_ntf_user_notification_template_override
        FOREIGN KEY (tenant_id, template_override_revision_id)
        REFERENCES ntf_tenant_template_revisions (tenant_id, template_revision_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON ntf_tenant_template_revisions
    TO dwp_notification_worker;
REVOKE ALL ON FUNCTION ntf_guard_tenant_template_revision() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ntf_guard_tenant_template_revision()
    TO dwp_notification_worker;

COMMENT ON TABLE ntf_tenant_template_revisions IS
    'Immutable tenant-local presentation overrides for Provider-owned notification contracts.';
COMMENT ON COLUMN ntf_user_notifications.template_override_revision_id IS
    'Exact tenant template revision used to render the durable in-app projection.';
