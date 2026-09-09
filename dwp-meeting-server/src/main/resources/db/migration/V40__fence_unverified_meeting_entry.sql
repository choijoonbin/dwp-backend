-- PUBLIC_CODE, external guests, and pre-host entry are schema placeholders, not enabled
-- capabilities. V40 makes the application boundary durable without destroying historical
-- guest rows or pretending an active provider room was closed by a database update.

UPDATE vm_tenant_policies
   SET guests_allowed = FALSE,
       allow_join_before_host = FALSE,
       require_authenticated_internal_users = TRUE,
       updated_at = CURRENT_TIMESTAMP
 WHERE guests_allowed
    OR allow_join_before_host
    OR NOT require_authenticated_internal_users;

ALTER TABLE vm_tenant_policies
    ADD CONSTRAINT ck_vm_policy_unverified_entry_closed CHECK (
        NOT guests_allowed
        AND NOT allow_join_before_host
        AND require_authenticated_internal_users);

-- A historical guest participant is sufficient evidence that the meeting depended on the
-- unavailable guest authority. Preserve that evidence and make every runtime entry guard see it.
UPDATE vm_meetings meeting
   SET guest_access_enabled = TRUE,
       updated_at = CURRENT_TIMESTAMP
 WHERE EXISTS (
       SELECT 1
         FROM vm_meeting_participants participant
        WHERE participant.tenant_id = meeting.tenant_id
          AND participant.meeting_id = meeting.meeting_id
          AND participant.participant_role = 'GUEST')
   AND NOT meeting.guest_access_enabled;

-- A live unsafe room could still accept a cached provider credential. Database migration cannot
-- prove provider termination, so deployment must drain it before V40 can be applied.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM vm_meetings
         WHERE lifecycle_state = 'LIVE'
           AND (access_scope = 'PUBLIC_CODE'
                OR guest_access_enabled
                OR allow_join_before_host)) THEN
        RAISE EXCEPTION
            'live meeting with unverified entry configuration requires provider drain before V40'
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

CREATE FUNCTION vm_reject_unverified_meeting_entry_configuration()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.access_scope = 'PUBLIC_CODE'
       OR NEW.guest_access_enabled
       OR NEW.allow_join_before_host THEN
        RAISE EXCEPTION 'unverified meeting entry configuration is disabled'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER vm_reject_unverified_meeting_entry_configuration
BEFORE INSERT OR UPDATE OF access_scope, guest_access_enabled, allow_join_before_host
ON vm_meetings
FOR EACH ROW EXECUTE FUNCTION vm_reject_unverified_meeting_entry_configuration();

CREATE FUNCTION vm_reject_unverified_guest_participant()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.participant_role = 'GUEST' THEN
        RAISE EXCEPTION 'unverified guest participants are disabled'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER vm_reject_unverified_guest_participant
BEFORE INSERT OR UPDATE OF participant_role
ON vm_meeting_participants
FOR EACH ROW EXECUTE FUNCTION vm_reject_unverified_guest_participant();

COMMENT ON CONSTRAINT ck_vm_policy_unverified_entry_closed ON vm_tenant_policies IS
    'Release fence: requires verified guest identity and governed pre-host activation before removal.';
COMMENT ON FUNCTION vm_reject_unverified_meeting_entry_configuration() IS
    'Rejects new PUBLIC_CODE, guest-access, and join-before-host state while retaining unsafe historical evidence.';
COMMENT ON FUNCTION vm_reject_unverified_guest_participant() IS
    'Rejects new GUEST participants until a verified one-time invitation authority is deployed.';
