ALTER TABLE cal_calendars
    ADD COLUMN owner_display_name VARCHAR(160),
    ADD COLUMN subscription_policy VARCHAR(20) NOT NULL DEFAULT 'OPTIONAL',
    ADD CONSTRAINT ck_cal_calendars_subscription_policy CHECK (
        subscription_policy IN ('REQUIRED', 'DEFAULT_ON', 'OPTIONAL')),
    ADD CONSTRAINT uk_cal_calendars_tenant_calendar UNIQUE (tenant_id, calendar_id);

UPDATE cal_calendars calendar
   SET owner_display_name = owner_name.organizer_name
  FROM (
      SELECT DISTINCT ON (tenant_id, calendar_id)
             tenant_id, calendar_id, organizer_name
        FROM cal_events
       WHERE organizer_name IS NOT NULL AND BTRIM(organizer_name) <> ''
       ORDER BY tenant_id, calendar_id, updated_at DESC, event_id
  ) owner_name
 WHERE calendar.tenant_id = owner_name.tenant_id
   AND calendar.calendar_id = owner_name.calendar_id
   AND calendar.calendar_type = 'PERSONAL';

UPDATE cal_calendars
   SET subscription_policy = CASE
           WHEN calendar_type = 'SYSTEM' THEN 'REQUIRED'
           WHEN calendar_type = 'TEAM' THEN 'DEFAULT_ON'
           ELSE 'OPTIONAL'
       END;

ALTER TABLE cal_events
    ADD COLUMN importance VARCHAR(12) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by BIGINT,
    ADD COLUMN deletion_reason VARCHAR(500),
    ADD COLUMN purge_after TIMESTAMPTZ,
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_cal_events_importance CHECK (
        importance IN ('LOW', 'NORMAL', 'HIGH')),
    ADD CONSTRAINT ck_cal_events_deletion CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL AND purge_after IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL
            AND (legal_hold OR purge_after IS NOT NULL))),
    ADD CONSTRAINT uk_cal_events_tenant_event UNIQUE (tenant_id, event_id),
    ADD CONSTRAINT fk_cal_events_tenant_calendar FOREIGN KEY (tenant_id, calendar_id)
        REFERENCES cal_calendars (tenant_id, calendar_id) NOT VALID;

ALTER TABLE cal_events VALIDATE CONSTRAINT fk_cal_events_tenant_calendar;

DO $$
BEGIN
    -- V147 normally owns this tenant-scoped candidate key. Keep V192 safe for
    -- installations that already have it while still repairing older baselines.
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'cal_resources'::regclass
           AND conname = 'uk_cal_resources_tenant_resource'
    ) THEN
        ALTER TABLE cal_resources
            ADD CONSTRAINT uk_cal_resources_tenant_resource
            UNIQUE (tenant_id, resource_id);
    END IF;
END
$$;

ALTER TABLE cal_event_attendees
    ADD CONSTRAINT fk_cal_attendees_tenant_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE NOT VALID;

ALTER TABLE cal_event_attendees VALIDATE CONSTRAINT fk_cal_attendees_tenant_event;

ALTER TABLE cal_resource_bookings
    ADD CONSTRAINT fk_cal_bookings_tenant_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT fk_cal_bookings_tenant_resource FOREIGN KEY (tenant_id, resource_id)
        REFERENCES cal_resources (tenant_id, resource_id) NOT VALID;

ALTER TABLE cal_resource_bookings
    VALIDATE CONSTRAINT fk_cal_bookings_tenant_event;
ALTER TABLE cal_resource_bookings
    VALIDATE CONSTRAINT fk_cal_bookings_tenant_resource;

CREATE TABLE cal_calendar_access_grants (
    grant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    calendar_id UUID NOT NULL,
    principal_type VARCHAR(16) NOT NULL,
    principal_person_public_id UUID,
    principal_group_ref UUID,
    principal_display_name VARCHAR(160),
    access_level VARCHAR(24) NOT NULL,
    can_view_private BOOLEAN NOT NULL DEFAULT FALSE,
    valid_until TIMESTAMPTZ,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT fk_cal_grants_tenant_calendar FOREIGN KEY (tenant_id, calendar_id)
        REFERENCES cal_calendars (tenant_id, calendar_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_grants_tenant_person FOREIGN KEY (
        tenant_id, principal_person_public_id)
        REFERENCES cal_identity_links (tenant_id, person_public_id),
    CONSTRAINT ck_cal_grants_principal_type CHECK (
        principal_type IN ('TENANT', 'PERSON', 'GROUP')),
    CONSTRAINT ck_cal_grants_principal CHECK (
        (principal_type = 'TENANT'
            AND principal_person_public_id IS NULL AND principal_group_ref IS NULL)
        OR (principal_type = 'PERSON'
            AND principal_person_public_id IS NOT NULL AND principal_group_ref IS NULL)
        OR (principal_type = 'GROUP'
            AND principal_person_public_id IS NULL AND principal_group_ref IS NOT NULL)),
    CONSTRAINT ck_cal_grants_access CHECK (
        access_level IN ('VIEW_FREE_BUSY', 'VIEW_DETAILS', 'EDIT', 'MANAGE')),
    CONSTRAINT ck_cal_grants_state CHECK (
        lifecycle_state IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_cal_grants_tenant_principal
    ON cal_calendar_access_grants (tenant_id, calendar_id)
    WHERE principal_type = 'TENANT' AND lifecycle_state = 'ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS uk_cal_grants_person_principal
    ON cal_calendar_access_grants (
        tenant_id, calendar_id, principal_person_public_id)
    WHERE principal_type = 'PERSON' AND lifecycle_state = 'ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS uk_cal_grants_group_principal
    ON cal_calendar_access_grants (tenant_id, calendar_id, principal_group_ref)
    WHERE principal_type = 'GROUP' AND lifecycle_state = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_cal_grants_person_lookup
    ON cal_calendar_access_grants (
        tenant_id, principal_person_public_id, lifecycle_state, calendar_id)
    WHERE principal_person_public_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cal_grants_group_lookup
    ON cal_calendar_access_grants (
        tenant_id, principal_group_ref, lifecycle_state, calendar_id)
    WHERE principal_group_ref IS NOT NULL;

CREATE TABLE cal_calendar_subscriptions (
    tenant_id BIGINT NOT NULL,
    person_public_id UUID NOT NULL,
    calendar_id UUID NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT TRUE,
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL DEFAULT 0,
    color_override VARCHAR(7),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, person_public_id, calendar_id),
    CONSTRAINT fk_cal_subscriptions_tenant_person FOREIGN KEY (
        tenant_id, person_public_id)
        REFERENCES cal_identity_links (tenant_id, person_public_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_subscriptions_tenant_calendar FOREIGN KEY (
        tenant_id, calendar_id)
        REFERENCES cal_calendars (tenant_id, calendar_id) ON DELETE CASCADE,
    CONSTRAINT ck_cal_subscriptions_order CHECK (display_order BETWEEN 0 AND 10000),
    CONSTRAINT ck_cal_subscriptions_color CHECK (
        color_override IS NULL OR color_override ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE INDEX IF NOT EXISTS idx_cal_subscriptions_favorite
    ON cal_calendar_subscriptions (
        tenant_id, person_public_id, favorite DESC, display_order, calendar_id);

CREATE TABLE cal_event_user_preferences (
    tenant_id BIGINT NOT NULL,
    person_public_id UUID NOT NULL,
    event_id UUID NOT NULL,
    starred BOOLEAN NOT NULL DEFAULT FALSE,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, person_public_id, event_id),
    CONSTRAINT fk_cal_event_preferences_tenant_person FOREIGN KEY (
        tenant_id, person_public_id)
        REFERENCES cal_identity_links (tenant_id, person_public_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_event_preferences_tenant_event FOREIGN KEY (
        tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_cal_event_preferences_starred
    ON cal_event_user_preferences (tenant_id, person_public_id, starred, event_id)
    WHERE starred;

CREATE TABLE cal_event_tombstones (
    tombstone_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    source_ref VARCHAR(255) NOT NULL,
    recurrence_id TIMESTAMPTZ,
    sequence BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purge_after TIMESTAMPTZ,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_cal_event_tombstones_retention CHECK (
        legal_hold OR purge_after IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_cal_event_tombstones_source
    ON cal_event_tombstones (
        tenant_id, source_type, source_ref,
        COALESCE(recurrence_id, '-infinity'::timestamptz));

CREATE TABLE cal_event_occurrence_overrides (
    override_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    original_starts_at TIMESTAMPTZ NOT NULL,
    override_kind VARCHAR(20) NOT NULL,
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    importance VARCHAR(12),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT fk_cal_occurrence_overrides_tenant_event FOREIGN KEY (
        tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE,
    CONSTRAINT uk_cal_occurrence_override UNIQUE (
        tenant_id, event_id, original_starts_at),
    CONSTRAINT ck_cal_occurrence_override_kind CHECK (
        override_kind IN ('MODIFIED', 'CANCELLED')),
    CONSTRAINT ck_cal_occurrence_override_period CHECK (
        override_kind = 'CANCELLED'
        OR (starts_at IS NOT NULL AND ends_at IS NOT NULL AND ends_at > starts_at)),
    CONSTRAINT ck_cal_occurrence_override_importance CHECK (
        importance IS NULL OR importance IN ('LOW', 'NORMAL', 'HIGH'))
);

-- Every tenant receives exactly one governed company source. Existing branded
-- SYSTEM calendars remain authoritative and are not duplicated.
INSERT INTO cal_calendars (
    calendar_id, tenant_id, calendar_key, name_ko, name_en, color_hex,
    calendar_type, visibility, subscription_policy, created_by, updated_by)
SELECT gen_random_uuid(), tenant.tenant_id, 'company',
       tenant.display_name || ' 전사 일정', tenant.display_name || ' company calendar',
       '#0F766E', 'SYSTEM', 'DETAILS', 'REQUIRED', 1, 1
  FROM sys_service_tenants tenant
 WHERE NOT EXISTS (
       SELECT 1
         FROM cal_calendars calendar
        WHERE calendar.tenant_id = tenant.tenant_id
          AND calendar.calendar_type = 'SYSTEM'
          AND calendar.lifecycle_state = 'ACTIVE')
ON CONFLICT (tenant_id, calendar_key) DO NOTHING;

INSERT INTO cal_calendar_access_grants (
    tenant_id, calendar_id, principal_type, access_level,
    can_view_private, lifecycle_state, created_by, updated_by)
SELECT tenant_id, calendar_id, 'TENANT', 'VIEW_DETAILS',
       FALSE, 'ACTIVE', COALESCE(created_by, 1), COALESCE(updated_by, 1)
  FROM cal_calendars
 WHERE calendar_type IN ('SYSTEM', 'TEAM')
ON CONFLICT DO NOTHING;

COMMENT ON TABLE cal_calendar_access_grants IS
    'Deny-by-default object grants for tenant, person, and verified group calendar access.';
COMMENT ON TABLE cal_calendar_subscriptions IS
    'Per-person source visibility, favorite, order, and color without mutating shared calendars.';
COMMENT ON TABLE cal_event_user_preferences IS
    'Per-person event star and hide state without changing the shared event version.';
COMMENT ON COLUMN cal_calendars.subscription_policy IS
    'REQUIRED sources cannot be unsubscribed, DEFAULT_ON starts selected, OPTIONAL is user controlled.';
COMMENT ON COLUMN cal_events.importance IS
    'Organizer-owned importance shared with permitted viewers; personal starring is stored separately.';
