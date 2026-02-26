-- V85: analysis_quality_signals 운영 인덱스 및 정규화 보강

CREATE INDEX IF NOT EXISTS ix_case_analysis_result_analysis_quality_signals
    ON dwp_aura.case_analysis_result USING GIN (analysis_quality_signals);

-- quality_gate_codes 기반으로 표시 신호 재계산 (신규 코드: 기타(<코드>) 보존)
WITH labels AS (
    SELECT
        r.run_id,
        CASE t.code
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
            ELSE '기타(' || t.code || ')'
        END AS label,
        CASE t.code
            WHEN 'EVIDENCE_MISSING' THEN 1
            WHEN 'RAG_ZERO' THEN 2
            WHEN 'INPUT_PARTIAL' THEN 3
            WHEN 'SENTENCE_CITATION_MISSING' THEN 4
            WHEN 'EVIDENCE_COVERAGE_LOW' THEN 5
            WHEN 'RISK_ARTICLE_MISMATCH' THEN 6
            WHEN 'POLICY_CONFLICT' THEN 7
            WHEN 'POLICY_CONFLICT_DETECTED' THEN 7
            WHEN 'POLICY_REEVAL_APPLIED' THEN 8
            WHEN 'FACT_CONTEXT_PARTIAL' THEN 9
            WHEN 'OK' THEN 10
            ELSE 99
        END AS priority
    FROM dwp_aura.case_analysis_result r
    CROSS JOIN LATERAL (
        SELECT code
        FROM jsonb_array_elements_text(COALESCE(r.quality_gate_codes, '[]'::jsonb)) AS e(code)
    ) t
),
dedup AS (
    SELECT DISTINCT run_id, label, priority
    FROM labels
),
agg AS (
    SELECT
        run_id,
        jsonb_agg(label ORDER BY priority, label) AS ordered_labels
    FROM dedup
    GROUP BY run_id
),
normalized AS (
    SELECT
        a.run_id,
        CASE
            WHEN a.ordered_labels IS NULL OR jsonb_array_length(a.ordered_labels) = 0
                THEN '["정상"]'::jsonb
            WHEN jsonb_array_length(a.ordered_labels) > 1
                THEN COALESCE(
                    (
                        SELECT jsonb_agg(x.label ORDER BY x.priority, x.label)
                        FROM (
                            SELECT DISTINCT
                                e.value AS label,
                                CASE e.value
                                    WHEN '근거 데이터 없음' THEN 1
                                    WHEN '규정 검색 실패' THEN 2
                                    WHEN '입력 데이터 일부 누락' THEN 3
                                    WHEN '문장 근거 미연결' THEN 4
                                    WHEN '근거 커버리지 낮음' THEN 5
                                    WHEN '위험유형-조항 불일치' THEN 6
                                    WHEN '정책 신호 충돌' THEN 7
                                    WHEN '정책 재검토 적용' THEN 8
                                    WHEN '사실 컨텍스트 일부 누락' THEN 9
                                    WHEN '정상' THEN 10
                                    ELSE 99
                                END AS priority
                            FROM jsonb_array_elements_text(a.ordered_labels) e(value)
                            WHERE e.value <> '정상'
                        ) x
                    ),
                    '["정상"]'::jsonb
                )
            ELSE a.ordered_labels
        END AS final_labels
    FROM agg a
)
UPDATE dwp_aura.case_analysis_result r
SET analysis_quality_signals = n.final_labels
FROM normalized n
WHERE r.run_id = n.run_id;

-- quality_gate_codes가 비어있는 과거 데이터는 '정상' 기본값으로 보정
UPDATE dwp_aura.case_analysis_result
SET analysis_quality_signals = '["정상"]'::jsonb
WHERE analysis_quality_signals IS NULL
   OR analysis_quality_signals = '[]'::jsonb;

