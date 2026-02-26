package com.dwp.services.synapsex.service.analysis;

import com.dwp.services.synapsex.dto.analysis.AnalysisReplayGateRunRequest;
import com.dwp.services.synapsex.dto.analysis.AnalysisReplayGateRunResponse;
import com.dwp.services.synapsex.dto.analysis.AuraQualityMetricsResponse;
import com.dwp.services.synapsex.entity.AnalysisReplayGateRun;
import com.dwp.services.synapsex.repository.AnalysisReplayGateRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuraQualityMetricsService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AnalysisReplayGateRunRepository analysisReplayGateRunRepository;

    @Transactional(readOnly = true)
    public AuraQualityMetricsResponse getQualityMetrics(Long tenantId, Instant from, Instant to) {
        Instant toTs = to != null ? to : Instant.now();
        Instant fromTs = from != null ? from : toTs.minusSeconds(30L * 24 * 60 * 60);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("from", Timestamp.from(fromTs))
                .addValue("to", Timestamp.from(toTs));
        String sql = """
                SELECT
                  COUNT(*) AS total_count,
                  SUM(CASE WHEN sentence_citation_map IS NULL OR sentence_citation_map = '{}'::jsonb OR sentence_citation_map = '[]'::jsonb THEN 1 ELSE 0 END) AS sentence_citation_missing_count,
                  SUM(CASE WHEN grounding_coverage_ratio IS NOT NULL AND grounding_coverage_ratio < 0.70 THEN 1 ELSE 0 END) AS evidence_coverage_low_count,
                  SUM(CASE WHEN quality_gate_codes IS NOT NULL AND jsonb_exists(quality_gate_codes, 'POLICY_REEVAL_APPLIED') THEN 1 ELSE 0 END) AS policy_reeval_applied_count,
                  SUM(CASE WHEN quality_gate_codes IS NOT NULL AND jsonb_exists(quality_gate_codes, 'RAG_ZERO') THEN 1 ELSE 0 END) AS rag_zero_count
                FROM dwp_aura.case_analysis_result r
                JOIN dwp_aura.case_analysis_run ar ON ar.run_id = r.run_id
                WHERE ar.tenant_id = :tenantId
                  AND r.created_at BETWEEN :from AND :to
                """;
        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(sql, p);
        long total = toLong(row.get("total_count"));
        long missing = toLong(row.get("sentence_citation_missing_count"));
        long lowCoverage = toLong(row.get("evidence_coverage_low_count"));
        long policyReeval = toLong(row.get("policy_reeval_applied_count"));
        long ragZero = toLong(row.get("rag_zero_count"));
        return AuraQualityMetricsResponse.builder()
                .from(fromTs)
                .to(toTs)
                .totalCount(total)
                .sentenceCitationMissingCount(missing)
                .evidenceCoverageLowCount(lowCoverage)
                .policyReevalAppliedCount(policyReeval)
                .ragZeroCount(ragZero)
                .sentenceCitationMissingRatio(ratio(missing, total))
                .evidenceCoverageLowRatio(ratio(lowCoverage, total))
                .policyReevalAppliedRatio(ratio(policyReeval, total))
                .ragZeroRatio(ratio(ragZero, total))
                .build();
    }

    @Transactional
    public AnalysisReplayGateRunResponse saveReplayGateRun(Long tenantId, AnalysisReplayGateRunRequest req) {
        AnalysisReplayGateRun run = AnalysisReplayGateRun.builder()
                .tenantId(tenantId)
                .runKey(req.getRunKey())
                .gatePassed(req.getGatePassed())
                .resultJson(req.getResultJson())
                .build();
        run = analysisReplayGateRunRepository.save(run);
        return AnalysisReplayGateRunResponse.builder()
                .id(run.getId())
                .runKey(run.getRunKey())
                .gatePassed(run.getGatePassed())
                .resultJson(run.getResultJson())
                .createdAt(run.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisReplayGateRunResponse> getLatestReplayGateRun(Long tenantId) {
        return analysisReplayGateRunRepository.findFirstByTenantIdOrderByCreatedAtDesc(tenantId)
                .map(run -> AnalysisReplayGateRunResponse.builder()
                        .id(run.getId())
                        .runKey(run.getRunKey())
                        .gatePassed(run.getGatePassed())
                        .resultJson(run.getResultJson())
                        .createdAt(run.getCreatedAt())
                        .build());
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }

    private static BigDecimal ratio(long part, long total) {
        if (total <= 0) return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }
}
