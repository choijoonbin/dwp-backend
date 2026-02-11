-- V41: RAG 문서 업로드/벡터화 상태 코드 (Phase 6 — sys_codes 기반 상태 관리)
-- 목적: FE/BE에서 RAG 문서 상태를 READY → PROCESSING → COMPLETED(FAILED) 로 관리

SET search_path TO public;

INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('RAG_DOCUMENT_STATUS', 'RAG 문서 상태', 'RAG 문서 업로드·벡터화 상태 (READY, PROCESSING, COMPLETED, FAILED)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('RAG_DOCUMENT_STATUS', 'READY', '대기', '대기', 'Ready', '업로드 완료, 벡터화 대기', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RAG_DOCUMENT_STATUS', 'PROCESSING', '처리중', '처리중', 'Processing', 'Aura 벡터화 진행 중', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RAG_DOCUMENT_STATUS', 'COMPLETED', '완료', '완료', 'Completed', '벡터화 완료', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RAG_DOCUMENT_STATUS', 'FAILED', '실패', '실패', 'Failed', '벡터화 실패', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;
