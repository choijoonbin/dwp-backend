-- V30: Synapse 화면 라벨/코드 다국어(ko/en) 동기화
-- 목적: FE i18n과 BE API 응답 일관성 확보 (Accept-Language 기반)
-- 참고: docs/job/PROMPT_BE_I18N_SYNAPSE_LABELS_AND_CODES.md

-- ========================================
-- 1. sys_code_groups: ACTION_TYPE 추가
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('ACTION_TYPE', '조치 유형', '조치 실행/이력 화면용 (POST_REVERSAL, BLOCK_PAYMENT 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. sys_codes: ACTION_TYPE (name_ko, name_en)
-- ========================================
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('ACTION_TYPE', 'POST_REVERSAL', '전기 반전', '전기 반전', 'Post Reversal', '전기 반전', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ACTION_TYPE', 'BLOCK_PAYMENT', '결제 차단', '결제 차단', 'Block Payment', '결제 차단', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ACTION_TYPE', 'FLAG_REVIEW', '검토 요청', '검토 요청', 'Flag for Review', '검토 요청', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ACTION_TYPE', 'CLEAR_ITEM', '항목 정리', '항목 정리', 'Clear Item', '항목 정리', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ACTION_TYPE', 'UPDATE_MASTER', '마스터 데이터 업데이트', '마스터 데이터 업데이트', 'Update Master Data', '마스터 데이터 업데이트', 50, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 3. sys_codes: CASE_STATUS 라벨 정정 + 누락 코드 추가 (FE common.json 정렬)
-- ========================================
UPDATE sys_codes SET name_ko='오픈', name_en='Open' WHERE group_key='CASE_STATUS' AND code='OPEN';
UPDATE sys_codes SET name_ko='진행 중', name_en='In Progress' WHERE group_key='CASE_STATUS' AND code='IN_PROGRESS';
UPDATE sys_codes SET name_ko='승인 대기', name_en='Pending Approval' WHERE group_key='CASE_STATUS' AND code='PENDING_APPROVAL';
UPDATE sys_codes SET name_ko='검토', name_en='Review' WHERE group_key='CASE_STATUS' AND code='IN_REVIEW';

INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('CASE_STATUS', 'PENDING', '대기', '대기', 'Pending', '대기', 12, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'EXECUTED', '실행됨', '실행됨', 'Executed', '실행됨', 42, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'FAILED', '실패', '실패', 'Failed', '실패', 52, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'COMPLETED', '완료', '완료', 'Completed', '완료', 62, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'TRIAGE', '트리아지', '트리아지', 'Triage', '트리아지', 14, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'REVIEW', '검토', '검토', 'Review', '검토', 18, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 4. sys_codes: SEVERITY 라벨 정정 (FE common.json 정렬)
-- ========================================
UPDATE sys_codes SET name_ko='긴급', name_en='Critical' WHERE group_key='SEVERITY' AND code='CRITICAL';
UPDATE sys_codes SET name_ko='보통', name_en='Medium' WHERE group_key='SEVERITY' AND code='MEDIUM';

-- ========================================
-- 5. sys_code_usages: Actions, Archive 메뉴에 ACTION_TYPE 매핑
-- ========================================
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.autonomous-operations.actions', 'ACTION_TYPE', 'MENU', true, 10, '조치 유형 필터/배지', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.autonomous-operations.archive', 'ACTION_TYPE', 'MENU', true, 10, '조치 유형 필터/배지', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = true,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 6. sys_menus: §2.2 doc 정렬 (menu_name_ko, menu_name_en)
-- ========================================

-- §2.2.1 통합 관제 센터 / 자율 운영 센터
UPDATE sys_menus SET menu_name_ko='통합 관제 센터', menu_name_en='Integrated Control Center' WHERE tenant_id=1 AND menu_key='menu.command-center';
UPDATE sys_menus SET menu_name_ko='자율 운영 센터', menu_name_en='Autonomous Operations' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations';
UPDATE sys_menus SET menu_name_ko='케이스 작업함', menu_name_en='Cases' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.cases';
UPDATE sys_menus SET menu_name_ko='이상 징후 탐지', menu_name_en='Anomaly Detection' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.anomalies';
UPDATE sys_menus SET menu_name_ko='채권·채무 최적화', menu_name_en='AR/AP Optimization' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.optimization';
UPDATE sys_menus SET menu_name_ko='조치 실행 센터', menu_name_en='Action Execution Center' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.actions';
UPDATE sys_menus SET menu_name_ko='조치 이력 보관함', menu_name_en='Action Archive' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.archive';

-- §2.2.2 원천 데이터·이력 허브
UPDATE sys_menus SET menu_name_ko='원천 데이터·이력 허브', menu_name_en='Master Data & History' WHERE tenant_id=1 AND menu_key='menu.master-data-history';
UPDATE sys_menus SET menu_name_ko='전표 조회', menu_name_en='Documents' WHERE tenant_id=1 AND menu_key='menu.master-data-history.documents';
UPDATE sys_menus SET menu_name_ko='미결제 항목', menu_name_en='Open Items' WHERE tenant_id=1 AND menu_key='menu.master-data-history.open-items';
UPDATE sys_menus SET menu_name_ko='거래처 허브', menu_name_en='Entities' WHERE tenant_id=1 AND menu_key='menu.master-data-history.entities';
UPDATE sys_menus SET menu_name_ko='계보·근거 뷰어', menu_name_en='Lineage' WHERE tenant_id=1 AND menu_key='menu.master-data-history.lineage';

-- §2.2.3 지식·정책 허브
UPDATE sys_menus SET menu_name_ko='지식·정책 허브', menu_name_en='Knowledge & Policy' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy';
UPDATE sys_menus SET menu_name_ko='규정·문서 라이브러리', menu_name_en='RAG' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.rag';
UPDATE sys_menus SET menu_name_ko='정책 프로파일', menu_name_en='Policies' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.policies';
UPDATE sys_menus SET menu_name_ko='조치 가드레일', menu_name_en='Guardrails' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.guardrails';
UPDATE sys_menus SET menu_name_ko='용어·코드 사전', menu_name_en='Dictionary' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.dictionary';
UPDATE sys_menus SET menu_name_ko='피드백·라벨링', menu_name_en='Feedback' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.feedback';

-- §2.2.4 대사·감사 센터
UPDATE sys_menus SET menu_name_ko='대사·감사 센터', menu_name_en='Reconciliation & Audit' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit';
UPDATE sys_menus SET menu_name_ko='정합성 대사 리포트', menu_name_en='Reconciliation' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit.reconciliation';
UPDATE sys_menus SET menu_name_ko='조치 결과 대사', menu_name_en='Action Reconciliation' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit.action-recon';
UPDATE sys_menus SET menu_name_ko='감사 추적 로그', menu_name_en='Audit' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit.audit';
UPDATE sys_menus SET menu_name_ko='효과·성과 분석', menu_name_en='Analytics' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit.analytics';

-- §2.2.5 거버넌스·설정
UPDATE sys_menus SET menu_name_ko='거버넌스·설정', menu_name_en='Governance & Config' WHERE tenant_id=1 AND menu_key='menu.governance-config';
UPDATE sys_menus SET menu_name_ko='자율성·통제 설정', menu_name_en='Governance' WHERE tenant_id=1 AND menu_key='menu.governance-config.governance';
UPDATE sys_menus SET menu_name_ko='에이전트 구성 관리', menu_name_en='Agent Config' WHERE tenant_id=1 AND menu_key='menu.governance-config.agent-config';
UPDATE sys_menus SET menu_name_ko='연동·데이터 운영', menu_name_en='Integrations' WHERE tenant_id=1 AND menu_key='menu.governance-config.integrations';
UPDATE sys_menus SET menu_name_ko='시스템 관리', menu_name_en='Admin' WHERE tenant_id=1 AND menu_key='menu.governance-config.admin';

-- §2.2.6 Admin (DWP 통합 Admin)
UPDATE sys_menus SET menu_name_ko='관리', menu_name_en='Admin' WHERE tenant_id=1 AND menu_key='menu.admin';
UPDATE sys_menus SET menu_name_ko='통합 모니터링', menu_name_en='Monitoring' WHERE tenant_id=1 AND menu_key='menu.admin.monitoring';
UPDATE sys_menus SET menu_name_ko='배치 모니터링', menu_name_en='Batch Monitoring' WHERE tenant_id=1 AND menu_key='menu.admin.batch-monitoring';
UPDATE sys_menus SET menu_name_ko='사용자 관리', menu_name_en='Users' WHERE tenant_id=1 AND menu_key='menu.admin.users';
UPDATE sys_menus SET menu_name_ko='역할 관리', menu_name_en='Roles' WHERE tenant_id=1 AND menu_key='menu.admin.roles';
UPDATE sys_menus SET menu_name_ko='리소스 관리', menu_name_en='Resources' WHERE tenant_id=1 AND menu_key='menu.admin.resources';
UPDATE sys_menus SET menu_name_ko='감사 로그', menu_name_en='Audit Logs' WHERE tenant_id=1 AND menu_key='menu.admin.audit';
UPDATE sys_menus SET menu_name_ko='메뉴 관리', menu_name_en='Menus' WHERE tenant_id=1 AND menu_key='menu.admin.menus';
UPDATE sys_menus SET menu_name_ko='코드 관리', menu_name_en='Codes' WHERE tenant_id=1 AND menu_key='menu.admin.codes';
UPDATE sys_menus SET menu_name_ko='코드 사용정의', menu_name_en='Code Usages' WHERE tenant_id=1 AND menu_key='menu.admin.code-usages';

-- ========================================
-- 7. (선택) ENTITY_TYPE, COUNTRY — Entities 화면 필터용
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('ENTITY_TYPE', '거래처 유형', '거래처 분류 (VENDOR, CUSTOMER)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('COUNTRY', '국가', 'ISO 3166-1 alpha-3 국가 코드', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('ENTITY_TYPE', 'VENDOR', '공급업체', '공급업체', 'Vendor', '공급업체', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ENTITY_TYPE', 'CUSTOMER', '고객', '고객', 'Customer', '고객', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('COUNTRY', 'KOR', '대한민국', '대한민국', 'South Korea', '대한민국', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('COUNTRY', 'USA', '미국', '미국', 'United States', '미국', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('COUNTRY', 'JPN', '일본', '일본', 'Japan', '일본', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('COUNTRY', 'CHN', '중국', '중국', 'China', '중국', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.master-data-history.entities', 'ENTITY_TYPE', 'MENU', true, 10, '거래처 유형 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.master-data-history.entities', 'COUNTRY', 'MENU', true, 20, '국가 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = true,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;
