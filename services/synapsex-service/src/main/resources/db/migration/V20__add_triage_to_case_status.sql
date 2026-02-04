-- Drill-down 계약: spec의 TRIAGE를 app_codes에 추가 (FE 호환)
-- status=OPEN,TRIAGE 다중값 지원. TRIAGE는 애플리케이션에서 TRIAGED로 매핑하여 조회.

SET search_path TO dwp_aura, public;

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('CASE_STATUS', 'TRIAGE', 'Triage', '분류중 (TRIAGED와 동일)', 14, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();
