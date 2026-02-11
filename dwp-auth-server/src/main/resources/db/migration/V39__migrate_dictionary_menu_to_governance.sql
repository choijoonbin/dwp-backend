-- V39: 용어·코드 사전 메뉴를 지식·정책 허브 → 거버넌스·설정 하위로 이동
-- 목적: '용어·코드 사전'을 거버넌스·설정 메뉴 하단에 배치, 정책 권한 정교화와 일치

-- 1. menu.knowledge-policy.dictionary 의 부모를 menu.governance-config 로 변경
UPDATE sys_menus
SET parent_menu_key = 'menu.governance-config',
    sort_order     = 55,
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.knowledge-policy.dictionary';

-- 2. sort_order 재조정: 거버넌스·설정 하위 순서 유지
--    기존 governance(51), agent-config(52), integrations(53), admin(54) → dictionary(55)로 설정 메뉴 하단 배치
--    (위 UPDATE에서 이미 55 적용)

-- 참고: com_resources / com_role_permissions 의 resource key 는 menu.knowledge-policy.dictionary 로 유지.
--       메뉴 트리만 이동하므로 RBAC 매핑 변경 없음.
