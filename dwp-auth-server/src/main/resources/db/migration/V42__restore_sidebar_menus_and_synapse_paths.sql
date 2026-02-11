-- V42: 사이드바 메뉴 복원 및 menu_path FE 라우팅 정렬
-- 목적: V38에서 비노출 처리한 anomalies/cases/actions 재노출; 모든 Synapse 하위 메뉴의 menu_path를 /synapse/* 로 통일

SET search_path TO public;

-- ========================================
-- 1. 사이드바 재노출: V38에서 is_visible='N' 처리한 메뉴 복원
-- ========================================
UPDATE sys_menus
SET is_visible = 'Y', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key IN (
    'menu.autonomous-operations.anomalies',
    'menu.autonomous-operations.cases',
    'menu.autonomous-operations.actions'
  );

-- ========================================
-- 2. 자율 운영 센터 하위: menu_path = /synapse/* (FE normalizePath 규칙)
-- ========================================
UPDATE sys_menus SET menu_path = '/synapse/cases',       updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.cases';
UPDATE sys_menus SET menu_path = '/synapse/anomalies',   updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.anomalies';
UPDATE sys_menus SET menu_path = '/synapse/optimization', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.optimization';
UPDATE sys_menus SET menu_path = '/synapse/actions',     updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.actions';
UPDATE sys_menus SET menu_path = '/synapse/archive',     updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.archive';

-- ========================================
-- 3. 지식·정책 허브 하위: RAG, 정책, 가드레일, 사전, 피드백
-- ========================================
UPDATE sys_menus SET menu_path = '/synapse/rag',         updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.rag';
UPDATE sys_menus SET menu_path = '/synapse/policies',     updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.policies';
UPDATE sys_menus SET menu_path = '/synapse/guardrails',  updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.guardrails';
UPDATE sys_menus SET menu_path = '/synapse/dictionary',  updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.dictionary';
UPDATE sys_menus SET menu_path = '/synapse/feedback',    updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.feedback';

-- ========================================
-- 4. 원천 데이터·이력 허브 하위
-- ========================================
UPDATE sys_menus SET menu_path = '/synapse/documents',   updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history.documents';
UPDATE sys_menus SET menu_path = '/synapse/open-items',  updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history.open-items';
UPDATE sys_menus SET menu_path = '/synapse/entities',    updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history.entities';
UPDATE sys_menus SET menu_path = '/synapse/lineage',     updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history.lineage';

-- ========================================
-- 5. 대사·감사 센터 하위
-- ========================================
UPDATE sys_menus SET menu_path = '/synapse/reconciliation', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit.reconciliation';
UPDATE sys_menus SET menu_path = '/synapse/action-recon',  updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit.action-recon';
UPDATE sys_menus SET menu_path = '/synapse/audit',        updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit.audit';
UPDATE sys_menus SET menu_path = '/synapse/analytics',    updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit.analytics';

-- ========================================
-- 6. 거버넌스·설정 하위
-- ========================================
UPDATE sys_menus SET menu_path = '/synapse/governance',   updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.governance-config.governance';
UPDATE sys_menus SET menu_path = '/synapse/agent-config',  updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.governance-config.agent-config';
UPDATE sys_menus SET menu_path = '/synapse/integrations', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.governance-config.integrations';
UPDATE sys_menus SET menu_path = '/synapse/admin',        updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.governance-config.admin';
