-- V48: 사이드바 3대 메뉴 강제 업데이트
-- 1) 메뉴명에서 불필요한 배지([12], [3] 등) 제거 — 텍스트만 유지
-- 2) 1레벨: 통합 워크벤치, 지식·정책 허브, 거버넌스·설정 명칭 확정

SET search_path TO public;

-- 배지 패턴 제거: 공백 + [숫자/문자] + 공백 → 공백 하나로. 앞뒤 공백 trim
UPDATE sys_menus
SET menu_name   = TRIM(REGEXP_REPLACE(menu_name,   '\s*\[[^\]]*\]\s*', '', 'g')),
    menu_name_ko = TRIM(REGEXP_REPLACE(COALESCE(menu_name_ko, menu_name), '\s*\[[^\]]*\]\s*', '', 'g')),
    menu_name_en = TRIM(REGEXP_REPLACE(COALESCE(menu_name_en, menu_name), '\s*\[[^\]]*\]\s*', '', 'g')),
    updated_at   = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND (menu_name ~ '\s*\[[^\]]*\]'
    OR COALESCE(menu_name_ko, '') ~ '\s*\[[^\]]*\]'
    OR COALESCE(menu_name_en, '') ~ '\s*\[[^\]]*\]');

-- 1레벨 3대 메뉴 명칭 확정 (공유 CSV 기준)
UPDATE sys_menus SET menu_name = '통합 워크벤치', menu_name_ko = '통합 워크벤치', menu_name_en = 'Unified Workbench', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.workbench';

UPDATE sys_menus SET menu_name = '지식·정책 허브', menu_name_ko = '지식·정책 허브', menu_name_en = 'Knowledge & Policy', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.knowledge-policy';

UPDATE sys_menus SET menu_name = '거버넌스·설정', menu_name_ko = '거버넌스·설정', menu_name_en = 'Governance & Config', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.governance-config';
