package com.dwp.services.synapsex.service.rag;

import com.dwp.services.synapsex.dto.rag.RagEvalRunDto;
import com.dwp.services.synapsex.dto.rag.RagEvalRunUpsertRequest;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.entity.RagDocumentQualityReport;
import com.dwp.services.synapsex.entity.RagEvalRun;
import com.dwp.services.synapsex.repository.RagDocumentQualityReportRepository;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import com.dwp.services.synapsex.repository.RagEvalRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagGovernanceService {

    private static final BigDecimal ZERO_RATE_THRESHOLD = new BigDecimal("0.20");
    private static final BigDecimal HIT_AT_K_THRESHOLD = new BigDecimal("0.70");

    private final RagDocumentRepository ragDocumentRepository;
    private final RagDocumentQualityReportRepository qualityReportRepository;
    private final RagEvalRunRepository ragEvalRunRepository;

    @Transactional
    public void persistQualityReport(Long tenantId, Long docId, UUID runId, JsonNode report) {
        if (report == null || report.isNull()) return;

        RagDocument doc = ragDocumentRepository.findByDocIdAndTenantId(docId, tenantId).orElse(null);
        if (doc == null) {
            log.warn("RAG quality_report doc lookup by tenant/doc failed. fallback by docId only. tenantId={} docId={}", tenantId, docId);
            doc = ragDocumentRepository.findById(docId).orElse(null);
        }
        if (doc == null) return;

        boolean gatePassed = readBooleanAny(report, false, "quality_gate_passed", "qualityGatePassed");
        log.info("RAG quality_report parse docId={} runId={} quality_gate_passed={} raw_has_field={}",
                docId, runId, gatePassed, report.has("quality_gate_passed") || report.has("qualityGatePassed"));
        int inputChunks = readInt(report, "input_chunks", 0);
        int finalChunks = readInt(report, "final_chunks", 0);
        BigDecimal articleCoverage = readDecimal(report, "article_coverage", BigDecimal.ZERO);
        BigDecimal noiseRate = readDecimal(report, "noise_rate", BigDecimal.ZERO);
        BigDecimal duplicateRate = readDecimal(report, "duplicate_rate", BigDecimal.ZERO);
        BigDecimal shortChunkRate = readDecimal(report, "short_chunk_rate", BigDecimal.ZERO);
        int removedEmpty = readInt(report, "removed_empty", 0);
        int removedHeadingOnly = readInt(report, "removed_heading_only", 0);
        int removedDuplicateExact = readInt(report, "removed_duplicate_exact", 0);
        int removedDuplicateNear = readInt(report, "removed_duplicate_near", 0);
        JsonNode missingRequired = report.get("missing_required");
        JsonNode errors = report.get("errors");

        RagDocumentQualityReport entity = RagDocumentQualityReport.builder()
                .tenantId(tenantId)
                .docId(docId)
                .runId(runId)
                .qualityGatePassed(gatePassed)
                .inputChunks(inputChunks)
                .finalChunks(finalChunks)
                .articleCoverage(scale4(articleCoverage))
                .noiseRate(scale4(noiseRate))
                .duplicateRate(scale4(duplicateRate))
                .shortChunkRate(scale4(shortChunkRate))
                .removedEmpty(removedEmpty)
                .removedHeadingOnly(removedHeadingOnly)
                .removedDuplicateExact(removedDuplicateExact)
                .removedDuplicateNear(removedDuplicateNear)
                .missingRequired(missingRequired)
                .errors(errors)
                .rawReportJson(report)
                .build();
        qualityReportRepository.save(entity);

        doc.setQualityGatePassed(gatePassed);
        doc.setLastQualityScore(scale4(articleCoverage.subtract(noiseRate.add(duplicateRate).add(shortChunkRate))));
        doc.setLastQualityReportJson(report);
        ragDocumentRepository.save(doc);
        log.info("RAG document quality updated docId={} qualityGatePassed={} lastQualityScore={}",
                docId, doc.getQualityGatePassed(), doc.getLastQualityScore());
    }

    @Transactional
    public RagEvalRunDto saveEvalRun(Long tenantId, RagEvalRunUpsertRequest request) {
        boolean gatePassed = request.getGatePassed() != null
                ? request.getGatePassed()
                : evaluateGate(request.getZeroRate(), request.getHitAtK());
        RagEvalRun entity = RagEvalRun.builder()
                .tenantId(tenantId)
                .runKey(request.getRunKey())
                .zeroRate(scale4(request.getZeroRate()))
                .hitAtK(scale4(request.getHitAtK()))
                .strictHitTop1(scale4(request.getStrictHitTop1()))
                .totalCases(request.getTotalCases())
                .resultJson(request.getResultJson())
                .gatePassed(gatePassed)
                .build();
        entity = ragEvalRunRepository.save(entity);
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public Optional<RagEvalRunDto> getLatestEvalRun(Long tenantId) {
        return ragEvalRunRepository.findFirstByTenantIdOrderByCreatedAtDesc(tenantId).map(this::toDto);
    }

    private RagEvalRunDto toDto(RagEvalRun e) {
        return RagEvalRunDto.builder()
                .id(e.getId())
                .runKey(e.getRunKey())
                .zeroRate(e.getZeroRate())
                .hitAtK(e.getHitAtK())
                .strictHitTop1(e.getStrictHitTop1())
                .totalCases(e.getTotalCases())
                .resultJson(e.getResultJson())
                .gatePassed(e.getGatePassed())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private static boolean evaluateGate(BigDecimal zeroRate, BigDecimal hitAtK) {
        if (zeroRate == null || hitAtK == null) return false;
        return zeroRate.compareTo(ZERO_RATE_THRESHOLD) <= 0 && hitAtK.compareTo(HIT_AT_K_THRESHOLD) >= 0;
    }

    private static BigDecimal readDecimal(JsonNode root, String key, BigDecimal defaultValue) {
        if (root == null || root.get(key) == null || root.get(key).isNull()) return defaultValue;
        try {
            return new BigDecimal(root.get(key).asText());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static int readInt(JsonNode root, String key, int defaultValue) {
        if (root == null || root.get(key) == null || root.get(key).isNull()) return defaultValue;
        try {
            return Integer.parseInt(root.get(key).asText());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean readBoolean(JsonNode root, String key, boolean defaultValue) {
        if (root == null || root.get(key) == null || root.get(key).isNull()) return defaultValue;
        JsonNode v = root.get(key);
        if (v.isBoolean()) return v.asBoolean();
        String s = v.asText();
        return "true".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static boolean readBooleanAny(JsonNode root, boolean defaultValue, String... keys) {
        if (root == null || keys == null || keys.length == 0) return defaultValue;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            if (root.has(key) && !root.get(key).isNull()) {
                return readBoolean(root, key, defaultValue);
            }
        }
        return defaultValue;
    }

    private static BigDecimal scale4(BigDecimal v) {
        if (v == null) return null;
        return v.setScale(4, RoundingMode.HALF_UP);
    }
}
