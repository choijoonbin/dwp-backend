ALTER TABLE svc_request_timeline
    DROP CONSTRAINT fk_svc_timeline_request;

ALTER TABLE svc_request_timeline
    ADD CONSTRAINT fk_svc_timeline_request
    FOREIGN KEY (tenant_id, service_request_id)
    REFERENCES svc_requests(tenant_id, service_request_id)
    ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION svc_reject_timeline_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'svc_request_timeline is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_svc_request_timeline_append_only
    BEFORE UPDATE OR DELETE ON svc_request_timeline
    FOR EACH ROW EXECUTE FUNCTION svc_reject_timeline_mutation();

WITH seed AS (
    SELECT *
      FROM (VALUES
        ('f12a232d-63d2-4ad0-b255-b63c75367101'::uuid,
         'technology.software-access', 5::bigint,
         '신규 프로젝트 데이터 모델링 도구 접근 요청',
         '{"softwareName":"DataGrip","businessReason":"신규 프로젝트 데이터 모델 검토","neededBy":"2026-08-18"}'::jsonb,
         'SUBMITTED', 'NORMAL', NULL::varchar,
         CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP + INTERVAL '23 hours'),
        ('7a773a69-0acb-4e83-b70e-f7b94ad64202'::uuid,
         'technology.device-help', 5::bigint,
         '회의실 외부 모니터 화면 깜빡임',
         '{"deviceType":"MONITOR","issue":"3층 A회의실 모니터가 간헐적으로 꺼집니다.","assetTag":"SKAX-MON-0317"}'::jsonb,
         'TRIAGED', 'HIGH', 'jiwoo.bae@sk.com',
         CURRENT_TIMESTAMP - INTERVAL '5 hours', CURRENT_TIMESTAMP + INTERVAL '3 hours'),
        ('94ef360b-0dda-4177-adb8-66e67a8bd503'::uuid,
         'workplace.facility-help', 10::bigint,
         '출입구 바닥 미끄럼 안전 위험',
         '{"location":"판교 캠퍼스 B동 1층","issue":"우천 시 출입구 바닥에 물이 고입니다.","safetyRisk":true}'::jsonb,
         'IN_PROGRESS', 'URGENT', 'jiwoo.bae@sk.com',
         CURRENT_TIMESTAMP - INTERVAL '10 hours', CURRENT_TIMESTAMP - INTERVAL '2 hours'),
        ('4398c23f-9a8d-4895-99f2-f78e62be7b04'::uuid,
         'finance.corporate-card', 15::bigint,
         '해외 결제 승인 확인을 위한 추가 정보 제출',
         '{"requestType":"TRANSACTION","businessReason":"해외 클라우드 서비스 구독 갱신"}'::jsonb,
         'AWAITING_REQUESTER', 'NORMAL', 'finance.operations@sk.com',
         CURRENT_TIMESTAMP - INTERVAL '20 hours', CURRENT_TIMESTAMP + INTERVAL '28 hours'),
        ('dbaaaec1-2530-4b54-beae-8853ad8ae405'::uuid,
         'people.employment-certificate', 24::bigint,
         '영문 재직증명서 1부 발급',
         '{"purpose":"비자 신청","language":"EN","copies":1}'::jsonb,
         'RESOLVED', 'NORMAL', 'people.operations@sk.com',
         CURRENT_TIMESTAMP - INTERVAL '30 hours', CURRENT_TIMESTAMP - INTERVAL '6 hours')
      ) value(request_id, service_key, requester_user_id, summary, payload,
              status, priority, assigned_to, submitted_at, sla_due_at)
), inserted AS (
    INSERT INTO svc_requests (
        service_request_id, tenant_id, request_number, requester_user_id,
        service_definition_id, service_key, service_name_ko, service_name_en,
        summary, request_payload, request_schema_snapshot, schema_version,
        status, priority, data_classification, assigned_group, assigned_to,
        submitted_at, sla_due_at, resolved_at, idempotency_key,
        created_at, created_by, updated_at, updated_by)
    SELECT seed.request_id,
           tenant.tenant_id,
           'SR-' || lpad(nextval('svc_request_number_seq')::text, 8, '0'),
           seed.requester_user_id,
           definition.service_definition_id,
           definition.service_key,
           definition.name_ko,
           definition.name_en,
           seed.summary,
           seed.payload,
           definition.request_schema,
           definition.schema_version,
           seed.status,
           seed.priority,
           definition.data_classification,
           definition.owner_group,
           seed.assigned_to,
           seed.submitted_at,
           seed.sla_due_at,
           CASE WHEN seed.status = 'RESOLVED'
                THEN CURRENT_TIMESTAMP - INTERVAL '6 hours' END,
           seed.request_id,
           seed.submitted_at,
           seed.requester_user_id,
           CASE WHEN seed.status = 'SUBMITTED' THEN seed.submitted_at
                ELSE CURRENT_TIMESTAMP - INTERVAL '30 minutes' END,
           CASE WHEN seed.status = 'SUBMITTED'
                THEN seed.requester_user_id ELSE 1 END
      FROM seed
      JOIN sys_service_tenants tenant ON tenant.tenant_key = 'default'
      JOIN svc_definitions definition
        ON definition.tenant_id = tenant.tenant_id
       AND definition.service_key = seed.service_key
    ON CONFLICT (tenant_id, requester_user_id, idempotency_key) DO NOTHING
    RETURNING tenant_id, service_request_id, requester_user_id, status,
              submitted_at, assigned_to
)
INSERT INTO svc_request_timeline (
    service_request_event_id, tenant_id, service_request_id, event_type,
    status, actor_type, actor_id, note, occurred_at)
SELECT gen_random_uuid(), tenant_id, service_request_id, 'REQUEST_SUBMITTED',
       'SUBMITTED', 'USER', requester_user_id, NULL, submitted_at
  FROM inserted
UNION ALL
SELECT gen_random_uuid(), tenant_id, service_request_id, 'STATUS_CHANGED',
       status, 'SYSTEM', NULL,
       CASE status
           WHEN 'TRIAGED' THEN '담당 그룹이 요청을 분류하고 우선순위를 확인했습니다.'
           WHEN 'IN_PROGRESS' THEN '담당자가 요청 처리를 시작했습니다.'
           WHEN 'AWAITING_REQUESTER' THEN '거래 확인을 위한 증빙 자료를 기다리고 있습니다.'
           WHEN 'RESOLVED' THEN '요청한 증명서가 발급되었습니다.'
       END,
       CURRENT_TIMESTAMP - INTERVAL '30 minutes'
  FROM inserted
 WHERE status <> 'SUBMITTED';

COMMENT ON FUNCTION svc_reject_timeline_mutation() IS
    'Enforces append-only employee-service lifecycle evidence at the database boundary.';
