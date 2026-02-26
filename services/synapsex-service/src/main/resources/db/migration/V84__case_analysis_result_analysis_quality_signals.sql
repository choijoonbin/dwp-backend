-- V84: case_analysis_result 사용자 노출용 분석 신뢰 신호 컬럼 추가

ALTER TABLE dwp_aura.case_analysis_result
    ADD COLUMN IF NOT EXISTS analysis_quality_signals jsonb;

COMMENT ON COLUMN dwp_aura.case_analysis_result.analysis_quality_signals
    IS '사용자 노출용 분석 신뢰 신호 목록(quality_gate_codes 코드명 매핑)';

-- 기존 데이터 보강: quality_gate_codes -> analysis_quality_signals(표시명) 매핑
UPDATE dwp_aura.case_analysis_result r
SET analysis_quality_signals = COALESCE((
    SELECT jsonb_agg(mapped.label)
    FROM (
        SELECT DISTINCT CASE code
            WHEN 'OK' THEN '정상'
            WHEN 'EVIDENCE_MISSING' THEN '근거 데이터 없음'
            WHEN 'RAG_ZERO' THEN '규정 검색 실패'
            WHEN 'INPUT_PARTIAL' THEN '입력 데이터 일부 누락'
            WHEN 'POLICY_CONFLICT' THEN '정책 신호 충돌'
            WHEN 'POLICY_CONFLICT_DETECTED' THEN '정책 신호 충돌'
            WHEN 'POLICY_REEVAL_APPLIED' THEN '정책 재검토 적용'
            WHEN 'RISK_ARTICLE_MISMATCH' THEN '위험유형-조항 불일치'
            WHEN 'SENTENCE_CITATION_MISSING' THEN '문장 근거 미연결'
            WHEN 'EVIDENCE_COVERAGE_LOW' THEN '근거 커버리지 낮음'
            WHEN 'FACT_CONTEXT_PARTIAL' THEN '사실 컨텍스트 일부 누락'
            ELSE NULL
        END AS label
        FROM jsonb_array_elements_text(COALESCE(r.quality_gate_codes, '[]'::jsonb)) AS t(code)
    ) mapped
    WHERE mapped.label IS NOT NULL
), '[]'::jsonb)
WHERE r.analysis_quality_signals IS NULL;

