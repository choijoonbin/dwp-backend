-- V20: sys_menus sort_order 재배치 — 그룹 순서 SynapseX → APPS → admin
-- 그룹 내 정렬은 기존 상대 순서 유지. (낮을수록 앞)

-- SynapseX (엔터프라이즈 루트 10~60, 하위 11~15, 21~24, 31~35, 41~44, 51~54)
UPDATE sys_menus SET sort_order = 10, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.command-center';
UPDATE sys_menus SET sort_order = 20, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations';
UPDATE sys_menus SET sort_order = 30, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history';
UPDATE sys_menus SET sort_order = 40, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy';
UPDATE sys_menus SET sort_order = 50, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit';
UPDATE sys_menus SET sort_order = 60, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.governance-config';

UPDATE sys_menus SET sort_order = 11, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.cases';
UPDATE sys_menus SET sort_order = 12, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.anomalies';
UPDATE sys_menus SET sort_order = 13, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.optimization';
UPDATE sys_menus SET sort_order = 14, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.actions';
UPDATE sys_menus SET sort_order = 15, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.archive';

UPDATE sys_menus SET sort_order = 21, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history.documents';
UPDATE sys_menus SET sort_order = 22, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history.open-items';
UPDATE sys_menus SET sort_order = 23, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history.entities';
UPDATE sys_menus SET sort_order = 24, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history.lineage';

UPDATE sys_menus SET sort_order = 31, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.rag';
UPDATE sys_menus SET sort_order = 32, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.policies';
UPDATE sys_menus SET sort_order = 33, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.guardrails';
UPDATE sys_menus SET sort_order = 34, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.dictionary';
UPDATE sys_menus SET sort_order = 35, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy.feedback';

UPDATE sys_menus SET sort_order = 41, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit.reconciliation';
UPDATE sys_menus SET sort_order = 42, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit.action-recon';
UPDATE sys_menus SET sort_order = 43, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit.audit';
UPDATE sys_menus SET sort_order = 44, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit.analytics';

UPDATE sys_menus SET sort_order = 51, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.governance-config.governance';
UPDATE sys_menus SET sort_order = 52, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.governance-config.agent-config';
UPDATE sys_menus SET sort_order = 53, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.governance-config.integrations';
UPDATE sys_menus SET sort_order = 54, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.governance-config.admin';

-- APPS (100~122)
UPDATE sys_menus SET sort_order = 100, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.dashboard';
UPDATE sys_menus SET sort_order = 110, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.mail';
UPDATE sys_menus SET sort_order = 120, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.ai-workspace';
UPDATE sys_menus SET sort_order = 111, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.mail.inbox';
UPDATE sys_menus SET sort_order = 112, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.mail.sent';

-- admin (menu.admin, 200~208)
UPDATE sys_menus SET sort_order = 200, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin';
UPDATE sys_menus SET sort_order = 201, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.monitoring';
UPDATE sys_menus SET sort_order = 202, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.users';
UPDATE sys_menus SET sort_order = 203, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.roles';
UPDATE sys_menus SET sort_order = 204, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.resources';
UPDATE sys_menus SET sort_order = 205, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.audit';
UPDATE sys_menus SET sort_order = 206, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.menus';
UPDATE sys_menus SET sort_order = 207, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.codes';
UPDATE sys_menus SET sort_order = 208, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.code-usages';
