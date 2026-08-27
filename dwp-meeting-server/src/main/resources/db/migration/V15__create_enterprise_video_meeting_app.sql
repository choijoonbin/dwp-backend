CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Independent workforce projection. Production updates arrive from People/Auth
-- lifecycle events; local SKAX rows use the same stable identity references as
-- msg_people_snapshot without introducing a cross-database runtime join.
CREATE TABLE vm_people_snapshot (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    person_public_id UUID,
    email_address VARCHAR(255) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    job_title VARCHAR(180),
    organization_name VARCHAR(180),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT uk_vm_people_email UNIQUE (tenant_id, email_address),
    CONSTRAINT ck_vm_people_state CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE vm_tenant_policies (
    tenant_id BIGINT PRIMARY KEY,
    meetings_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    waiting_room_required BOOLEAN NOT NULL DEFAULT TRUE,
    guests_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    participant_chat_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    reactions_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    screen_share_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    unmute_control VARCHAR(24) NOT NULL DEFAULT 'REQUEST_ONLY',
    recording_policy VARCHAR(24) NOT NULL DEFAULT 'NEVER',
    allow_join_before_host BOOLEAN NOT NULL DEFAULT FALSE,
    require_authenticated_internal_users BOOLEAN NOT NULL DEFAULT TRUE,
    maximum_participants INTEGER NOT NULL DEFAULT 100,
    retention_days INTEGER NOT NULL DEFAULT 1095,
    artifact_retention_days INTEGER NOT NULL DEFAULT 365,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_vm_policy_unmute CHECK (unmute_control = 'REQUEST_ONLY'),
    CONSTRAINT ck_vm_policy_recording CHECK (
        recording_policy IN ('NEVER', 'HOST_OPT_IN', 'ADMIN_REQUIRED')),
    CONSTRAINT ck_vm_policy_capacity CHECK (maximum_participants BETWEEN 2 AND 1000),
    CONSTRAINT ck_vm_policy_retention CHECK (retention_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_vm_policy_artifact_retention CHECK (
        artifact_retention_days BETWEEN 1 AND retention_days)
);

CREATE TABLE vm_meetings (
    meeting_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    meeting_kind VARCHAR(24) NOT NULL DEFAULT 'FORMAL_MEETING',
    title VARCHAR(240) NOT NULL,
    description VARCHAR(4000),
    agenda VARCHAR(8000),
    lifecycle_state VARCHAR(20) NOT NULL,
    access_scope VARCHAR(20) NOT NULL DEFAULT 'INVITED',
    join_code VARCHAR(26) NOT NULL,
    scheduled_start_at TIMESTAMPTZ,
    scheduled_end_at TIMESTAMPTZ,
    time_zone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
    waiting_room_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    guest_access_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    allow_join_before_host BOOLEAN NOT NULL DEFAULT FALSE,
    default_microphone_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_camera_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    provider VARCHAR(24),
    room_name VARCHAR(180),
    organizer_user_id BIGINT NOT NULL,
    organizer_person_public_id UUID,
    organizer_name VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(160),
    request_hash CHAR(64),
    correlation_id VARCHAR(160),
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    ended_by BIGINT,
    decisions JSONB NOT NULL DEFAULT '[]'::jsonb,
    follow_up_actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_vm_meeting_tenant_id UNIQUE (tenant_id, meeting_id),
    CONSTRAINT uk_vm_meeting_join_code UNIQUE (tenant_id, join_code),
    CONSTRAINT ck_vm_meeting_kind CHECK (meeting_kind = 'FORMAL_MEETING'),
    CONSTRAINT ck_vm_meeting_state CHECK (
        lifecycle_state IN ('DRAFT', 'SCHEDULED', 'LOBBY', 'LIVE', 'ENDED', 'CANCELLED')),
    CONSTRAINT ck_vm_meeting_scope CHECK (
        access_scope IN ('INTERNAL', 'INVITED', 'PUBLIC_CODE')),
    CONSTRAINT ck_vm_meeting_join_code CHECK (
        join_code ~ '^[A-HJ-NP-Z2-9]{10,16}$'),
    CONSTRAINT ck_vm_meeting_schedule CHECK (
        (scheduled_start_at IS NULL AND scheduled_end_at IS NULL)
        OR (scheduled_start_at IS NOT NULL AND scheduled_end_at > scheduled_start_at)),
    CONSTRAINT ck_vm_meeting_live_provider CHECK (
        lifecycle_state NOT IN ('LIVE', 'ENDED')
        OR (provider IS NOT NULL AND room_name IS NOT NULL AND started_at IS NOT NULL)),
    CONSTRAINT ck_vm_meeting_end CHECK (
        lifecycle_state <> 'ENDED' OR (ended_at IS NOT NULL AND ended_by IS NOT NULL)),
    CONSTRAINT ck_vm_meeting_json CHECK (
        jsonb_typeof(decisions) = 'array' AND jsonb_typeof(follow_up_actions) = 'array'),
    CONSTRAINT ck_vm_meeting_idempotency CHECK (
        (idempotency_key IS NULL) = (request_hash IS NULL))
);

CREATE UNIQUE INDEX uk_vm_meeting_idempotency
    ON vm_meetings (tenant_id, organizer_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE vm_meeting_participants (
    participant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    user_id BIGINT,
    person_public_id UUID,
    email_address VARCHAR(255) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    job_title VARCHAR(180),
    organization_name VARCHAR(180),
    participant_role VARCHAR(20) NOT NULL,
    attendance_state VARCHAR(20) NOT NULL DEFAULT 'INVITED',
    can_self_unmute BOOLEAN NOT NULL DEFAULT TRUE,
    join_requested_at TIMESTAMPTZ,
    admitted_at TIMESTAMPTZ,
    admitted_by BIGINT,
    joined_at TIMESTAMPTZ,
    left_at TIMESTAMPTZ,
    unmute_requested_at TIMESTAMPTZ,
    unmute_requested_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_vm_participant_tenant_meeting_id UNIQUE (
        tenant_id, meeting_id, participant_id),
    CONSTRAINT fk_vm_participant_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_participant_role CHECK (
        participant_role IN ('ORGANIZER', 'CO_HOST', 'PRESENTER', 'ATTENDEE', 'GUEST')),
    CONSTRAINT ck_vm_participant_state CHECK (
        attendance_state IN ('INVITED', 'REQUESTED', 'ADMITTED', 'DENIED', 'JOINED', 'LEFT')),
    CONSTRAINT ck_vm_participant_admission CHECK (
        attendance_state NOT IN ('ADMITTED', 'JOINED', 'LEFT') OR admitted_at IS NOT NULL),
    CONSTRAINT ck_vm_participant_join CHECK (
        attendance_state NOT IN ('JOINED', 'LEFT') OR joined_at IS NOT NULL),
    CONSTRAINT ck_vm_participant_leave CHECK (
        attendance_state <> 'LEFT' OR left_at IS NOT NULL),
    CONSTRAINT ck_vm_participant_unmute_request CHECK (
        (unmute_requested_at IS NULL) = (unmute_requested_by IS NULL))
);

CREATE UNIQUE INDEX uk_vm_participant_user
    ON vm_meeting_participants (tenant_id, meeting_id, user_id)
    WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uk_vm_participant_email
    ON vm_meeting_participants (tenant_id, meeting_id, lower(email_address));

CREATE TABLE vm_meeting_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    meeting_id UUID,
    participant_id UUID,
    actor_user_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    correlation_id VARCHAR(160),
    idempotency_key VARCHAR(160),
    event_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_event_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_vm_event_participant FOREIGN KEY (
        tenant_id, meeting_id, participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_vm_event_type CHECK (event_type IN (
        'CREATED', 'SCHEDULED', 'JOIN_REQUESTED', 'ADMITTED', 'DENIED',
        'STARTED', 'TOKEN_ISSUED', 'JOINED', 'LEFT', 'ENDED', 'CANCELLED',
        'UNMUTE_REQUESTED', 'POLICY_UPDATED')),
    CONSTRAINT ck_vm_event_meeting_scope CHECK (
        (event_type = 'POLICY_UPDATED' AND meeting_id IS NULL)
        OR (event_type <> 'POLICY_UPDATED' AND meeting_id IS NOT NULL)),
    CONSTRAINT ck_vm_event_payload CHECK (jsonb_typeof(event_payload) = 'object')
);

CREATE UNIQUE INDEX uk_vm_event_idempotency
    ON vm_meeting_events (tenant_id, event_type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE vm_meeting_artifacts (
    artifact_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    artifact_type VARCHAR(24) NOT NULL,
    artifact_state VARCHAR(20) NOT NULL DEFAULT 'NONE',
    storage_provider VARCHAR(32),
    object_key VARCHAR(1000),
    content_type VARCHAR(120),
    size_bytes BIGINT,
    sha256 CHAR(64),
    retention_until TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_vm_artifact_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT uk_vm_artifact_kind UNIQUE (tenant_id, meeting_id, artifact_type),
    CONSTRAINT ck_vm_artifact_type CHECK (
        artifact_type IN ('RECORDING', 'TRANSCRIPT', 'SUMMARY', 'ATTENDANCE', 'CHAT_EXPORT')),
    CONSTRAINT ck_vm_artifact_state CHECK (
        artifact_state IN ('NONE', 'PROCESSING', 'AVAILABLE', 'UNAVAILABLE', 'FAILED', 'DELETED')),
    CONSTRAINT ck_vm_artifact_available CHECK (
        artifact_state <> 'AVAILABLE'
        OR (storage_provider IS NOT NULL AND object_key IS NOT NULL
            AND content_type IS NOT NULL AND size_bytes >= 0 AND sha256 IS NOT NULL)),
    CONSTRAINT ck_vm_artifact_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX ix_vm_meeting_home
    ON vm_meetings (tenant_id, lifecycle_state, scheduled_start_at, updated_at DESC);
CREATE INDEX ix_vm_meeting_organizer
    ON vm_meetings (tenant_id, organizer_user_id, created_at DESC);
CREATE INDEX ix_vm_participant_user_home
    ON vm_meeting_participants (tenant_id, user_id, meeting_id, attendance_state);
CREATE INDEX ix_vm_participant_waiting_room
    ON vm_meeting_participants (tenant_id, meeting_id, attendance_state, join_requested_at)
    WHERE attendance_state = 'REQUESTED';
CREATE INDEX ix_vm_event_history
    ON vm_meeting_events (tenant_id, meeting_id, occurred_at DESC);
CREATE INDEX ix_vm_artifact_retention
    ON vm_meeting_artifacts (artifact_state, retention_until)
    WHERE artifact_state IN ('AVAILABLE', 'FAILED');

INSERT INTO vm_tenant_policies (
    tenant_id, meetings_enabled, waiting_room_required, guests_allowed,
    participant_chat_allowed, reactions_allowed, screen_share_allowed,
    unmute_control, recording_policy, retention_days, artifact_retention_days,
    created_by, updated_by)
VALUES (
    1, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE,
    'REQUEST_ONLY', 'NEVER', 1095, 365, 1, 1)
ON CONFLICT (tenant_id) DO NOTHING;

WITH workforce (
    user_id, person_public_id, email_address, display_name,
    job_title, organization_name
) AS (
    VALUES
        (3, '34f5e51a-2ca6-c6f6-6627-b44f08f31d1d'::uuid, 'seoyeon.lee@sk.com', '이서연', 'Executive Strategy Officer', 'CEO Staff'),
        (4, '5af80da3-0dd8-b3bc-2f44-22d90eecaac4'::uuid, 'hyunwoo.park@sk.com', '박현우', 'Digital Platform 부문장', 'Digital Platform 부문'),
        (5, '00ba0853-02a8-7499-b6d8-009251e6a464'::uuid, 'yujin.choi@sk.com', '최유진', 'Enterprise Transformation 부문장', 'Enterprise Transformation 부문'),
        (8, '306b543f-741f-6fd3-36bf-48325f3e7e20'::uuid, 'doyun.kim@sk.com', '김도윤', 'AI Platform 본부장', 'AI Platform 본부'),
        (9, 'e96089af-ead6-2f6a-6111-1d2e15058b1d'::uuid, 'seojin.yoon@sk.com', '윤서진', 'Cloud & Infra 본부장', 'Cloud & Infra 본부'),
        (10, 'bda29b83-7a8f-ded4-083b-244f055bd6c4'::uuid, 'minseok.jang@sk.com', '장민석', 'GenAI Engineering 팀장', 'GenAI Engineering 팀'),
        (14, '71ed1904-1405-e7ce-3f27-0845298ba1e2'::uuid, 'subin.oh@sk.com', '오수빈', 'Data Platform 팀장', 'Data Platform 팀'),
        (15, '94d55a4f-96de-09fd-5454-bbd64b60ccb3'::uuid, 'taehoon.kang@sk.com', '강태훈', 'Data Architect', 'Data Platform 팀'),
        (16, '457477f1-ee4a-9b12-3668-ec7663989ee5'::uuid, 'yerin.moon@sk.com', '문예린', 'Analytics Engineer', 'Data Platform 팀'),
        (18, 'a3e07946-57b1-4441-ae00-d14ad9eb284c'::uuid, 'jiwoo.bae@sk.com', '배지우', 'Site Reliability Engineer', 'Cloud Platform 팀'),
        (20, '3edde887-9716-8950-e7a0-045998101987'::uuid, 'minseo.kim@sk.com', '김민서', 'Network Operations Lead', 'Network Operations 팀'),
        (23, 'd4bc013d-8c7a-fbcb-be2a-7d83286e0b18'::uuid, 'chaewon.kim@sk.com', '김채원', 'SAP Transformation Consultant', 'ERP Innovation 본부'),
        (26, '6edd429e-6650-00a3-d68a-2bd4cc954551'::uuid, 'seowoo.jung@sk.com', '정서우', 'Business Consultant', 'Digital Consulting 본부'),
        (27, 'd1b648ab-318d-824d-50f6-11c418b75f9a'::uuid, 'dohyun.lee@sk.com', '이도현', 'Change Management Lead', 'Digital Consulting 본부'),
        (29, '6625e4a8-eaa9-c5d7-20bc-47f2029677b3'::uuid, 'gunwoo.choi@sk.com', '최건우', 'UX Strategist', 'Customer Experience 팀'),
        (31, '073c6aef-f778-94ac-4bb3-0e355fa41dbc'::uuid, 'jisoo.hong@sk.com', '홍지수', 'People & Culture 팀장', 'People & Culture 팀'),
        (32, '3490c134-c01b-d32d-eda2-f257c94496f2'::uuid, 'doyoon.nam@sk.com', '남도윤', 'HR Business Partner', 'People & Culture 팀'),
        (34, '6dddb2e7-e311-0455-2c15-55d1ff0e2379'::uuid, 'taeyeon.kim@sk.com', '김태연', 'Finance & Risk 팀장', 'Finance & Risk 팀'),
        (35, 'cc4804fd-f65a-998f-b162-4c2d594ec767'::uuid, 'seungmin.yoo@sk.com', '유승민', 'Financial Controller', 'Finance & Risk 팀'),
        (36, 'aaf32653-4578-46a9-c679-7302615e84cc'::uuid, 'james.wilson@sk.com', 'James Wilson', 'Risk Analyst', 'Finance & Risk 팀'),
        (900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid, 'joonbin@sk.com', '최준빈', 'SKAX integrated verification administrator', 'Tenant Control Plane')
)
INSERT INTO vm_people_snapshot (
    tenant_id, user_id, person_public_id, email_address, display_name,
    job_title, organization_name, lifecycle_state)
SELECT 1, user_id, person_public_id, email_address, display_name,
       job_title, organization_name, 'ACTIVE'
  FROM workforce
ON CONFLICT (tenant_id, user_id) DO UPDATE SET
    person_public_id = EXCLUDED.person_public_id,
    email_address = EXCLUDED.email_address,
    display_name = EXCLUDED.display_name,
    job_title = EXCLUDED.job_title,
    organization_name = EXCLUDED.organization_name,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

WITH meeting_seed (
    meeting_key, title, description, agenda, lifecycle_state, access_scope,
    join_code, start_at, end_at, waiting_room, guest_access,
    organizer_user_id, provider, room_name, started_at, ended_at, ended_by,
    decisions, follow_ups
) AS (
    VALUES
        ('live-platform-operations', 'DWP 플랫폼 운영 점검',
         '서비스 상태와 오늘의 릴리스 위험을 점검합니다.',
         '1. 서비스 상태  2. 릴리스 위험  3. 담당자 확인',
         'LIVE', 'INVITED', '7K9M4Q2X8R6T',
         CURRENT_TIMESTAMP - INTERVAL '20 minutes', CURRENT_TIMESTAMP + INTERVAL '40 minutes',
         TRUE, FALSE, 4, 'LIVEKIT', 'dwp-meeting-seed-live-operations',
         CURRENT_TIMESTAMP - INTERVAL '18 minutes', NULL, NULL,
         '[]'::jsonb, '[]'::jsonb),
        ('upcoming-ai-governance', 'AI 거버넌스 설계 리뷰',
         '통제된 AI 실행 정책과 감사 근거를 검토합니다.',
         '정책 경계, 사용자 승인, 감사 이벤트, 다음 릴리스',
         'SCHEDULED', 'INVITED', '6W3R8T5Y2P9K',
         CURRENT_TIMESTAMP + INTERVAL '3 hours', CURRENT_TIMESTAMP + INTERVAL '4 hours',
         TRUE, TRUE, 5, NULL, NULL, NULL, NULL, NULL,
         '[]'::jsonb, '[]'::jsonb),
        ('upcoming-customer-design', '고객 경험 디자인 워크숍',
         '홈과 협업 앱의 핵심 사용자 여정을 검증합니다.',
         '인사이트 공유, 프로토타입 리뷰, 실험 과제 선정',
         'SCHEDULED', 'INTERNAL', '8N5C2V7B4X9M',
         CURRENT_TIMESTAMP + INTERVAL '1 day 2 hours', CURRENT_TIMESTAMP + INTERVAL '1 day 3 hours 30 minutes',
         TRUE, FALSE, 29, NULL, NULL, NULL, NULL, NULL,
         '[]'::jsonb, '[]'::jsonb),
        ('ended-quarterly-review', 'Digital Platform 분기 운영 리뷰',
         '분기 안정성 지표와 다음 분기 투자 우선순위를 확정했습니다.',
         'SLO, 고객 영향, 용량 계획, 실행 책임자',
         'ENDED', 'INVITED', '9R4T7Y2W6K3M',
         CURRENT_TIMESTAMP - INTERVAL '3 days 2 hours', CURRENT_TIMESTAMP - INTERVAL '3 days 1 hour',
         TRUE, FALSE, 4, 'LIVEKIT', 'dwp-meeting-seed-quarterly-review',
         CURRENT_TIMESTAMP - INTERVAL '3 days 2 hours', CURRENT_TIMESTAMP - INTERVAL '3 days 1 hour', 4,
         '[{"decision":"핵심 서비스 SLO를 99.95%로 상향","ownerUserId":18,"status":"CONFIRMED"}]'::jsonb,
         '[{"action":"네트워크 용량 계획 갱신","ownerUserId":20,"dueInDays":7,"status":"OPEN"}]'::jsonb),
        ('ended-experience-review', 'DWP 홈 경험 디자인 리뷰',
         '개인화 홈과 도움 센터의 정보 구조를 검증했습니다.',
         '홈 위젯, 편집 모드, 이용 가이드, 접근성',
         'ENDED', 'INTERNAL', '5X8M3Q7R2V9K',
         CURRENT_TIMESTAMP - INTERVAL '7 days 90 minutes', CURRENT_TIMESTAMP - INTERVAL '7 days',
         TRUE, FALSE, 29, 'LIVEKIT', 'dwp-meeting-seed-experience-review',
         CURRENT_TIMESTAMP - INTERVAL '7 days 90 minutes', CURRENT_TIMESTAMP - INTERVAL '7 days', 29,
         '[{"decision":"도움 센터는 오버레이 레일로 제공","ownerUserId":29,"status":"CONFIRMED"}]'::jsonb,
         '[{"action":"키보드 접근성 검증","ownerUserId":26,"dueInDays":5,"status":"DONE"}]'::jsonb)
)
INSERT INTO vm_meetings (
    meeting_id, tenant_id, title, description, agenda, lifecycle_state,
    access_scope, join_code, scheduled_start_at, scheduled_end_at, time_zone,
    waiting_room_enabled, guest_access_enabled, provider, room_name,
    organizer_user_id, organizer_person_public_id, organizer_name,
    started_at, ended_at, ended_by, decisions, follow_up_actions,
    created_by, updated_by)
SELECT md5('vm:meeting:1:' || seed.meeting_key)::uuid, 1,
       seed.title, seed.description, seed.agenda, seed.lifecycle_state,
       seed.access_scope, seed.join_code, seed.start_at, seed.end_at, 'Asia/Seoul',
       seed.waiting_room, seed.guest_access, seed.provider, seed.room_name,
       seed.organizer_user_id, organizer.person_public_id, organizer.display_name,
       seed.started_at, seed.ended_at, seed.ended_by, seed.decisions, seed.follow_ups,
       seed.organizer_user_id, seed.organizer_user_id
  FROM meeting_seed seed
  JOIN vm_people_snapshot organizer
    ON organizer.tenant_id = 1 AND organizer.user_id = seed.organizer_user_id
ON CONFLICT (meeting_id) DO NOTHING;

WITH participant_seed (meeting_key, user_id, role, attendance_state) AS (
    VALUES
        ('live-platform-operations', 4, 'ORGANIZER', 'JOINED'),
        ('live-platform-operations', 18, 'PRESENTER', 'JOINED'),
        ('live-platform-operations', 20, 'ATTENDEE', 'ADMITTED'),
        ('live-platform-operations', 9, 'ATTENDEE', 'REQUESTED'),
        ('upcoming-ai-governance', 5, 'ORGANIZER', 'ADMITTED'),
        ('upcoming-ai-governance', 4, 'CO_HOST', 'INVITED'),
        ('upcoming-ai-governance', 10, 'PRESENTER', 'INVITED'),
        ('upcoming-ai-governance', 14, 'ATTENDEE', 'INVITED'),
        ('upcoming-customer-design', 29, 'ORGANIZER', 'ADMITTED'),
        ('upcoming-customer-design', 26, 'PRESENTER', 'INVITED'),
        ('upcoming-customer-design', 27, 'ATTENDEE', 'INVITED'),
        ('upcoming-customer-design', 4, 'ATTENDEE', 'INVITED'),
        ('ended-quarterly-review', 4, 'ORGANIZER', 'LEFT'),
        ('ended-quarterly-review', 18, 'PRESENTER', 'LEFT'),
        ('ended-quarterly-review', 20, 'ATTENDEE', 'LEFT'),
        ('ended-quarterly-review', 5, 'ATTENDEE', 'LEFT'),
        ('ended-experience-review', 29, 'ORGANIZER', 'LEFT'),
        ('ended-experience-review', 26, 'PRESENTER', 'LEFT'),
        ('ended-experience-review', 4, 'ATTENDEE', 'LEFT')
)
INSERT INTO vm_meeting_participants (
    participant_id, tenant_id, meeting_id, user_id, person_public_id,
    email_address, display_name, job_title, organization_name,
    participant_role, attendance_state, can_self_unmute,
    join_requested_at, admitted_at, admitted_by, joined_at, left_at,
    created_by, updated_by)
SELECT md5('vm:participant:1:' || seed.meeting_key || ':' || seed.user_id)::uuid,
       1, meeting.meeting_id, person.user_id, person.person_public_id,
       person.email_address, person.display_name, person.job_title,
       person.organization_name, seed.role, seed.attendance_state, TRUE,
       CASE WHEN seed.attendance_state = 'REQUESTED' THEN CURRENT_TIMESTAMP - INTERVAL '2 minutes' END,
       CASE WHEN seed.attendance_state IN ('ADMITTED', 'JOINED', 'LEFT') THEN
           COALESCE(meeting.started_at, CURRENT_TIMESTAMP) END,
       CASE WHEN seed.attendance_state IN ('ADMITTED', 'JOINED', 'LEFT') THEN meeting.organizer_user_id END,
       CASE WHEN seed.attendance_state IN ('JOINED', 'LEFT') THEN meeting.started_at END,
       CASE WHEN seed.attendance_state = 'LEFT' THEN meeting.ended_at END,
       meeting.organizer_user_id, meeting.organizer_user_id
  FROM participant_seed seed
  JOIN vm_meetings meeting
    ON meeting.tenant_id = 1
   AND meeting.meeting_id = md5('vm:meeting:1:' || seed.meeting_key)::uuid
  JOIN vm_people_snapshot person
    ON person.tenant_id = 1 AND person.user_id = seed.user_id
ON CONFLICT (participant_id) DO NOTHING;

INSERT INTO vm_meeting_events (
    event_id, tenant_id, meeting_id, actor_user_id, event_type,
    correlation_id, event_payload, occurred_at)
SELECT md5('vm:event:created:' || meeting.meeting_id)::uuid,
       meeting.tenant_id, meeting.meeting_id, meeting.organizer_user_id,
       CASE WHEN meeting.lifecycle_state = 'SCHEDULED' THEN 'SCHEDULED' ELSE 'CREATED' END,
       'seed:skax:video-meetings',
       jsonb_build_object('source', 'SKAX_DEVELOPMENT_SEED'), meeting.created_at
  FROM vm_meetings meeting
 WHERE meeting.tenant_id = 1
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO vm_meeting_events (
    event_id, tenant_id, meeting_id, actor_user_id, event_type,
    correlation_id, event_payload, occurred_at)
SELECT md5('vm:event:started:' || meeting.meeting_id)::uuid,
       meeting.tenant_id, meeting.meeting_id, meeting.organizer_user_id,
       'STARTED', 'seed:skax:video-meetings',
       jsonb_build_object('provider', meeting.provider), meeting.started_at
  FROM vm_meetings meeting
 WHERE meeting.tenant_id = 1 AND meeting.started_at IS NOT NULL
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO vm_meeting_events (
    event_id, tenant_id, meeting_id, actor_user_id, event_type,
    correlation_id, event_payload, occurred_at)
SELECT md5('vm:event:ended:' || meeting.meeting_id)::uuid,
       meeting.tenant_id, meeting.meeting_id, meeting.ended_by,
       'ENDED', 'seed:skax:video-meetings',
       jsonb_build_object('provider', meeting.provider), meeting.ended_at
  FROM vm_meetings meeting
 WHERE meeting.tenant_id = 1 AND meeting.lifecycle_state = 'ENDED'
ON CONFLICT (event_id) DO NOTHING;

-- P0 contains no media pipeline. Rows are explicit about the absence of an
-- object so clients cannot mistake seeded metadata for a playable artifact.
INSERT INTO vm_meeting_artifacts (
    artifact_id, tenant_id, meeting_id, artifact_type, artifact_state,
    metadata, created_by, updated_by)
SELECT md5('vm:artifact:recording:' || meeting.meeting_id)::uuid,
       meeting.tenant_id, meeting.meeting_id, 'RECORDING', 'NONE',
       '{"reason":"EGRESS_NOT_CONFIGURED"}'::jsonb,
       meeting.organizer_user_id, meeting.organizer_user_id
  FROM vm_meetings meeting
 WHERE meeting.tenant_id = 1 AND meeting.lifecycle_state = 'ENDED'
UNION ALL
SELECT md5('vm:artifact:transcript:' || meeting.meeting_id)::uuid,
       meeting.tenant_id, meeting.meeting_id, 'TRANSCRIPT', 'UNAVAILABLE',
       '{"reason":"TRANSCRIPTION_NOT_CONFIGURED"}'::jsonb,
       meeting.organizer_user_id, meeting.organizer_user_id
  FROM vm_meetings meeting
 WHERE meeting.tenant_id = 1 AND meeting.lifecycle_state = 'ENDED'
ON CONFLICT (tenant_id, meeting_id, artifact_type) DO NOTHING;
