-- V28: i18n 2차 — 다국어 시드 데이터 (name_ko, name_en / menu_name_ko, menu_name_en)
-- 목적: Accept-Language 기반 코드/메뉴 라벨이 null fallback 없이 반환되도록 시드
-- 참고: docs/job/PROMPT_BE_I18N_PHASE2_SEED_TRANSLATIONS_AND_OPTIONAL_ERRORS.txt

-- ========================================
-- 1. sys_codes: name_ko, name_en 시드
-- ========================================

-- RESOURCE_TYPE
UPDATE sys_codes SET name_ko='메뉴', name_en='Menu' WHERE group_key='RESOURCE_TYPE' AND code='MENU';
UPDATE sys_codes SET name_ko='UI 컴포넌트', name_en='UI Component' WHERE group_key='RESOURCE_TYPE' AND code='UI_COMPONENT';
UPDATE sys_codes SET name_ko='페이지', name_en='Page' WHERE group_key='RESOURCE_TYPE' AND code='PAGE';
UPDATE sys_codes SET name_ko='API', name_en='API' WHERE group_key='RESOURCE_TYPE' AND code='API';

-- SUBJECT_TYPE
UPDATE sys_codes SET name_ko='사용자', name_en='User' WHERE group_key='SUBJECT_TYPE' AND code='USER';
UPDATE sys_codes SET name_ko='부서', name_en='Department' WHERE group_key='SUBJECT_TYPE' AND code='DEPARTMENT';

-- ROLE_CODE
UPDATE sys_codes SET name_ko='관리자', name_en='Admin' WHERE group_key='ROLE_CODE' AND code='ADMIN';
UPDATE sys_codes SET name_ko='일반 사용자', name_en='User' WHERE group_key='ROLE_CODE' AND code='USER';
UPDATE sys_codes SET name_ko='Admin', name_en='SynapseX Admin' WHERE group_key='ROLE_CODE' AND code='SYNAPSEX_ADMIN';
UPDATE sys_codes SET name_ko='SynapseX_Operator', name_en='SynapseX Operator' WHERE group_key='ROLE_CODE' AND code='SYNAPSEX_OPERATOR';
UPDATE sys_codes SET name_ko='SynapseX_Viewer', name_en='SynapseX Viewer' WHERE group_key='ROLE_CODE' AND code='SYNAPSEX_VIEWER';

-- IDP_PROVIDER_TYPE
UPDATE sys_codes SET name_ko='로컬 인증', name_en='Local' WHERE group_key='IDP_PROVIDER_TYPE' AND code='LOCAL';
UPDATE sys_codes SET name_ko='SAML', name_en='SAML' WHERE group_key='IDP_PROVIDER_TYPE' AND code='SAML';
UPDATE sys_codes SET name_ko='OIDC', name_en='OIDC' WHERE group_key='IDP_PROVIDER_TYPE' AND code='OIDC';

-- PERMISSION_CODE
UPDATE sys_codes SET name_ko='조회', name_en='View' WHERE group_key='PERMISSION_CODE' AND code='VIEW';
UPDATE sys_codes SET name_ko='사용', name_en='Use' WHERE group_key='PERMISSION_CODE' AND code='USE';
UPDATE sys_codes SET name_ko='편집', name_en='Edit' WHERE group_key='PERMISSION_CODE' AND code='EDIT';
UPDATE sys_codes SET name_ko='승인', name_en='Approve' WHERE group_key='PERMISSION_CODE' AND code='APPROVE';
UPDATE sys_codes SET name_ko='실행', name_en='Execute' WHERE group_key='PERMISSION_CODE' AND code='EXECUTE';

-- USER_STATUS
UPDATE sys_codes SET name_ko='활성', name_en='Active' WHERE group_key='USER_STATUS' AND code='ACTIVE';
UPDATE sys_codes SET name_ko='비활성', name_en='Inactive' WHERE group_key='USER_STATUS' AND code='INACTIVE';
UPDATE sys_codes SET name_ko='잠금', name_en='Locked' WHERE group_key='USER_STATUS' AND code='LOCKED';

-- EFFECT_TYPE
UPDATE sys_codes SET name_ko='허용', name_en='Allow' WHERE group_key='EFFECT_TYPE' AND code='ALLOW';
UPDATE sys_codes SET name_ko='거부', name_en='Deny' WHERE group_key='EFFECT_TYPE' AND code='DENY';

-- RESOURCE_STATUS
UPDATE sys_codes SET name_ko='활성', name_en='Enabled' WHERE group_key='RESOURCE_STATUS' AND code='ENABLED';
UPDATE sys_codes SET name_ko='비활성', name_en='Disabled' WHERE group_key='RESOURCE_STATUS' AND code='DISABLED';

-- LOGIN_TYPE
UPDATE sys_codes SET name_ko='로컬 로그인', name_en='Local' WHERE group_key='LOGIN_TYPE' AND code='LOCAL';
UPDATE sys_codes SET name_ko='SSO 로그인', name_en='SSO' WHERE group_key='LOGIN_TYPE' AND code='SSO';

-- MONITORING_CONFIG_KEY (V6~V14 등, 주요 코드만)
UPDATE sys_codes SET name_ko='분당 최소 호출 수', name_en='Min Requests Per Minute' WHERE group_key='MONITORING_CONFIG_KEY' AND code='MIN_REQ_PER_MINUTE';
UPDATE sys_codes SET name_ko='에러율 임계치(%)', name_en='Error Rate Threshold (%)' WHERE group_key='MONITORING_CONFIG_KEY' AND code='ERROR_RATE_THRESHOLD';

-- ========================================
-- 2. sys_menus: menu_name_ko, menu_name_en 시드
-- ========================================

-- SynapseX 대메뉴 (depth=1)
UPDATE sys_menus SET menu_name_ko='통합 관제 센터', menu_name_en='Command Center' WHERE tenant_id=1 AND menu_key='menu.command-center';
UPDATE sys_menus SET menu_name_ko='자율 운영 센터', menu_name_en='Autonomous Operations' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations';
UPDATE sys_menus SET menu_name_ko='원천 데이터·이력 허브', menu_name_en='Master Data & History' WHERE tenant_id=1 AND menu_key='menu.master-data-history';
UPDATE sys_menus SET menu_name_ko='지식·정책 허브', menu_name_en='Knowledge & Policy' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy';
UPDATE sys_menus SET menu_name_ko='대사·감사 센터', menu_name_en='Reconciliation & Audit' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit';
UPDATE sys_menus SET menu_name_ko='거버넌스·설정', menu_name_en='Governance & Config' WHERE tenant_id=1 AND menu_key='menu.governance-config';

-- 자율 운영 센터 하위
UPDATE sys_menus SET menu_name_ko='케이스 작업함', menu_name_en='Cases' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.cases';
UPDATE sys_menus SET menu_name_ko='이상 징후 탐지', menu_name_en='Anomalies' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.anomalies';
UPDATE sys_menus SET menu_name_ko='채권·채무 최적화', menu_name_en='Optimization' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.optimization';
UPDATE sys_menus SET menu_name_ko='조치 실행 센터', menu_name_en='Actions' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.actions';
UPDATE sys_menus SET menu_name_ko='조치 이력 보관함', menu_name_en='Archive' WHERE tenant_id=1 AND menu_key='menu.autonomous-operations.archive';

-- 원천 데이터·이력 허브 하위
UPDATE sys_menus SET menu_name_ko='전표 조회', menu_name_en='Documents' WHERE tenant_id=1 AND menu_key='menu.master-data-history.documents';
UPDATE sys_menus SET menu_name_ko='미결제 항목', menu_name_en='Open Items' WHERE tenant_id=1 AND menu_key='menu.master-data-history.open-items';
UPDATE sys_menus SET menu_name_ko='거래처 허브', menu_name_en='Entities' WHERE tenant_id=1 AND menu_key='menu.master-data-history.entities';
UPDATE sys_menus SET menu_name_ko='계보·근거 뷰어', menu_name_en='Lineage' WHERE tenant_id=1 AND menu_key='menu.master-data-history.lineage';

-- 지식·정책 허브 하위
UPDATE sys_menus SET menu_name_ko='규정·문서 라이브러리', menu_name_en='RAG Library' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.rag';
UPDATE sys_menus SET menu_name_ko='정책 프로파일', menu_name_en='Policies' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.policies';
UPDATE sys_menus SET menu_name_ko='조치 가드레일', menu_name_en='Guardrails' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.guardrails';
UPDATE sys_menus SET menu_name_ko='용어·코드 사전', menu_name_en='Dictionary' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.dictionary';
UPDATE sys_menus SET menu_name_ko='피드백·라벨링', menu_name_en='Feedback' WHERE tenant_id=1 AND menu_key='menu.knowledge-policy.feedback';

-- 대사·감사 센터 하위
UPDATE sys_menus SET menu_name_ko='정합성 대사 리포트', menu_name_en='Reconciliation' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit.reconciliation';
UPDATE sys_menus SET menu_name_ko='조치 결과 대사', menu_name_en='Action Recon' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit.action-recon';
UPDATE sys_menus SET menu_name_ko='감사 추적 로그', menu_name_en='Audit' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit.audit';
UPDATE sys_menus SET menu_name_ko='효과·성과 분석', menu_name_en='Analytics' WHERE tenant_id=1 AND menu_key='menu.reconciliation-audit.analytics';

-- 거버넌스·설정 하위
UPDATE sys_menus SET menu_name_ko='자율성·통제 설정', menu_name_en='Governance' WHERE tenant_id=1 AND menu_key='menu.governance-config.governance';
UPDATE sys_menus SET menu_name_ko='에이전트 구성 관리', menu_name_en='Agent Config' WHERE tenant_id=1 AND menu_key='menu.governance-config.agent-config';
UPDATE sys_menus SET menu_name_ko='연동·데이터 운영', menu_name_en='Integrations' WHERE tenant_id=1 AND menu_key='menu.governance-config.integrations';
UPDATE sys_menus SET menu_name_ko='시스템 관리', menu_name_en='Admin' WHERE tenant_id=1 AND menu_key='menu.governance-config.admin';

-- APPS 메뉴
UPDATE sys_menus SET menu_name_ko='대시보드', menu_name_en='Dashboard' WHERE tenant_id=1 AND menu_key='menu.dashboard';
UPDATE sys_menus SET menu_name_ko='메일', menu_name_en='Mail' WHERE tenant_id=1 AND menu_key='menu.mail';
UPDATE sys_menus SET menu_name_ko='받은 메일함', menu_name_en='Inbox' WHERE tenant_id=1 AND menu_key='menu.mail.inbox';
UPDATE sys_menus SET menu_name_ko='보낸 메일함', menu_name_en='Sent' WHERE tenant_id=1 AND menu_key='menu.mail.sent';
UPDATE sys_menus SET menu_name_ko='AI 워크스페이스', menu_name_en='AI Workspace' WHERE tenant_id=1 AND menu_key='menu.ai-workspace';

-- Admin 메뉴
UPDATE sys_menus SET menu_name_ko='관리', menu_name_en='Admin' WHERE tenant_id=1 AND menu_key='menu.admin';
UPDATE sys_menus SET menu_name_ko='통합 모니터링', menu_name_en='Monitoring' WHERE tenant_id=1 AND menu_key='menu.admin.monitoring';
UPDATE sys_menus SET menu_name_ko='배치 모니터링', menu_name_en='Batch Monitoring' WHERE tenant_id=1 AND menu_key='menu.admin.batch-monitoring';
UPDATE sys_menus SET menu_name_ko='사용자 관리', menu_name_en='Users' WHERE tenant_id=1 AND menu_key='menu.admin.users';
UPDATE sys_menus SET menu_name_ko='역할 관리', menu_name_en='Roles' WHERE tenant_id=1 AND menu_key='menu.admin.roles';
UPDATE sys_menus SET menu_name_ko='리소스 관리', menu_name_en='Resources' WHERE tenant_id=1 AND menu_key='menu.admin.resources';
UPDATE sys_menus SET menu_name_ko='감사 로그', menu_name_en='Audit' WHERE tenant_id=1 AND menu_key='menu.admin.audit';
UPDATE sys_menus SET menu_name_ko='메뉴 관리', menu_name_en='Menus' WHERE tenant_id=1 AND menu_key='menu.admin.menus';
UPDATE sys_menus SET menu_name_ko='코드 관리', menu_name_en='Codes' WHERE tenant_id=1 AND menu_key='menu.admin.codes';
UPDATE sys_menus SET menu_name_ko='코드 사용정의', menu_name_en='Code Usages' WHERE tenant_id=1 AND menu_key='menu.admin.code-usages';
