-- V18: 엔터프라이즈 메뉴(depth=1 대메뉴)의 menu_path를 menu_key와 동일하게 정의
-- FE 라우팅/정규화 시 menu_path를 canonical 값으로 사용할 수 있도록 함

UPDATE sys_menus SET menu_path = 'menu.command-center', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.command-center';

UPDATE sys_menus SET menu_path = 'menu.autonomous-operations', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations';

UPDATE sys_menus SET menu_path = 'menu.master-data-history', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.master-data-history';

UPDATE sys_menus SET menu_path = 'menu.knowledge-policy', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy';

UPDATE sys_menus SET menu_path = 'menu.reconciliation-audit', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.reconciliation-audit';

UPDATE sys_menus SET menu_path = 'menu.governance-config', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.governance-config';
