-- V33: ACTION_TYPE app_codes (액션 제안 type 표시명용)
-- action-proposals API typeName resolved from app_codes

SET search_path TO dwp_aura, public;

INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('ACTION_TYPE', 'Action Type', '조치 유형 (액션 제안/조치 실행)', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET group_name = EXCLUDED.group_name, description = EXCLUDED.description, updated_at = now();

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('ACTION_TYPE', 'POST_REVERSAL', '전기 반전', '전기 반전', 10, true, now(), now()),
    ('ACTION_TYPE', 'BLOCK_PAYMENT', '결제 차단', '결제 차단', 20, true, now(), now()),
    ('ACTION_TYPE', 'FLAG_REVIEW', '검토 요청', '검토 요청', 30, true, now(), now()),
    ('ACTION_TYPE', 'CLEAR_ITEM', '항목 정리', '항목 정리', 40, true, now(), now()),
    ('ACTION_TYPE', 'UPDATE_MASTER', '마스터 데이터 업데이트', '마스터 데이터 업데이트', 50, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, updated_at = now();
