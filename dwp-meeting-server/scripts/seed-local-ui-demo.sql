-- Explicit operator-only LOCAL demonstration data. Never register as a Flyway migration.
-- Default is a complete constraint-checked rehearsal followed by ROLLBACK.
-- Apply only to the local dwp-postgres container after a protected database backup.
\set ON_ERROR_STOP on
\if :{?apply_seed}
\else
  \set apply_seed false
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
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '29' AND success)
       OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'A healthy V29 Meeting schema is required';
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

CREATE TEMP TABLE demo_new_rooms (room_id UUID PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO vm_personal_meeting_rooms (room_id, tenant_id, owner_user_id, name, opaque_alias)
    VALUES (pg_temp.demo_id('personal-room'), 1, 900018, '[화면점검] 최준빈 개인 회의실',
        encode(gen_random_bytes(16), 'hex'))
    ON CONFLICT (tenant_id, owner_user_id) DO NOTHING RETURNING room_id
) INSERT INTO demo_new_rooms SELECT room_id FROM inserted;

DO $$
DECLARE table_name TEXT; altered BOOLEAN;
BEGIN
    FOR table_name IN SELECT DISTINCT b.table_name FROM demo_before b LOOP
        EXECUTE format('SELECT EXISTS(SELECT row_value FROM demo_before WHERE table_name = %L
            EXCEPT SELECT to_jsonb(t) FROM public.%I t)', table_name, table_name) INTO altered;
        IF altered THEN RAISE EXCEPTION 'Pre-existing rows changed in %; rolling back', table_name; END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM vm_meetings m JOIN demo_new_meetings n USING (meeting_id)
            WHERE m.lifecycle_state = 'LIVE' OR m.media_access_state = 'ACTIVE') THEN
        RAISE EXCEPTION 'Demo must never imply an active media room';
    END IF;
    IF EXISTS (SELECT 1 FROM vm_meeting_artifacts a JOIN demo_new_meetings n USING (meeting_id)
            WHERE a.artifact_state NOT IN ('NONE', 'UNAVAILABLE') OR a.object_key IS NOT NULL
                OR a.server_side_processing_allowed) THEN
        RAISE EXCEPTION 'Demo must never imply available media or processing permission';
    END IF;
    IF EXISTS (SELECT 1 FROM vm_meeting_intelligence_runs r JOIN demo_new_meetings n USING (meeting_id))
       OR EXISTS (SELECT 1 FROM vm_meeting_media_operations r JOIN demo_new_meetings n USING (meeting_id))
       OR EXISTS (SELECT 1 FROM vm_meeting_recording_sessions r JOIN demo_new_meetings n USING (meeting_id))
       OR EXISTS (SELECT 1 FROM vm_meeting_content_notice_acknowledgements r JOIN demo_new_meetings n USING (meeting_id)) THEN
        RAISE EXCEPTION 'No execution or consent evidence may be fabricated by demo data';
    END IF;
END $$;

SELECT (SELECT count(*) FROM demo_new_meetings) AS inserted_meetings,
       (SELECT count(*) FROM vm_meeting_participants JOIN demo_new_meetings USING (meeting_id)) AS inserted_participants,
       (SELECT count(*) FROM vm_meeting_agenda_items JOIN demo_new_meetings USING (meeting_id)) AS inserted_agenda_items,
       (SELECT count(*) FROM demo_new_templates) AS inserted_templates,
       (SELECT count(*) FROM demo_new_rooms) AS inserted_personal_rooms;
SELECT m.lifecycle_state, count(*) AS demo_meetings
FROM vm_meetings m JOIN demo_meeting_plan p USING (meeting_id) GROUP BY m.lifecycle_state ORDER BY 1;

\if :apply_seed
    COMMIT;
    \echo 'LOCAL DEMO COMMITTED. Existing rows preserved; no invitations, media or AI execution created.'
\else
    ROLLBACK;
    \echo 'DRY RUN PASSED AND ROLLED BACK. No persistent changes.'
\endif
