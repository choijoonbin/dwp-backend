-- V16: 엔터프라이즈 메뉴 추가 (MANAGEMENT 그룹)
-- 트리: 통합 관제 센터, 자율 운영 센터, 원천 데이터·이력 허브, 지식·정책 허브, 대사·감사 센터, 거버넌스·설정
-- sort_order: 기존 10~108(APPS/Admin)과 중복 방지를 위해 200~264 사용

INSERT INTO sys_menus (tenant_id, menu_key, menu_name, menu_path, menu_icon, menu_group, parent_menu_key, sort_order, depth, is_visible, is_enabled, description, created_at, updated_at)
VALUES
    -- depth=1: 대메뉴(그룹)
    (1, 'menu.command-center', '통합 관제 센터', '/', 'solar:monitor-bold', 'MANAGEMENT', NULL, 200, 1, 'Y', 'Y', '통합 관제 센터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.autonomous-operations', '자율 운영 센터', NULL, 'solar:widget-bold', 'MANAGEMENT', NULL, 210, 1, 'Y', 'Y', '자율 운영 센터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.master-data-history', '원천 데이터·이력 허브', NULL, 'solar:database-bold', 'MANAGEMENT', NULL, 220, 1, 'Y', 'Y', '원천 데이터·이력 허브', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.knowledge-policy', '지식·정책 허브', NULL, 'solar:book-bold', 'MANAGEMENT', NULL, 230, 1, 'Y', 'Y', '지식·정책 허브', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.reconciliation-audit', '대사·감사 센터', NULL, 'solar:clipboard-check-bold', 'MANAGEMENT', NULL, 240, 1, 'Y', 'Y', '대사·감사 센터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.governance-config', '거버넌스·설정', NULL, 'solar:settings-bold', 'MANAGEMENT', NULL, 250, 1, 'Y', 'Y', '거버넌스·설정', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- 자율 운영 센터 하위 (depth=2)
    (1, 'menu.autonomous-operations.cases', '케이스 작업함', '/cases', 'solar:folder-with-files-bold', 'MANAGEMENT', 'menu.autonomous-operations', 211, 2, 'Y', 'Y', '케이스 작업함', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.autonomous-operations.anomalies', '이상 징후 탐지', '/anomalies', 'solar:danger-triangle-bold', 'MANAGEMENT', 'menu.autonomous-operations', 212, 2, 'Y', 'Y', '이상 징후 탐지', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.autonomous-operations.optimization', '채권·채무 최적화', '/optimization', 'solar:chart-2-bold', 'MANAGEMENT', 'menu.autonomous-operations', 213, 2, 'Y', 'Y', '채권·채무 최적화', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.autonomous-operations.actions', '조치 실행 센터', '/actions', 'solar:play-circle-bold', 'MANAGEMENT', 'menu.autonomous-operations', 214, 2, 'Y', 'Y', '조치 실행 센터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.autonomous-operations.archive', '조치 이력 보관함', '/archive', 'solar:archive-bold', 'MANAGEMENT', 'menu.autonomous-operations', 215, 2, 'Y', 'Y', '조치 이력 보관함', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- 원천 데이터·이력 허브 하위 (depth=2)
    (1, 'menu.master-data-history.documents', '전표 조회', '/documents', 'solar:document-bold', 'MANAGEMENT', 'menu.master-data-history', 221, 2, 'Y', 'Y', '전표 조회', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.master-data-history.open-items', '미결제 항목', '/open-items', 'solar:wallet-bold', 'MANAGEMENT', 'menu.master-data-history', 222, 2, 'Y', 'Y', '미결제 항목', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.master-data-history.entities', '거래처 허브', '/entities', 'solar:users-group-rounded-bold', 'MANAGEMENT', 'menu.master-data-history', 223, 2, 'Y', 'Y', '거래처 허브', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.master-data-history.lineage', '계보·근거 뷰어', '/lineage', 'solar:link-bold', 'MANAGEMENT', 'menu.master-data-history', 224, 2, 'Y', 'Y', '계보·근거 뷰어', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- 지식·정책 허브 하위 (depth=2)
    (1, 'menu.knowledge-policy.rag', '규정·문서 라이브러리', '/rag', 'solar:book-2-bold', 'MANAGEMENT', 'menu.knowledge-policy', 231, 2, 'Y', 'Y', '규정·문서 라이브러리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.knowledge-policy.policies', '정책 프로파일', '/policies', 'solar:shield-check-bold', 'MANAGEMENT', 'menu.knowledge-policy', 232, 2, 'Y', 'Y', '정책 프로파일', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.knowledge-policy.guardrails', '조치 가드레일', '/guardrails', 'solar:shield-user-bold', 'MANAGEMENT', 'menu.knowledge-policy', 233, 2, 'Y', 'Y', '조치 가드레일', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.knowledge-policy.dictionary', '용어·코드 사전', '/dictionary', 'solar:code-bold', 'MANAGEMENT', 'menu.knowledge-policy', 234, 2, 'Y', 'Y', '용어·코드 사전', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.knowledge-policy.feedback', '피드백·라벨링', '/feedback', 'solar:like-bold', 'MANAGEMENT', 'menu.knowledge-policy', 235, 2, 'Y', 'Y', '피드백·라벨링', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- 대사·감사 센터 하위 (depth=2)
    (1, 'menu.reconciliation-audit.reconciliation', '정합성 대사 리포트', '/reconciliation', 'solar:clipboard-list-bold', 'MANAGEMENT', 'menu.reconciliation-audit', 241, 2, 'Y', 'Y', '정합성 대사 리포트', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.reconciliation-audit.action-recon', '조치 결과 대사', '/action-recon', 'solar:checklist-bold', 'MANAGEMENT', 'menu.reconciliation-audit', 242, 2, 'Y', 'Y', '조치 결과 대사', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.reconciliation-audit.audit', '감사 추적 로그', '/audit', 'solar:history-bold', 'MANAGEMENT', 'menu.reconciliation-audit', 243, 2, 'Y', 'Y', '감사 추적 로그', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.reconciliation-audit.analytics', '효과·성과 분석', '/analytics', 'solar:chart-square-bold', 'MANAGEMENT', 'menu.reconciliation-audit', 244, 2, 'Y', 'Y', '효과·성과 분석', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- 거버넌스·설정 하위 (depth=2)
    (1, 'menu.governance-config.governance', '자율성·통제 설정', '/governance', 'solar:slider-bold', 'MANAGEMENT', 'menu.governance-config', 251, 2, 'Y', 'Y', '자율성·통제 설정', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.governance-config.agent-config', '에이전트 구성 관리', '/agent-config', 'solar:robot-bold', 'MANAGEMENT', 'menu.governance-config', 252, 2, 'Y', 'Y', '에이전트 구성 관리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.governance-config.integrations', '연동·데이터 운영', '/integrations', 'solar:link-circle-bold', 'MANAGEMENT', 'menu.governance-config', 253, 2, 'Y', 'Y', '연동·데이터 운영', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.governance-config.admin', '시스템 관리', '/admin', 'solar:settings-bold', 'MANAGEMENT', 'menu.governance-config', 254, 2, 'Y', 'Y', '시스템 관리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, menu_key) DO UPDATE SET
    menu_name = EXCLUDED.menu_name,
    menu_path = EXCLUDED.menu_path,
    menu_icon = EXCLUDED.menu_icon,
    menu_group = EXCLUDED.menu_group,
    parent_menu_key = EXCLUDED.parent_menu_key,
    sort_order = EXCLUDED.sort_order,
    depth = EXCLUDED.depth,
    is_visible = EXCLUDED.is_visible,
    is_enabled = EXCLUDED.is_enabled,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

SELECT setval('sys_menus_sys_menu_id_seq', (SELECT MAX(sys_menu_id) FROM sys_menus));
