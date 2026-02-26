-- V82: case_analysis_result 컬럼 순서 재정렬 및 코멘트 추가
-- 순서: 식별/범위 → 핵심 결과 → 운영/요약 → 근거(JSON) → 품질/감사

SET search_path TO dwp_aura, public;

-- 1) 새 테이블 생성 (컬럼 순서: 중요도·연관성)
CREATE TABLE dwp_aura.case_analysis_result_new (
    run_id                      UUID NOT NULL,
    tenant_id                   BIGINT NOT NULL,
    score                       NUMERIC(5,2),
    severity                    VARCHAR(20),
    reason_text                 TEXT,
    risk_score                  INTEGER,
    violation_clause            TEXT,
    reasoning_summary           TEXT,
    recommended_action          TEXT,
    confidence_json             JSONB,
    evidence_json               JSONB,
    similar_json                JSONB,
    rag_refs_json               JSONB,
    evidence_map_json           JSONB,
    sentence_citation_map       JSONB,
    analysis_score_breakdown    JSONB,
    quality_gate_codes          JSONB,
    grounding_coverage_ratio    NUMERIC(5,4),
    ungrounded_claim_sentences  INTEGER,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT case_analysis_result_new_pkey PRIMARY KEY (run_id),
    CONSTRAINT case_analysis_result_new_run_id_fkey
        FOREIGN KEY (run_id) REFERENCES dwp_aura.case_analysis_run(run_id) ON DELETE CASCADE
);

-- 2) 데이터 복사
INSERT INTO dwp_aura.case_analysis_result_new (
    run_id, tenant_id, score, severity, reason_text,
    risk_score, violation_clause, reasoning_summary, recommended_action,
    confidence_json, evidence_json, similar_json, rag_refs_json, evidence_map_json,
    sentence_citation_map, analysis_score_breakdown, quality_gate_codes,
    grounding_coverage_ratio, ungrounded_claim_sentences, created_at
)
SELECT
    run_id, tenant_id, score, severity, reason_text,
    risk_score, violation_clause, reasoning_summary, recommended_action,
    confidence_json, evidence_json, similar_json, rag_refs_json, evidence_map_json,
    sentence_citation_map, analysis_score_breakdown, quality_gate_codes,
    grounding_coverage_ratio, ungrounded_claim_sentences, created_at
FROM dwp_aura.case_analysis_result;

-- 3) 기존 테이블 교체
DROP TABLE dwp_aura.case_analysis_result;
ALTER TABLE dwp_aura.case_analysis_result_new RENAME TO case_analysis_result;

-- 4) 인덱스 재생성
CREATE INDEX IF NOT EXISTS ix_case_analysis_result_tenant_created
    ON dwp_aura.case_analysis_result (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_case_analysis_result_created_at
    ON dwp_aura.case_analysis_result (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_case_analysis_result_quality_gate_codes
    ON dwp_aura.case_analysis_result USING GIN (quality_gate_codes);

-- 5) 테이블·컬럼 코멘트
COMMENT ON TABLE dwp_aura.case_analysis_result IS 'Phase2: 분석 결과 (점수/근거/유사/RAG/권고). run당 1건.';

COMMENT ON COLUMN dwp_aura.case_analysis_result.run_id IS '분석 실행 식별자 (1회 분석 단위 UUID)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.tenant_id IS '테넌트 식별자 (데이터 격리 키)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.score IS '최종 위험 점수 (정규화 점수, 보통 0~1 또는 UI용 스케일 기준)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.severity IS '최종 위험 등급 (LOW/MEDIUM/HIGH/CRITICAL)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.reason_text IS '사용자 노출용 최종 판단 문장';
COMMENT ON COLUMN dwp_aura.case_analysis_result.risk_score IS '운영 집계용 정수 위험점수 (예: 0~100)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.violation_clause IS '주요 위반 조항 요약 문자열';
COMMENT ON COLUMN dwp_aura.case_analysis_result.reasoning_summary IS '판단 근거 요약 (감사용)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.recommended_action IS '권고 조치 요약';
COMMENT ON COLUMN dwp_aura.case_analysis_result.confidence_json IS '점수 구성요소/신뢰도 상세 (JSON)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.evidence_json IS '분석에 사용한 근거 목록 (JSON)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.similar_json IS '유사 케이스/유사 패턴 정보 (JSON)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.rag_refs_json IS 'RAG 검색 참조/인용 원본 (JSON)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.evidence_map_json IS '전표 항목↔근거 매핑 (JSON)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.sentence_citation_map IS '결론 문장별 citation 연결 결과 (JSON)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.analysis_score_breakdown IS '분석 점수 분해 상세 (JSON)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.quality_gate_codes IS '품질게이트 코드 목록 (JSON/배열)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.grounding_coverage_ratio IS '결론 문장 근거 연결률 (0~1)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.ungrounded_claim_sentences IS '근거 미연결 주장 문장 수';
COMMENT ON COLUMN dwp_aura.case_analysis_result.created_at IS '분석 결과 생성 시각';
