ALTER TABLE cal_calendars
    ADD COLUMN owner_person_public_id UUID;

WITH stable_owner AS (
    SELECT calendar_id, MIN(organizer_person_public_id::text)::uuid AS person_public_id
      FROM cal_events
     WHERE organizer_person_public_id IS NOT NULL
     GROUP BY calendar_id
    HAVING COUNT(DISTINCT organizer_person_public_id) = 1
)
UPDATE cal_calendars calendar
   SET owner_person_public_id = stable_owner.person_public_id,
       updated_at = CURRENT_TIMESTAMP
  FROM stable_owner
 WHERE calendar.calendar_id = stable_owner.calendar_id
   AND calendar.calendar_type = 'PERSONAL';

CREATE UNIQUE INDEX uk_cal_personal_calendar_owner
    ON cal_calendars (tenant_id, owner_person_public_id)
    WHERE calendar_type = 'PERSONAL'
      AND owner_person_public_id IS NOT NULL;

CREATE INDEX idx_cal_calendar_owner_person
    ON cal_calendars (tenant_id, owner_person_public_id, lifecycle_state)
    WHERE owner_person_public_id IS NOT NULL;

COMMENT ON COLUMN cal_calendars.owner_person_public_id IS
    'Stable people-directory identity used for personal calendar ownership. IAM user IDs remain an audited runtime reference only.';
