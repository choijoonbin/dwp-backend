-- Local-only, database-backed HR experience for the current SKAX workforce.
-- Production loads only db/migration. Every generated value is reference data,
-- deterministic for a stable worker identity, and safe to recreate on a clean DB.

CREATE TEMP TABLE seed_skax_hr_members ON COMMIT DROP AS
SELECT worker.tenant_id,
       worker.worker_id,
       worker.worker_type,
       worker.worker_status,
       worker.original_hire_date,
       person.public_id AS person_public_id,
       person.display_name,
       (('x' || SUBSTRING(MD5(person.public_id::TEXT), 1, 8))::BIT(32)::BIGINT) AS seed_value
  FROM sys_service_tenants tenant
  JOIN ppl_persons person
    ON person.tenant_id = tenant.tenant_id
   AND person.lifecycle_state = 'ACTIVE'
  JOIN ppl_workers worker
    ON worker.tenant_id = person.tenant_id
   AND worker.person_id = person.person_id
   AND worker.worker_status IN ('ACTIVE', 'LEAVE')
 WHERE tenant.tenant_key = 'default'
   AND tenant.lifecycle_state = 'ACTIVE';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM seed_skax_hr_members) THEN
        RAISE EXCEPTION 'SKAX local HR seed requires an active default workforce';
    END IF;
END
$$;

-- Time: one current card per worker, weekday entries, mixed work modes, and
-- deterministic exceptions. Self-service rows are not overwritten.
INSERT INTO tme_time_cards (
    public_id, tenant_id, worker_id, period_start_date, period_end_date,
    status, scheduled_minutes, recorded_minutes, exception_count,
    submitted_at, decided_at, decided_by, decision_note,
    data_origin, created_by, updated_by)
SELECT MD5('local-seed:skax-hr:time-card:' || member.tenant_id || ':'
           || member.worker_id || ':' || DATE_TRUNC('week', CURRENT_DATE)::DATE)::UUID,
       member.tenant_id,
       member.worker_id,
       DATE_TRUNC('week', CURRENT_DATE)::DATE,
       (DATE_TRUNC('week', CURRENT_DATE) + INTERVAL '6 days')::DATE,
       CASE
           WHEN member.worker_status = 'LEAVE' THEN 'LOCKED'
           WHEN MOD(member.seed_value, 13) = 0 THEN 'SUBMITTED'
           WHEN MOD(member.seed_value, 17) = 0 THEN 'APPROVED'
           WHEN MOD(member.seed_value, 19) = 0 THEN 'REJECTED'
           ELSE 'OPEN'
       END,
       2400, 0, 0,
       CASE WHEN MOD(member.seed_value, 13) = 0
                 OR MOD(member.seed_value, 17) = 0
                 OR MOD(member.seed_value, 19) = 0
            THEN CURRENT_TIMESTAMP - INTERVAL '6 hours' ELSE NULL END,
       CASE WHEN MOD(member.seed_value, 17) = 0
                 OR MOD(member.seed_value, 19) = 0
            THEN CURRENT_TIMESTAMP - INTERVAL '2 hours' ELSE NULL END,
       CASE WHEN MOD(member.seed_value, 17) = 0
                 OR MOD(member.seed_value, 19) = 0 THEN 1 ELSE NULL END,
       CASE WHEN MOD(member.seed_value, 17) = 0 THEN 'Local reference approval'
            WHEN MOD(member.seed_value, 19) = 0 THEN 'Local reference correction requested'
            ELSE NULL END,
       'REFERENCE', 1, 1
  FROM seed_skax_hr_members member
ON CONFLICT (tenant_id, worker_id, period_start_date) DO UPDATE SET
    status = EXCLUDED.status,
    scheduled_minutes = EXCLUDED.scheduled_minutes,
    submitted_at = EXCLUDED.submitted_at,
    decided_at = EXCLUDED.decided_at,
    decided_by = EXCLUDED.decided_by,
    decision_note = EXCLUDED.decision_note,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE tme_time_cards.data_origin = 'REFERENCE';

INSERT INTO tme_time_entries (
    public_id, tenant_id, time_card_id, worker_id, work_date, entry_type,
    minutes, work_mode, note, source_reference, created_by, updated_by)
SELECT MD5('local-seed:skax-hr:time-entry:' || card.tenant_id || ':'
           || card.worker_id || ':' || work_day.work_date)::UUID,
       card.tenant_id, card.time_card_id, card.worker_id,
       work_day.work_date, 'WORK',
       420 + 30 * MOD(member.seed_value + work_day.day_offset, 3)::INTEGER,
       (ARRAY['OFFICE', 'REMOTE', 'HYBRID', 'FIELD'])[
           1 + MOD(member.seed_value + work_day.day_offset, 4)::INTEGER],
       (ARRAY[
           '고객 과제 및 협업 업무',
           '집중 업무와 산출물 정리',
           '팀 협업 및 운영 점검',
           '프로젝트 실행 업무'
       ])[1 + MOD(member.seed_value + work_day.day_offset, 4)::INTEGER],
       'local-seed:skax-hr:v1:time', 1, 1
  FROM seed_skax_hr_members member
  JOIN tme_time_cards card
    ON card.tenant_id = member.tenant_id
   AND card.worker_id = member.worker_id
   AND card.period_start_date = DATE_TRUNC('week', CURRENT_DATE)::DATE
 CROSS JOIN LATERAL (
     SELECT generated.work_date::DATE,
            (generated.work_date::DATE - DATE_TRUNC('week', CURRENT_DATE)::DATE)::INTEGER AS day_offset
       FROM GENERATE_SERIES(
           DATE_TRUNC('week', CURRENT_DATE)::TIMESTAMP,
           LEAST(
               CURRENT_DATE,
               (DATE_TRUNC('week', CURRENT_DATE) + INTERVAL '4 days')::DATE
           )::TIMESTAMP,
           INTERVAL '1 day') generated(work_date)
 ) work_day
 WHERE member.worker_status = 'ACTIVE'
ON CONFLICT (tenant_id, time_card_id, work_date, entry_type)
    WHERE lifecycle_state = 'ACTIVE'
DO UPDATE SET
    minutes = EXCLUDED.minutes,
    work_mode = EXCLUDED.work_mode,
    note = EXCLUDED.note,
    source_reference = EXCLUDED.source_reference,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE tme_time_entries.source_reference IN (
    'reference:time-foundation', 'local-seed:skax-hr:v1:time');

INSERT INTO tme_time_exceptions (
    public_id, tenant_id, time_card_id, worker_id, exception_code,
    severity, occurred_on, message, lifecycle_state, resolution_note,
    resolved_at, resolved_by, created_by, updated_by)
SELECT MD5('local-seed:skax-hr:time-exception:' || card.tenant_id || ':'
           || card.worker_id || ':' || card.period_start_date)::UUID,
       card.tenant_id, card.time_card_id, card.worker_id,
       CASE WHEN MOD(member.seed_value, 3) = 0
            THEN 'MISSING_CHECK_OUT' ELSE 'RECORDED_BELOW_SCHEDULE' END,
       CASE WHEN MOD(member.seed_value, 5) = 0 THEN 'BLOCKING' ELSE 'WARNING' END,
       LEAST(CURRENT_DATE, card.period_start_date + 3),
       CASE WHEN MOD(member.seed_value, 3) = 0
            THEN '퇴근 기록을 확인해 주세요.'
            ELSE '기록 시간이 예정 시간보다 짧습니다.' END,
       CASE WHEN MOD(member.seed_value, 4) = 0 THEN 'RESOLVED' ELSE 'OPEN' END,
       CASE WHEN MOD(member.seed_value, 4) = 0
            THEN '구성원이 기록을 확인했습니다.' ELSE NULL END,
       CASE WHEN MOD(member.seed_value, 4) = 0
            THEN CURRENT_TIMESTAMP - INTERVAL '1 hour' ELSE NULL END,
       CASE WHEN MOD(member.seed_value, 4) = 0 THEN 1 ELSE NULL END,
       1, 1
  FROM seed_skax_hr_members member
  JOIN tme_time_cards card
    ON card.tenant_id = member.tenant_id
   AND card.worker_id = member.worker_id
   AND card.period_start_date = DATE_TRUNC('week', CURRENT_DATE)::DATE
 WHERE member.worker_status = 'ACTIVE'
   AND MOD(member.seed_value, 11) = 0
   AND MOD(member.seed_value, 13) <> 0
ON CONFLICT (public_id) DO UPDATE SET
    severity = EXCLUDED.severity,
    message = EXCLUDED.message,
    lifecycle_state = EXCLUDED.lifecycle_state,
    resolution_note = EXCLUDED.resolution_note,
    resolved_at = EXCLUDED.resolved_at,
    resolved_by = EXCLUDED.resolved_by,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

UPDATE tme_time_cards card
   SET recorded_minutes = COALESCE((
           SELECT SUM(entry.minutes)::INTEGER
             FROM tme_time_entries entry
            WHERE entry.tenant_id = card.tenant_id
              AND entry.time_card_id = card.time_card_id
              AND entry.lifecycle_state = 'ACTIVE'), 0),
       exception_count = (
           SELECT COUNT(*)::INTEGER
             FROM tme_time_exceptions exception
            WHERE exception.tenant_id = card.tenant_id
              AND exception.time_card_id = card.time_card_id
              AND exception.lifecycle_state = 'OPEN'),
       updated_at = CURRENT_TIMESTAMP
  FROM seed_skax_hr_members member
 WHERE card.tenant_id = member.tenant_id
   AND card.worker_id = member.worker_id
   AND card.period_start_date = DATE_TRUNC('week', CURRENT_DATE)::DATE
   AND card.data_origin = 'REFERENCE';

-- Absence: policy-shaped plans, eligibility, balances, historical evidence,
-- and future approval workload. Only employees receive employee leave plans.
WITH plan_seed(plan_key, name, accrual_method, approval_policy, plan_rules) AS (
    VALUES
        ('ANNUAL_LEAVE', '연차 휴가', 'ANNUAL', 'MANAGER',
         '{"dataOrigin":"REFERENCE","minimumMinutes":60,"partialDay":true,"localSeedVersion":"v1"}'::JSONB),
        ('SICK_LEAVE', '건강 회복 휴가', 'EVENT', 'MANAGER',
         '{"dataOrigin":"REFERENCE","minimumMinutes":240,"certificateThresholdDays":3,"localSeedVersion":"v1"}'::JSONB),
        ('FAMILY_CARE', '가족 돌봄 휴가', 'ANNUAL', 'HR',
         '{"dataOrigin":"REFERENCE","minimumMinutes":240,"localSeedVersion":"v1"}'::JSONB)
)
INSERT INTO abs_leave_plans (
    tenant_id, plan_key, name, accrual_method, approval_policy,
    negative_balance_allowed, plan_rules, lifecycle_state, created_by, updated_by)
SELECT tenant.tenant_id, seed.plan_key, seed.name, seed.accrual_method,
       seed.approval_policy, FALSE, seed.plan_rules, 'ACTIVE', 1, 1
  FROM sys_service_tenants tenant
 CROSS JOIN plan_seed seed
 WHERE tenant.tenant_key = 'default'
ON CONFLICT (tenant_id, plan_key) DO UPDATE SET
    name = EXCLUDED.name,
    accrual_method = EXCLUDED.accrual_method,
    approval_policy = EXCLUDED.approval_policy,
    plan_rules = EXCLUDED.plan_rules,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO abs_worker_plan_enrollments (
    tenant_id, worker_id, leave_plan_id, effective_start_date,
    eligibility_reason, lifecycle_state, created_by, updated_by)
SELECT member.tenant_id, member.worker_id, plan.leave_plan_id,
       COALESCE(member.original_hire_date, CURRENT_DATE),
       'local-seed:skax-employee-eligibility', 'ACTIVE', 1, 1
  FROM seed_skax_hr_members member
  JOIN abs_leave_plans plan
    ON plan.tenant_id = member.tenant_id
   AND plan.plan_key IN ('ANNUAL_LEAVE', 'SICK_LEAVE', 'FAMILY_CARE')
 WHERE member.worker_type = 'EMPLOYEE'
ON CONFLICT (tenant_id, worker_id, leave_plan_id, effective_start_date) DO UPDATE SET
    eligibility_reason = EXCLUDED.eligibility_reason,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO abs_leave_balances (
    tenant_id, worker_id, leave_plan_id, balance_year,
    granted_minutes, used_minutes, pending_minutes, adjustment_minutes,
    as_of_date, data_origin, created_by, updated_by)
SELECT member.tenant_id, member.worker_id, plan.leave_plan_id,
       EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER,
       CASE plan.plan_key
           WHEN 'ANNUAL_LEAVE' THEN 7200 + 480 * MOD(member.seed_value, 6)::INTEGER
           WHEN 'SICK_LEAVE' THEN 2400
           ELSE 1440
       END,
       0, 0, 0, CURRENT_DATE, 'REFERENCE', 1, 1
  FROM seed_skax_hr_members member
  JOIN abs_leave_plans plan
    ON plan.tenant_id = member.tenant_id
   AND plan.plan_key IN ('ANNUAL_LEAVE', 'SICK_LEAVE', 'FAMILY_CARE')
 WHERE member.worker_type = 'EMPLOYEE'
ON CONFLICT (tenant_id, worker_id, leave_plan_id, balance_year) DO UPDATE SET
    granted_minutes = EXCLUDED.granted_minutes,
    as_of_date = CURRENT_DATE,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE abs_leave_balances.data_origin = 'REFERENCE';

WITH request_seed AS (
    SELECT member.*,
           plan.leave_plan_id,
           (CURRENT_DATE - (21 + MOD(member.seed_value, 70)::INTEGER))::DATE AS leave_date,
           CASE WHEN MOD(member.seed_value, 4) = 0 THEN 240 ELSE 480 END AS leave_minutes,
           CASE MOD(member.seed_value, 5)
               WHEN 0 THEN 'REJECTED'
               WHEN 1 THEN 'CANCELLED'
               ELSE 'APPROVED'
           END AS leave_status
      FROM seed_skax_hr_members member
      JOIN abs_leave_plans plan
        ON plan.tenant_id = member.tenant_id
       AND plan.plan_key = 'ANNUAL_LEAVE'
     WHERE member.worker_type = 'EMPLOYEE'
)
INSERT INTO abs_leave_requests (
    public_id, tenant_id, worker_id, leave_plan_id, start_at, end_at,
    requested_minutes, reason, status, submitted_at, decided_at, decided_by,
    decision_note, cancelled_at, cancelled_by, cancellation_note,
    created_by, updated_by)
SELECT MD5('local-seed:skax-hr:leave-history:' || seed.tenant_id || ':' || seed.worker_id)::UUID,
       seed.tenant_id, seed.worker_id, seed.leave_plan_id,
       (seed.leave_date + TIME '09:00') AT TIME ZONE 'Asia/Seoul',
       ((seed.leave_date + TIME '09:00') AT TIME ZONE 'Asia/Seoul')
           + MAKE_INTERVAL(mins => seed.leave_minutes),
       seed.leave_minutes, '[Local reference] 개인 일정', seed.leave_status,
       ((seed.leave_date - 7 + TIME '10:00') AT TIME ZONE 'Asia/Seoul'),
       CASE WHEN seed.leave_status IN ('APPROVED', 'REJECTED')
            THEN ((seed.leave_date - 5 + TIME '11:00') AT TIME ZONE 'Asia/Seoul') ELSE NULL END,
       CASE WHEN seed.leave_status IN ('APPROVED', 'REJECTED') THEN 1 ELSE NULL END,
       CASE WHEN seed.leave_status = 'APPROVED' THEN '팀 일정과 인수인계를 확인했습니다.'
            WHEN seed.leave_status = 'REJECTED' THEN '업무 일정 조정 후 다시 요청해 주세요.'
            ELSE NULL END,
       CASE WHEN seed.leave_status = 'CANCELLED'
            THEN ((seed.leave_date - 6 + TIME '09:00') AT TIME ZONE 'Asia/Seoul') ELSE NULL END,
       CASE WHEN seed.leave_status = 'CANCELLED' THEN seed.worker_id ELSE NULL END,
       CASE WHEN seed.leave_status = 'CANCELLED' THEN '개인 일정이 변경되었습니다.' ELSE NULL END,
       seed.worker_id, seed.worker_id
  FROM request_seed seed
ON CONFLICT (public_id) DO NOTHING;

WITH request_seed AS (
    SELECT member.*,
           plan.leave_plan_id,
           (CURRENT_DATE + (5 + MOD(member.seed_value, 35)::INTEGER))::DATE AS leave_date,
           CASE WHEN MOD(member.seed_value, 3) = 0 THEN 240 ELSE 480 END AS leave_minutes,
           CASE MOD(member.seed_value, 7)
               WHEN 0 THEN 'DRAFT'
               WHEN 1 THEN 'REJECTED'
               WHEN 2 THEN 'CANCELLED'
               WHEN 3 THEN 'APPROVED'
               ELSE 'SUBMITTED'
           END AS leave_status
      FROM seed_skax_hr_members member
      JOIN abs_leave_plans plan
        ON plan.tenant_id = member.tenant_id
       AND plan.plan_key = 'ANNUAL_LEAVE'
     WHERE member.worker_type = 'EMPLOYEE'
)
INSERT INTO abs_leave_requests (
    public_id, tenant_id, worker_id, leave_plan_id, start_at, end_at,
    requested_minutes, reason, status, submitted_at, decided_at, decided_by,
    decision_note, cancelled_at, cancelled_by, cancellation_note,
    created_by, updated_by)
SELECT MD5('local-seed:skax-hr:leave-upcoming:' || seed.tenant_id || ':' || seed.worker_id)::UUID,
       seed.tenant_id, seed.worker_id, seed.leave_plan_id,
       (seed.leave_date + TIME '09:00') AT TIME ZONE 'Asia/Seoul',
       ((seed.leave_date + TIME '09:00') AT TIME ZONE 'Asia/Seoul')
           + MAKE_INTERVAL(mins => seed.leave_minutes),
       seed.leave_minutes,
       (ARRAY[
           '[Local reference] 재충전 휴가',
           '[Local reference] 가족 일정',
           '[Local reference] 개인 용무',
           '[Local reference] 건강 관리'
       ])[1 + MOD(seed.seed_value, 4)::INTEGER],
       seed.leave_status,
       CASE WHEN seed.leave_status = 'DRAFT' THEN NULL
            ELSE CURRENT_TIMESTAMP - INTERVAL '1 day' END,
       CASE WHEN seed.leave_status IN ('APPROVED', 'REJECTED')
            THEN CURRENT_TIMESTAMP - INTERVAL '6 hours' ELSE NULL END,
       CASE WHEN seed.leave_status IN ('APPROVED', 'REJECTED') THEN 1 ELSE NULL END,
       CASE WHEN seed.leave_status = 'APPROVED' THEN '팀 운영 일정을 확인했습니다.'
            WHEN seed.leave_status = 'REJECTED' THEN '대체 일정을 협의해 주세요.'
            ELSE NULL END,
       CASE WHEN seed.leave_status = 'CANCELLED'
            THEN CURRENT_TIMESTAMP - INTERVAL '4 hours' ELSE NULL END,
       CASE WHEN seed.leave_status = 'CANCELLED' THEN seed.worker_id ELSE NULL END,
       CASE WHEN seed.leave_status = 'CANCELLED' THEN '요청자가 일정을 변경했습니다.' ELSE NULL END,
       seed.worker_id, seed.worker_id
  FROM request_seed seed
ON CONFLICT (public_id) DO NOTHING;

UPDATE abs_leave_balances balance
   SET used_minutes = COALESCE((
           SELECT SUM(request.requested_minutes)::INTEGER
             FROM abs_leave_requests request
            WHERE request.tenant_id = balance.tenant_id
              AND request.worker_id = balance.worker_id
              AND request.leave_plan_id = balance.leave_plan_id
              AND request.status = 'APPROVED'), 0),
       pending_minutes = COALESCE((
           SELECT SUM(request.requested_minutes)::INTEGER
             FROM abs_leave_requests request
            WHERE request.tenant_id = balance.tenant_id
              AND request.worker_id = balance.worker_id
              AND request.leave_plan_id = balance.leave_plan_id
              AND request.status = 'SUBMITTED'), 0),
       as_of_date = CURRENT_DATE,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM abs_leave_plans plan
 WHERE balance.tenant_id = plan.tenant_id
   AND balance.leave_plan_id = plan.leave_plan_id
   AND plan.plan_key = 'ANNUAL_LEAVE'
   AND balance.balance_year = EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER
   AND balance.data_origin = 'REFERENCE';

-- Benefits: multiple plan categories and one open election window. Values are
-- enrollment references, not carrier confirmations.
INSERT INTO bnf_benefit_programs (
    tenant_id, program_key, name, description, program_year,
    lifecycle_state, created_by, updated_by)
SELECT tenant.tenant_id, 'CORE_BENEFITS', 'SKAX 구성원 복리후생',
       'Local reference program for integrated HR experience verification.',
       EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER, 'ACTIVE', 1, 1
  FROM sys_service_tenants tenant
 WHERE tenant.tenant_key = 'default'
ON CONFLICT (tenant_id, program_key, program_year) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

WITH plan_seed(plan_key, plan_type, name, provider_name, plan_summary) AS (
    VALUES
        ('HEALTH_CORE', 'HEALTH', '건강 보장 프로그램', 'SKAX Benefits',
         '{"dataOrigin":"REFERENCE","coverage":"Employee health support"}'::JSONB),
        ('WELLNESS_CORE', 'WELLNESS', '웰니스 지원', 'SKAX Wellbeing',
         '{"dataOrigin":"REFERENCE","coverage":"Preventive care and wellbeing"}'::JSONB),
        ('RETIREMENT_CORE', 'RETIREMENT', '퇴직연금 안내', 'SKAX Retirement',
         '{"dataOrigin":"REFERENCE","coverage":"Retirement plan reference"}'::JSONB),
        ('FLEX_ALLOWANCE', 'ALLOWANCE', '선택형 복지 포인트', 'SKAX Benefits',
         '{"dataOrigin":"REFERENCE","coverage":"Flexible wellbeing allowance"}'::JSONB)
)
INSERT INTO bnf_benefit_plans (
    tenant_id, benefit_program_id, plan_key, plan_type, name,
    provider_name, plan_summary, lifecycle_state, created_by, updated_by)
SELECT program.tenant_id, program.benefit_program_id, seed.plan_key,
       seed.plan_type, seed.name, seed.provider_name, seed.plan_summary,
       'ACTIVE', 1, 1
  FROM bnf_benefit_programs program
  JOIN sys_service_tenants tenant
    ON tenant.tenant_id = program.tenant_id
   AND tenant.tenant_key = 'default'
 CROSS JOIN plan_seed seed
 WHERE program.program_key = 'CORE_BENEFITS'
   AND program.program_year = EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER
ON CONFLICT (tenant_id, plan_key) DO UPDATE SET
    benefit_program_id = EXCLUDED.benefit_program_id,
    plan_type = EXCLUDED.plan_type,
    name = EXCLUDED.name,
    provider_name = EXCLUDED.provider_name,
    plan_summary = EXCLUDED.plan_summary,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO bnf_enrollment_windows (
    public_id, tenant_id, benefit_program_id, window_type, name,
    opens_at, closes_at, lifecycle_state, created_by, updated_by)
SELECT MD5('local-seed:skax-hr:benefit-window:' || program.tenant_id || ':'
           || program.program_year)::UUID,
       program.tenant_id, program.benefit_program_id, 'OPEN_ENROLLMENT',
       program.program_year || ' 복리후생 선택 기간',
       DATE_TRUNC('month', CURRENT_TIMESTAMP) - INTERVAL '7 days',
       DATE_TRUNC('month', CURRENT_TIMESTAMP) + INTERVAL '45 days',
       'OPEN', 1, 1
  FROM bnf_benefit_programs program
  JOIN sys_service_tenants tenant
    ON tenant.tenant_id = program.tenant_id
   AND tenant.tenant_key = 'default'
 WHERE program.program_key = 'CORE_BENEFITS'
   AND program.program_year = EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER
ON CONFLICT (public_id) DO UPDATE SET
    opens_at = EXCLUDED.opens_at,
    closes_at = EXCLUDED.closes_at,
    lifecycle_state = 'OPEN',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO bnf_enrollments (
    public_id, tenant_id, worker_id, benefit_plan_id, coverage_level,
    effective_start_date, status, source_reference, created_by, updated_by)
SELECT MD5('local-seed:skax-hr:benefit:' || member.tenant_id || ':'
           || member.worker_id || ':' || plan.plan_key || ':'
           || EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER)::UUID,
       member.tenant_id, member.worker_id, plan.benefit_plan_id,
       CASE plan.plan_key
           WHEN 'HEALTH_CORE' THEN (ARRAY[
               'EMPLOYEE', 'EMPLOYEE_SPOUSE', 'EMPLOYEE_CHILDREN', 'FAMILY'
           ])[1 + MOD(member.seed_value, 4)::INTEGER]
           WHEN 'FLEX_ALLOWANCE' THEN CASE WHEN MOD(member.seed_value, 9) = 0
                                           THEN 'WAIVED' ELSE 'EMPLOYEE' END
           ELSE 'EMPLOYEE'
       END,
       DATE_TRUNC('year', CURRENT_DATE)::DATE,
       CASE WHEN plan.plan_key = 'FLEX_ALLOWANCE' AND MOD(member.seed_value, 9) = 0
            THEN 'WAIVED' ELSE 'ACTIVE' END,
       'local-seed:skax-hr:v1:benefits', 1, 1
  FROM seed_skax_hr_members member
  JOIN bnf_benefit_plans plan
    ON plan.tenant_id = member.tenant_id
   AND plan.plan_key IN ('HEALTH_CORE', 'WELLNESS_CORE', 'RETIREMENT_CORE', 'FLEX_ALLOWANCE')
 WHERE member.worker_type = 'EMPLOYEE'
ON CONFLICT (tenant_id, worker_id, benefit_plan_id, effective_start_date) DO UPDATE SET
    coverage_level = EXCLUDED.coverage_level,
    status = EXCLUDED.status,
    source_reference = EXCLUDED.source_reference,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE bnf_enrollments.source_reference IN (
    'reference:benefits-foundation', 'local-seed:skax-hr:v1:benefits');

-- Pay: opaque statement references only. No salary or bank data is generated.
WITH cycle_seed AS (
    SELECT offset_month,
           (DATE_TRUNC('month', CURRENT_DATE)
               - MAKE_INTERVAL(months => offset_month))::DATE AS period_start
      FROM GENERATE_SERIES(0, 3) offset_month
)
INSERT INTO pay_pay_cycles (
    public_id, tenant_id, cycle_key, name, period_start_date, period_end_date,
    pay_date, status, readiness, created_by, updated_by)
SELECT MD5('local-seed:skax-hr:pay-cycle:' || tenant.tenant_id || ':'
           || TO_CHAR(seed.period_start, 'YYYY-MM'))::UUID,
       tenant.tenant_id, TO_CHAR(seed.period_start, 'YYYY-MM'),
       TO_CHAR(seed.period_start, 'YYYY-MM') || ' 월 급여',
       seed.period_start,
       (seed.period_start + INTERVAL '1 month - 1 day')::DATE,
       (seed.period_start + INTERVAL '1 month - 1 day')::DATE,
       CASE WHEN seed.offset_month = 0 THEN 'COLLECTING' ELSE 'PAID' END,
       JSONB_BUILD_OBJECT(
           'dataOrigin', 'LOCAL_SEED',
           'timeValidated', seed.offset_month > 0,
           'absenceValidated', seed.offset_month > 0,
           'sourceConfirmed', seed.offset_month > 0),
       1, 1
  FROM sys_service_tenants tenant
 CROSS JOIN cycle_seed seed
 WHERE tenant.tenant_key = 'default'
ON CONFLICT (tenant_id, cycle_key) DO UPDATE SET
    name = EXCLUDED.name,
    period_start_date = EXCLUDED.period_start_date,
    period_end_date = EXCLUDED.period_end_date,
    pay_date = EXCLUDED.pay_date,
    status = EXCLUDED.status,
    readiness = EXCLUDED.readiness,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE pay_pay_cycles.readiness->>'dataOrigin' IN ('REFERENCE', 'LOCAL_SEED');

INSERT INTO pay_statement_references (
    public_id, tenant_id, worker_id, pay_cycle_id, statement_period_label,
    document_reference, availability_state, published_at, created_by, updated_by)
SELECT MD5('local-seed:skax-hr:pay-statement:' || member.tenant_id || ':'
           || member.worker_id || ':' || cycle.cycle_key)::UUID,
       member.tenant_id, member.worker_id, cycle.pay_cycle_id,
       cycle.cycle_key,
       'reference://local-seed/pay-statement/' || member.tenant_id || '/'
           || member.worker_id || '/' || cycle.cycle_key,
       CASE WHEN cycle.status = 'PAID' THEN 'AVAILABLE' ELSE 'PENDING' END,
       CASE WHEN cycle.status = 'PAID'
            THEN (cycle.pay_date + TIME '09:00') AT TIME ZONE 'Asia/Seoul'
            ELSE NULL END,
       1, 1
  FROM seed_skax_hr_members member
  JOIN pay_pay_cycles cycle
    ON cycle.tenant_id = member.tenant_id
   AND cycle.period_start_date >= (DATE_TRUNC('month', CURRENT_DATE) - INTERVAL '3 months')::DATE
 WHERE member.worker_type = 'EMPLOYEE'
ON CONFLICT (tenant_id, worker_id, pay_cycle_id) DO UPDATE SET
    statement_period_label = EXCLUDED.statement_period_label,
    document_reference = EXCLUDED.document_reference,
    availability_state = EXCLUDED.availability_state,
    published_at = EXCLUDED.published_at,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE pay_statement_references.document_reference LIKE 'reference://%';

-- Talent: every current worker receives a varied journey, two goals, and two
-- learning assignments. Contingent workers remain included in growth content.
UPDATE tal_journey_templates template
   SET name = '성장 기반 여정',
       description = '강점 점검부터 목표·학습 실행까지 이어지는 기본 성장 여정입니다.',
       task_blueprint = '[{"key":"profile","title":"커리어 프로필 점검"},{"key":"goal","title":"성장 목표 확인"},{"key":"learning","title":"학습 활동 선택"}]'::JSONB,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE template.template_key = 'GROWTH_FOUNDATION'
   AND template.tenant_id IN (SELECT DISTINCT tenant_id FROM seed_skax_hr_members);

UPDATE tal_journey_instances instance
   SET progress_percent = 10 + 10 * MOD(member.seed_value, 8)::INTEGER,
       target_date = CURRENT_DATE + (45 + MOD(member.seed_value, 75)::INTEGER),
       status = CASE WHEN MOD(member.seed_value, 14) = 0 THEN 'PAUSED' ELSE 'ACTIVE' END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM seed_skax_hr_members member,
       tal_journey_templates template
 WHERE instance.tenant_id = member.tenant_id
   AND instance.worker_id = member.worker_id
   AND template.tenant_id = instance.tenant_id
   AND template.journey_template_id = instance.journey_template_id
   AND template.template_key = 'GROWTH_FOUNDATION';

INSERT INTO tal_journey_templates (
    tenant_id, template_key, journey_type, name, description,
    task_blueprint, lifecycle_state, created_by, updated_by)
SELECT tenant.tenant_id, 'CAREER_ACCELERATOR', 'GROWTH',
       '커리어 성장 여정', '역할 기반 성장 목표와 학습 활동을 연결합니다.',
       '[{"key":"strength","title":"강점 프로필 점검"},{"key":"goal","title":"성장 목표 정렬"},{"key":"learning","title":"학습 활동 실행"},{"key":"checkin","title":"리더 체크인"}]'::JSONB,
       'ACTIVE', 1, 1
  FROM sys_service_tenants tenant
 WHERE tenant.tenant_key = 'default'
ON CONFLICT (tenant_id, template_key) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    task_blueprint = EXCLUDED.task_blueprint,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO tal_journey_instances (
    public_id, tenant_id, journey_template_id, worker_id, starts_on,
    target_date, progress_percent, status, created_by, updated_by)
SELECT MD5('local-seed:skax-hr:journey:' || member.tenant_id || ':'
           || member.worker_id || ':career-accelerator')::UUID,
       member.tenant_id, template.journey_template_id, member.worker_id,
       CURRENT_DATE - (10 + MOD(member.seed_value, 50)::INTEGER),
       CURRENT_DATE + (30 + MOD(member.seed_value, 90)::INTEGER),
       10 + 10 * MOD(member.seed_value, 9)::INTEGER,
       CASE WHEN MOD(member.seed_value, 12) = 0 THEN 'PAUSED' ELSE 'ACTIVE' END,
       1, 1
  FROM seed_skax_hr_members member
  JOIN tal_journey_templates template
    ON template.tenant_id = member.tenant_id
   AND template.template_key = 'CAREER_ACCELERATOR'
ON CONFLICT (public_id) DO UPDATE SET
    target_date = EXCLUDED.target_date,
    progress_percent = EXCLUDED.progress_percent,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

WITH goal_seed(goal_key_prefix, goal_type, title_index_offset, due_days, visibility) AS (
    VALUES
        ('LOCAL_GROWTH_', 'DEVELOPMENT', 0, 90, 'MANAGER'),
        ('LOCAL_TEAM_IMPACT_', 'TEAM', 2, 120, 'TEAM')
)
INSERT INTO tal_goals (
    public_id, tenant_id, worker_id, goal_key, title, description,
    goal_type, progress_percent, due_date, status, visibility,
    created_by, updated_by)
SELECT MD5('local-seed:skax-hr:goal:' || member.tenant_id || ':'
           || member.worker_id || ':' || seed.goal_key_prefix)::UUID,
       member.tenant_id, member.worker_id,
       seed.goal_key_prefix || EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER,
       (ARRAY[
           '핵심 역량 한 단계 확장',
           '직무 전문성 학습 결과 적용',
           '팀 업무 품질과 예측 가능성 향상',
           '고객 가치 중심의 개선 과제 실행',
           '협업 방식과 지식 공유 개선'
       ])[1 + MOD(member.seed_value + seed.title_index_offset, 5)::INTEGER],
       'Local reference goal for end-to-end HR experience verification.',
       seed.goal_type,
       15 + 10 * MOD(member.seed_value + seed.title_index_offset, 8)::INTEGER,
       CURRENT_DATE + seed.due_days,
       CASE WHEN MOD(member.seed_value + seed.title_index_offset, 10) = 0
            THEN 'AT_RISK' ELSE 'ACTIVE' END,
       seed.visibility, 1, 1
  FROM seed_skax_hr_members member
 CROSS JOIN goal_seed seed
ON CONFLICT (tenant_id, worker_id, goal_key) DO NOTHING;

UPDATE tal_goals goal
   SET title = (ARRAY[
           '올해의 성장 방향과 실행 계획 정렬',
           '직무 전문성을 업무 성과로 연결',
           '고객 가치 중심의 개선 결과 만들기',
           '팀 협업과 지식 공유 방식 고도화'
       ])[1 + MOD(member.seed_value, 4)::INTEGER],
       description = 'Local reference goal for end-to-end HR experience verification.',
       progress_percent = 20 + 10 * MOD(member.seed_value, 7)::INTEGER,
       status = CASE WHEN MOD(member.seed_value, 15) = 0 THEN 'AT_RISK' ELSE 'ACTIVE' END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM seed_skax_hr_members member
 WHERE goal.tenant_id = member.tenant_id
   AND goal.worker_id = member.worker_id
   AND goal.goal_key = 'REFERENCE_GROWTH_'
       || EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER;

UPDATE tal_learning_assignments learning
   SET title = '데이터·AI 기본 소양',
       provider_name = 'DWP Learning',
       progress_percent = 10 + 10 * MOD(member.seed_value + 3, 8)::INTEGER,
       status = CASE WHEN MOD(member.seed_value, 5) = 0
                     THEN 'ASSIGNED' ELSE 'IN_PROGRESS' END,
       source_reference = 'local-seed:skax-hr:v1:learning-foundation',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM seed_skax_hr_members member
 WHERE learning.tenant_id = member.tenant_id
   AND learning.worker_id = member.worker_id
   AND learning.learning_key = 'REFERENCE_DATA_LITERACY_'
       || EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER
   AND learning.source_reference IN (
       'reference:learning-foundation',
       'local-seed:skax-hr:v1:learning-foundation');

DELETE FROM tal_learning_assignments
 WHERE source_reference = 'local-seed:skax-hr:v1:learning'
   AND learning_key LIKE 'LOCAL_RESPONSIBLE_AI_%';

WITH learning_seed(learning_key_prefix, title, provider_name, required, due_days) AS (
    VALUES
        ('LOCAL_SECURITY_', '개인정보 보호와 정보보안', 'DWP Learning', TRUE, 45),
        ('LOCAL_COLLABORATION_', '효과적인 협업과 피드백', 'SKAX Learning Hub', FALSE, 75)
)
INSERT INTO tal_learning_assignments (
    public_id, tenant_id, worker_id, learning_key, title, provider_name,
    required, progress_percent, due_date, status, source_reference,
    created_by, updated_by)
SELECT MD5('local-seed:skax-hr:learning:' || member.tenant_id || ':'
           || member.worker_id || ':' || seed.learning_key_prefix)::UUID,
       member.tenant_id, member.worker_id,
       seed.learning_key_prefix || EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER,
       seed.title, seed.provider_name, seed.required,
       10 + 10 * MOD(member.seed_value + seed.due_days, 8)::INTEGER,
       CURRENT_DATE + seed.due_days,
       CASE WHEN MOD(member.seed_value + seed.due_days, 4) = 0
            THEN 'ASSIGNED' ELSE 'IN_PROGRESS' END,
       'local-seed:skax-hr:v1:learning', 1, 1
  FROM seed_skax_hr_members member
 CROSS JOIN learning_seed seed
ON CONFLICT (tenant_id, worker_id, learning_key) DO UPDATE SET
    title = EXCLUDED.title,
    provider_name = EXCLUDED.provider_name,
    required = EXCLUDED.required,
    progress_percent = EXCLUDED.progress_percent,
    due_date = EXCLUDED.due_date,
    status = EXCLUDED.status,
    source_reference = EXCLUDED.source_reference,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE tal_learning_assignments.source_reference = 'local-seed:skax-hr:v1:learning';

-- Fail the local startup if any current SKAX worker was skipped in the domains
-- that apply to that worker classification.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM seed_skax_hr_members member
         WHERE NOT EXISTS (
                   SELECT 1 FROM tme_time_cards card
                    WHERE card.tenant_id = member.tenant_id
                      AND card.worker_id = member.worker_id
                      AND card.period_start_date = DATE_TRUNC('week', CURRENT_DATE)::DATE)
            OR NOT EXISTS (
                   SELECT 1 FROM tal_goals goal
                    WHERE goal.tenant_id = member.tenant_id
                      AND goal.worker_id = member.worker_id
                      AND goal.goal_key LIKE 'LOCAL_GROWTH_%')
            OR NOT EXISTS (
                   SELECT 1 FROM tal_learning_assignments learning
                    WHERE learning.tenant_id = member.tenant_id
                      AND learning.worker_id = member.worker_id
                      AND learning.source_reference = 'local-seed:skax-hr:v1:learning')
    ) THEN
        RAISE EXCEPTION 'SKAX local HR seed did not cover every current worker';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM seed_skax_hr_members member
         WHERE member.worker_type = 'EMPLOYEE'
           AND (NOT EXISTS (
                    SELECT 1 FROM abs_leave_balances balance
                    JOIN abs_leave_plans plan
                      ON plan.tenant_id = balance.tenant_id
                     AND plan.leave_plan_id = balance.leave_plan_id
                   WHERE balance.tenant_id = member.tenant_id
                     AND balance.worker_id = member.worker_id
                     AND plan.plan_key = 'ANNUAL_LEAVE'
                     AND balance.balance_year = EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER)
                OR NOT EXISTS (
                    SELECT 1 FROM pay_statement_references statement
                   WHERE statement.tenant_id = member.tenant_id
                     AND statement.worker_id = member.worker_id
                     AND statement.document_reference LIKE 'reference://local-seed/%'))
    ) THEN
        RAISE EXCEPTION 'SKAX local HR seed did not cover every eligible employee';
    END IF;
END
$$;
