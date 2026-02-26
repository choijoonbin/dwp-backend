package com.dwp.services.synapsex.service.analysis;

import com.dwp.services.synapsex.dto.case_.ShadowCompareDto;
import com.dwp.services.synapsex.entity.CaseAnalysisResult;
import com.dwp.services.synapsex.entity.CaseAnalysisRun;
import com.dwp.services.synapsex.repository.CaseAnalysisResultRepository;
import com.dwp.services.synapsex.repository.CaseAnalysisRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShadowCompareService {

    private final CaseAnalysisRunRepository caseAnalysisRunRepository;
    private final CaseAnalysisResultRepository caseAnalysisResultRepository;

    @Transactional(readOnly = true)
    public ShadowCompareDto compare(Long tenantId, Long caseId, UUID targetRunId) {
        List<CaseAnalysisRun> runs = caseAnalysisRunRepository.findByTenantIdAndCaseIdOrderByStartedAtDesc(tenantId, caseId);
        if (runs.isEmpty()) {
            return ShadowCompareDto.builder()
                    .caseId(caseId)
                    .runId(targetRunId)
                    .build();
        }

        CaseAnalysisRun primaryRun = resolvePrimaryRun(runs, targetRunId);
        CaseAnalysisRun shadowRun = resolveShadowRun(runs, primaryRun);

        CaseAnalysisResult primaryResult = (primaryRun != null && primaryRun.getRunId() != null)
                ? caseAnalysisResultRepository.findByRunId(primaryRun.getRunId()).orElse(null)
                : null;
        CaseAnalysisResult shadowResult = (shadowRun != null && shadowRun.getRunId() != null)
                ? caseAnalysisResultRepository.findByRunId(shadowRun.getRunId()).orElse(null)
                : null;

        String primaryVerdict = normalizeVerdict(primaryResult);
        String shadowVerdict = normalizeVerdict(shadowResult);

        return ShadowCompareDto.builder()
                .caseId(caseId)
                .runId(primaryRun != null ? primaryRun.getRunId() : targetRunId)
                .primaryAgent(agentLabel(primaryRun))
                .shadowAgent(agentLabel(shadowRun))
                .verdictMatch(primaryVerdict != null && shadowVerdict != null ? primaryVerdict.equals(shadowVerdict) : null)
                .scoreDelta(delta(
                        primaryResult != null ? primaryResult.getScore() : null,
                        shadowResult != null ? shadowResult.getScore() : null
                ))
                .citationCoverageDelta(delta(
                        primaryResult != null ? primaryResult.getGroundingCoverageRatio() : null,
                        shadowResult != null ? shadowResult.getGroundingCoverageRatio() : null
                ))
                .holdReasonDelta(diffHoldSignals(primaryResult, shadowResult))
                .build();
    }

    private CaseAnalysisRun resolvePrimaryRun(List<CaseAnalysisRun> runs, UUID targetRunId) {
        if (targetRunId == null) return runs.get(0);
        for (CaseAnalysisRun run : runs) {
            if (run != null && targetRunId.equals(run.getRunId())) return run;
        }
        return runs.get(0);
    }

    private CaseAnalysisRun resolveShadowRun(List<CaseAnalysisRun> runs, CaseAnalysisRun primaryRun) {
        for (CaseAnalysisRun run : runs) {
            if (run == null) continue;
            if (primaryRun == null || !run.getRunId().equals(primaryRun.getRunId())) return run;
        }
        return null;
    }

    private static String normalizeVerdict(CaseAnalysisResult result) {
        if (result == null || result.getSeverity() == null) return null;
        return result.getSeverity().trim().toUpperCase(Locale.ROOT);
    }

    private static String agentLabel(CaseAnalysisRun run) {
        if (run == null) return null;
        return (run.getRequestedBy() != null ? run.getRequestedBy() : "UNKNOWN")
                + ":"
                + (run.getMode() != null ? run.getMode() : "UNKNOWN");
    }

    private static BigDecimal delta(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.subtract(b);
    }

    private static String diffHoldSignals(CaseAnalysisResult primary, CaseAnalysisResult shadow) {
        Set<String> p = extractHoldSignals(primary);
        Set<String> s = extractHoldSignals(shadow);
        if (p.isEmpty() && s.isEmpty()) return null;

        List<String> added = new ArrayList<>();
        for (String code : p) {
            if (!s.contains(code)) added.add(code);
        }
        List<String> removed = new ArrayList<>();
        for (String code : s) {
            if (!p.contains(code)) removed.add(code);
        }
        if (added.isEmpty() && removed.isEmpty()) return "UNCHANGED";
        return "added=" + added + ", removed=" + removed;
    }

    private static Set<String> extractHoldSignals(CaseAnalysisResult result) {
        Set<String> out = new LinkedHashSet<>();
        if (result == null || result.getQualityGateCodes() == null || !result.getQualityGateCodes().isArray()) return out;
        result.getQualityGateCodes().forEach(n -> {
            if (n != null && n.isTextual()) {
                String code = n.asText();
                if (code != null && !code.isBlank()) out.add(code.trim());
            }
        });
        return out;
    }
}
