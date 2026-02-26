-- V86: 사용자 노출 신호명 변경
-- '정책 신호 충돌' -> '규정 판단 불일치'

UPDATE dwp_aura.case_analysis_result r
SET analysis_quality_signals = sub.mapped
FROM (
    SELECT
        run_id,
        jsonb_agg(
            CASE
                WHEN e.value = '정책 신호 충돌' THEN '규정 판단 불일치'
                ELSE e.value
            END
            ORDER BY e.ordinality
        ) AS mapped
    FROM dwp_aura.case_analysis_result t
    CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(t.analysis_quality_signals, '[]'::jsonb))
        WITH ORDINALITY AS e(value, ordinality)
    GROUP BY run_id
) sub
WHERE r.run_id = sub.run_id;

