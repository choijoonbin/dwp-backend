CREATE TABLE vm_meeting_preparations (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    agenda_version BIGINT NOT NULL DEFAULT 0 CHECK (agenda_version >= 0),
    invitation_revision BIGINT NOT NULL DEFAULT 1 CHECK (invitation_revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE
);

CREATE TABLE vm_meeting_agenda_items (
    item_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 49),
    title VARCHAR(240) NOT NULL CHECK (length(trim(title)) > 0),
    objective VARCHAR(2000),
    owner_user_id BIGINT,
    planned_minutes INTEGER CHECK (planned_minutes BETWEEN 1 AND 1440),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, meeting_id, item_id),
    UNIQUE (tenant_id, meeting_id, position),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meeting_preparations (tenant_id, meeting_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, owner_user_id)
        REFERENCES vm_people_snapshot (tenant_id, user_id)
);

CREATE TABLE vm_meeting_invitation_responses (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    invitation_revision BIGINT NOT NULL CHECK (invitation_revision > 0),
    response_state VARCHAR(24) NOT NULL DEFAULT 'NEEDS_RESPONSE'
        CHECK (response_state IN ('NEEDS_RESPONSE', 'ACCEPTED', 'TENTATIVE',
                                  'DECLINED', 'RECONFIRM_REQUIRED')),
    responded_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id, participant_id),
    FOREIGN KEY (tenant_id, meeting_id, participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id)
        ON DELETE CASCADE
);

-- Only command digests and opaque result/version evidence are persisted here.
-- Agenda text and personal response content must never enter command/audit receipts.
CREATE TABLE vm_meeting_preparation_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    operation VARCHAR(24) NOT NULL CHECK (operation IN ('AGENDA_REPLACE', 'INVITATION_RESPOND')),
    idempotency_key VARCHAR(160) NOT NULL,
    request_sha256 CHAR(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    result_version BIGINT NOT NULL CHECK (result_version >= 0),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, meeting_id, actor_user_id, operation, idempotency_key),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE
);

CREATE TABLE vm_meeting_template_sources (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    template_id UUID NOT NULL,
    template_version BIGINT NOT NULL CHECK (template_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, template_id, template_version)
        REFERENCES vm_meeting_template_revisions (tenant_id, template_id, revision)
);

INSERT INTO vm_meeting_preparations (tenant_id, meeting_id)
SELECT tenant_id, meeting_id FROM vm_meetings;

INSERT INTO vm_meeting_invitation_responses (
    tenant_id, meeting_id, participant_id, invitation_revision, response_state)
SELECT participant.tenant_id, participant.meeting_id, participant.participant_id, 1,
       CASE WHEN participant.participant_role = 'ORGANIZER'
            THEN 'ACCEPTED' ELSE 'NEEDS_RESPONSE' END
  FROM vm_meeting_participants participant
  JOIN vm_meetings meeting
    ON meeting.tenant_id = participant.tenant_id AND meeting.meeting_id = participant.meeting_id
 WHERE participant.created_by = meeting.organizer_user_id
    OR EXISTS (SELECT 1 FROM vm_meeting_participants inviter
                WHERE inviter.tenant_id = participant.tenant_id
                  AND inviter.meeting_id = participant.meeting_id
                  AND inviter.user_id = participant.created_by
                  AND inviter.participant_role IN ('ORGANIZER', 'CO_HOST')
                  AND inviter.attendance_state <> 'DENIED');

CREATE FUNCTION vm_initialize_meeting_preparation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO vm_meeting_preparations (tenant_id, meeting_id)
    VALUES (NEW.tenant_id, NEW.meeting_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER vm_initialize_meeting_preparation
AFTER INSERT ON vm_meetings
FOR EACH ROW EXECUTE FUNCTION vm_initialize_meeting_preparation();

CREATE FUNCTION vm_initialize_invitation_response() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO vm_meeting_invitation_responses (
        tenant_id, meeting_id, participant_id, invitation_revision, response_state)
    SELECT NEW.tenant_id, NEW.meeting_id, NEW.participant_id, invitation_revision,
           CASE WHEN NEW.participant_role = 'ORGANIZER'
                THEN 'ACCEPTED' ELSE 'NEEDS_RESPONSE' END
      FROM vm_meeting_preparations preparation
      JOIN vm_meetings meeting
        ON meeting.tenant_id = preparation.tenant_id AND meeting.meeting_id = preparation.meeting_id
     WHERE preparation.tenant_id = NEW.tenant_id AND preparation.meeting_id = NEW.meeting_id
       AND (NEW.created_by = meeting.organizer_user_id OR EXISTS (
            SELECT 1 FROM vm_meeting_participants inviter
             WHERE inviter.tenant_id = NEW.tenant_id AND inviter.meeting_id = NEW.meeting_id
               AND inviter.user_id = NEW.created_by
               AND inviter.participant_role IN ('ORGANIZER', 'CO_HOST')
               AND inviter.attendance_state <> 'DENIED'));
    RETURN NEW;
END;
$$;

CREATE TRIGGER vm_initialize_invitation_response
AFTER INSERT ON vm_meeting_participants
FOR EACH ROW EXECUTE FUNCTION vm_initialize_invitation_response();

-- An invitation response is not admission. Rescheduling never mutates attendance,
-- notice acknowledgement, provider tokens, or completed historical occurrences.
CREATE FUNCTION vm_reconfirm_changed_meeting_invitation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE next_revision BIGINT;
BEGIN
    IF OLD.lifecycle_state IN ('DRAFT', 'SCHEDULED', 'LOBBY') AND (
        NEW.scheduled_start_at IS DISTINCT FROM OLD.scheduled_start_at OR
        NEW.scheduled_end_at IS DISTINCT FROM OLD.scheduled_end_at OR
        NEW.time_zone IS DISTINCT FROM OLD.time_zone) THEN
        UPDATE vm_meeting_preparations
           SET invitation_revision = invitation_revision + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = NEW.tenant_id AND meeting_id = NEW.meeting_id
        RETURNING invitation_revision INTO next_revision;
        UPDATE vm_meeting_invitation_responses response
           SET invitation_revision = next_revision,
               response_state = CASE WHEN participant.participant_role = 'ORGANIZER'
                    THEN 'ACCEPTED' ELSE 'RECONFIRM_REQUIRED' END,
               responded_at = NULL, version = response.version + 1,
               updated_at = CURRENT_TIMESTAMP
          FROM vm_meeting_participants participant
         WHERE response.tenant_id = NEW.tenant_id AND response.meeting_id = NEW.meeting_id
           AND participant.tenant_id = response.tenant_id
           AND participant.meeting_id = response.meeting_id
           AND participant.participant_id = response.participant_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER vm_reconfirm_changed_meeting_invitation
AFTER UPDATE OF scheduled_start_at, scheduled_end_at, time_zone ON vm_meetings
FOR EACH ROW EXECUTE FUNCTION vm_reconfirm_changed_meeting_invitation();

CREATE INDEX ix_vm_invitation_response_state
    ON vm_meeting_invitation_responses (tenant_id, response_state, meeting_id);

COMMENT ON TABLE vm_meeting_agenda_items IS
    'Meeting-owned preparation content; access and retention follow the parent meeting, never audit payloads.';
COMMENT ON TABLE vm_meeting_invitation_responses IS
    'Explicit invitation response independent from attendance, admission and recording consent.';
