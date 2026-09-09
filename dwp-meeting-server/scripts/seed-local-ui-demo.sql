-- Explicit operator-only LOCAL demonstration data. Never register as a Flyway migration.
-- Default is a complete constraint-checked rehearsal followed by ROLLBACK.
-- Apply only to the local dwp-postgres container after a protected database backup.
\set ON_ERROR_STOP on
\if :{?apply_seed}
\else
  \set apply_seed false
\endif
\if :{?refresh_demo_schedule}
\else
  \set refresh_demo_schedule false
\endif

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL TIME ZONE 'Asia/Seoul';
SELECT pg_advisory_xact_lock(190904, 900018);

DO $$
BEGIN
    IF current_database() <> 'dwp_meetings' THEN
        RAISE EXCEPTION 'Only the explicitly selected local dwp_meetings database is supported';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '38' AND success)
       OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'A healthy V38 Meeting schema is required';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vm_people_snapshot WHERE tenant_id = 1
            AND user_id = 900018 AND email_address = 'joonbin@sk.com'
            AND lifecycle_state = 'ACTIVE') THEN
        RAISE EXCEPTION 'Verified tenant 1 / user 900018 projection is required';
    END IF;
    IF (SELECT count(*) FROM vm_people_snapshot WHERE tenant_id = 1
            AND user_id IN (3, 4, 8, 10, 14, 15, 29) AND lifecycle_state = 'ACTIVE') <> 7 THEN
        RAISE EXCEPTION 'The expected local workforce reference population is missing';
    END IF;
END $$;

-- Exact before-images remain transaction-local, never logged or exported.
-- The postcondition rejects alteration/deletion of ANY pre-existing row in these tables.
CREATE TEMP TABLE demo_before (table_name TEXT NOT NULL, row_value JSONB NOT NULL) ON COMMIT DROP;
DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'vm_meetings', 'vm_meeting_participants', 'vm_meeting_preparations',
        'vm_meeting_agenda_items', 'vm_meeting_invitation_responses', 'vm_meeting_artifacts',
        'vm_meeting_content_plans', 'vm_meeting_preparation_materials',
        'vm_meeting_personal_preparations', 'vm_meeting_personal_preparation_items',
        'vm_meeting_collaboration_sequences', 'vm_meeting_chat_messages',
        'vm_meeting_hand_requests', 'vm_meeting_hand_events',
        'vm_meeting_facilitation_states', 'vm_meeting_facilitation_questions',
        'vm_meeting_facilitation_question_upvotes', 'vm_meeting_facilitation_polls',
        'vm_meeting_facilitation_poll_options', 'vm_meeting_facilitation_poll_votes',
        'vm_meeting_events',
        'vm_meeting_templates', 'vm_meeting_template_revisions',
        'vm_meeting_template_agenda_items', 'vm_meeting_template_favorites',
        'vm_meeting_template_sources', 'vm_personal_meeting_rooms', 'vm_meeting_user_preferences'
    ] LOOP
        EXECUTE format('INSERT INTO demo_before SELECT %L, to_jsonb(t) FROM public.%I t',
            table_name, table_name);
    END LOOP;
END $$;

-- Stable RFC-compatible IDs, but cryptographically random invitation secrets.
CREATE FUNCTION pg_temp.demo_id(label TEXT) RETURNS UUID LANGUAGE SQL IMMUTABLE AS $$
    SELECT (substr(h, 1, 8) || '-' || substr(h, 9, 4) || '-5' || substr(h, 14, 3)
        || '-a' || substr(h, 18, 3) || '-' || substr(h, 21, 12))::uuid
    FROM (SELECT md5('meeting-ui-demo-v1|1|900018|' || label) h) input;
$$;
CREATE FUNCTION pg_temp.demo_join_code() RETURNS TEXT LANGUAGE plpgsql VOLATILE AS $$
DECLARE entropy BYTEA := gen_random_bytes(16); result TEXT := ''; i INTEGER;
BEGIN
    FOR i IN 0..15 LOOP
        result := result || substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', get_byte(entropy, i) % 32 + 1, 1);
    END LOOP;
    RETURN result;
END $$;

CREATE TEMP TABLE demo_template_plan (
    seq INTEGER PRIMARY KEY, template_id UUID, name TEXT, category TEXT,
    duration INTEGER, scope TEXT, purpose TEXT
) ON COMMIT DROP;
INSERT INTO demo_template_plan (seq, name, category, duration, scope, purpose) VALUES
    (1, '주간 팀 정례', 'TEAM', 30, 'PERSONAL', '지난주 실행 결과와 이번 주 우선순위를 짧게 합의합니다.'),
    (2, '제품 출시 Go / No-Go', 'DECISION', 45, 'PERSONAL', '품질·운영·고객 준비 상태를 비교하고 출시 조건을 결정합니다.'),
    (3, '1:1 성장 대화', 'ONE_ON_ONE', 30, 'PERSONAL', '진행 중인 업무와 필요한 지원, 다음 성장 목표를 확인합니다.'),
    (4, '스프린트 회고', 'RETROSPECTIVE', 60, 'PERSONAL', '유지할 점과 개선할 점을 구분하고 다음 실험을 정합니다.'),
    (5, '서비스 경험 워크숍', 'WORKSHOP', 90, 'PERSONAL', '사용자 여정의 불편과 해결 가설을 함께 구체화합니다.'),
    (6, '고객 요구사항 검토', 'OTHER', 45, 'PERSONAL', '가상 고객의 요구를 수용 기준과 후속 질문으로 정리합니다.'),
    (7, '아키텍처 의사결정', 'DECISION', 60, 'PERSONAL', '대안별 가용성·보안·비용을 비교하고 ADR 초안을 검토합니다.'),
    (8, '글로벌 협업 / APAC·EMEA Sync', 'TEAM', 45, 'PERSONAL', '지역별 의존성과 일정 차이를 확인하는 한영 혼합 시연입니다.'),
    (9, '프로젝트 킥오프 표준', 'WORKSHOP', 60, 'ORGANIZATION', '범위·책임·산출물·공동 작업 방식을 합의하는 조직 시연 양식입니다.'),
    (10, '보안·개인정보 검토 표준', 'DECISION', 45, 'ORGANIZATION', '민감정보 최소 수집과 접근·보존 정책을 검토하는 시연 양식입니다.'),
    (11, '운영 개선 회고 표준', 'RETROSPECTIVE', 60, 'ORGANIZATION', '실제 장애 기록이 아닌 가상 사례로 재발 방지 과제를 도출합니다.'),
    (12, '부문 간 의존성 점검', 'TEAM', 30, 'ORGANIZATION', '선행 작업·인계 조건·미결 질문을 확인하는 조직 시연 양식입니다.');
UPDATE demo_template_plan SET template_id = pg_temp.demo_id('template:' || seq),
    name = '[화면점검] ' || name, purpose = '[합성 시연 / 실제 업무 아님] ' || purpose;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM demo_template_plan p JOIN vm_meeting_templates t USING (template_id)
            WHERE t.tenant_id <> 1 OR t.owner_user_id <> 900018) THEN
        RAISE EXCEPTION 'Reserved demo template identity collision';
    END IF;
END $$;

CREATE TEMP TABLE demo_new_templates (template_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_templates (template_id, tenant_id, owner_user_id, template_scope,
        name, purpose, category, duration_minutes, updated_by)
    SELECT template_id, 1, 900018, scope, name, purpose, category, duration, 900018
    FROM demo_template_plan ON CONFLICT (template_id) DO NOTHING RETURNING template_id
) INSERT INTO demo_new_templates SELECT template_id FROM inserted;

INSERT INTO vm_meeting_template_agenda_items
    (tenant_id, template_id, position, title, description, presenter_role, duration_minutes)
SELECT 1, p.template_id, agenda.position, agenda.title, agenda.description, agenda.role,
       CASE agenda.position WHEN 0 THEN p.duration / 3 WHEN 1 THEN p.duration / 3
            ELSE p.duration - 2 * (p.duration / 3) END
FROM demo_template_plan p JOIN demo_new_templates n USING (template_id)
CROSS JOIN (VALUES
    (0, '목표와 현재 상황 확인', '회의 목적과 오늘 필요한 판단을 확인합니다.', '주최자'),
    (1, '대안·쟁점 함께 검토', '사실과 가정을 구분하고 각 대안의 영향을 비교합니다.', '발표자'),
    (2, '결론·다음 행동 정리', '합의 사항, 담당자와 다음 확인 시점을 정리합니다.', '진행자')
) agenda(position, title, description, role);

INSERT INTO vm_meeting_template_revisions (tenant_id, template_id, revision, snapshot, created_by)
SELECT 1, p.template_id, 0,
    jsonb_build_object('name', p.name, 'purpose', p.purpose, 'category', p.category,
        'durationMinutes', p.duration, 'agendaItems', (
            SELECT jsonb_agg(jsonb_build_object('title', a.title, 'description', a.description,
                'role', a.presenter_role, 'durationMinutes', a.duration_minutes) ORDER BY a.position)
            FROM vm_meeting_template_agenda_items a WHERE a.tenant_id = 1 AND a.template_id = p.template_id)),
    900018
FROM demo_template_plan p JOIN demo_new_templates n USING (template_id);
INSERT INTO vm_meeting_template_favorites (tenant_id, user_id, template_id)
SELECT 1, 900018, p.template_id FROM demo_template_plan p
JOIN demo_new_templates n USING (template_id) WHERE p.seq IN (1, 2, 3);

CREATE TEMP TABLE demo_meeting_plan AS
SELECT seq, pg_temp.demo_id('meeting:' || seq) AS meeting_id,
    CASE WHEN seq <= 16 THEN 'SCHEDULED' WHEN seq <= 26 THEN 'ENDED'
        WHEN seq <= 28 THEN 'CANCELLED' WHEN seq = 29 THEN 'DRAFT' ELSE 'LOBBY' END AS state,
    CASE WHEN seq % 4 = 0 THEN 10 ELSE 900018 END::BIGINT AS organizer,
    CASE WHEN seq <= 6 THEN date_trunc('minute', now()) + (20 + (seq - 1) * 40) * INTERVAL '1 minute'
        WHEN seq <= 16 THEN date_trunc('day', now()) + ((seq - 7) / 2 + 1) * INTERVAL '1 day'
            + CASE WHEN seq % 2 = 1 THEN INTERVAL '9 hours 30 minutes' ELSE INTERVAL '15 hours' END
        WHEN seq <= 26 THEN date_trunc('day', now()) - (seq - 16) * INTERVAL '1 day' + INTERVAL '14 hours'
        WHEN seq <= 28 THEN date_trunc('day', now()) + (seq - 26) * INTERVAL '1 day' + INTERVAL '11 hours'
        WHEN seq = 30 THEN date_trunc('minute', now()) + INTERVAL '10 minutes'
        ELSE NULL END AS starts_at,
    (CASE WHEN seq % 3 = 0 THEN 60 WHEN seq % 3 = 1 THEN 30 ELSE 45 END) AS duration,
    '[화면점검] ' || (ARRAY[
        '분기 제품 출시 의사결정', '플랫폼 디자인 시스템 싱크', '1:1 정기 체크인',
        '고객 온보딩 여정 개선', '보안 검토 및 접근 정책', 'APAC / EMEA 협업 점검',
        '다음 스프린트 우선순위', 'AI 업무 지원 시나리오 검토', '서비스 운영 인수인계',
        '사용자 인터뷰 질문 설계', '프로젝트 리스크와 의존성', '데이터 보존 정책 워크숍',
        '모바일 접근성 검토', '파트너 공동 워크숍 준비', '고객지원 인사이트 공유',
        '내부 출시 리허설', '제품 탐색 개선 회고', '성능 개선 결과 검토',
        '분기 목표 중간 점검', '서비스 안정성 회고', '온보딩 개선안 리뷰',
        '업무 자동화 워크숍', '디자인 리뷰와 후속 질문', '사용성 검증 결과 공유',
        '아키텍처 대안 비교', '보안 가이드 검토 회고', '범위 조정으로 취소한 회의',
        '일정 중복으로 취소한 회의', '작성 중인 다음 달 킥오프', '회의 준비·입장 대기 점검'
    ])[seq] AS title
FROM generate_series(1, 30) seq;

-- Schedule rotation is a separate, explicit operator action. It can touch only expired,
-- version-zero reserved rows with no command, processing or user-owned child evidence.
-- Base participants/agenda/invitation snapshots from this seed are permitted only at v0.
CREATE TEMP TABLE demo_refreshed_meetings (
    meeting_id UUID PRIMARY KEY,
    previous_start_at TIMESTAMPTZ NOT NULL,
    previous_end_at TIMESTAMPTZ NOT NULL,
    refreshed_start_at TIMESTAMPTZ NOT NULL,
    refreshed_end_at TIMESTAMPTZ NOT NULL
) ON COMMIT DROP;
\if :refresh_demo_schedule
WITH candidates AS (
    SELECT plan.meeting_id, plan.starts_at,
           plan.starts_at + plan.duration * INTERVAL '1 minute' AS ends_at,
           meeting.scheduled_start_at AS previous_start_at,
           meeting.scheduled_end_at AS previous_end_at
    FROM demo_meeting_plan plan
    JOIN vm_meetings meeting
      ON meeting.tenant_id = 1 AND meeting.meeting_id = plan.meeting_id
    JOIN vm_meeting_preparations preparation
      ON preparation.tenant_id = 1 AND preparation.meeting_id = plan.meeting_id
    WHERE plan.state = 'SCHEDULED'
      AND meeting.lifecycle_state = 'SCHEDULED'
      AND meeting.correlation_id = 'meeting-ui-demo-v1'
      AND meeting.title = plan.title
      AND meeting.description LIKE '[합성 시연 데이터]%'
      AND meeting.organizer_user_id = plan.organizer
      AND meeting.version = 0
      AND meeting.scheduled_end_at <= CURRENT_TIMESTAMP
      AND plan.starts_at > CURRENT_TIMESTAMP
      AND preparation.agenda_version = 0
      AND preparation.materials_version = 0
      AND preparation.invitation_revision = 1
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_participants participant
                       WHERE participant.tenant_id = 1
                         AND participant.meeting_id = plan.meeting_id
                         AND participant.version <> 0)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_invitation_responses response
                       WHERE response.tenant_id = 1 AND response.meeting_id = plan.meeting_id
                         AND response.version <> 0)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_schedule_commands command
                       WHERE command.tenant_id = 1 AND command.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_occurrences occurrence
                       WHERE occurrence.tenant_id = 1 AND occurrence.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_preparation_materials material
                       WHERE material.tenant_id = 1 AND material.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_personal_preparations personal
                       WHERE personal.tenant_id = 1 AND personal.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_collaboration_sequences collaboration
                       WHERE collaboration.tenant_id = 1
                         AND collaboration.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_facilitation_states facilitation
                       WHERE facilitation.tenant_id = 1
                         AND facilitation.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_events event
                       WHERE event.tenant_id = 1 AND event.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_recording_sessions execution
                       WHERE execution.tenant_id = 1 AND execution.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_intelligence_runs execution
                       WHERE execution.tenant_id = 1 AND execution.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_media_operations execution
                       WHERE execution.tenant_id = 1 AND execution.meeting_id = plan.meeting_id)
      AND NOT EXISTS (SELECT 1 FROM vm_meeting_content_notice_acknowledgements evidence
                       WHERE evidence.tenant_id = 1 AND evidence.meeting_id = plan.meeting_id)
), updated AS (
    UPDATE vm_meetings meeting
       SET scheduled_start_at = candidate.starts_at,
           scheduled_end_at = candidate.ends_at,
           updated_at = CURRENT_TIMESTAMP
      FROM candidates candidate
     WHERE meeting.tenant_id = 1 AND meeting.meeting_id = candidate.meeting_id
    RETURNING meeting.meeting_id, candidate.previous_start_at, candidate.previous_end_at,
              meeting.scheduled_start_at, meeting.scheduled_end_at
)
INSERT INTO demo_refreshed_meetings
SELECT * FROM updated;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM demo_refreshed_meetings refreshed
            JOIN vm_meeting_preparations preparation
              ON preparation.tenant_id = 1 AND preparation.meeting_id = refreshed.meeting_id
            WHERE preparation.agenda_version <> 0 OR preparation.materials_version <> 0
               OR preparation.invitation_revision <> 2)
       OR EXISTS (SELECT 1 FROM demo_refreshed_meetings refreshed
            JOIN vm_meeting_invitation_responses response
              ON response.tenant_id = 1 AND response.meeting_id = refreshed.meeting_id
            JOIN vm_meeting_participants participant
              ON participant.tenant_id = response.tenant_id
             AND participant.meeting_id = response.meeting_id
             AND participant.participant_id = response.participant_id
            WHERE response.version <> 1 OR response.responded_at IS NOT NULL
               OR response.invitation_revision <> 2
               OR response.response_state <> CASE
                    WHEN participant.participant_role = 'ORGANIZER'
                    THEN 'ACCEPTED' ELSE 'RECONFIRM_REQUIRED' END) THEN
        RAISE EXCEPTION 'Schedule refresh invitation reconfirmation escaped its v0 contract';
    END IF;
END $$;

-- The generic preservation proof starts before rotation. Move only the explicitly proven
-- refreshed rows to their new baseline; every other pre-existing row remains byte-checked.
UPDATE demo_before before_image
   SET row_value = to_jsonb(meeting)
  FROM vm_meetings meeting
  JOIN demo_refreshed_meetings refreshed USING (meeting_id)
 WHERE before_image.table_name = 'vm_meetings'
   AND (before_image.row_value ->> 'meeting_id')::UUID = meeting.meeting_id;
UPDATE demo_before before_image
   SET row_value = to_jsonb(preparation)
  FROM vm_meeting_preparations preparation
  JOIN demo_refreshed_meetings refreshed USING (meeting_id)
 WHERE preparation.tenant_id = 1
   AND before_image.table_name = 'vm_meeting_preparations'
   AND (before_image.row_value ->> 'meeting_id')::UUID = preparation.meeting_id;
UPDATE demo_before before_image
   SET row_value = to_jsonb(response)
  FROM vm_meeting_invitation_responses response
  JOIN demo_refreshed_meetings refreshed USING (meeting_id)
 WHERE response.tenant_id = 1
   AND before_image.table_name = 'vm_meeting_invitation_responses'
   AND (before_image.row_value ->> 'meeting_id')::UUID = response.meeting_id
   AND (before_image.row_value ->> 'participant_id')::UUID = response.participant_id;
\endif

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM demo_meeting_plan p JOIN vm_meetings m USING (meeting_id)
            WHERE m.tenant_id <> 1 OR m.correlation_id IS DISTINCT FROM 'meeting-ui-demo-v1') THEN
        RAISE EXCEPTION 'Reserved demo meeting identity collision';
    END IF;
END $$;

CREATE TEMP TABLE demo_new_meetings (meeting_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meetings (meeting_id, tenant_id, title, description, agenda, lifecycle_state,
        access_scope, join_code, scheduled_start_at, scheduled_end_at, time_zone,
        waiting_room_enabled, guest_access_enabled, allow_join_before_host,
        default_microphone_enabled, default_camera_enabled, organizer_user_id,
        organizer_person_public_id, organizer_name, idempotency_key, request_hash, correlation_id,
        provider, room_name, started_at, ended_at, ended_by, media_incarnation, media_access_state,
        created_at, updated_at, created_by, updated_by)
    SELECT p.meeting_id, 1, p.title,
        '[합성 시연 데이터] 화면과 탐색 흐름을 점검하기 위한 가상 회의입니다. 실제 초대 발송·참석·녹화·AI 분석 결과가 아닙니다. '
        || CASE WHEN p.state = 'CANCELLED' THEN '범위 또는 일정 변경으로 취소된 상황을 시연합니다.'
                WHEN p.state = 'ENDED' THEN '종료 및 참석 내역은 화면점검용 가상 기록입니다. 녹화와 전사 원본은 존재하지 않습니다.'
                ELSE '진행 목표: 사용자 흐름을 확인하고 결정할 쟁점과 다음 행동을 정리합니다.' END,
        E'1. 목표와 현황 확인\n2. 대안 및 쟁점 검토\n3. 결론과 다음 행동 정리',
        p.state, 'INVITED', pg_temp.demo_join_code(), p.starts_at,
        p.starts_at + p.duration * INTERVAL '1 minute', 'Asia/Seoul',
        TRUE, FALSE, FALSE, FALSE, FALSE, p.organizer, person.person_public_id,
        person.display_name, NULL, NULL, 'meeting-ui-demo-v1',
        CASE WHEN p.state = 'ENDED' THEN 'DEV_SEED' END,
        CASE WHEN p.state = 'ENDED' THEN 'demo-only-no-provider-' || p.meeting_id END,
        CASE WHEN p.state = 'ENDED' THEN p.starts_at END,
        CASE WHEN p.state = 'ENDED' THEN p.starts_at + p.duration * INTERVAL '1 minute' END,
        CASE WHEN p.state = 'ENDED' THEN p.organizer END,
        CASE WHEN p.state = 'ENDED' THEN gen_random_uuid() END,
        CASE WHEN p.state = 'ENDED' THEN 'ENDED' ELSE 'INACTIVE' END,
        COALESCE(LEAST(p.starts_at - INTERVAL '2 days', now()), now()),
        COALESCE(LEAST(p.starts_at + p.duration * INTERVAL '1 minute', now()), now()),
        p.organizer, p.organizer
    FROM demo_meeting_plan p
    JOIN vm_people_snapshot person ON person.tenant_id = 1 AND person.user_id = p.organizer
    ON CONFLICT (meeting_id) DO NOTHING RETURNING meeting_id
) INSERT INTO demo_new_meetings SELECT meeting_id FROM inserted;

-- Only insert participants for newly inserted roots. Replays preserve all user edits.
INSERT INTO vm_meeting_participants (participant_id, tenant_id, meeting_id, user_id,
    person_public_id, email_address, display_name, job_title, organization_name,
    participant_role, attendance_state, admitted_at, admitted_by, joined_at, left_at,
    join_requested_at, created_by, updated_by)
SELECT pg_temp.demo_id('participant:' || p.seq || ':' || person.user_id), 1, p.meeting_id,
    person.user_id, person.person_public_id, person.email_address, person.display_name,
    person.job_title, person.organization_name,
    CASE WHEN person.user_id = p.organizer THEN 'ORGANIZER'
         WHEN person.user_id = 900018 AND p.seq % 8 = 0 THEN 'CO_HOST'
         WHEN person.user_id = 14 THEN 'PRESENTER' ELSE 'ATTENDEE' END,
    CASE WHEN p.state = 'ENDED' THEN 'LEFT'
         WHEN person.user_id = p.organizer THEN 'ADMITTED'
         WHEN p.state = 'LOBBY' AND person.user_id IN (8, 14) THEN 'REQUESTED'
         ELSE 'INVITED' END,
    CASE WHEN p.state = 'ENDED' THEN p.starts_at - INTERVAL '5 minutes'
         WHEN person.user_id = p.organizer THEN now() END,
    CASE WHEN p.state = 'ENDED' OR person.user_id = p.organizer THEN p.organizer END,
    CASE WHEN p.state = 'ENDED' THEN p.starts_at END,
    CASE WHEN p.state = 'ENDED' THEN p.starts_at + p.duration * INTERVAL '1 minute' END,
    CASE WHEN p.state = 'LOBBY' AND person.user_id IN (8, 14) THEN now() END,
    p.organizer, p.organizer
FROM demo_meeting_plan p JOIN demo_new_meetings n USING (meeting_id)
JOIN vm_people_snapshot person ON person.tenant_id = 1 AND
    (person.user_id IN (900018, 10, 14, 8)
        OR person.user_id = CASE WHEN p.seq % 2 = 0 THEN 29 ELSE 15 END
        OR (p.seq % 3 = 0 AND person.user_id IN (3, 4)));

UPDATE vm_meeting_invitation_responses response
SET response_state = CASE WHEN participant.participant_role = 'ORGANIZER' THEN 'ACCEPTED'
        WHEN p.state = 'ENDED' THEN 'ACCEPTED'
        ELSE (ARRAY['NEEDS_RESPONSE', 'ACCEPTED', 'TENTATIVE', 'DECLINED', 'RECONFIRM_REQUIRED'])
            [((participant.user_id + p.seq) % 5 + 1)::INTEGER] END,
    responded_at = CASE WHEN p.state = 'ENDED' THEN p.starts_at - INTERVAL '1 day'
        WHEN participant.participant_role = 'ORGANIZER'
        OR (participant.user_id + p.seq) % 5 IN (1, 2, 3) THEN now() END
FROM demo_new_meetings n JOIN demo_meeting_plan p USING (meeting_id)
JOIN vm_meeting_participants participant ON participant.tenant_id = 1 AND participant.meeting_id = p.meeting_id
WHERE response.tenant_id = 1 AND response.meeting_id = p.meeting_id
    AND response.participant_id = participant.participant_id;

INSERT INTO vm_meeting_agenda_items (item_id, tenant_id, meeting_id, position, title,
    objective, owner_user_id, planned_minutes, created_by, updated_by)
SELECT pg_temp.demo_id('agenda:' || p.seq || ':' || agenda.position), 1, p.meeting_id,
    agenda.position, agenda.title,
    '[화면점검] ' || agenda.objective, CASE WHEN agenda.position = 0 THEN p.organizer ELSE 14 END,
    CASE agenda.position WHEN 0 THEN p.duration / 3 WHEN 1 THEN p.duration / 3
         ELSE p.duration - 2 * (p.duration / 3) END, p.organizer, p.organizer
FROM demo_meeting_plan p JOIN demo_new_meetings n USING (meeting_id)
CROSS JOIN (VALUES
    (0, '목표·현황 및 오늘 결정할 내용', '목표와 제약 조건을 확인합니다. 작성된 내용은 합성 시연이며 실제 업무의 결론이 아닙니다.'),
    (1, '대안 비교와 미결 쟁점', '사용자 경험, 운영 안정성, 접근 권한 관점에서 대안을 검토합니다.'),
    (2, '결론·담당·다음 확인 시점', '합의와 보류 사항을 구분하고 다음 점검 행동을 정리합니다.')
) agenda(position, title, objective);

-- Explicit unavailable states, NEVER fabricated AVAILABLE/PROCESSING artifacts or jobs.
INSERT INTO vm_meeting_artifacts (artifact_id, tenant_id, meeting_id, artifact_type,
    artifact_state, metadata, created_by, updated_by)
SELECT pg_temp.demo_id('artifact:' || p.seq || ':' || a.kind), 1, p.meeting_id, a.kind,
    CASE WHEN a.kind = 'RECORDING' THEN 'NONE' ELSE 'UNAVAILABLE' END,
    jsonb_build_object('demo', TRUE, 'seedNamespace', 'meeting-ui-demo-v1',
        'reasonCode', 'DEMO_NO_MEDIA_OR_PROVIDER_EXECUTION'), p.organizer, p.organizer
FROM demo_meeting_plan p JOIN demo_new_meetings n USING (meeting_id)
CROSS JOIN (VALUES ('RECORDING'), ('TRANSCRIPT'), ('SUMMARY')) a(kind)
WHERE p.state = 'ENDED';

-- Templates are immutable source references. No source links are attached to another host's private template.
INSERT INTO vm_meeting_template_sources (tenant_id, meeting_id, template_id, template_version)
SELECT 1, p.meeting_id, t.template_id, 0
FROM demo_meeting_plan p JOIN demo_new_meetings n USING (meeting_id)
JOIN demo_template_plan t ON t.seq = ((p.seq - 1) % 8 + 1)
JOIN vm_meeting_templates actual ON actual.template_id = t.template_id
    AND actual.tenant_id = 1 AND actual.owner_user_id = 900018 AND actual.deleted_at IS NULL
JOIN vm_meeting_template_revisions r ON r.tenant_id = 1 AND r.template_id = t.template_id AND r.revision = 0
WHERE p.seq <= 6 AND p.organizer = 900018;

-- Enrich only untouched, exact demo roots. A meeting that a user has edited or used for
-- content processing is deliberately skipped rather than being reset or back-filled.
CREATE TEMP TABLE demo_enrichment_meetings ON COMMIT DROP AS
SELECT p.*, meeting.created_at AS persisted_created_at,
       meeting.updated_at AS persisted_updated_at
FROM demo_meeting_plan p
JOIN vm_meetings meeting ON meeting.tenant_id = 1 AND meeting.meeting_id = p.meeting_id
WHERE meeting.correlation_id = 'meeting-ui-demo-v1'
  AND meeting.title = p.title
  AND meeting.description LIKE '[합성 시연 데이터]%'
  AND meeting.lifecycle_state = p.state
  AND meeting.organizer_user_id = p.organizer
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_recording_sessions execution
                   WHERE execution.tenant_id = 1 AND execution.meeting_id = p.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_intelligence_runs execution
                   WHERE execution.tenant_id = 1 AND execution.meeting_id = p.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_media_operations execution
                   WHERE execution.tenant_id = 1 AND execution.meeting_id = p.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_content_notice_acknowledgements execution
                   WHERE execution.tenant_id = 1 AND execution.meeting_id = p.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_artifacts artifact
                   WHERE artifact.tenant_id = 1 AND artifact.meeting_id = p.meeting_id
                     AND (artifact.artifact_state NOT IN ('NONE', 'UNAVAILABLE')
                          OR artifact.object_key IS NOT NULL
                          OR artifact.server_side_processing_allowed))
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_content_plans plan
                   WHERE plan.tenant_id = 1 AND plan.meeting_id = p.meeting_id
                     AND plan.plan_state <> 'DISABLED');

-- Ended demo meetings may carry explicitly manual, synthetic outcomes. These are ordinary
-- participant-visible meeting notes, not an AI report, transcript, Work assignment or proof of
-- external execution. Enrichment is limited to exact, untouched v0 demo roots whose outcome
-- arrays are still empty; a user-edited or previously enriched meeting is never overwritten.
CREATE TEMP TABLE demo_manual_outcome_targets ON COMMIT DROP AS
SELECT target.seq, target.meeting_id
FROM demo_enrichment_meetings target
JOIN vm_meetings meeting
  ON meeting.tenant_id = 1 AND meeting.meeting_id = target.meeting_id
WHERE target.seq BETWEEN 17 AND 26
  AND meeting.lifecycle_state = 'ENDED'
  AND meeting.provider = 'DEV_SEED'
  AND meeting.room_name = 'demo-only-no-provider-' || meeting.meeting_id
  AND meeting.media_access_state = 'ENDED'
  AND meeting.version = 0
  AND meeting.decisions = '[]'::jsonb
  AND meeting.follow_up_actions = '[]'::jsonb
  AND EXISTS (SELECT 1 FROM vm_meeting_participants participant
               WHERE participant.tenant_id = 1
                 AND participant.meeting_id = target.meeting_id
                 AND participant.user_id = 900018
                 AND participant.attendance_state = 'LEFT')
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_schedule_commands command
                   WHERE command.tenant_id = 1 AND command.meeting_id = target.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_occurrences occurrence
                   WHERE occurrence.tenant_id = 1 AND occurrence.meeting_id = target.meeting_id);
CREATE TEMP TABLE demo_manual_outcome_updates (meeting_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH updated AS (
    UPDATE vm_meetings meeting
       SET decisions = jsonb_build_array(
               jsonb_build_object(
                   'decision', '[화면점검 · 수동 기록 · AI 결과 아님] ' ||
                       (ARRAY[
                           '탐색 화면의 핵심 행동을 한 화면에서 확인합니다.',
                           '모바일에서도 주요 회의 정보의 우선순위를 유지합니다.',
                           '후속 초안은 실제 업무 등록 전 담당자가 검토합니다.',
                           '빈 상태와 데이터 상태를 모두 접근성 기준으로 점검합니다.',
                           '회의 자료는 재검증 전까지 다운로드할 수 없게 유지합니다.',
                           '사용자 역할에 따라 보이는 행동을 최소 권한으로 제한합니다.',
                           '일정 변경은 미리보기와 재확인을 거쳐 반영합니다.',
                           '회의 결과는 보존 상태와 게시 범위를 함께 표시합니다.',
                           '관리자 화면에는 원문 대신 운영 상태만 집계합니다.',
                           '외부 공급자 미설정 상태는 성공처럼 표시하지 않습니다.'
                       ])[target.seq - 16],
                   'ownerUserId', 900018,
                   'status', 'CONFIRMED',
                   'demo', true,
                   'synthetic', true,
                   'origin', 'MANUAL_SEED_NOT_AI'),
               jsonb_build_object(
                   'decision', '[화면점검 · 수동 기록 · AI 결과 아님] 다음 점검에서 데스크톱·모바일 표현을 함께 대조합니다.',
                   'ownerUserId', 14,
                   'status', 'CONFIRMED',
                   'demo', true,
                   'synthetic', true,
                   'origin', 'MANUAL_SEED_NOT_AI')),
           follow_up_actions = jsonb_build_array(
               jsonb_build_object(
                   'action', '[화면점검 · 수동 후속 초안 · 실제 업무 아님] 담당 화면의 정보 밀도와 행동 우선순위를 검토합니다.',
                   'ownerUserId', 900018,
                   'dueInDays', 1 + mod(target.seq, 5),
                   'status', CASE WHEN mod(target.seq, 2) = 0 THEN 'IN_PROGRESS' ELSE 'OPEN' END,
                   'demo', true,
                   'synthetic', true,
                   'origin', 'MANUAL_SEED_NOT_WORK'),
               jsonb_build_object(
                   'action', '[화면점검 · 수동 후속 초안 · 실제 업무 아님] 접근성·반응형 대조 결과를 다음 회의에서 확인합니다.',
                   'ownerUserId', 14,
                   'dueInDays', 3 + mod(target.seq, 5),
                   'status', 'OPEN',
                   'demo', true,
                   'synthetic', true,
                   'origin', 'MANUAL_SEED_NOT_WORK')),
           version = meeting.version + 1,
           updated_at = CURRENT_TIMESTAMP,
           updated_by = 900018
      FROM demo_manual_outcome_targets target
     WHERE meeting.tenant_id = 1 AND meeting.meeting_id = target.meeting_id
       AND meeting.version = 0
       AND meeting.decisions = '[]'::jsonb
       AND meeting.follow_up_actions = '[]'::jsonb
    RETURNING meeting.meeting_id
)
INSERT INTO demo_manual_outcome_updates SELECT meeting_id FROM updated;

-- The generic preservation proof still byte-checks every other pre-existing row. Only the exact
-- manual-outcome targets above become the new baseline after their bounded optimistic update.
UPDATE demo_before before_image
   SET row_value = to_jsonb(meeting)
  FROM vm_meetings meeting
  JOIN demo_manual_outcome_updates updated USING (meeting_id)
 WHERE before_image.table_name = 'vm_meetings'
   AND (before_image.row_value ->> 'meeting_id')::UUID = meeting.meeting_id;

-- A disabled plan is intent/readiness metadata only. It explicitly requests no recording,
-- transcription or AI processing and creates no notice, consent, command or provider work.
CREATE TEMP TABLE demo_new_content_plans (plan_id UUID PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE demo_content_plan_targets ON COMMIT DROP AS
SELECT target.*
FROM demo_enrichment_meetings target
WHERE NOT EXISTS (SELECT 1 FROM vm_meeting_content_plans plan
                   WHERE plan.tenant_id = 1 AND plan.meeting_id = target.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_events event
                   WHERE event.tenant_id = 1 AND event.meeting_id = target.meeting_id);
WITH inserted AS (
    INSERT INTO vm_meeting_content_plans (
        plan_id, tenant_id, meeting_id, plan_state, created_by, updated_by)
    SELECT pg_temp.demo_id('content-plan:' || target.seq), 1, target.meeting_id,
           'DISABLED', target.organizer, target.organizer
    FROM demo_content_plan_targets target
    ON CONFLICT DO NOTHING RETURNING plan_id
) INSERT INTO demo_new_content_plans SELECT plan_id FROM inserted;

-- Convenience preferences contain no device ID, consent or authority. Existing account
-- preferences always win and are never overwritten by the inspection seed.
CREATE TEMP TABLE demo_new_preferences (user_id BIGINT PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_user_preferences (
        tenant_id, user_id, display_name, microphone_off, camera_off,
        prejoin_enabled, reminder_enabled, reminder_minutes, recap_notifications)
    SELECT 1, person.user_id, person.display_name, TRUE, TRUE, TRUE, TRUE, 10, TRUE
    FROM vm_people_snapshot person
    WHERE person.tenant_id = 1 AND person.user_id = 900018
    ON CONFLICT (tenant_id, user_id) DO NOTHING RETURNING user_id
) INSERT INTO demo_new_preferences SELECT user_id FROM inserted;

-- Preparation references are opaque metadata only. They deliberately have no bytes, URL,
-- access token, size or digest and remain unusable until a governed adapter revalidates them.
CREATE TEMP TABLE demo_material_targets ON COMMIT DROP AS
SELECT target.*
FROM demo_enrichment_meetings target
JOIN vm_meeting_preparations preparation
  ON preparation.tenant_id = 1 AND preparation.meeting_id = target.meeting_id
WHERE target.seq BETWEEN 1 AND 6
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_preparation_materials material
                   WHERE material.tenant_id = 1 AND material.meeting_id = target.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_events event
                   WHERE event.tenant_id = 1 AND event.meeting_id = target.meeting_id);
CREATE TEMP TABLE demo_new_materials (material_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_preparation_materials (
        material_id, tenant_id, meeting_id, display_name, content_type,
        reference_provider, opaque_reference, source_version, classification,
        size_bytes, content_sha256, retention_until, access_verification_state,
        lifecycle_state, created_by, updated_by)
    SELECT pg_temp.demo_id('material:' || target.seq), 1, target.meeting_id,
           '[화면점검] 사전 읽기자료 참조 메타데이터 (실제 파일 없음)',
           'application/pdf', 'DWP_FILES',
           'meeting-ui-demo-v1/material/' || lpad(target.seq::TEXT, 2, '0'),
           'demo-v1', 'INTERNAL', NULL, NULL,
           CURRENT_TIMESTAMP + make_interval(days => policy.retention_days),
           'PENDING_REVALIDATION', 'ACTIVE', target.organizer, target.organizer
    FROM demo_material_targets target
    JOIN vm_tenant_policies policy ON policy.tenant_id = 1
    ON CONFLICT DO NOTHING RETURNING material_id
) INSERT INTO demo_new_materials SELECT material_id FROM inserted;

-- Personal preparation is self-only and free of notes. Two checked agenda identifiers are
-- enough to render progress without projecting another participant's private preparation.
CREATE TEMP TABLE demo_personal_preparation_targets ON COMMIT DROP AS
SELECT target.*, participant.participant_id, preparation.agenda_version
FROM demo_enrichment_meetings target
JOIN vm_meeting_participants participant
  ON participant.tenant_id = 1 AND participant.meeting_id = target.meeting_id
 AND participant.user_id = 900018 AND participant.attendance_state <> 'DENIED'
JOIN vm_meeting_preparations preparation
  ON preparation.tenant_id = 1 AND preparation.meeting_id = target.meeting_id
WHERE target.seq BETWEEN 1 AND 6
  AND (SELECT count(*) FROM vm_meeting_agenda_items agenda
        WHERE agenda.tenant_id = 1 AND agenda.meeting_id = target.meeting_id
          AND agenda.position IN (0, 2)) = 2
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_personal_preparations personal
                   WHERE personal.tenant_id = 1 AND personal.meeting_id = target.meeting_id
                     AND personal.participant_id = participant.participant_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_events event
                   WHERE event.tenant_id = 1 AND event.meeting_id = target.meeting_id);
CREATE TEMP TABLE demo_new_personal_preparations (
    meeting_id UUID PRIMARY KEY, participant_id UUID NOT NULL
) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_personal_preparations (
        tenant_id, meeting_id, participant_id, agenda_version, version)
    SELECT 1, meeting_id, participant_id, agenda_version, 1
    FROM demo_personal_preparation_targets
    ON CONFLICT DO NOTHING RETURNING meeting_id, participant_id
) INSERT INTO demo_new_personal_preparations SELECT meeting_id, participant_id FROM inserted;
CREATE TEMP TABLE demo_new_personal_preparation_items (
    meeting_id UUID NOT NULL, participant_id UUID NOT NULL, agenda_item_id UUID PRIMARY KEY
) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_personal_preparation_items (
        tenant_id, meeting_id, participant_id, agenda_item_id)
    SELECT 1, target.meeting_id, target.participant_id, agenda.item_id
    FROM demo_personal_preparation_targets target
    JOIN vm_meeting_agenda_items agenda
      ON agenda.tenant_id = 1 AND agenda.meeting_id = target.meeting_id
     AND agenda.position IN (0, 2)
    ON CONFLICT DO NOTHING RETURNING meeting_id, participant_id, agenda_item_id
) INSERT INTO demo_new_personal_preparation_items
SELECT meeting_id, participant_id, agenda_item_id FROM inserted;

-- Collaboration examples are bounded, clearly synthetic history on two ENDED demo meetings.
-- Any existing sequence, chat or speaking-right row makes the whole meeting ineligible.
CREATE TEMP TABLE demo_collaboration_targets ON COMMIT DROP AS
SELECT target.*
FROM demo_enrichment_meetings target
WHERE target.seq IN (17, 18)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_collaboration_sequences sequence
                   WHERE sequence.tenant_id = 1 AND sequence.meeting_id = target.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_chat_messages message
                   WHERE message.tenant_id = 1 AND message.meeting_id = target.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_hand_requests request
                   WHERE request.tenant_id = 1 AND request.meeting_id = target.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_events event
                   WHERE event.tenant_id = 1 AND event.meeting_id = target.meeting_id)
  AND (SELECT count(*) FROM vm_meeting_participants participant
        WHERE participant.tenant_id = 1 AND participant.meeting_id = target.meeting_id
          AND participant.user_id IN (900018, 14)) = 2;
CREATE TEMP TABLE demo_new_collaboration_sequences (meeting_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_collaboration_sequences (tenant_id, meeting_id, last_sequence)
    SELECT 1, meeting_id, 4 FROM demo_collaboration_targets
    ON CONFLICT DO NOTHING RETURNING meeting_id
) INSERT INTO demo_new_collaboration_sequences SELECT meeting_id FROM inserted;
CREATE TEMP TABLE demo_new_chat_messages (message_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_chat_messages (
        message_id, tenant_id, meeting_id, participant_id, sender_user_id,
        sender_person_public_id, sender_display_name, sender_role,
        created_sequence, last_sequence, message_state, message_text,
        retention_until, created_at, updated_at)
    SELECT pg_temp.demo_id('chat:' || target.seq || ':' || message.sequence),
           1, target.meeting_id, participant.participant_id, participant.user_id,
           participant.person_public_id, participant.display_name, participant.participant_role,
           message.sequence, message.sequence, 'ACTIVE', message.body,
           target.starts_at + target.duration * INTERVAL '1 minute'
               + make_interval(days => policy.chat_retention_days),
           target.starts_at + message.sequence * INTERVAL '5 minutes',
           target.starts_at + message.sequence * INTERVAL '5 minutes'
    FROM demo_collaboration_targets target
    JOIN vm_tenant_policies policy ON policy.tenant_id = 1
    CROSS JOIN (VALUES
        (1::BIGINT, 900018::BIGINT,
         '[합성 화면점검 / 실제 대화 아님] 오늘 확인할 결정 기준을 먼저 맞춰보겠습니다.'),
        (2::BIGINT, 14::BIGINT,
         '[합성 화면점검 / 실제 대화 아님] 검토 결과와 다음 행동을 안건별로 정리하겠습니다.')
    ) message(sequence, user_id, body)
    JOIN vm_meeting_participants participant
      ON participant.tenant_id = 1 AND participant.meeting_id = target.meeting_id
     AND participant.user_id = message.user_id
    ON CONFLICT DO NOTHING RETURNING message_id
) INSERT INTO demo_new_chat_messages SELECT message_id FROM inserted;
CREATE TEMP TABLE demo_new_hand_requests (request_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_hand_requests (
        request_id, tenant_id, meeting_id, participant_id, requester_user_id,
        requester_person_public_id, requester_display_name, requester_role,
        raised_sequence, last_sequence, request_state, raised_at,
        resolved_at, resolved_by, updated_at)
    SELECT pg_temp.demo_id('hand:' || target.seq), 1, target.meeting_id,
           participant.participant_id, participant.user_id, participant.person_public_id,
           participant.display_name, participant.participant_role, 3, 4, 'LOWERED',
           target.starts_at + INTERVAL '15 minutes',
           target.starts_at + INTERVAL '17 minutes', participant.user_id,
           target.starts_at + INTERVAL '17 minutes'
    FROM demo_collaboration_targets target
    JOIN vm_meeting_participants participant
      ON participant.tenant_id = 1 AND participant.meeting_id = target.meeting_id
     AND participant.user_id = 900018
    ON CONFLICT DO NOTHING RETURNING request_id
) INSERT INTO demo_new_hand_requests SELECT request_id FROM inserted;
CREATE TEMP TABLE demo_new_hand_events (event_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_hand_events (
        event_id, tenant_id, meeting_id, request_id, sequence,
        event_type, actor_user_id, occurred_at)
    SELECT pg_temp.demo_id('hand-event:' || target.seq || ':' || event.sequence),
           1, target.meeting_id, pg_temp.demo_id('hand:' || target.seq),
           event.sequence, event.event_type, 900018,
           target.starts_at + event.elapsed
    FROM demo_collaboration_targets target
    CROSS JOIN (VALUES
        (3::BIGINT, 'RAISED'::VARCHAR, INTERVAL '15 minutes'),
        (4::BIGINT, 'LOWERED'::VARCHAR, INTERVAL '17 minutes')
    ) event(sequence, event_type, elapsed)
    ON CONFLICT DO NOTHING RETURNING event_id
) INSERT INTO demo_new_hand_events SELECT event_id FROM inserted;

-- Two more ENDED meetings carry synthetic Q&A and a closed anonymous poll. No command
-- receipt or live timer is forged, and no question/answer text is copied from a real meeting.
CREATE TEMP TABLE demo_facilitation_targets ON COMMIT DROP AS
SELECT target.*
FROM demo_enrichment_meetings target
WHERE target.seq IN (19, 20)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_facilitation_states state
                   WHERE state.tenant_id = 1 AND state.meeting_id = target.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_facilitation_questions question
                   WHERE question.tenant_id = 1 AND question.meeting_id = target.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_facilitation_polls poll
                   WHERE poll.tenant_id = 1 AND poll.meeting_id = target.meeting_id)
  AND NOT EXISTS (SELECT 1 FROM vm_meeting_events event
                   WHERE event.tenant_id = 1 AND event.meeting_id = target.meeting_id)
  AND (SELECT count(*) FROM vm_meeting_participants participant
        WHERE participant.tenant_id = 1 AND participant.meeting_id = target.meeting_id
          AND participant.user_id IN (900018, 8, 10, 14)) = 4;
CREATE TEMP TABLE demo_new_facilitation_states (meeting_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_facilitation_states (
        tenant_id, meeting_id, last_sequence, timer_state,
        retention_until, updated_at, updated_by)
    SELECT 1, target.meeting_id, 4, 'IDLE',
           target.starts_at + target.duration * INTERVAL '1 minute'
               + make_interval(days => policy.retention_days),
           target.starts_at + INTERVAL '20 minutes', target.organizer
    FROM demo_facilitation_targets target
    JOIN vm_tenant_policies policy ON policy.tenant_id = 1
    ON CONFLICT DO NOTHING RETURNING meeting_id
) INSERT INTO demo_new_facilitation_states SELECT meeting_id FROM inserted;
CREATE TEMP TABLE demo_new_facilitation_questions (question_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_facilitation_questions (
        question_id, tenant_id, meeting_id, author_participant_id, author_user_id,
        question_text, question_state, answer_text, answered_at, answered_by,
        version, created_sequence, last_sequence, retention_until, created_at, updated_at)
    SELECT pg_temp.demo_id('facilitation-question:' || target.seq), 1, target.meeting_id,
           participant.participant_id, participant.user_id,
           '[합성 화면점검 / 실제 질문 아님] 이 안건의 완료 기준은 무엇인가요?',
           'ANSWERED', '[합성 화면점검 / 실제 답변 아님] 담당자와 확인 시점을 함께 기록합니다.',
           target.starts_at + INTERVAL '10 minutes', target.organizer,
           1, 1, 2,
           target.starts_at + target.duration * INTERVAL '1 minute'
               + make_interval(days => policy.retention_days),
           target.starts_at + INTERVAL '5 minutes', target.starts_at + INTERVAL '10 minutes'
    FROM demo_facilitation_targets target
    JOIN vm_tenant_policies policy ON policy.tenant_id = 1
    JOIN vm_meeting_participants participant
      ON participant.tenant_id = 1 AND participant.meeting_id = target.meeting_id
     AND participant.user_id = 900018
    ON CONFLICT DO NOTHING RETURNING question_id
) INSERT INTO demo_new_facilitation_questions SELECT question_id FROM inserted;
CREATE TEMP TABLE demo_new_facilitation_upvotes (
    meeting_id UUID NOT NULL, question_id UUID NOT NULL, voter_user_id BIGINT NOT NULL,
    PRIMARY KEY (meeting_id, question_id, voter_user_id)
) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_facilitation_question_upvotes (
        tenant_id, meeting_id, question_id, voter_participant_id, voter_user_id, created_at)
    SELECT 1, target.meeting_id, pg_temp.demo_id('facilitation-question:' || target.seq),
           participant.participant_id, participant.user_id,
           target.starts_at + INTERVAL '7 minutes'
    FROM demo_facilitation_targets target
    JOIN vm_meeting_participants participant
      ON participant.tenant_id = 1 AND participant.meeting_id = target.meeting_id
     AND participant.user_id IN (8, 14)
    ON CONFLICT DO NOTHING RETURNING meeting_id, question_id, voter_user_id
) INSERT INTO demo_new_facilitation_upvotes SELECT meeting_id, question_id, voter_user_id FROM inserted;
CREATE TEMP TABLE demo_new_facilitation_polls (poll_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_facilitation_polls (
        poll_id, tenant_id, meeting_id, creator_participant_id, creator_user_id,
        poll_question, poll_state, anonymous, version, created_sequence, last_sequence,
        opened_at, closed_at, retention_until, created_at, updated_at)
    SELECT pg_temp.demo_id('facilitation-poll:' || target.seq), 1, target.meeting_id,
           participant.participant_id, participant.user_id,
           '[합성 화면점검 / 실제 투표 아님] 다음 검토 우선순위를 선택해 주세요.',
           'CLOSED', TRUE, 2, 3, 4,
           target.starts_at + INTERVAL '15 minutes', target.starts_at + INTERVAL '20 minutes',
           target.starts_at + target.duration * INTERVAL '1 minute'
               + make_interval(days => policy.retention_days),
           target.starts_at + INTERVAL '14 minutes', target.starts_at + INTERVAL '20 minutes'
    FROM demo_facilitation_targets target
    JOIN vm_tenant_policies policy ON policy.tenant_id = 1
    JOIN vm_meeting_participants participant
      ON participant.tenant_id = 1 AND participant.meeting_id = target.meeting_id
     AND participant.user_id = target.organizer
    ON CONFLICT DO NOTHING RETURNING poll_id
) INSERT INTO demo_new_facilitation_polls SELECT poll_id FROM inserted;
CREATE TEMP TABLE demo_new_facilitation_options (option_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_facilitation_poll_options (
        option_id, tenant_id, meeting_id, poll_id, position, option_label)
    SELECT pg_temp.demo_id('facilitation-option:' || target.seq || ':' || option.position),
           1, target.meeting_id, pg_temp.demo_id('facilitation-poll:' || target.seq),
           option.position, option.label
    FROM demo_facilitation_targets target
    CROSS JOIN (VALUES
        (0, '사용자 흐름'), (1, '운영 안정성'), (2, '접근성과 문서화')
    ) option(position, label)
    ON CONFLICT DO NOTHING RETURNING option_id
) INSERT INTO demo_new_facilitation_options SELECT option_id FROM inserted;
CREATE TEMP TABLE demo_new_facilitation_votes (
    meeting_id UUID NOT NULL, poll_id UUID NOT NULL, voter_user_id BIGINT NOT NULL,
    PRIMARY KEY (meeting_id, poll_id, voter_user_id)
) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_facilitation_poll_votes (
        tenant_id, meeting_id, poll_id, option_id, voter_participant_id,
        voter_user_id, ballot_version, created_at, updated_at)
    SELECT 1, target.meeting_id, pg_temp.demo_id('facilitation-poll:' || target.seq),
           pg_temp.demo_id('facilitation-option:' || target.seq || ':'
               || mod(participant.user_id + target.seq, 3)),
           participant.participant_id, participant.user_id, 1,
           target.starts_at + INTERVAL '17 minutes', target.starts_at + INTERVAL '17 minutes'
    FROM demo_facilitation_targets target
    JOIN vm_meeting_participants participant
      ON participant.tenant_id = 1 AND participant.meeting_id = target.meeting_id
     AND participant.user_id IN (900018, 8, 10, 14)
    ON CONFLICT DO NOTHING RETURNING meeting_id, poll_id, voter_user_id
) INSERT INTO demo_new_facilitation_votes SELECT meeting_id, poll_id, voter_user_id FROM inserted;

-- Lifecycle events use only the already-declared synthetic state of untouched demo roots.
-- They contain no token, provider identity, transcript, media locator or personal content.
CREATE TEMP TABLE demo_event_targets ON COMMIT DROP AS
SELECT target.* FROM demo_enrichment_meetings target
WHERE NOT EXISTS (SELECT 1 FROM vm_meeting_events event
                   WHERE event.tenant_id = 1 AND event.meeting_id = target.meeting_id);
CREATE TEMP TABLE demo_new_meeting_events (event_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_meeting_events (
        event_id, tenant_id, meeting_id, actor_user_id, event_type,
        correlation_id, idempotency_key, event_payload, occurred_at)
    SELECT pg_temp.demo_id('meeting-event:' || target.seq || ':' || event.event_type),
           1, target.meeting_id, target.organizer, event.event_type,
           'meeting-ui-demo-v1',
           'meeting-ui-demo-v1:event:' || target.seq || ':' || lower(event.event_type),
           jsonb_build_object('demo', TRUE, 'synthetic', TRUE,
               'seedNamespace', 'meeting-ui-demo-v1', 'externalExecution', FALSE),
           event.occurred_at
    FROM demo_event_targets target
    CROSS JOIN LATERAL (
        SELECT 'SCHEDULED'::VARCHAR AS event_type,
               target.persisted_created_at AS occurred_at
         WHERE target.state IN ('SCHEDULED', 'LOBBY')
        UNION ALL
        SELECT 'STARTED', target.starts_at WHERE target.state = 'ENDED'
        UNION ALL
        SELECT 'ENDED', target.starts_at + target.duration * INTERVAL '1 minute'
         WHERE target.state = 'ENDED'
        UNION ALL
        SELECT 'CANCELLED', target.persisted_updated_at WHERE target.state = 'CANCELLED'
        UNION ALL
        SELECT 'CREATED', target.persisted_created_at WHERE target.state = 'DRAFT'
    ) event
    ON CONFLICT DO NOTHING RETURNING event_id
) INSERT INTO demo_new_meeting_events SELECT event_id FROM inserted;

CREATE TEMP TABLE demo_new_rooms (room_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_personal_meeting_rooms (room_id, tenant_id, owner_user_id, name, opaque_alias)
    VALUES (pg_temp.demo_id('personal-room'), 1, 900018, '[화면점검] 최준빈 개인 회의실',
        encode(gen_random_bytes(16), 'hex'))
    ON CONFLICT (tenant_id, owner_user_id) DO NOTHING RETURNING room_id
) INSERT INTO demo_new_rooms SELECT room_id FROM inserted;

DO $$
DECLARE table_name TEXT; altered BOOLEAN; expected_count BIGINT; actual_count BIGINT;
BEGIN
    FOR table_name IN SELECT DISTINCT b.table_name FROM demo_before b LOOP
        EXECUTE format('SELECT EXISTS(SELECT row_value FROM demo_before WHERE table_name = %L
            EXCEPT SELECT to_jsonb(t) FROM public.%I t)', table_name, table_name) INTO altered;
        IF altered THEN RAISE EXCEPTION 'Pre-existing rows changed in %; rolling back', table_name; END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM demo_refreshed_meetings refreshed
            WHERE refreshed.previous_end_at > CURRENT_TIMESTAMP
               OR refreshed.refreshed_start_at <= CURRENT_TIMESTAMP
               OR refreshed.refreshed_end_at <= refreshed.refreshed_start_at) THEN
        RAISE EXCEPTION 'Schedule refresh escaped its expired and future-bounded contract';
    END IF;
    IF EXISTS (SELECT 1 FROM vm_meetings m JOIN demo_new_meetings n USING (meeting_id)
            WHERE m.lifecycle_state = 'LIVE' OR m.media_access_state = 'ACTIVE') THEN
        RAISE EXCEPTION 'Demo must never imply an active media room';
    END IF;
    IF EXISTS (SELECT 1 FROM vm_meeting_artifacts a
            JOIN demo_enrichment_meetings n USING (meeting_id)
            WHERE a.artifact_state NOT IN ('NONE', 'UNAVAILABLE') OR a.object_key IS NOT NULL
                OR a.server_side_processing_allowed) THEN
        RAISE EXCEPTION 'Demo must never imply available media or processing permission';
    END IF;
    IF EXISTS (SELECT 1 FROM vm_meeting_intelligence_runs r
                    JOIN demo_enrichment_meetings n USING (meeting_id))
       OR EXISTS (SELECT 1 FROM vm_meeting_media_operations r
                    JOIN demo_enrichment_meetings n USING (meeting_id))
       OR EXISTS (SELECT 1 FROM vm_meeting_recording_sessions r
                    JOIN demo_enrichment_meetings n USING (meeting_id))
       OR EXISTS (SELECT 1 FROM vm_meeting_content_notice_acknowledgements r
                    JOIN demo_enrichment_meetings n USING (meeting_id)) THEN
        RAISE EXCEPTION 'No execution or consent evidence may be fabricated by demo data';
    END IF;
    IF EXISTS (SELECT 1 FROM vm_meeting_content_plans plan
            JOIN demo_enrichment_meetings target USING (meeting_id)
            WHERE plan.plan_state <> 'DISABLED' OR plan.recording_requested
               OR plan.transcription_requested OR plan.ai_summary_requested
               OR plan.current_notice_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Demo content plans must remain disabled and request no processing';
    END IF;
    IF (SELECT count(*) FROM demo_manual_outcome_updates)
            <> (SELECT count(*) FROM demo_manual_outcome_targets)
       OR EXISTS (SELECT 1 FROM vm_meetings meeting
            JOIN demo_manual_outcome_updates updated USING (meeting_id)
            WHERE jsonb_array_length(meeting.decisions) <> 2
               OR jsonb_array_length(meeting.follow_up_actions) <> 2
               OR NOT meeting.decisions @>
                    '[{"demo":true,"synthetic":true,"origin":"MANUAL_SEED_NOT_AI"}]'::jsonb
               OR NOT meeting.follow_up_actions @>
                    '[{"demo":true,"synthetic":true,"origin":"MANUAL_SEED_NOT_WORK"}]'::jsonb
               OR meeting.decisions -> 0 ->> 'decision'
                    NOT LIKE '[화면점검 · 수동 기록 · AI 결과 아님]%'
               OR meeting.follow_up_actions -> 0 ->> 'action'
                    NOT LIKE '[화면점검 · 수동 후속 초안 · 실제 업무 아님]%'
               OR EXISTS (
                    SELECT 1
                      FROM jsonb_array_elements(meeting.decisions) decision(item)
                     WHERE NOT EXISTS (
                         SELECT 1 FROM vm_meeting_participants participant
                          WHERE participant.tenant_id = meeting.tenant_id
                            AND participant.meeting_id = meeting.meeting_id
                            AND participant.user_id = (decision.item ->> 'ownerUserId')::BIGINT))
               OR EXISTS (
                    SELECT 1
                      FROM jsonb_array_elements(meeting.follow_up_actions) follow_up(item)
                     WHERE NOT EXISTS (
                         SELECT 1 FROM vm_meeting_participants participant
                          WHERE participant.tenant_id = meeting.tenant_id
                            AND participant.meeting_id = meeting.meeting_id
                            AND participant.user_id = (follow_up.item ->> 'ownerUserId')::BIGINT))) THEN
        RAISE EXCEPTION 'Manual demo outcomes escaped synthetic or participant-bound limits';
    END IF;
    IF (SELECT count(*) FROM demo_new_content_plans)
            <> (SELECT count(*) FROM demo_content_plan_targets) THEN
        RAISE EXCEPTION 'Disabled content plan seed collision or partial insert';
    END IF;
    IF (SELECT count(*) FROM demo_new_materials)
            <> (SELECT count(*) FROM demo_material_targets) THEN
        RAISE EXCEPTION 'Preparation material seed collision or partial insert';
    END IF;
    IF EXISTS (SELECT 1 FROM vm_meeting_preparation_materials material
            JOIN demo_new_materials inserted USING (material_id)
            WHERE material.access_verification_state <> 'PENDING_REVALIDATION'
               OR material.last_verified_at IS NOT NULL OR material.size_bytes IS NOT NULL
               OR material.content_sha256 IS NOT NULL
               OR material.opaque_reference LIKE 'http%') THEN
        RAISE EXCEPTION 'Demo preparation material must remain opaque and unverified';
    END IF;
    IF (SELECT count(*) FROM demo_new_personal_preparations)
            <> (SELECT count(*) FROM demo_personal_preparation_targets)
       OR (SELECT count(*) FROM demo_new_personal_preparation_items)
            <> 2 * (SELECT count(*) FROM demo_personal_preparation_targets) THEN
        RAISE EXCEPTION 'Personal preparation seed collision or incomplete agenda projection';
    END IF;
    IF (SELECT count(*) FROM demo_new_collaboration_sequences)
            <> (SELECT count(*) FROM demo_collaboration_targets)
       OR (SELECT count(*) FROM demo_new_chat_messages)
            <> 2 * (SELECT count(*) FROM demo_collaboration_targets)
       OR (SELECT count(*) FROM demo_new_hand_requests)
            <> (SELECT count(*) FROM demo_collaboration_targets)
       OR (SELECT count(*) FROM demo_new_hand_events)
            <> 2 * (SELECT count(*) FROM demo_collaboration_targets) THEN
        RAISE EXCEPTION 'Collaboration seed collision or incomplete history';
    END IF;
    IF EXISTS (SELECT 1 FROM vm_meeting_chat_messages message
            JOIN demo_new_chat_messages inserted USING (message_id)
            WHERE message.message_text NOT LIKE '[합성 화면점검 / 실제 대화 아님]%'
               OR message.retention_until <= message.created_at) THEN
        RAISE EXCEPTION 'Demo chat must be labelled synthetic and retention bounded';
    END IF;
    IF (SELECT count(*) FROM demo_new_facilitation_states)
            <> (SELECT count(*) FROM demo_facilitation_targets)
       OR (SELECT count(*) FROM demo_new_facilitation_questions)
            <> (SELECT count(*) FROM demo_facilitation_targets)
       OR (SELECT count(*) FROM demo_new_facilitation_upvotes)
            <> 2 * (SELECT count(*) FROM demo_facilitation_targets)
       OR (SELECT count(*) FROM demo_new_facilitation_polls)
            <> (SELECT count(*) FROM demo_facilitation_targets)
       OR (SELECT count(*) FROM demo_new_facilitation_options)
            <> 3 * (SELECT count(*) FROM demo_facilitation_targets)
       OR (SELECT count(*) FROM demo_new_facilitation_votes)
            <> 4 * (SELECT count(*) FROM demo_facilitation_targets) THEN
        RAISE EXCEPTION 'Facilitation seed collision or incomplete history';
    END IF;
    SELECT coalesce(sum(CASE WHEN state = 'ENDED' THEN 2 ELSE 1 END), 0)
      INTO expected_count FROM demo_event_targets;
    SELECT count(*) INTO actual_count FROM demo_new_meeting_events;
    IF actual_count <> expected_count
       OR EXISTS (SELECT 1 FROM vm_meeting_events event
                    JOIN demo_new_meeting_events inserted USING (event_id)
                   WHERE event.event_type = 'TOKEN_ISSUED'
                      OR event.event_payload ->> 'externalExecution' <> 'false') THEN
        RAISE EXCEPTION 'Lifecycle event seed collision or external evidence leak';
    END IF;
END $$;

SELECT (SELECT count(*) FROM demo_new_meetings) AS inserted_meetings,
       (SELECT count(*) FROM vm_meeting_participants JOIN demo_new_meetings USING (meeting_id)) AS inserted_participants,
       (SELECT count(*) FROM vm_meeting_agenda_items JOIN demo_new_meetings USING (meeting_id)) AS inserted_agenda_items,
       (SELECT count(*) FROM demo_new_templates) AS inserted_templates,
       (SELECT count(*) FROM demo_new_rooms) AS inserted_personal_rooms,
       (SELECT count(*) FROM demo_refreshed_meetings) AS refreshed_schedules;
SELECT (SELECT count(*) FROM demo_new_content_plans) AS inserted_disabled_plans,
       (SELECT count(*) FROM demo_new_preferences) AS inserted_preferences,
       (SELECT count(*) FROM demo_manual_outcome_updates) AS inserted_manual_outcomes,
       (SELECT count(*) FROM demo_new_materials) AS inserted_material_metadata,
       (SELECT count(*) FROM demo_new_personal_preparations) AS inserted_my_preparations,
       (SELECT count(*) FROM demo_new_personal_preparation_items) AS inserted_prepared_items,
       (SELECT count(*) FROM demo_new_chat_messages) AS inserted_chat_messages,
       (SELECT count(*) FROM demo_new_hand_requests) AS inserted_hand_requests,
       (SELECT count(*) FROM demo_new_hand_events) AS inserted_hand_events,
       (SELECT count(*) FROM demo_new_facilitation_questions) AS inserted_questions,
       (SELECT count(*) FROM demo_new_facilitation_polls) AS inserted_polls,
       (SELECT count(*) FROM demo_new_facilitation_votes) AS inserted_poll_votes,
       (SELECT count(*) FROM demo_new_meeting_events) AS inserted_lifecycle_events;
SELECT m.lifecycle_state, count(*) AS demo_meetings
FROM vm_meetings m JOIN demo_meeting_plan p USING (meeting_id) GROUP BY m.lifecycle_state ORDER BY 1;

\if :apply_seed
    COMMIT;
    \echo 'LOCAL DEMO COMMITTED. Existing user rows preserved; no invitations, media or AI execution created.'
\else
    ROLLBACK;
    \echo 'DRY RUN PASSED AND ROLLED BACK. No persistent changes.'
\endif
