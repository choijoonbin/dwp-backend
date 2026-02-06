-- V33: sys_codes name_ko, name_en 수동 수정값 보존
-- 목적: 재기동 시 name_ko/name_en이 NULL로 초기화되는 현상 방지
-- 원인 후보: DB 재생성(테스트/도커), ON CONFLICT 시 EXCLUDED.name_ko가 NULL로 덮어쓰기
-- 조치: idempotent COALESCE로 NULL 복구 (기존 값은 보존)
-- 참고: Admin API(PATCH /api/admin/codes/{id})로 name_ko, name_en 수정 시 DB에 영구 반영됨

UPDATE sys_codes
SET
    name_ko = COALESCE(name_ko, name),
    name_en = COALESCE(name_en, name)
WHERE name_ko IS NULL OR name_en IS NULL;
