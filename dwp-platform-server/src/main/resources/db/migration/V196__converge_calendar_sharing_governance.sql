-- Forward-only convergence for Calendar sharing. V192 is immutable because it
-- has already been applied in shared environments.

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
CREATE INDEX IF NOT EXISTS idx_cal_subscriptions_favorite
    ON cal_calendar_subscriptions (
        tenant_id, person_public_id, favorite DESC, display_order, calendar_id);
CREATE INDEX IF NOT EXISTS idx_cal_event_preferences_starred
    ON cal_event_user_preferences (tenant_id, person_public_id, starred, event_id)
    WHERE starred;
CREATE UNIQUE INDEX IF NOT EXISTS uk_cal_event_tombstones_source
    ON cal_event_tombstones (
        tenant_id, source_type, source_ref,
        COALESCE(recurrence_id, '-infinity'::timestamptz));

-- TEAM calendars are deny-by-default. Membership must be represented by an
-- explicit verified GROUP or PERSON grant; a tenant-wide grant is reserved for
-- governed SYSTEM company calendars.
UPDATE cal_calendar_access_grants grant_row
   SET lifecycle_state = 'REVOKED',
       version = grant_row.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = COALESCE(grant_row.updated_by, 1)
  FROM cal_calendars calendar
 WHERE calendar.tenant_id = grant_row.tenant_id
   AND calendar.calendar_id = grant_row.calendar_id
   AND calendar.calendar_type = 'TEAM'
   AND grant_row.principal_type = 'TENANT'
   AND grant_row.lifecycle_state = 'ACTIVE';

-- Repair a missing company grant without broadening any personal or team
-- calendar. The partial unique index keeps this idempotent.
INSERT INTO cal_calendar_access_grants (
    tenant_id, calendar_id, principal_type, access_level,
    can_view_private, lifecycle_state, created_by, updated_by)
SELECT calendar.tenant_id, calendar.calendar_id, 'TENANT', 'VIEW_DETAILS',
       FALSE, 'ACTIVE', COALESCE(calendar.created_by, 1),
       COALESCE(calendar.updated_by, 1)
  FROM cal_calendars calendar
 WHERE calendar.calendar_type = 'SYSTEM'
   AND calendar.lifecycle_state = 'ACTIVE'
   AND NOT EXISTS (
       SELECT 1
         FROM cal_calendar_access_grants grant_row
        WHERE grant_row.tenant_id = calendar.tenant_id
          AND grant_row.calendar_id = calendar.calendar_id
          AND grant_row.principal_type = 'TENANT'
          AND grant_row.lifecycle_state = 'ACTIVE')
ON CONFLICT DO NOTHING;
