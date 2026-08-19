UPDATE wp_resources
   SET booking_mode = 'UNAVAILABLE',
       assigned_display_name = NULL,
       updated_at = CURRENT_TIMESTAMP
 WHERE booking_mode = 'ASSIGNED'
   AND assigned_user_id IS NULL
   AND assigned_person_public_id IS NULL;

ALTER TABLE wp_resources
    DROP CONSTRAINT ck_wp_resources_assignment,
    ADD CONSTRAINT ck_wp_resources_assignment CHECK (
        booking_mode <> 'ASSIGNED'
        OR assigned_user_id IS NOT NULL
        OR assigned_person_public_id IS NOT NULL);

COMMENT ON CONSTRAINT ck_wp_resources_assignment ON wp_resources IS
    'Assigned resources require a verified directory identity; display labels alone never grant access.';
