-- V45: RAG 공통 코드 등록 (Phase 6+ — Aura 정의 정렬)
-- 목적: RAG_DOC_TYPE(문서 유형), RAG_PROC_STATUS(처리 상태)를 sys_codes에 등록.
--       rag_document.doc_type / rag_document.status 는 synapsex에서 CHECK 제약으로 동일 값 집합 참조.

SET search_path TO public;

-- 1. RAG_DOC_TYPE: 문서 유형 (REGULATION, MANUAL, POLICY, GENERAL)
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('RAG_DOC_TYPE', 'RAG 문서 유형', 'RAG 문서 분류 (규정, 매뉴얼, 정책, 일반)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('RAG_DOC_TYPE', 'REGULATION', '규정', '규정', 'Regulation', '규정/법규 문서', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RAG_DOC_TYPE', 'MANUAL', '매뉴얼', '매뉴얼', 'Manual', '매뉴얼/가이드 문서', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RAG_DOC_TYPE', 'POLICY', '정책', '정책', 'Policy', '정책 문서', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RAG_DOC_TYPE', 'GENERAL', '일반', '일반', 'General', '일반 문서', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 2. RAG_PROC_STATUS: 처리 상태 (READY, PROCESSING, COMPLETED, FAILED) — Aura 콜백/벡터화 상태와 동일
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('RAG_PROC_STATUS', 'RAG 처리 상태', 'RAG 문서 업로드·벡터화 상태 (READY, PROCESSING, COMPLETED, FAILED)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('RAG_PROC_STATUS', 'READY', '대기', '대기', 'Ready', '업로드 완료, 벡터화 대기', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RAG_PROC_STATUS', 'PROCESSING', '처리중', '처리중', 'Processing', 'Aura 벡터화 진행 중', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RAG_PROC_STATUS', 'COMPLETED', '완료', '완료', 'Completed', '벡터화 완료', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RAG_PROC_STATUS', 'FAILED', '실패', '실패', 'Failed', '벡터화 실패', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;
