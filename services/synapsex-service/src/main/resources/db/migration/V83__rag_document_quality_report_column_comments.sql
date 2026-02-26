-- V83: rag_document_quality_report 컬럼 코멘트 추가
SET search_path TO dwp_aura, public;

COMMENT ON COLUMN dwp_aura.rag_document_quality_report.id IS '품질 리포트 PK';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.doc_id IS '대상 RAG 문서 ID (FK: rag_document.doc_id)';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.run_id IS '리포트 생성 실행(run) 식별자 (없으면 NULL 가능)';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.quality_gate_passed IS '품질게이트 통과 여부';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.input_chunks IS '게이트 적용 전 입력 청크 수';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.final_chunks IS '게이트 적용 후 최종 청크 수';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.article_coverage IS '조항 메타(regulation_article) 커버리지 비율';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.noise_rate IS '잔존 노이즈 비율';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.duplicate_rate IS '중복 비율';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.short_chunk_rate IS '짧은/heading-only 청크 비율';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.removed_empty IS '빈 청크 제거 건수';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.removed_heading_only IS '제목-only 청크 제거 건수';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.removed_duplicate_exact IS '완전중복 제거 건수';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.removed_duplicate_near IS '유사중복 제거 건수';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.missing_required IS '필수 메타 누락 키 목록';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.errors IS '품질게이트 오류 코드 목록';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.raw_report_json IS '원본 품질 리포트 JSON';
COMMENT ON COLUMN dwp_aura.rag_document_quality_report.created_at IS '리포트 생성 시각';
