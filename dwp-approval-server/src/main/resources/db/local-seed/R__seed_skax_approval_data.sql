-- Local-only, database-backed approval workload for provisioned SKAX users.
-- This location is enabled by scripts/devctl.py and is excluded from the
-- default production Flyway locations.
INSERT INTO apr_tenants (tenant_id)
VALUES (1)
ON CONFLICT (tenant_id) DO NOTHING;
SELECT seed_approval_form_catalog(1);
SELECT seed_approval_tenant(1);
SELECT seed_approval_product_templates(1);
SELECT seed_approval_form_catalog(1);

CREATE TEMP TABLE seed_skax_members (
    member_order INTEGER PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    person_public_id UUID NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    email VARCHAR(320) NOT NULL UNIQUE,
    organization_name VARCHAR(200) NOT NULL,
    job_title VARCHAR(200) NOT NULL
) ON COMMIT PRESERVE ROWS;

INSERT INTO seed_skax_members (
    member_order, user_id, person_public_id, display_name, email,
    organization_name, job_title)
VALUES
    (1, 5, '3edde887-9716-8950-e7a0-045998101987', '김민서', 'minseo.kim@sk.com',
     'Network Operations 팀', 'Network Operations Lead'),
    (2, 9, '34f5e51a-2ca6-c6f6-6627-b44f08f31d1d', '이서연', 'seoyeon.lee@sk.com',
     'CEO Staff', 'Executive Strategy Officer'),
    (3, 10, '5af80da3-0dd8-b3bc-2f44-22d90eecaac4', '박현우', 'hyunwoo.park@sk.com',
     'Digital Platform 부문', 'Digital Platform 부문장'),
    (4, 11, '00ba0853-02a8-7499-b6d8-009251e6a464', '최유진', 'yujin.choi@sk.com',
     'Enterprise Transformation 부문', 'Enterprise Transformation 부문장'),
    (5, 14, '306b543f-741f-6fd3-36bf-48325f3e7e20', '김도윤', 'doyun.kim@sk.com',
     'AI Platform 본부', 'AI Platform 본부장'),
    (6, 15, 'e96089af-ead6-2f6a-6111-1d2e15058b1d', '윤서진', 'seojin.yoon@sk.com',
     'Cloud & Infra 본부', 'Cloud & Infra 본부장'),
    (7, 16, 'bda29b83-7a8f-ded4-083b-244f055bd6c4', '장민석', 'minseok.jang@sk.com',
     'GenAI Engineering 팀', 'GenAI Engineering 팀장'),
    (8, 20, '71ed1904-1405-e7ce-3f27-0845298ba1e2', '오수빈', 'subin.oh@sk.com',
     'Data Platform 팀', 'Data Platform 팀장'),
    (9, 21, '94d55a4f-96de-09fd-5454-bbd64b60ccb3', '강태훈', 'taehoon.kang@sk.com',
     'Data Platform 팀', 'Data Architect'),
    (10, 22, '457477f1-ee4a-9b12-3668-ec7663989ee5', '문예린', 'yerin.moon@sk.com',
     'Data Platform 팀', 'Analytics Engineer'),
    (11, 24, 'a3e07946-57b1-4441-ae00-d14ad9eb284c', '배지우', 'jiwoo.bae@sk.com',
     'Cloud Platform 팀', 'Site Reliability Engineer'),
    (12, 29, 'd4bc013d-8c7a-fbcb-be2a-7d83286e0b18', '김채원', 'chaewon.kim@sk.com',
     'ERP Innovation 본부', 'SAP Transformation Consultant'),
    (13, 32, '6edd429e-6650-00a3-d68a-2bd4cc954551', '정서우', 'seowoo.jung@sk.com',
     'Digital Consulting 본부', 'Business Consultant'),
    (14, 33, 'd1b648ab-318d-824d-50f6-11c418b75f9a', '이도현', 'dohyun.lee@sk.com',
     'Digital Consulting 본부', 'Change Management Lead'),
    (15, 35, '6625e4a8-eaa9-c5d7-20bc-47f2029677b3', '최건우', 'gunwoo.choi@sk.com',
     'Customer Experience 팀', 'UX Strategist'),
    (16, 37, '073c6aef-f778-94ac-4bb3-0e355fa41dbc', '홍지수', 'jisoo.hong@sk.com',
     'People & Culture 팀', 'People & Culture 팀장'),
    (17, 38, '3490c134-c01b-d32d-eda2-f257c94496f2', '남도윤', 'doyoon.nam@sk.com',
     'People & Culture 팀', 'HR Business Partner'),
    (18, 40, '6dddb2e7-e311-0455-2c15-55d1ff0e2379', '김태연', 'taeyeon.kim@sk.com',
     'Finance & Risk 팀', 'Finance & Risk 팀장'),
    (19, 41, 'cc4804fd-f65a-998f-b162-4c2d594ec767', '유승민', 'seungmin.yoo@sk.com',
     'Finance & Risk 팀', 'Financial Controller'),
    (20, 42, 'aaf32653-4578-46a9-c679-7302615e84cc', 'James Wilson', 'james.wilson@sk.com',
     'Finance & Risk 팀', 'Risk Analyst'),
    (21, 900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b', '최준빈', 'joonbin@sk.com',
     'Digital Platform 부문', 'Company Tenant 최고 권한 관리자');

CREATE TEMP TABLE seed_skax_requests ON COMMIT PRESERVE ROWS AS
WITH scenarios AS (
    SELECT *
      FROM (VALUES
        (1, 'ACTIVE'),
        (2, 'NEEDS_INFO'),
        (3, 'ARCHIVE'),
        (4, 'DRAFT')
      ) scenario(scenario_order, scenario_key)
), request_base AS (
    SELECT member.*,
           scenario.scenario_order,
           scenario.scenario_key,
           CASE 1 + MOD(member.member_order + scenario.scenario_order - 2, 3)
               WHEN 1 THEN 'CAPEX_PURCHASE'
               WHEN 2 THEN 'ACCESS_EXCEPTION'
               ELSE 'SUPPLIER_ONBOARDING'
           END AS workflow_key,
           CASE scenario.scenario_key
               WHEN 'ACTIVE' THEN 'IN_REVIEW'
               WHEN 'NEEDS_INFO' THEN 'NEEDS_INFO'
               WHEN 'DRAFT' THEN 'DRAFT'
               ELSE CASE MOD(member.member_order, 5)
                   WHEN 0 THEN 'REJECTED'
                   WHEN 1 THEN 'WITHDRAWN'
                   ELSE 'APPROVED'
               END
           END AS request_status
      FROM seed_skax_members member
     CROSS JOIN scenarios scenario
), request_content AS (
    SELECT base.*,
           md5('skax-approval-request:v1:' || base.user_id || ':' || base.scenario_key)::uuid
               AS request_id,
           'SKAX-' || base.user_id || '-' || base.scenario_order AS request_number,
           CASE base.workflow_key
               WHEN 'CAPEX_PURCHASE' THEN
                   (ARRAY[
                       'AI 개발 환경 GPU 증설', '클라우드 예약 인스턴스 전환',
                       '데이터 파이프라인 저장소 확장', '보안 네트워크 장비 교체'
                   ])[1 + MOD(base.member_order + base.scenario_order, 4)]
               WHEN 'ACCESS_EXCEPTION' THEN
                   (ARRAY[
                       '고객 분석 데이터 접근 예외', '운영 로그 조회 권한 연장',
                       '네트워크 관리 콘솔 긴급 접근', '분석 워크스페이스 권한 요청'
                   ])[1 + MOD(base.member_order + base.scenario_order, 4)]
               ELSE
                   (ARRAY[
                       'AI 모델 평가 협력사 등록', '클라우드 전환 컨설팅사 등록',
                       '데이터 품질 검증사 온보딩', '글로벌 리서치 파트너 등록'
                   ])[1 + MOD(base.member_order + base.scenario_order, 4)]
           END || ' - ' || base.organization_name AS title,
           CASE base.workflow_key
               WHEN 'CAPEX_PURCHASE' THEN
                   base.organization_name || '의 업무 생산성과 안정성을 위한 투자 검토 요청입니다.'
               WHEN 'ACCESS_EXCEPTION' THEN
                   base.job_title || ' 업무 수행에 필요한 최소 권한과 보완 통제를 검토해 주세요.'
               ELSE
                   base.organization_name || '의 신규 프로젝트 수행을 위한 협력사 사전 검토 요청입니다.'
           END AS summary,
           CASE base.scenario_key
               WHEN 'DRAFT' THEN NULL
               ELSE CURRENT_TIMESTAMP
                    - make_interval(hours => base.member_order * 3 + base.scenario_order * 5)
           END AS submitted_at,
           CASE base.scenario_key
               WHEN 'ACTIVE' THEN CURRENT_TIMESTAMP
                    + make_interval(hours => (MOD(base.member_order, 5) - 2) * 6 + 2)
               WHEN 'NEEDS_INFO' THEN CURRENT_TIMESTAMP
                    + make_interval(days => 1 + MOD(base.member_order, 3))
               WHEN 'DRAFT' THEN CURRENT_TIMESTAMP
                    + make_interval(days => 5 + MOD(base.member_order, 5))
               ELSE CURRENT_TIMESTAMP
                    - make_interval(days => 1 + MOD(base.member_order, 10))
           END AS due_at,
           CASE WHEN base.scenario_key = 'ARCHIVE'
               THEN CURRENT_TIMESTAMP - make_interval(hours => 4 + base.member_order * 2)
               ELSE NULL
           END AS completed_at,
           CASE MOD(base.member_order + base.scenario_order, 4)
               WHEN 0 THEN 'URGENT'
               WHEN 1 THEN 'HIGH'
               WHEN 2 THEN 'NORMAL'
               ELSE 'LOW'
           END AS priority,
           CASE base.workflow_key
               WHEN 'ACCESS_EXCEPTION' THEN 'RESTRICTED'
               WHEN 'SUPPLIER_ONBOARDING' THEN 'CONFIDENTIAL'
               ELSE CASE WHEN MOD(base.member_order, 3) = 0
                    THEN 'CONFIDENTIAL' ELSE 'INTERNAL' END
           END AS data_classification
      FROM request_base base
)
SELECT content.*,
       CASE content.workflow_key
           WHEN 'CAPEX_PURCHASE' THEN jsonb_build_object(
               'summary', content.summary,
               'amount', 12000000 + content.member_order * 1750000
                   + content.scenario_order * 250000,
               'currency', CASE WHEN MOD(content.member_order, 5) = 0 THEN 'USD' ELSE 'KRW' END,
               'costCenter', 'CC-' || LPAD((3000 + content.member_order)::text, 4, '0'),
               'vendor', (ARRAY['SK Shieldus', 'AWS Korea', 'Dell Technologies', 'MegazoneCloud'])
                   [1 + MOD(content.member_order, 4)],
               'neededBy', TO_CHAR(CURRENT_DATE + 14 + content.member_order, 'YYYY-MM-DD'))
           WHEN 'ACCESS_EXCEPTION' THEN jsonb_build_object(
               'summary', content.summary,
               'systemName', (ARRAY['DWP Production', 'Enterprise Data Lake', 'Network OSS', 'SAP S/4HANA'])
                   [1 + MOD(content.member_order, 4)],
               'accessRole', (ARRAY['Read only analyst', 'Incident responder', 'Operations reviewer', 'Project contributor'])
                   [1 + MOD(content.member_order + 1, 4)],
               'startDate', TO_CHAR(CURRENT_DATE + 1, 'YYYY-MM-DD'),
               'endDate', TO_CHAR(CURRENT_DATE + 31 + MOD(content.member_order, 60), 'YYYY-MM-DD'),
               'compensatingControl', 'MFA, 세션 기록, 주간 접근 로그 검토를 적용합니다.')
           ELSE jsonb_build_object(
               'summary', content.summary,
               'supplierName', (ARRAY['Nova AI Labs', 'Apex Cloud Partners', 'Vertex Data Quality', 'Global Insight Group'])
                   [1 + MOD(content.member_order, 4)],
               'countryCode', (ARRAY['KR', 'US', 'SG', 'JP'])
                   [1 + MOD(content.member_order + 2, 4)],
               'contractValue', 45000000 + content.member_order * 2100000,
               'dataAccessLevel', (ARRAY['INTERNAL', 'CONFIDENTIAL', 'NONE'])
                   [1 + MOD(content.member_order, 3)],
               'targetDate', TO_CHAR(CURRENT_DATE + 21 + content.member_order, 'YYYY-MM-DD'))
       END AS payload
  FROM request_content content;

INSERT INTO apr_requests (
    request_id, tenant_id, request_number, workflow_version_id, form_version_id,
    title, summary, requester_user_id, requester_person_public_id,
    requester_name, requester_org_name, status, priority, data_classification,
    submitted_at, due_at, completed_at, source_system, source_reference,
    reference_seed_key, management_resource_set_key,
    version, created_at, created_by, updated_at, updated_by)
SELECT seed.request_id, 1, seed.request_number,
       workflow_version.workflow_version_id, form_version.form_version_id,
       seed.title, seed.summary, seed.user_id, seed.person_public_id,
       seed.display_name, seed.organization_name, seed.request_status,
       seed.priority, seed.data_classification,
       seed.submitted_at, seed.due_at, seed.completed_at,
       'DWP_LOCAL_SEED', seed.email || ':' || LOWER(seed.scenario_key),
       'seed:skax-approval:v1:' || seed.user_id || ':' || LOWER(seed.scenario_key),
       'RS_APPROVALS',
       CASE WHEN seed.scenario_key = 'DRAFT' THEN 0 ELSE 1 END,
       COALESCE(seed.submitted_at, CURRENT_TIMESTAMP - INTERVAL '1 hour'),
       seed.user_id,
       COALESCE(seed.completed_at, seed.submitted_at, CURRENT_TIMESTAMP - INTERVAL '30 minutes'),
       seed.user_id
  FROM seed_skax_requests seed
  JOIN apr_workflow_definitions workflow
    ON workflow.tenant_id = 1
   AND workflow.workflow_key = seed.workflow_key
   AND workflow.management_resource_set_key = 'RS_APPROVALS'
  JOIN apr_workflow_versions workflow_version
    ON workflow_version.tenant_id = workflow.tenant_id
   AND workflow_version.workflow_id = workflow.workflow_id
   AND workflow_version.version_number = workflow.current_version
  JOIN apr_forms form
    ON form.tenant_id = workflow.tenant_id
   AND form.form_key = workflow.workflow_key || '_FORM'
   AND form.management_resource_set_key = 'RS_APPROVALS'
  JOIN apr_form_versions form_version
    ON form_version.tenant_id = form.tenant_id
   AND form_version.form_id = form.form_id
   AND form_version.version_number = form.current_version
ON CONFLICT (tenant_id, reference_seed_key) DO NOTHING;

INSERT INTO apr_request_payloads (
    tenant_id, request_id, payload, payload_sha256, schema_version,
    created_at, updated_at)
SELECT 1, seed.request_id, seed.payload,
       encode(sha256(convert_to(seed.payload::text, 'UTF8')), 'hex'), 2,
       COALESCE(seed.submitted_at, CURRENT_TIMESTAMP - INTERVAL '1 hour'),
       COALESCE(seed.completed_at, seed.submitted_at, CURRENT_TIMESTAMP - INTERVAL '30 minutes')
  FROM seed_skax_requests seed
  JOIN apr_requests request
    ON request.tenant_id = 1
   AND request.request_id = seed.request_id
ON CONFLICT (tenant_id, request_id) DO NOTHING;

WITH expanded_steps AS (
    SELECT seed.*,
           step.value AS step_definition,
           step.ordinality::INTEGER AS sequence_number,
           JSONB_ARRAY_LENGTH(workflow_version.definition->'steps') AS step_count,
           CASE seed.scenario_key
               WHEN 'ACTIVE' THEN 1 + MOD(seed.member_order - 1,
                   JSONB_ARRAY_LENGTH(workflow_version.definition->'steps'))
               WHEN 'NEEDS_INFO' THEN 1 + MOD(seed.member_order,
                   JSONB_ARRAY_LENGTH(workflow_version.definition->'steps'))
               ELSE JSONB_ARRAY_LENGTH(workflow_version.definition->'steps')
           END AS current_sequence
      FROM seed_skax_requests seed
      JOIN apr_requests request
        ON request.tenant_id = 1
       AND request.request_id = seed.request_id
      JOIN apr_workflow_versions workflow_version
        ON workflow_version.tenant_id = request.tenant_id
       AND workflow_version.workflow_version_id = request.workflow_version_id
     CROSS JOIN LATERAL JSONB_ARRAY_ELEMENTS(workflow_version.definition->'steps')
          WITH ORDINALITY AS step(value, ordinality)
     WHERE seed.scenario_key <> 'DRAFT'
)
INSERT INTO apr_steps (
    step_id, tenant_id, request_id, step_key, step_name, sequence_number,
    approval_mode, candidate_role, status, started_at, due_at, completed_at,
    version, created_at, updated_at)
SELECT md5('skax-approval-step:v1:' || expanded.request_id || ':' || expanded.sequence_number)::uuid,
       1, expanded.request_id,
       expanded.step_definition->>'key', expanded.step_definition->>'name',
       expanded.sequence_number,
       COALESCE(expanded.step_definition->>'mode', 'ANY'),
       COALESCE(expanded.step_definition->>'candidateRole', 'APPROVAL_OPERATOR'),
       CASE expanded.scenario_key
           WHEN 'ACTIVE' THEN CASE
               WHEN expanded.sequence_number < expanded.current_sequence THEN 'APPROVED'
               WHEN expanded.sequence_number = expanded.current_sequence THEN 'IN_PROGRESS'
               ELSE 'WAITING'
           END
           WHEN 'NEEDS_INFO' THEN CASE
               WHEN expanded.sequence_number < expanded.current_sequence THEN 'APPROVED'
               WHEN expanded.sequence_number = expanded.current_sequence THEN 'IN_PROGRESS'
               ELSE 'WAITING'
           END
           ELSE CASE expanded.request_status
               WHEN 'APPROVED' THEN 'APPROVED'
               WHEN 'REJECTED' THEN CASE WHEN expanded.sequence_number = 1
                    THEN 'REJECTED' ELSE 'CANCELLED' END
               ELSE 'CANCELLED'
           END
       END,
       CASE
           WHEN expanded.scenario_key IN ('ACTIVE', 'NEEDS_INFO')
                AND expanded.sequence_number <= expanded.current_sequence
               THEN expanded.submitted_at
                    + make_interval(hours => (expanded.sequence_number - 1) * 4)
           WHEN expanded.scenario_key = 'ARCHIVE' THEN expanded.submitted_at
                    + make_interval(hours => (expanded.sequence_number - 1) * 4)
           ELSE NULL
       END,
       expanded.submitted_at
            + make_interval(mins => (expanded.step_definition->>'slaMinutes')::INTEGER),
       CASE
           WHEN expanded.scenario_key = 'ARCHIVE' THEN expanded.completed_at
           WHEN expanded.sequence_number < expanded.current_sequence
               THEN expanded.submitted_at
                    + make_interval(hours => expanded.sequence_number * 4 - 1)
           ELSE NULL
       END,
       CASE WHEN expanded.scenario_key = 'ARCHIVE'
                 OR expanded.sequence_number < expanded.current_sequence THEN 1 ELSE 0 END,
       expanded.submitted_at,
       COALESCE(expanded.completed_at, expanded.submitted_at)
  FROM expanded_steps expanded
ON CONFLICT (tenant_id, request_id, sequence_number) DO NOTHING;

WITH step_context AS (
    SELECT seed.*,
           step.step_id, step.sequence_number, step.candidate_role,
           JSONB_ARRAY_LENGTH(workflow_version.definition->'steps') AS step_count,
           CASE seed.scenario_key
               WHEN 'ACTIVE' THEN 1 + MOD(seed.member_order - 1,
                   JSONB_ARRAY_LENGTH(workflow_version.definition->'steps'))
               WHEN 'NEEDS_INFO' THEN 1 + MOD(seed.member_order,
                   JSONB_ARRAY_LENGTH(workflow_version.definition->'steps'))
               ELSE JSONB_ARRAY_LENGTH(workflow_version.definition->'steps')
           END AS current_sequence
      FROM seed_skax_requests seed
      JOIN apr_requests request
        ON request.tenant_id = 1
       AND request.request_id = seed.request_id
      JOIN apr_workflow_versions workflow_version
        ON workflow_version.tenant_id = request.tenant_id
       AND workflow_version.workflow_version_id = request.workflow_version_id
      JOIN apr_steps step
        ON step.tenant_id = request.tenant_id
       AND step.request_id = request.request_id
), task_context AS (
    SELECT context.*,
           CASE
               WHEN context.scenario_key = 'ACTIVE'
                    AND context.sequence_number = context.current_sequence THEN 1
               WHEN context.scenario_key = 'NEEDS_INFO'
                    AND context.sequence_number = context.current_sequence THEN 2
               ELSE context.sequence_number
           END AS assignee_offset
      FROM step_context context
     WHERE (context.scenario_key IN ('ACTIVE', 'NEEDS_INFO')
                AND context.sequence_number <= context.current_sequence)
        OR (context.scenario_key = 'ARCHIVE'
                AND context.request_status = 'APPROVED')
        OR (context.scenario_key = 'ARCHIVE'
                AND context.request_status IN ('REJECTED', 'WITHDRAWN')
                AND context.sequence_number = 1)
)
INSERT INTO apr_tasks (
    task_id, tenant_id, request_id, step_id,
    assignee_user_id, assignee_person_public_id, candidate_role,
    status, risk_score, decision_reason, claimed_at, due_at, completed_at,
    version, created_at, updated_at)
SELECT md5('skax-approval-task:v1:' || context.request_id || ':' || context.sequence_number)::uuid,
       1, context.request_id, context.step_id,
       assignee.user_id, assignee.person_public_id, context.candidate_role,
       CASE context.scenario_key
           WHEN 'ACTIVE' THEN CASE
               WHEN context.sequence_number < context.current_sequence THEN 'APPROVED'
               WHEN MOD(context.member_order, 3) = 0 THEN 'CLAIMED'
               ELSE 'PENDING'
           END
           WHEN 'NEEDS_INFO' THEN CASE
               WHEN context.sequence_number < context.current_sequence THEN 'APPROVED'
               ELSE 'INFO_REQUESTED'
           END
           ELSE CASE context.request_status
               WHEN 'APPROVED' THEN 'APPROVED'
               WHEN 'REJECTED' THEN 'REJECTED'
               ELSE 'CANCELLED'
           END
       END,
       25 + MOD(context.member_order * 11 + context.scenario_order * 17
           + context.sequence_number * 7, 71),
       CASE
           WHEN context.scenario_key = 'NEEDS_INFO'
                AND context.sequence_number = context.current_sequence
               THEN '영향 범위와 검토 증빙 자료를 추가해 주세요.'
           WHEN context.request_status = 'REJECTED'
               THEN '필수 보완 통제가 충족되지 않아 반려되었습니다.'
           WHEN context.request_status = 'WITHDRAWN'
               THEN '요청자가 우선순위 변경으로 결재를 회수했습니다.'
           WHEN context.sequence_number < context.current_sequence
                OR context.request_status = 'APPROVED'
               THEN '업무 근거와 정책 요건을 확인했습니다.'
           ELSE NULL
       END,
       CASE
           WHEN context.scenario_key = 'ACTIVE'
                AND context.sequence_number = context.current_sequence
                AND MOD(context.member_order, 3) <> 0 THEN NULL
           ELSE context.submitted_at
                + make_interval(hours => context.sequence_number * 2)
       END,
       context.submitted_at
            + make_interval(hours => context.sequence_number * 8),
       CASE
           WHEN context.scenario_key = 'ACTIVE'
                AND context.sequence_number = context.current_sequence THEN NULL
           WHEN context.scenario_key = 'NEEDS_INFO'
                AND context.sequence_number = context.current_sequence THEN NULL
           ELSE COALESCE(context.completed_at,
                context.submitted_at + make_interval(hours => context.sequence_number * 4 - 1))
       END,
       CASE
           WHEN context.scenario_key = 'ACTIVE'
                AND context.sequence_number = context.current_sequence THEN 0
           ELSE 1
       END,
       context.submitted_at,
       COALESCE(context.completed_at, context.submitted_at)
  FROM task_context context
  JOIN seed_skax_members assignee
    ON assignee.member_order = 1 + MOD(
        context.member_order - 1 + context.assignee_offset,
        (SELECT COUNT(*)::INTEGER FROM seed_skax_members))
ON CONFLICT (task_id) DO NOTHING;

INSERT INTO apr_request_events (
    event_id, tenant_id, request_id, event_type, actor_type, actor_id,
    outcome, message, correlation_id, event_data, occurred_at)
SELECT md5('skax-approval-event:v1:' || seed.request_id || ':created')::uuid,
       1, seed.request_id, 'REQUEST_CREATED', 'USER', seed.user_id::text,
       'SUCCESS', seed.display_name || '님이 결재 문서를 작성했습니다.',
       'local-seed:' || seed.request_id,
       jsonb_build_object(
           'source', 'DWP_LOCAL_SEED',
           'email', seed.email,
           'actorDisplayName', seed.display_name),
       COALESCE(seed.submitted_at, CURRENT_TIMESTAMP - INTERVAL '1 hour') - INTERVAL '20 minutes'
  FROM seed_skax_requests seed
  JOIN apr_requests request ON request.tenant_id = 1 AND request.request_id = seed.request_id
ON CONFLICT (event_id) DO UPDATE
    SET event_data = EXCLUDED.event_data;

INSERT INTO apr_request_events (
    event_id, tenant_id, request_id, event_type, actor_type, actor_id,
    outcome, message, correlation_id, event_data, occurred_at)
SELECT md5('skax-approval-event:v1:' || seed.request_id || ':submitted')::uuid,
       1, seed.request_id, 'REQUEST_SUBMITTED', 'USER', seed.user_id::text,
       'SUCCESS', '결재 요청이 제출되어 승인 경로가 시작되었습니다.',
       'local-seed:' || seed.request_id,
       jsonb_build_object(
           'workflowKey', seed.workflow_key,
           'actorDisplayName', seed.display_name), seed.submitted_at
  FROM seed_skax_requests seed
  JOIN apr_requests request ON request.tenant_id = 1 AND request.request_id = seed.request_id
 WHERE seed.scenario_key <> 'DRAFT'
ON CONFLICT (event_id) DO UPDATE
    SET event_data = EXCLUDED.event_data;

INSERT INTO apr_request_events (
    event_id, tenant_id, request_id, event_type, actor_type, actor_id,
    outcome, message, correlation_id, event_data, occurred_at)
SELECT md5('skax-approval-event:v1:' || task.task_id || ':decision')::uuid,
       1, task.request_id,
       CASE task.status
           WHEN 'APPROVED' THEN 'TASK_APPROVED'
           WHEN 'REJECTED' THEN 'TASK_REJECTED'
           WHEN 'INFO_REQUESTED' THEN 'INFORMATION_REQUESTED'
           ELSE 'REQUEST_WITHDRAWN'
       END,
       'USER',
       COALESCE(task.decision_actor_user_id, task.assignee_user_id)::text, 'SUCCESS',
       COALESCE(task.decision_reason, '결재 상태가 변경되었습니다.'),
       'local-seed:' || task.request_id,
       jsonb_build_object(
           'taskId', task.task_id,
           'status', task.status,
           'actorDisplayName', actor.display_name,
           'stepName', step.step_name,
           'stepSequence', step.sequence_number,
           'delegated', task.delegated_from_user_id IS NOT NULL),
       CASE WHEN request.submitted_at IS NOT NULL
            THEN request.submitted_at + make_interval(hours => step.sequence_number * 4)
            ELSE COALESCE(task.completed_at, task.claimed_at, task.updated_at)
       END
  FROM apr_tasks task
  JOIN apr_requests request
   ON request.tenant_id = task.tenant_id
   AND request.request_id = task.request_id
  JOIN apr_steps step
    ON step.tenant_id = task.tenant_id
   AND step.step_id = task.step_id
  LEFT JOIN seed_skax_members actor
    ON actor.user_id = COALESCE(task.decision_actor_user_id, task.assignee_user_id)
 WHERE request.reference_seed_key LIKE 'seed:skax-approval:v1:%'
   AND task.status IN ('APPROVED', 'REJECTED', 'INFO_REQUESTED', 'CANCELLED')
ON CONFLICT (event_id) DO UPDATE
    SET actor_id = EXCLUDED.actor_id,
        event_data = EXCLUDED.event_data,
        occurred_at = EXCLUDED.occurred_at;

INSERT INTO apr_delegations (
    delegation_id, tenant_id, delegator_user_id, delegate_user_id,
    delegate_person_public_id, delegate_display_name, delegate_email,
    delegated_role_codes,
    scope_type, workflow_id, workflow_key, starts_at, ends_at, lifecycle_state,
    reason, version, created_at, created_by, updated_at, updated_by)
SELECT md5('skax-approval-delegation:v1:' || delegator.user_id)::uuid,
       1, delegator.user_id, delegate.user_id,
       delegate.person_public_id, delegate.display_name, delegate.email,
       jsonb_build_array('WORKSPACE_MEMBER'),
       CASE WHEN MOD(delegator.user_id, 2) = 0 THEN 'WORKFLOW' ELSE 'ALL' END,
       CASE WHEN MOD(delegator.user_id, 2) = 0
            THEN md5('approval-workflow:1:CAPEX_PURCHASE')::uuid ELSE NULL END,
       CASE WHEN MOD(delegator.user_id, 2) = 0 THEN 'CAPEX_PURCHASE' ELSE NULL END,
       CURRENT_TIMESTAMP - INTERVAL '5 days',
       CASE MOD(delegator.member_order, 3)
           WHEN 0 THEN CURRENT_TIMESTAMP - INTERVAL '1 day'
           ELSE CURRENT_TIMESTAMP + INTERVAL '7 days'
       END,
       CASE MOD(delegator.member_order, 3)
           WHEN 0 THEN 'EXPIRED'
           WHEN 1 THEN 'ACTIVE'
           ELSE 'REVOKED'
       END,
       '휴가 및 프로젝트 일정에 따른 결재 대행 설정',
       0, CURRENT_TIMESTAMP - INTERVAL '5 days', delegator.user_id,
       CURRENT_TIMESTAMP - INTERVAL '2 days', delegator.user_id
  FROM seed_skax_members delegator
  JOIN seed_skax_members delegate
    ON delegate.member_order = 1 + MOD(
        delegator.member_order,
        (SELECT COUNT(*)::INTEGER FROM seed_skax_members))
 WHERE MOD(delegator.member_order - 1, 4) = 0
ON CONFLICT (delegation_id) DO UPDATE
    SET delegate_user_id = EXCLUDED.delegate_user_id,
        delegate_person_public_id = EXCLUDED.delegate_person_public_id,
        delegate_display_name = EXCLUDED.delegate_display_name,
        delegate_email = EXCLUDED.delegate_email,
        delegated_role_codes = EXCLUDED.delegated_role_codes,
        starts_at = EXCLUDED.starts_at,
        ends_at = EXCLUDED.ends_at,
        lifecycle_state = EXCLUDED.lifecycle_state,
        reason = EXCLUDED.reason,
        updated_at = EXCLUDED.updated_at,
        updated_by = EXCLUDED.updated_by;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM seed_skax_members delegator
          JOIN apr_delegations delegation
            ON delegation.delegation_id =
               md5('skax-approval-delegation:v1:' || delegator.user_id)::uuid
         WHERE MOD(delegator.member_order - 1, 4) = 0
           AND (delegation.scope_type IS DISTINCT FROM
                    CASE WHEN MOD(delegator.user_id, 2) = 0
                         THEN 'WORKFLOW' ELSE 'ALL' END
                OR delegation.workflow_id IS DISTINCT FROM
                    CASE WHEN MOD(delegator.user_id, 2) = 0
                         THEN md5('approval-workflow:1:CAPEX_PURCHASE')::uuid
                         ELSE NULL END
                OR delegation.workflow_key IS DISTINCT FROM
                    CASE WHEN MOD(delegator.user_id, 2) = 0
                         THEN 'CAPEX_PURCHASE' ELSE NULL END)) THEN
        RAISE EXCEPTION 'SKAX Approval delegation seed identity is inconsistent';
    END IF;
END
$$;

DROP TABLE seed_skax_requests;
DROP TABLE seed_skax_members;
