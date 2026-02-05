-- ======================================================================
-- agent_tasks 누락 컬럼 코멘트 추가
-- ======================================================================

COMMENT ON COLUMN agent_tasks.id IS '작업 식별자 (PK)';
COMMENT ON COLUMN agent_tasks.description IS '작업 설명';
COMMENT ON COLUMN agent_tasks.input_data IS '입력 데이터 (JSON 등)';
COMMENT ON COLUMN agent_tasks.result_data IS '결과 데이터 (JSON 등)';
COMMENT ON COLUMN agent_tasks.error_message IS '오류 메시지 (실패 시)';
COMMENT ON COLUMN agent_tasks.started_at IS '시작 시각';
COMMENT ON COLUMN agent_tasks.completed_at IS '완료 시각';
COMMENT ON COLUMN agent_tasks.created_at IS '생성일시';
COMMENT ON COLUMN agent_tasks.updated_at IS '수정일시';
