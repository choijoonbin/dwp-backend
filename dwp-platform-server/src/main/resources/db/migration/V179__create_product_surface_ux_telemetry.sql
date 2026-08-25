-- CORE-006 UX analytics is intentionally isolated from security audit storage.
-- The raw schema contains no actor, person, object, raw URL, query or scope identifier.

CREATE TABLE plt_product_surface_ux_event (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    cohort VARCHAR(24) NOT NULL CHECK (cohort IN (
        'baseline', 'internal', 'design-partner', 'eligible-10', 'eligible-25',
        'eligible-50', 'eligible-90', 'holdout', 'full')),
    schema_version SMALLINT NOT NULL CHECK (schema_version = 1),
    event_name VARCHAR(48) NOT NULL CHECK (event_name IN (
        'surface.exposed',
        'surface.switch.started', 'surface.switch.completed', 'surface.switch.failed',
        'surface.returned', 'surface.route.denied',
        'surface.scope.switch.started', 'surface.scope.switch.completed',
        'surface.scope.switch.failed', 'surface.scope.invalid',
        'surface.assignment.expired', 'surface.policy.lock.viewed',
        'surface.task.started', 'surface.task.completed',
        'surface.task.failed', 'surface.task.abandoned')),
    product_key VARCHAR(48) NOT NULL,
    surface_key VARCHAR(64),
    from_surface_key VARCHAR(64),
    to_surface_key VARCHAR(64),
    target_surface_key VARCHAR(64),
    route_id VARCHAR(128),
    scope_kind VARCHAR(32) CHECK (scope_kind IS NULL OR scope_kind IN (
        'SELF', 'ORG_UNIT', 'LEGAL_ENTITY', 'DOMAIN', 'RESOURCE',
        'RESOURCE_SET', 'TARGET_POPULATION', 'SUPPORT_SESSION')),
    device_class VARCHAR(16) CHECK (
        device_class IS NULL OR device_class IN ('DESKTOP', 'TABLET', 'MOBILE')),
    elapsed_bucket VARCHAR(16) CHECK (elapsed_bucket IS NULL OR elapsed_bucket IN (
        'LT_1S', 'S1_TO_5', 'S5_TO_15', 'S15_TO_30', 'S30_TO_60',
        'M1_TO_5', 'GTE_5M')),
    reason_code VARCHAR(32),
    task_kind VARCHAR(24),
    policy_kind VARCHAR(32),
    read_only BOOLEAN,
    attempt_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_plt_product_surface_ux_event_retention
    ON plt_product_surface_ux_event (occurred_at, event_id);
CREATE INDEX idx_plt_product_surface_ux_event_journey
    ON plt_product_surface_ux_event (tenant_id, attempt_id, occurred_at)
    WHERE attempt_id IS NOT NULL;

CREATE TABLE plt_product_surface_ux_daily (
    bucket_date DATE NOT NULL,
    tenant_id BIGINT NOT NULL,
    cohort VARCHAR(24) NOT NULL,
    schema_version SMALLINT NOT NULL,
    event_name VARCHAR(48) NOT NULL,
    product_key VARCHAR(48) NOT NULL,
    dimension_key CHAR(32) NOT NULL,
    surface_key VARCHAR(64) NOT NULL DEFAULT '',
    from_surface_key VARCHAR(64) NOT NULL DEFAULT '',
    to_surface_key VARCHAR(64) NOT NULL DEFAULT '',
    target_surface_key VARCHAR(64) NOT NULL DEFAULT '',
    route_id VARCHAR(128) NOT NULL DEFAULT '',
    scope_kind VARCHAR(32) NOT NULL DEFAULT '',
    device_class VARCHAR(16) NOT NULL DEFAULT '',
    elapsed_bucket VARCHAR(16) NOT NULL DEFAULT '',
    reason_code VARCHAR(32) NOT NULL DEFAULT '',
    task_kind VARCHAR(24) NOT NULL DEFAULT '',
    policy_kind VARCHAR(32) NOT NULL DEFAULT '',
    read_only BOOLEAN,
    event_count BIGINT NOT NULL CHECK (event_count >= 0),
    attempt_count BIGINT NOT NULL CHECK (attempt_count >= 0),
    first_occurred_at TIMESTAMPTZ NOT NULL,
    last_occurred_at TIMESTAMPTZ NOT NULL,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (
        bucket_date, tenant_id, cohort, schema_version,
        event_name, product_key, dimension_key)
);

CREATE INDEX idx_plt_product_surface_ux_daily_retention
    ON plt_product_surface_ux_daily (bucket_date);

COMMENT ON TABLE plt_product_surface_ux_event IS
    'Privacy-minimized CORE-006 UX events; raw retention is fixed at 30 days.';
COMMENT ON TABLE plt_product_surface_ux_daily IS
    'Aggregate-only CORE-006 UX measures; retention is fixed at 180 days.';
COMMENT ON COLUMN plt_product_surface_ux_event.attempt_id IS
    'Rotating, journey-local UUID; never an actor, tenant, object or scope identifier.';

REVOKE ALL ON plt_product_surface_ux_event FROM PUBLIC;
REVOKE ALL ON plt_product_surface_ux_daily FROM PUBLIC;

-- Database operations provision this no-login analytics role outside application migrations.
-- If present, it receives aggregate read access only; raw events remain service-owner only.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dwp_product_surface_analytics_reader') THEN
        GRANT SELECT ON plt_product_surface_ux_daily
            TO dwp_product_surface_analytics_reader;
    END IF;
END
$$;
