-- Server-authoritative live facilitation. Questions and poll labels are product content;
-- audit/idempotency rows retain only identifiers, versions and request digests.
CREATE TABLE vm_meeting_facilitation_states (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    timer_state VARCHAR(16) NOT NULL DEFAULT 'IDLE'
        CHECK (timer_state IN ('IDLE', 'RUNNING', 'PAUSED', 'COMPLETED')),
    agenda_item_id UUID,
    timer_planned_seconds INTEGER CHECK (timer_planned_seconds BETWEEN 60 AND 86400),
    timer_elapsed_seconds INTEGER NOT NULL DEFAULT 0 CHECK (timer_elapsed_seconds >= 0),
    timer_running_since TIMESTAMPTZ,
    timer_version BIGINT NOT NULL DEFAULT 0 CHECK (timer_version >= 0),
    retention_until TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CHECK (
        (timer_state = 'IDLE' AND agenda_item_id IS NULL
            AND timer_planned_seconds IS NULL AND timer_running_since IS NULL)
        OR (timer_state = 'RUNNING' AND agenda_item_id IS NOT NULL
            AND timer_planned_seconds IS NOT NULL AND timer_running_since IS NOT NULL)
        OR (timer_state IN ('PAUSED', 'COMPLETED') AND agenda_item_id IS NOT NULL
            AND timer_planned_seconds IS NOT NULL AND timer_running_since IS NULL)
    )
);

CREATE TABLE vm_meeting_facilitation_questions (
    question_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    author_participant_id UUID NOT NULL,
    author_user_id BIGINT NOT NULL,
    question_text VARCHAR(2000) NOT NULL CHECK (length(trim(question_text)) > 0),
    question_state VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (question_state IN ('OPEN', 'ANSWERED', 'DISMISSED')),
    answer_text VARCHAR(4000),
    answered_at TIMESTAMPTZ,
    answered_by BIGINT,
    dismissed_at TIMESTAMPTZ,
    dismissed_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_sequence BIGINT NOT NULL CHECK (created_sequence > 0),
    last_sequence BIGINT NOT NULL CHECK (last_sequence >= created_sequence),
    retention_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, meeting_id, question_id),
    UNIQUE (tenant_id, meeting_id, created_sequence),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meeting_facilitation_states (tenant_id, meeting_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, meeting_id, author_participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id),
    CHECK (
        (question_state = 'OPEN' AND answer_text IS NULL AND answered_at IS NULL
            AND answered_by IS NULL AND dismissed_at IS NULL AND dismissed_by IS NULL)
        OR (question_state = 'ANSWERED' AND answer_text IS NOT NULL
            AND answered_at IS NOT NULL AND answered_by IS NOT NULL
            AND dismissed_at IS NULL AND dismissed_by IS NULL)
        OR (question_state = 'DISMISSED' AND answer_text IS NULL
            AND answered_at IS NULL AND answered_by IS NULL
            AND dismissed_at IS NOT NULL AND dismissed_by IS NOT NULL)
    )
);

CREATE TABLE vm_meeting_facilitation_question_upvotes (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    question_id UUID NOT NULL,
    voter_participant_id UUID NOT NULL,
    voter_user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id, question_id, voter_user_id),
    FOREIGN KEY (tenant_id, meeting_id, question_id)
        REFERENCES vm_meeting_facilitation_questions (tenant_id, meeting_id, question_id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, meeting_id, voter_participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id)
);

CREATE TABLE vm_meeting_facilitation_polls (
    poll_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    creator_participant_id UUID NOT NULL,
    creator_user_id BIGINT NOT NULL,
    poll_question VARCHAR(1000) NOT NULL CHECK (length(trim(poll_question)) > 0),
    poll_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (poll_state IN ('DRAFT', 'OPEN', 'CLOSED')),
    anonymous BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_sequence BIGINT NOT NULL CHECK (created_sequence > 0),
    last_sequence BIGINT NOT NULL CHECK (last_sequence >= created_sequence),
    opened_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    retention_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, meeting_id, poll_id),
    UNIQUE (tenant_id, meeting_id, created_sequence),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meeting_facilitation_states (tenant_id, meeting_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, meeting_id, creator_participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id),
    CHECK ((poll_state = 'DRAFT' AND opened_at IS NULL AND closed_at IS NULL)
        OR (poll_state = 'OPEN' AND opened_at IS NOT NULL AND closed_at IS NULL)
        OR (poll_state = 'CLOSED' AND opened_at IS NOT NULL AND closed_at IS NOT NULL))
);

CREATE TABLE vm_meeting_facilitation_poll_options (
    option_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    poll_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 9),
    option_label VARCHAR(500) NOT NULL CHECK (length(trim(option_label)) > 0),
    UNIQUE (tenant_id, meeting_id, poll_id, option_id),
    UNIQUE (tenant_id, meeting_id, poll_id, position),
    FOREIGN KEY (tenant_id, meeting_id, poll_id)
        REFERENCES vm_meeting_facilitation_polls (tenant_id, meeting_id, poll_id)
        ON DELETE CASCADE
);

CREATE TABLE vm_meeting_facilitation_poll_votes (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    poll_id UUID NOT NULL,
    option_id UUID NOT NULL,
    voter_participant_id UUID NOT NULL,
    voter_user_id BIGINT NOT NULL,
    ballot_version BIGINT NOT NULL DEFAULT 0 CHECK (ballot_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id, poll_id, voter_user_id),
    FOREIGN KEY (tenant_id, meeting_id, poll_id, option_id)
        REFERENCES vm_meeting_facilitation_poll_options
            (tenant_id, meeting_id, poll_id, option_id),
    FOREIGN KEY (tenant_id, meeting_id, voter_participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id)
);

CREATE TABLE vm_meeting_facilitation_commands (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    operation VARCHAR(32) NOT NULL CHECK (operation IN (
        'QUESTION_ASK', 'QUESTION_UPVOTE', 'QUESTION_ANSWER', 'QUESTION_DISMISS',
        'POLL_CREATE', 'POLL_OPEN', 'POLL_CLOSE', 'POLL_VOTE',
        'TIMER_START', 'TIMER_PAUSE', 'TIMER_RESUME', 'TIMER_ADVANCE')),
    idempotency_key UUID NOT NULL,
    request_sha256 CHAR(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    result_resource_id UUID,
    result_version BIGINT NOT NULL CHECK (result_version >= 0),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id, actor_user_id, operation, idempotency_key),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meeting_facilitation_states (tenant_id, meeting_id) ON DELETE CASCADE
);

CREATE TABLE vm_meeting_facilitation_retention_evidence (
    execution_id UUID PRIMARY KEY,
    deleted_meeting_count INTEGER NOT NULL CHECK (deleted_meeting_count >= 0),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_vm_facilitation_questions_poll
    ON vm_meeting_facilitation_questions (tenant_id, meeting_id, last_sequence);
CREATE INDEX ix_vm_facilitation_polls_poll
    ON vm_meeting_facilitation_polls (tenant_id, meeting_id, last_sequence);
CREATE INDEX ix_vm_facilitation_retention
    ON vm_meeting_facilitation_states (retention_until);

COMMENT ON TABLE vm_meeting_facilitation_states IS
    'Server-clock agenda timer and sequence fence for explicitly polled live facilitation.';
COMMENT ON TABLE vm_meeting_facilitation_poll_votes IS
    'One mutable ballot per tenant/meeting/poll/user, protected by ballot_version CAS.';
COMMENT ON TABLE vm_meeting_facilitation_commands IS
    'Content-free idempotency evidence; raw questions, answers and poll labels never enter receipts.';
