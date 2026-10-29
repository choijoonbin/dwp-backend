-- Bounded retention hierarchy: platform fallback -> tenant default -> contract
-- override. Active legal holds remain the final purge veto from V15.
CREATE TABLE ntf_tenant_retention_policies (
    tenant_id BIGINT PRIMARY KEY,
    default_ttl_seconds BIGINT NOT NULL
        CHECK (default_ttl_seconds BETWEEN 86400 AND 31536000),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    change_reason VARCHAR(500) NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE ntf_notification_type_versions
    ADD COLUMN retention_ttl_seconds BIGINT
        CHECK (retention_ttl_seconds IS NULL
            OR retention_ttl_seconds BETWEEN 86400 AND 31536000);

ALTER TABLE ntf_notifications
    ADD COLUMN retention_policy_source VARCHAR(30),
    ADD COLUMN retention_ttl_seconds BIGINT,
    ADD CONSTRAINT ck_ntf_notification_retention_decision CHECK (
        (retention_policy_source IS NULL AND retention_ttl_seconds IS NULL)
        OR (retention_policy_source IN (
                'PLATFORM_DEFAULT', 'TENANT_DEFAULT', 'CONTRACT_OVERRIDE')
            AND retention_ttl_seconds BETWEEN 86400 AND 31536000)
    );

-- The HRIS v2 contract is the first explicit bounded override. This does not
-- mutate its published templates or rendering contract.
UPDATE ntf_notification_type_versions
   SET retention_ttl_seconds = 2592000
 WHERE type_version_id = '11000000-0000-0000-0000-000000000401'
   AND retention_ttl_seconds IS NULL;

ALTER TABLE ntf_tenant_retention_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_tenant_retention_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_tenant_retention_policy_worker_scope
    ON ntf_tenant_retention_policies
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

GRANT SELECT ON ntf_tenant_retention_policies TO dwp_notification_worker;

COMMENT ON TABLE ntf_tenant_retention_policies IS
    'Tenant notification TTL default; contract override wins and active legal hold blocks purge.';
COMMENT ON COLUMN ntf_notification_type_versions.retention_ttl_seconds IS
    'Optional bounded contract override applied after the tenant default.';
COMMENT ON COLUMN ntf_notifications.retention_policy_source IS
    'Materialized evidence of the effective TTL decision source.';
COMMENT ON COLUMN ntf_notifications.retention_ttl_seconds IS
    'Materialized bounded TTL used to calculate expires_at.';
