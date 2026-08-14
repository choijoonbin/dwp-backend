CREATE TABLE cal_tenant_policies (
    tenant_id BIGINT PRIMARY KEY,
    week_start SMALLINT NOT NULL DEFAULT 1,
    working_day_start TIME NOT NULL DEFAULT TIME '09:00',
    working_day_end TIME NOT NULL DEFAULT TIME '18:00',
    default_event_minutes INTEGER NOT NULL DEFAULT 30,
    minimum_event_minutes INTEGER NOT NULL DEFAULT 15,
    maximum_event_minutes INTEGER NOT NULL DEFAULT 480,
    maximum_advance_days INTEGER NOT NULL DEFAULT 365,
    default_buffer_minutes INTEGER NOT NULL DEFAULT 10,
    weekly_focus_target_minutes INTEGER NOT NULL DEFAULT 600,
    daily_meeting_limit_minutes INTEGER NOT NULL DEFAULT 300,
    enforce_meeting_agenda BOOLEAN NOT NULL DEFAULT FALSE,
    allow_external_attendees BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_cal_policy_week_start CHECK (week_start BETWEEN 1 AND 7),
    CONSTRAINT ck_cal_policy_working_hours CHECK (working_day_end > working_day_start),
    CONSTRAINT ck_cal_policy_duration CHECK (
        minimum_event_minutes BETWEEN 5 AND 1440
        AND default_event_minutes BETWEEN minimum_event_minutes AND maximum_event_minutes
        AND maximum_event_minutes BETWEEN minimum_event_minutes AND 1440),
    CONSTRAINT ck_cal_policy_advance CHECK (maximum_advance_days BETWEEN 1 AND 1095),
    CONSTRAINT ck_cal_policy_buffer CHECK (default_buffer_minutes BETWEEN 0 AND 120),
    CONSTRAINT ck_cal_policy_targets CHECK (
        weekly_focus_target_minutes BETWEEN 0 AND 6000
        AND daily_meeting_limit_minutes BETWEEN 30 AND 1440)
);

CREATE TABLE cal_calendars (
    calendar_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    calendar_key VARCHAR(120) NOT NULL,
    owner_user_id BIGINT,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    color_hex VARCHAR(7) NOT NULL DEFAULT '#2563EB',
    calendar_type VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'DETAILS',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_cal_calendars_key UNIQUE (tenant_id, calendar_key),
    CONSTRAINT ck_cal_calendars_color CHECK (color_hex ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT ck_cal_calendars_type CHECK (
        calendar_type IN ('PERSONAL', 'TEAM', 'RESOURCE', 'SYSTEM')),
    CONSTRAINT ck_cal_calendars_visibility CHECK (
        visibility IN ('PRIVATE', 'FREE_BUSY', 'DETAILS')),
    CONSTRAINT ck_cal_calendars_state CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_cal_calendars_owner CHECK (
        (calendar_type = 'PERSONAL' AND owner_user_id IS NOT NULL)
        OR calendar_type <> 'PERSONAL')
);

CREATE TABLE cal_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    calendar_id UUID NOT NULL REFERENCES cal_calendars(calendar_id),
    organizer_user_id BIGINT NOT NULL,
    organizer_person_public_id UUID,
    organizer_name VARCHAR(160) NOT NULL,
    organizer_email VARCHAR(255),
    title VARCHAR(240) NOT NULL,
    description VARCHAR(4000),
    event_type VARCHAR(24) NOT NULL DEFAULT 'MEETING',
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    time_zone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
    all_day BOOLEAN NOT NULL DEFAULT FALSE,
    location VARCHAR(240),
    conference_url VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    visibility VARCHAR(20) NOT NULL DEFAULT 'DEFAULT',
    recurrence_pattern VARCHAR(20) NOT NULL DEFAULT 'NONE',
    recurrence_interval INTEGER NOT NULL DEFAULT 1,
    recurrence_until DATE,
    response_required BOOLEAN NOT NULL DEFAULT FALSE,
    source_type VARCHAR(20) NOT NULL DEFAULT 'NATIVE',
    source_ref VARCHAR(255),
    idempotency_key UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_cal_events_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_cal_events_type CHECK (
        event_type IN ('MEETING', 'FOCUS', 'TASK', 'OUT_OF_OFFICE', 'REMINDER')),
    CONSTRAINT ck_cal_events_status CHECK (
        status IN ('CONFIRMED', 'TENTATIVE', 'CANCELLED')),
    CONSTRAINT ck_cal_events_visibility CHECK (
        visibility IN ('DEFAULT', 'PUBLIC', 'PRIVATE', 'CONFIDENTIAL')),
    CONSTRAINT ck_cal_events_recurrence CHECK (
        recurrence_pattern IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY')
        AND recurrence_interval BETWEEN 1 AND 52),
    CONSTRAINT ck_cal_events_source CHECK (
        source_type IN ('NATIVE', 'GOOGLE', 'MICROSOFT', 'APPROVAL', 'HRIS')),
    CONSTRAINT uk_cal_events_idempotency
        UNIQUE (tenant_id, organizer_user_id, idempotency_key)
);

CREATE TABLE cal_event_attendees (
    attendee_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL REFERENCES cal_events(event_id) ON DELETE CASCADE,
    attendee_user_id BIGINT,
    attendee_person_public_id UUID,
    attendee_email VARCHAR(255) NOT NULL,
    attendee_name VARCHAR(160) NOT NULL,
    attendee_type VARCHAR(20) NOT NULL DEFAULT 'REQUIRED',
    response_status VARCHAR(20) NOT NULL DEFAULT 'NEEDS_ACTION',
    responded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_cal_event_attendee UNIQUE (event_id, attendee_email),
    CONSTRAINT ck_cal_attendee_type CHECK (
        attendee_type IN ('REQUIRED', 'OPTIONAL', 'RESOURCE')),
    CONSTRAINT ck_cal_attendee_response CHECK (
        response_status IN ('NEEDS_ACTION', 'ACCEPTED', 'TENTATIVE', 'DECLINED'))
);

CREATE TABLE cal_resources (
    resource_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    resource_code VARCHAR(80) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    resource_type VARCHAR(20) NOT NULL DEFAULT 'ROOM',
    site_name VARCHAR(160) NOT NULL,
    floor_name VARCHAR(80),
    capacity INTEGER NOT NULL DEFAULT 1,
    features JSONB NOT NULL DEFAULT '[]'::jsonb,
    time_zone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
    approval_required BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_cal_resources_code UNIQUE (tenant_id, resource_code),
    CONSTRAINT ck_cal_resources_type CHECK (
        resource_type IN ('ROOM', 'DESK', 'EQUIPMENT')),
    CONSTRAINT ck_cal_resources_capacity CHECK (capacity BETWEEN 1 AND 10000),
    CONSTRAINT ck_cal_resources_features CHECK (jsonb_typeof(features) = 'array'),
    CONSTRAINT ck_cal_resources_state CHECK (
        lifecycle_state IN ('AVAILABLE', 'MAINTENANCE', 'RETIRED'))
);

CREATE TABLE cal_resource_bookings (
    booking_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL REFERENCES cal_events(event_id) ON DELETE CASCADE,
    resource_id UUID NOT NULL REFERENCES cal_resources(resource_id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    booking_status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_cal_resource_booking_event UNIQUE (event_id, resource_id),
    CONSTRAINT ck_cal_resource_booking_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_cal_resource_booking_status CHECK (
        booking_status IN ('PENDING', 'CONFIRMED', 'DECLINED', 'CANCELLED'))
);

CREATE TABLE cal_audit_events (
    audit_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    event_id UUID,
    action VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    correlation_id VARCHAR(160),
    before_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_cal_audit_action CHECK (
        action ~ '^[a-z][a-z0-9.]{2,79}$'),
    CONSTRAINT ck_cal_audit_snapshots CHECK (
        jsonb_typeof(before_snapshot) = 'object'
        AND jsonb_typeof(after_snapshot) = 'object')
);

CREATE INDEX idx_cal_events_actor_period
    ON cal_events (tenant_id, organizer_user_id, starts_at, ends_at)
    WHERE status <> 'CANCELLED';
CREATE INDEX idx_cal_events_person_period
    ON cal_events (tenant_id, organizer_person_public_id, starts_at, ends_at)
    WHERE status <> 'CANCELLED' AND organizer_person_public_id IS NOT NULL;
CREATE INDEX idx_cal_events_calendar_period
    ON cal_events (tenant_id, calendar_id, starts_at, ends_at)
    WHERE status <> 'CANCELLED';
CREATE INDEX idx_cal_attendees_actor
    ON cal_event_attendees (tenant_id, attendee_user_id, event_id);
CREATE INDEX idx_cal_attendees_person
    ON cal_event_attendees (tenant_id, attendee_person_public_id, event_id)
    WHERE attendee_person_public_id IS NOT NULL;
CREATE INDEX idx_cal_resource_booking_period
    ON cal_resource_bookings (tenant_id, resource_id, starts_at, ends_at)
    WHERE booking_status IN ('PENDING', 'CONFIRMED');
CREATE INDEX idx_cal_audit_event_time
    ON cal_audit_events (tenant_id, event_id, occurred_at DESC);

INSERT INTO cal_tenant_policies (tenant_id, created_by, updated_by)
SELECT tenant_id, 1, 1 FROM sys_service_tenants
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    health_state, sort_order, lifecycle_state, created_by, updated_by)
SELECT tenant_id, 'dwp-calendar', '캘린더', 'Calendar',
       '일정, 집중시간, 참석 응답과 업무 공간 예약을 한곳에서 관리합니다.',
       'Manage schedules, focus time, responses, and workplace bookings in one place.',
       'DWP Workplace', 'PRODUCTIVITY', 'NATIVE', '/calendar/home',
       'calendar', 'APP.CALENDAR', 'HEALTHY', 38, 'ACTIVE', 1, 1
  FROM sys_service_tenants
ON CONFLICT (tenant_id, app_key) DO UPDATE SET
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description_ko = EXCLUDED.description_ko,
    description_en = EXCLUDED.description_en,
    owner_name = EXCLUDED.owner_name,
    category = EXCLUDED.category,
    launch_mode = EXCLUDED.launch_mode,
    launch_target = EXCLUDED.launch_target,
    icon_key = EXCLUDED.icon_key,
    resource_key = EXCLUDED.resource_key,
    health_state = EXCLUDED.health_state,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    version = adm_workspace_apps.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

-- Development reference data for SKAX. Identity references are external IDs by
-- contract and are replaced by HRIS/IdP projections during customer delivery.
INSERT INTO cal_calendars (
    calendar_id, tenant_id, calendar_key, owner_user_id, name_ko, name_en,
    color_hex, calendar_type, visibility, created_by, updated_by)
SELECT md5('calendar:personal:' || tenant.tenant_id || ':' || user_id)::uuid,
       tenant.tenant_id, 'personal-' || user_id, user_id,
       '내 캘린더', 'My calendar', '#2563EB', 'PERSONAL', 'PRIVATE', user_id, user_id
  FROM sys_service_tenants tenant
 CROSS JOIN generate_series(5, 25) user_id
 WHERE tenant.tenant_key = 'default'
ON CONFLICT (tenant_id, calendar_key) DO NOTHING;

INSERT INTO cal_calendars (
    calendar_id, tenant_id, calendar_key, name_ko, name_en, color_hex,
    calendar_type, visibility, created_by, updated_by)
SELECT md5('calendar:team:' || tenant_id)::uuid, tenant_id, 'skax-company',
       'SKAX 전사 일정', 'SKAX company calendar', '#0F766E',
       'SYSTEM', 'DETAILS', 1, 1
  FROM sys_service_tenants WHERE tenant_key = 'default'
UNION ALL
SELECT md5('calendar:platform-team:' || tenant_id)::uuid, tenant_id, 'digital-platform',
       'Digital Platform', 'Digital Platform', '#D97706',
       'TEAM', 'DETAILS', 1, 1
  FROM sys_service_tenants WHERE tenant_key = 'default'
ON CONFLICT (tenant_id, calendar_key) DO NOTHING;

INSERT INTO cal_resources (
    resource_id, tenant_id, resource_code, name_ko, name_en, resource_type,
    site_name, floor_name, capacity, features, approval_required, created_by, updated_by)
SELECT md5('calendar:resource:' || tenant.tenant_id || ':' || seed.code)::uuid,
       tenant.tenant_id, seed.code, seed.name_ko, seed.name_en, seed.type,
       seed.site, seed.floor_name, seed.capacity, seed.features::jsonb,
       seed.approval_required, 1, 1
  FROM sys_service_tenants tenant
 CROSS JOIN (VALUES
    ('TOWER-A-1201', 'A타워 1201 회의실', 'A Tower 1201', 'ROOM', 'SKAX 판교 캠퍼스', '12F', 8, '["DISPLAY","VIDEO","WHITEBOARD"]', FALSE),
    ('TOWER-A-1202', 'A타워 1202 포커스룸', 'A Tower 1202 Focus', 'ROOM', 'SKAX 판교 캠퍼스', '12F', 4, '["DISPLAY","WHITEBOARD"]', FALSE),
    ('TOWER-A-1501', 'A타워 1501 대회의실', 'A Tower 1501 Forum', 'ROOM', 'SKAX 판교 캠퍼스', '15F', 24, '["DISPLAY","VIDEO","WHITEBOARD","HYBRID"]', FALSE),
    ('TOWER-B-0803', 'B타워 0803 프로젝트룸', 'B Tower 0803 Project', 'ROOM', 'SKAX 판교 캠퍼스', '8F', 12, '["DISPLAY","VIDEO","WHITEBOARD"]', FALSE),
    ('SEOUL-ROOM-01', '서울 오피스 컨퍼런스룸', 'Seoul Conference Room', 'ROOM', 'SKAX 서울 오피스', '6F', 10, '["DISPLAY","VIDEO"]', FALSE),
    ('STUDIO-01', '고객 데모 스튜디오', 'Customer Demo Studio', 'ROOM', 'SKAX 판교 캠퍼스', '3F', 16, '["DISPLAY","VIDEO","RECORDING"]', TRUE),
    ('DESK-12F-NE', '12층 동북 좌석군', '12F North-east desks', 'DESK', 'SKAX 판교 캠퍼스', '12F', 20, '["MONITOR","ERGONOMIC"]', FALSE),
    ('KIT-HYBRID-01', '하이브리드 미팅 키트', 'Hybrid meeting kit', 'EQUIPMENT', 'SKAX 판교 캠퍼스', NULL, 1, '["CAMERA","MICROPHONE","SPEAKER"]', TRUE)
 ) seed(code, name_ko, name_en, type, site, floor_name, capacity, features, approval_required)
 WHERE tenant.tenant_key = 'default'
ON CONFLICT (tenant_id, resource_code) DO NOTHING;

WITH personal AS (
    SELECT tenant_id, owner_user_id, calendar_id
      FROM cal_calendars
     WHERE calendar_type = 'PERSONAL' AND owner_user_id BETWEEN 5 AND 25
), seeded AS (
    SELECT personal.*, seed.key, seed.day_offset, seed.start_hour, seed.duration_minutes,
           seed.title, seed.event_type, seed.location, seed.recurrence_pattern
      FROM personal
     CROSS JOIN (VALUES
        ('weekly-plan', 0, 9, 30, '이번 주 우선순위 정리', 'TASK', NULL, 'WEEKLY'),
        ('team-sync', 1, 10, 50, '팀 주간 싱크', 'MEETING', 'A타워 1201 회의실', 'WEEKLY'),
        ('focus', 2, 14, 120, '집중 업무 · 핵심 과제', 'FOCUS', NULL, 'WEEKLY'),
        ('one-on-one', 3, 16, 30, '1:1 체크인', 'MEETING', 'A타워 1202 포커스룸', 'WEEKLY'),
        ('review', 4, 15, 60, '주간 성과 리뷰', 'MEETING', '온라인', 'WEEKLY')
     ) seed(key, day_offset, start_hour, duration_minutes, title, event_type, location, recurrence_pattern)
)
INSERT INTO cal_events (
    event_id, tenant_id, calendar_id, organizer_user_id, organizer_name,
    title, description, event_type, starts_at, ends_at, time_zone,
    location, conference_url, visibility, recurrence_pattern,
    response_required, source_type, created_by, updated_by)
SELECT md5('calendar:event:' || tenant_id || ':' || owner_user_id || ':' || key)::uuid,
       tenant_id, calendar_id, owner_user_id, '나', title,
       CASE event_type
           WHEN 'FOCUS' THEN '알림을 최소화하고 핵심 과제에 집중하는 보호 시간입니다.'
           WHEN 'TASK' THEN '주간 목표와 실행 순서를 캘린더에서 바로 정리합니다.'
           ELSE '업무 맥락과 다음 행동을 함께 확인하는 일정입니다.' END,
       event_type,
       date_trunc('week', CURRENT_TIMESTAMP) + day_offset * INTERVAL '1 day'
           + start_hour * INTERVAL '1 hour',
       date_trunc('week', CURRENT_TIMESTAMP) + day_offset * INTERVAL '1 day'
           + start_hour * INTERVAL '1 hour' + duration_minutes * INTERVAL '1 minute',
       'Asia/Seoul', location,
       CASE WHEN location = '온라인' THEN 'https://meet.dwp.local/weekly-review' END,
       'DEFAULT', recurrence_pattern, FALSE, 'NATIVE', owner_user_id, owner_user_id
  FROM seeded
ON CONFLICT (event_id) DO NOTHING;

WITH company_calendar AS (
    SELECT tenant_id, calendar_id
      FROM cal_calendars WHERE calendar_key = 'skax-company'
), seeded AS (
    SELECT company_calendar.*, seed.key, seed.day_offset, seed.start_hour,
           seed.duration_minutes, seed.title, seed.description, seed.event_type, seed.location
      FROM company_calendar
     CROSS JOIN (VALUES
        ('townhall', 3, 11, 60, 'SKAX Monthly Connect', '경영 현황과 AX 실행 사례를 공유하고 실시간 질문을 받습니다.', 'MEETING', 'A타워 1501 대회의실'),
        ('learning', 4, 12, 50, 'Friday Learning · Responsible AI', '점심시간에 실무 사례 중심으로 책임 있는 AI 적용 원칙을 살펴봅니다.', 'MEETING', '고객 데모 스튜디오')
     ) seed(key, day_offset, start_hour, duration_minutes, title, description, event_type, location)
)
INSERT INTO cal_events (
    event_id, tenant_id, calendar_id, organizer_user_id, organizer_name,
    organizer_email, title, description, event_type, starts_at, ends_at,
    time_zone, location, conference_url, visibility, response_required,
    source_type, created_by, updated_by)
SELECT md5('calendar:company-event:' || tenant_id || ':' || key)::uuid,
       tenant_id, calendar_id, 10, '박현우', 'hyunwoo.park@sk.com',
       title, description, event_type,
       date_trunc('week', CURRENT_TIMESTAMP) + day_offset * INTERVAL '1 day'
           + start_hour * INTERVAL '1 hour',
       date_trunc('week', CURRENT_TIMESTAMP) + day_offset * INTERVAL '1 day'
           + start_hour * INTERVAL '1 hour' + duration_minutes * INTERVAL '1 minute',
       'Asia/Seoul', location, 'https://meet.dwp.local/skax-connect',
       'PUBLIC', TRUE, 'NATIVE', 10, 10
  FROM seeded
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO cal_event_attendees (
    attendee_id, tenant_id, event_id, attendee_user_id, attendee_email,
    attendee_name, attendee_type, response_status, responded_at)
SELECT md5('calendar:attendee:' || event.tenant_id || ':' || event.event_id || ':' || user_id)::uuid,
       event.tenant_id, event.event_id, user_id,
       'member' || user_id || '@sk.com', 'SKAX 구성원 ' || user_id,
       'REQUIRED',
       CASE WHEN user_id % 5 = 0 THEN 'NEEDS_ACTION'
            WHEN user_id % 7 = 0 THEN 'TENTATIVE'
            ELSE 'ACCEPTED' END,
       CASE WHEN user_id % 5 = 0 THEN NULL ELSE CURRENT_TIMESTAMP - INTERVAL '1 day' END
  FROM cal_events event
 CROSS JOIN generate_series(5, 25) user_id
 WHERE event.event_id = md5('calendar:company-event:' || event.tenant_id || ':townhall')::uuid
ON CONFLICT (event_id, attendee_email) DO NOTHING;

INSERT INTO cal_resource_bookings (
    booking_id, tenant_id, event_id, resource_id, starts_at, ends_at,
    booking_status, created_by, updated_by)
SELECT md5('calendar:booking:' || event.event_id || ':' || resource.resource_id)::uuid,
       event.tenant_id, event.event_id, resource.resource_id,
       event.starts_at, event.ends_at, 'CONFIRMED', event.organizer_user_id, event.organizer_user_id
  FROM cal_events event
  JOIN cal_resources resource
    ON resource.tenant_id = event.tenant_id
   AND resource.name_ko = event.location
 WHERE event.status = 'CONFIRMED'
ON CONFLICT (event_id, resource_id) DO NOTHING;

COMMENT ON TABLE cal_tenant_policies IS
    'Tenant scheduling guardrails. Administrators manage policy without access to private event details.';
COMMENT ON TABLE cal_events IS
    'Native and synchronized calendar events. External source references support future Google and Microsoft adapters.';
COMMENT ON TABLE cal_audit_events IS
    'Append-only evidence for calendar mutations, responses, and resource decisions.';
