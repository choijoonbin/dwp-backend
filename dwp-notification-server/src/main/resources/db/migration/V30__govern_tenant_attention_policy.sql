CREATE OR REPLACE FUNCTION ntf_attention_topics_are_canonical(topics JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    topic TEXT;
    topic_count INTEGER := 0;
    distinct_count INTEGER;
BEGIN
    IF topics IS NULL OR jsonb_typeof(topics) <> 'array'
       OR jsonb_array_length(topics) > 100 THEN
        RETURN FALSE;
    END IF;
    FOR topic IN SELECT jsonb_array_elements_text(topics)
    LOOP
        topic_count := topic_count + 1;
        IF topic !~ '^#[a-z0-9][a-z0-9._-]{1,79}$' THEN
            RETURN FALSE;
        END IF;
    END LOOP;
    SELECT COUNT(DISTINCT value)
      INTO distinct_count
      FROM jsonb_array_elements_text(topics);
    RETURN topic_count = distinct_count;
END
$$;

CREATE TABLE ntf_attention_governance_revisions (
    governance_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (
        state IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'REJECTED', 'WITHDRAWN')
    ),
    max_active_user_rules INTEGER NOT NULL CHECK (
        max_active_user_rules BETWEEN 1 AND 500
    ),
    max_vip_rules INTEGER NOT NULL CHECK (
        max_vip_rules BETWEEN 0 AND 500
        AND max_vip_rules <= max_active_user_rules
    ),
    max_follow_rules INTEGER NOT NULL CHECK (
        max_follow_rules BETWEEN 0 AND 500
        AND max_follow_rules <= max_active_user_rules
    ),
    approved_topic_allowlist JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (
        ntf_attention_topics_are_canonical(approved_topic_allowlist)
    ),
    mandatory_policy_precedence BOOLEAN NOT NULL,
    minimum_analytics_cohort INTEGER NOT NULL CHECK (
        minimum_analytics_cohort BETWEEN 10 AND 10000
    ),
    independent_reviewer_required BOOLEAN NOT NULL,
    revision_number BIGINT NOT NULL CHECK (revision_number > 0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    change_reason VARCHAR(500) NOT NULL CHECK (
        length(change_reason) BETWEEN 10 AND 500
        AND change_reason = trim(change_reason)
    ),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decision_reason VARCHAR(500),
    supersedes_governance_id UUID,
    CONSTRAINT uq_ntf_attention_governance_tenant_id
        UNIQUE (tenant_id, governance_id),
    CONSTRAINT uq_ntf_attention_governance_revision
        UNIQUE (tenant_id, revision_number),
    CONSTRAINT fk_ntf_attention_governance_supersedes
        FOREIGN KEY (tenant_id, supersedes_governance_id)
        REFERENCES ntf_attention_governance_revisions (tenant_id, governance_id),
    CONSTRAINT ck_ntf_attention_governance_decision_reason CHECK (
        decision_reason IS NULL OR (
            length(decision_reason) BETWEEN 10 AND 500
            AND decision_reason = trim(decision_reason)
        )
    ),
    CONSTRAINT ck_ntf_attention_governance_approval CHECK (
        (state IN ('PUBLISHED', 'SUPERSEDED')
            AND approved_by IS NOT NULL
            AND approved_at IS NOT NULL
            AND approved_by <> created_by
            AND mandatory_policy_precedence
            AND independent_reviewer_required
            AND decision_reason IS NOT NULL)
        OR (state NOT IN ('PUBLISHED', 'SUPERSEDED')
            AND approved_by IS NULL AND approved_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_ntf_attention_governance_open_draft
    ON ntf_attention_governance_revisions (tenant_id)
    WHERE state = 'DRAFT';

CREATE UNIQUE INDEX uq_ntf_attention_governance_published
    ON ntf_attention_governance_revisions (tenant_id)
    WHERE state = 'PUBLISHED';

CREATE INDEX ix_ntf_attention_governance_history
    ON ntf_attention_governance_revisions (
        tenant_id, revision_number DESC, updated_at DESC
    );

CREATE OR REPLACE FUNCTION ntf_guard_attention_governance_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Attention governance revisions are immutable';
    END IF;
    IF OLD.state NOT IN ('DRAFT', 'PUBLISHED') THEN
        RAISE EXCEPTION 'Final attention governance decisions are immutable';
    END IF;
    IF OLD.state = 'DRAFT' AND NEW.state NOT IN ('PUBLISHED', 'REJECTED', 'WITHDRAWN') THEN
        RAISE EXCEPTION 'Invalid attention governance draft transition';
    END IF;
    IF OLD.state = 'PUBLISHED' AND NEW.state <> 'SUPERSEDED' THEN
        RAISE EXCEPTION 'Published attention governance can only be superseded';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Attention governance version must advance exactly once';
    END IF;
    IF NEW.governance_id IS DISTINCT FROM OLD.governance_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.max_active_user_rules IS DISTINCT FROM OLD.max_active_user_rules
       OR NEW.max_vip_rules IS DISTINCT FROM OLD.max_vip_rules
       OR NEW.max_follow_rules IS DISTINCT FROM OLD.max_follow_rules
       OR NEW.approved_topic_allowlist IS DISTINCT FROM OLD.approved_topic_allowlist
       OR NEW.mandatory_policy_precedence IS DISTINCT FROM OLD.mandatory_policy_precedence
       OR NEW.minimum_analytics_cohort IS DISTINCT FROM OLD.minimum_analytics_cohort
       OR NEW.independent_reviewer_required IS DISTINCT FROM OLD.independent_reviewer_required
       OR NEW.revision_number IS DISTINCT FROM OLD.revision_number
       OR NEW.change_reason IS DISTINCT FROM OLD.change_reason
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.supersedes_governance_id IS DISTINCT FROM OLD.supersedes_governance_id THEN
        RAISE EXCEPTION 'Attention governance revision content cannot be mutated';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_ntf_attention_governance_revision_guard
BEFORE UPDATE OR DELETE ON ntf_attention_governance_revisions
FOR EACH ROW EXECUTE FUNCTION ntf_guard_attention_governance_revision();

ALTER TABLE ntf_attention_governance_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_attention_governance_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_attention_governance_worker_scope
    ON ntf_attention_governance_revisions
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());
CREATE POLICY ntf_attention_governance_api_read_scope
    ON ntf_attention_governance_revisions
    FOR SELECT TO dwp_notification_api
    USING (
        ntf_is_api()
        AND tenant_id = ntf_current_tenant_id()
        AND state = 'PUBLISHED'
    );

GRANT SELECT, INSERT, UPDATE ON ntf_attention_governance_revisions
    TO dwp_notification_worker;
GRANT SELECT ON ntf_attention_governance_revisions
    TO dwp_notification_api;
REVOKE DELETE ON ntf_attention_governance_revisions
    FROM dwp_notification_worker;
REVOKE ALL ON FUNCTION ntf_guard_attention_governance_revision() FROM PUBLIC;
REVOKE ALL ON FUNCTION ntf_attention_topics_are_canonical(JSONB) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ntf_guard_attention_governance_revision()
    TO dwp_notification_worker;
GRANT EXECUTE ON FUNCTION ntf_attention_topics_are_canonical(JSONB)
    TO dwp_notification_worker;

COMMENT ON TABLE ntf_attention_governance_revisions IS
    'Immutable tenant attention-governance revisions with independent draft approval.';
COMMENT ON COLUMN ntf_attention_governance_revisions.approved_topic_allowlist IS
    'Canonical non-sensitive topic tokens approved for tenant user attention rules.';
