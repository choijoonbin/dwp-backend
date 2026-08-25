-- V17 introduced recipient-owned fields, but legacy rows cannot be reconstructed
-- safely from the shared aggregation record: that record may already describe a
-- later event addressed to another recipient. Preserve the pre-existing
-- recipient title/preview and state, while redacting fields whose ownership
-- cannot be proven. Events admitted after V17 populate complete snapshots.
ALTER TABLE ntf_user_notifications DISABLE ROW LEVEL SECURITY;

UPDATE ntf_user_notifications
   SET actor_ref = NULL,
       subject_ref = NULL,
       target_ref = NULL,
       safe_body = '',
       action_payload = '{}'::jsonb,
       first_activity_at = last_activity_at,
       occurrence_count = 1;

ALTER TABLE ntf_user_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_notifications FORCE ROW LEVEL SECURITY;
