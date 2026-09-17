CREATE TABLE ntf_notification_quality_facts (
    fact_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    type_version_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    owner_app_key VARCHAR(100) NOT NULL,
    type_key VARCHAR(160) NOT NULL,
    owner_team VARCHAR(160) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    decision VARCHAR(20) NOT NULL
        CHECK (decision IN ('ADMITTED', 'SUPPRESSED', 'RATE_LIMITED')),
    reason_code VARCHAR(200) NOT NULL,
    attention_effect VARCHAR(20)
        CHECK (attention_effect IS NULL OR attention_effect IN ('FOLLOW', 'PRIORITIZE', 'MUTE')),
    attention_scope_kind VARCHAR(30)
        CHECK (attention_scope_kind IS NULL OR attention_scope_kind IN (
            'APP_TYPE', 'ACTOR', 'THREAD', 'RESOURCE', 'TOPIC_TOKEN'
        )),
    action_required BOOLEAN NOT NULL,
    collapsed BOOLEAN NOT NULL,
    thread_identity_hash CHAR(64) NOT NULL
        CHECK (thread_identity_hash ~ '^[a-f0-9]{64}$'),
    source_identity_hash CHAR(64) NOT NULL
        CHECK (source_identity_hash ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_quality_receipt UNIQUE (tenant_id, receipt_id),
    CONSTRAINT ck_ntf_quality_contract_keys CHECK (
        owner_app_key ~ '^[a-z0-9][a-z0-9-]{0,63}$'
        AND type_key ~ '^[A-Z0-9][A-Z0-9._-]{0,159}$'
    ),
    CONSTRAINT ck_ntf_quality_attention_pair CHECK (
        (attention_effect IS NULL AND attention_scope_kind IS NULL)
        OR (attention_effect IS NOT NULL AND attention_scope_kind IS NOT NULL)
    )
);

CREATE TABLE ntf_notification_quality_completion_facts (
    fact_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    notification_identity_hash CHAR(64) NOT NULL
        CHECK (notification_identity_hash ~ '^[a-f0-9]{64}$'),
    type_version_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    owner_app_key VARCHAR(100) NOT NULL,
    type_key VARCHAR(160) NOT NULL,
    owner_team VARCHAR(160) NOT NULL,
    thread_identity_hash CHAR(64) NOT NULL
        CHECK (thread_identity_hash ~ '^[a-f0-9]{64}$'),
    change_version BIGINT NOT NULL CHECK (change_version > 0),
    completed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_quality_completion UNIQUE (
        tenant_id, user_id, notification_identity_hash, change_version
    ),
    CONSTRAINT ck_ntf_quality_completion_contract_keys CHECK (
        owner_app_key ~ '^[a-z0-9][a-z0-9-]{0,63}$'
        AND type_key ~ '^[A-Z0-9][A-Z0-9._-]{0,159}$'
    )
);

CREATE INDEX ix_ntf_quality_fact_window
    ON ntf_notification_quality_facts (
        tenant_id, decided_at DESC, contract_id, user_id
    );
CREATE INDEX ix_ntf_quality_fact_type_window
    ON ntf_notification_quality_facts (
        tenant_id, contract_id, decided_at DESC
    );
CREATE INDEX ix_ntf_quality_fact_thread
    ON ntf_notification_quality_facts (
        tenant_id, user_id, type_version_id, thread_identity_hash, decided_at DESC
    ) WHERE decision = 'ADMITTED';
CREATE INDEX ix_ntf_quality_completion_window
    ON ntf_notification_quality_completion_facts (
        tenant_id, completed_at DESC, contract_id, user_id
    );
CREATE INDEX ix_ntf_quality_completion_thread
    ON ntf_notification_quality_completion_facts (
        tenant_id, user_id, type_version_id, thread_identity_hash, completed_at
    );

DO $quality_retention_role$
DECLARE
    application_user NAME := session_user;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
         WHERE rolname = 'dwp_notification_quality_retention'
    ) THEN
        CREATE ROLE dwp_notification_quality_retention
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    ALTER ROLE dwp_notification_quality_retention
        NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    IF application_user NOT IN (
        'dwp_notification_api',
        'dwp_notification_worker',
        'dwp_notification_quality_retention'
    ) THEN
        EXECUTE format(
            'GRANT dwp_notification_quality_retention TO %I',
            application_user
        );
    END IF;
END
$quality_retention_role$;

ALTER TABLE ntf_notification_quality_facts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notification_quality_facts FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_quality_fact_worker_select_scope
    ON ntf_notification_quality_facts
    FOR SELECT TO dwp_notification_worker
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());
CREATE POLICY ntf_quality_fact_worker_insert_scope
    ON ntf_notification_quality_facts
    FOR INSERT TO dwp_notification_worker
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());
CREATE POLICY ntf_quality_fact_retention_scope
    ON ntf_notification_quality_facts
    FOR DELETE TO dwp_notification_quality_retention
    USING (
        current_user = 'dwp_notification_quality_retention'
        AND tenant_id = ntf_current_tenant_id()
    );
CREATE POLICY ntf_quality_fact_retention_select_scope
    ON ntf_notification_quality_facts
    FOR SELECT TO dwp_notification_quality_retention
    USING (
        current_user = 'dwp_notification_quality_retention'
        AND tenant_id = ntf_current_tenant_id()
    );

ALTER TABLE ntf_notification_quality_completion_facts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notification_quality_completion_facts FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_quality_completion_worker_scope
    ON ntf_notification_quality_completion_facts
    FOR SELECT TO dwp_notification_worker
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());
CREATE POLICY ntf_quality_completion_api_insert_scope
    ON ntf_notification_quality_completion_facts
    FOR INSERT TO dwp_notification_api
    WITH CHECK (
        ntf_is_api()
        AND tenant_id = ntf_current_tenant_id()
        AND user_id = ntf_current_user_id()
    );
CREATE POLICY ntf_quality_completion_retention_scope
    ON ntf_notification_quality_completion_facts
    FOR DELETE TO dwp_notification_quality_retention
    USING (
        current_user = 'dwp_notification_quality_retention'
        AND tenant_id = ntf_current_tenant_id()
    );
CREATE POLICY ntf_quality_completion_retention_select_scope
    ON ntf_notification_quality_completion_facts
    FOR SELECT TO dwp_notification_quality_retention
    USING (
        current_user = 'dwp_notification_quality_retention'
        AND tenant_id = ntf_current_tenant_id()
    );

GRANT SELECT, INSERT ON ntf_notification_quality_facts TO dwp_notification_worker;
GRANT SELECT ON ntf_notification_quality_completion_facts TO dwp_notification_worker;
GRANT INSERT ON ntf_notification_quality_completion_facts TO dwp_notification_api;
GRANT USAGE ON SCHEMA public TO dwp_notification_quality_retention;
GRANT EXECUTE ON FUNCTION ntf_current_tenant_id()
    TO dwp_notification_quality_retention;
GRANT SELECT, DELETE ON
    ntf_notification_quality_facts,
    ntf_notification_quality_completion_facts
    TO dwp_notification_quality_retention;

CREATE OR REPLACE FUNCTION ntf_guard_notification_quality_fact()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND current_user = 'dwp_notification_quality_retention' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Notification quality facts are append-only';
END
$$;

CREATE TRIGGER trg_ntf_quality_fact_append_only
BEFORE UPDATE OR DELETE ON ntf_notification_quality_facts
FOR EACH ROW EXECUTE FUNCTION ntf_guard_notification_quality_fact();
CREATE TRIGGER trg_ntf_quality_completion_append_only
BEFORE UPDATE OR DELETE ON ntf_notification_quality_completion_facts
FOR EACH ROW EXECUTE FUNCTION ntf_guard_notification_quality_fact();

CREATE OR REPLACE FUNCTION ntf_purge_notification_quality_facts(
    requested_tenant_id BIGINT,
    requested_cutoff TIMESTAMPTZ,
    requested_limit INTEGER
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    removed_completions INTEGER;
    removed_decisions INTEGER;
BEGIN
    IF requested_tenant_id <> public.ntf_current_tenant_id()
       OR requested_cutoff IS NULL
       OR requested_cutoff > CURRENT_TIMESTAMP - INTERVAL '30 days'
       OR requested_limit < 1
       OR requested_limit > 500 THEN
        RAISE EXCEPTION 'Invalid notification quality retention request';
    END IF;

    DELETE FROM public.ntf_notification_quality_completion_facts target
     WHERE target.fact_id IN (
        SELECT candidate.fact_id
          FROM public.ntf_notification_quality_completion_facts candidate
         WHERE candidate.tenant_id = requested_tenant_id
           AND candidate.completed_at < requested_cutoff
         ORDER BY candidate.completed_at, candidate.fact_id
         LIMIT requested_limit
     );
    GET DIAGNOSTICS removed_completions = ROW_COUNT;

    DELETE FROM public.ntf_notification_quality_facts target
     WHERE target.fact_id IN (
        SELECT candidate.fact_id
          FROM public.ntf_notification_quality_facts candidate
         WHERE candidate.tenant_id = requested_tenant_id
           AND candidate.decided_at < requested_cutoff
         ORDER BY candidate.decided_at, candidate.fact_id
         LIMIT requested_limit
     );
    GET DIAGNOSTICS removed_decisions = ROW_COUNT;
    RETURN removed_completions + removed_decisions;
END
$$;

ALTER FUNCTION ntf_purge_notification_quality_facts(BIGINT, TIMESTAMPTZ, INTEGER)
    OWNER TO dwp_notification_quality_retention;
REVOKE ALL ON FUNCTION ntf_purge_notification_quality_facts(
    BIGINT, TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ntf_purge_notification_quality_facts(
    BIGINT, TIMESTAMPTZ, INTEGER) TO dwp_notification_worker;

REVOKE ALL ON FUNCTION ntf_guard_notification_quality_fact() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ntf_guard_notification_quality_fact()
    TO dwp_notification_api,
       dwp_notification_worker,
       dwp_notification_quality_retention;

COMMENT ON TABLE ntf_notification_quality_facts IS
    'Immutable, content-free recipient admission facts used for historical notification quality analytics.';
COMMENT ON TABLE ntf_notification_quality_completion_facts IS
    'Immutable, content-free user completion events used for historical action conversion analytics.';
COMMENT ON COLUMN ntf_notification_quality_facts.thread_identity_hash IS
    'Tenant and type-version namespaced SHA-256 identity; raw thread, scope keys and content are never stored.';
COMMENT ON COLUMN ntf_notification_quality_facts.source_identity_hash IS
    'Tenant and type-version namespaced SHA-256 identity; raw source identity is never stored.';
COMMENT ON FUNCTION ntf_purge_notification_quality_facts(
    BIGINT, TIMESTAMPTZ, INTEGER) IS
    'Bounded tenant-scoped retention for immutable quality facts older than thirty days.';
