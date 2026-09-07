CREATE TABLE vm_personal_meeting_rooms (
    room_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    opaque_alias CHAR(32) NOT NULL,
    invitation_revision BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_vm_personal_room_owner UNIQUE (tenant_id, owner_user_id),
    CONSTRAINT uk_vm_personal_room_alias UNIQUE (tenant_id, opaque_alias),
    CONSTRAINT uk_vm_personal_room_tenant UNIQUE (tenant_id, room_id),
    CONSTRAINT ck_vm_personal_room_alias CHECK (opaque_alias ~ '^[a-f0-9]{32}$'),
    CONSTRAINT ck_vm_personal_room_name CHECK (length(trim(name)) > 0),
    CONSTRAINT ck_vm_personal_room_revision CHECK (invitation_revision > 0 AND version >= 0)
);

CREATE TABLE vm_personal_meeting_room_sessions (
    tenant_id BIGINT NOT NULL,
    room_id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    invitation_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, room_id, meeting_id),
    CONSTRAINT uk_vm_personal_room_session_meeting UNIQUE (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, room_id) REFERENCES vm_personal_meeting_rooms (tenant_id, room_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES vm_meetings (tenant_id, meeting_id),
    CONSTRAINT ck_vm_personal_session_revision CHECK (invitation_revision > 0)
);
CREATE INDEX ix_vm_personal_room_sessions ON vm_personal_meeting_room_sessions (tenant_id, room_id, created_at DESC);
