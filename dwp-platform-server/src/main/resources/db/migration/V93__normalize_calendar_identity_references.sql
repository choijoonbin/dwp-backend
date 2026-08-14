UPDATE cal_event_attendees
   SET attendee_user_id = NULL,
       updated_at = CURRENT_TIMESTAMP
 WHERE attendee_person_public_id IS NOT NULL
   AND attendee_user_id IS NOT NULL;

TRUNCATE TABLE cal_identity_links;

COMMENT ON COLUMN cal_event_attendees.attendee_user_id IS
    'Legacy IAM reference used only when an authoritative attendee person_public_id is unavailable.';
