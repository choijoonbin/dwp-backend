-- V43: 사이드 메뉴 전면 복원 + 통합 워크벤치 경로 확정 + com_resources route 100% 동기화
-- 목적: 모든 Synapse/자율 운영 메뉴 is_visible='Y'; 워크벤치 menu_path/ menu_name 확정; com_resources.metadata_json.route = sys_menus.menu_path

SET search_path TO public;

-- ========================================
-- 1. 통합 워크벤치: menu_path·화면 제목 확정
-- ========================================
UPDATE sys_menus
SET menu_path   = '/synapse/workbench',
    menu_name   = '통합 워크벤치',
    menu_name_ko = '통합 워크벤치',
    menu_name_en = COALESCE(menu_name_en, 'Unified Workbench'),
    updated_at  = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.autonomous-operations.workbench';

-- ========================================
-- 2. 통합 관제 센터(menu.command-center) 진입점을 워크벤치로 통일 (경로 하드코딩 정리)
-- ========================================
UPDATE sys_menus
SET menu_path = '/synapse/workbench', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.command-center';

-- ========================================
-- 3. 사이드 메뉴 전면 복원: anomalies, cases, actions, rag, policies, dictionary 등 is_visible = 'Y'
-- ========================================
UPDATE sys_menus
SET is_visible = 'Y', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key IN (
    'menu.autonomous-operations.anomalies',
    'menu.autonomous-operations.cases',
    'menu.autonomous-operations.actions',
    'menu.autonomous-operations.optimization',
    'menu.autonomous-operations.archive',
    'menu.autonomous-operations.workbench',
    'menu.knowledge-policy.rag',
    'menu.knowledge-policy.policies',
    'menu.knowledge-policy.guardrails',
    'menu.knowledge-policy.dictionary',
    'menu.knowledge-policy.feedback',
    'menu.master-data-history.documents',
    'menu.master-data-history.open-items',
    'menu.master-data-history.entities',
    'menu.master-data-history.lineage',
    'menu.reconciliation-audit.reconciliation',
    'menu.reconciliation-audit.action-recon',
    'menu.reconciliation-audit.audit',
    'menu.reconciliation-audit.analytics',
    'menu.governance-config.governance',
    'menu.governance-config.agent-config',
    'menu.governance-config.integrations',
    'menu.governance-config.admin',
    'menu.command-center'
  );

-- ========================================
-- 4. com_resources: metadata_json.route 를 sys_menus.menu_path 와 100% 일치
--    (type='MENU' 이고 key = menu_key 인 행만, menu_path 가 있는 경우)
-- ========================================
UPDATE com_resources c
SET metadata_json = '{"route":"' || m.menu_path || '"}',
    updated_at = CURRENT_TIMESTAMP
FROM sys_menus m
WHERE c.tenant_id = m.tenant_id
  AND c.type = 'MENU'
  AND c.key = m.menu_key
  AND m.menu_path IS NOT NULL
  AND TRIM(m.menu_path) <> '';
