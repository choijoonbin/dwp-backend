-- Drill-down 계약용 app_code_groups / app_codes
-- Enum SoT: CASE_STATUS, ACTION_STATUS, SEVERITY, DRIVER_TYPE, SLA_RISK, ANOMALY_STATUS

SET search_path TO dwp_aura, public;

INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('CASE_STATUS', 'Case Status', '케이스 상태', true, now(), now()),
    ('ACTION_STATUS', 'Action Status', '조치 상태', true, now(), now()),
    ('SEVERITY', 'Severity', '심각도', true, now(), now()),
    ('DRIVER_TYPE', 'Driver Type', 'Top Risk Drivers 유형', true, now(), now()),
    ('SLA_RISK', 'SLA Risk', 'SLA 위험도', true, now(), now()),
    ('ANOMALY_STATUS', 'Anomaly Status', '이상 상태', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET group_name = EXCLUDED.group_name, description = EXCLUDED.description, updated_at = now();

-- SEVERITY
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('SEVERITY', 'CRITICAL', 'Critical', '치명적', 10, true, now(), now()),
    ('SEVERITY', 'HIGH', 'High', '높음', 20, true, now(), now()),
    ('SEVERITY', 'MEDIUM', 'Medium', '중간', 30, true, now(), now()),
    ('SEVERITY', 'LOW', 'Low', '낮음', 40, true, now(), now()),
    ('SEVERITY', 'INFO', 'Info', '정보', 50, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, updated_at = now();

-- CASE_STATUS
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('CASE_STATUS', 'OPEN', 'Open', '미해결', 10, true, now(), now()),
    ('CASE_STATUS', 'IN_REVIEW', 'In Review', '검토중', 20, true, now(), now()),
    ('CASE_STATUS', 'PENDING_APPROVAL', 'Pending Approval', '승인대기', 30, true, now(), now()),
    ('CASE_STATUS', 'APPROVED', 'Approved', '승인됨', 40, true, now(), now()),
    ('CASE_STATUS', 'REJECTED', 'Rejected', '거절됨', 50, true, now(), now()),
    ('CASE_STATUS', 'RESOLVED', 'Resolved', '해결됨', 60, true, now(), now()),
    ('CASE_STATUS', 'CLOSED', 'Closed', '종료', 70, true, now(), now()),
    ('CASE_STATUS', 'ARCHIVED', 'Archived', '보관', 80, true, now(), now()),
    ('CASE_STATUS', 'IN_PROGRESS', 'In Progress', '진행중', 25, true, now(), now()),
    ('CASE_STATUS', 'TRIAGED', 'Triaged', '분류됨', 15, true, now(), now()),
    ('CASE_STATUS', 'DISMISSED', 'Dismissed', '무시됨', 75, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, updated_at = now();

-- ACTION_STATUS
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('ACTION_STATUS', 'PENDING', 'Pending', '대기', 10, true, now(), now()),
    ('ACTION_STATUS', 'QUEUED', 'Queued', '대기열', 15, true, now(), now()),
    ('ACTION_STATUS', 'RUNNING', 'Running', '실행중', 20, true, now(), now()),
    ('ACTION_STATUS', 'SUCCESS', 'Success', '성공', 30, true, now(), now()),
    ('ACTION_STATUS', 'FAILED', 'Failed', '실패', 40, true, now(), now()),
    ('ACTION_STATUS', 'CANCELLED', 'Cancelled', '취소', 50, true, now(), now()),
    ('ACTION_STATUS', 'EXPIRED', 'Expired', '만료', 60, true, now(), now()),
    ('ACTION_STATUS', 'PLANNED', 'Planned', '계획됨', 5, true, now(), now()),
    ('ACTION_STATUS', 'PROPOSED', 'Proposed', '제안됨', 7, true, now(), now()),
    ('ACTION_STATUS', 'PENDING_APPROVAL', 'Pending Approval', '승인대기', 12, true, now(), now()),
    ('ACTION_STATUS', 'APPROVED', 'Approved', '승인됨', 25, true, now(), now()),
    ('ACTION_STATUS', 'EXECUTING', 'Executing', '실행중', 22, true, now(), now()),
    ('ACTION_STATUS', 'EXECUTED', 'Executed', '실행완료', 35, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, updated_at = now();

-- DRIVER_TYPE
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('DRIVER_TYPE', 'DUPLICATE_INVOICE', 'Duplicate Invoices', '중복 송장', 10, true, now(), now()),
    ('DRIVER_TYPE', 'BANK_CHANGE_RISK', 'Bank Change Risk', '은행 변경 위험', 20, true, now(), now()),
    ('DRIVER_TYPE', 'POLICY_VIOLATION', 'Policy Violation', '정책 위반', 30, true, now(), now()),
    ('DRIVER_TYPE', 'DATA_INTEGRITY', 'Data Integrity', '데이터 무결성', 40, true, now(), now()),
    ('DRIVER_TYPE', 'THRESHOLD_BREACH', 'Threshold Breach', '임계값 초과', 50, true, now(), now()),
    ('DRIVER_TYPE', 'ANOMALY', 'Anomaly', '이상', 60, true, now(), now()),
    ('DRIVER_TYPE', 'BANK_CHANGE', 'Bank Change', '은행 변경', 25, true, now(), now()),
    ('DRIVER_TYPE', 'DEFAULT', 'Other', '기타', 100, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, updated_at = now();

-- SLA_RISK
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('SLA_RISK', 'ON_TRACK', 'On Track', '정상', 10, true, now(), now()),
    ('SLA_RISK', 'AT_RISK', 'At Risk', '위험', 20, true, now(), now()),
    ('SLA_RISK', 'BREACHED', 'Breached', '위반', 30, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, updated_at = now();

-- ANOMALY_STATUS
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('ANOMALY_STATUS', 'NEW', 'New', '신규', 10, true, now(), now()),
    ('ANOMALY_STATUS', 'TRIAGED', 'Triaged', '분류됨', 20, true, now(), now()),
    ('ANOMALY_STATUS', 'CONFIRMED', 'Confirmed', '확인됨', 30, true, now(), now()),
    ('ANOMALY_STATUS', 'DISMISSED', 'Dismissed', '무시됨', 40, true, now(), now()),
    ('ANOMALY_STATUS', 'FALSE_POSITIVE', 'False Positive', '오탐', 50, true, now(), now()),
    ('ANOMALY_STATUS', 'RESOLVED', 'Resolved', '해결됨', 60, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, updated_at = now();
