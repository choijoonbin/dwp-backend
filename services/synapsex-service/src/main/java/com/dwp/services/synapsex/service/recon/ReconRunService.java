package com.dwp.services.synapsex.service.recon;

import com.dwp.services.synapsex.dto.recon.ReconRunDetailDto;
import com.dwp.services.synapsex.dto.recon.ReconRunListDto;
import com.dwp.services.synapsex.dto.recon.StartReconRequest;
import com.dwp.services.synapsex.entity.ReconResult;
import com.dwp.services.synapsex.entity.ReconRun;
import com.dwp.services.synapsex.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Phase 4 Reconciliation 실행 서비스
 * Default rules: fi_doc_item vs fi_open_item, cleared flags, orphan counts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconRunService {

    private final ReconRunRepository reconRunRepository;
    private final ReconResultRepository reconResultRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final IngestionErrorRepository ingestionErrorRepository;

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    @Transactional
    public ReconRunDetailDto startRecon(Long tenantId, StartReconRequest request) {
        ReconRun run = ReconRun.builder()
                .tenantId(tenantId)
                .runType(request.getRunType())
                .status("RUNNING")
                .build();
        run = reconRunRepository.save(run);

        try {
            List<ReconResult> results = executeRecon(tenantId, run.getRunId(), request.getRunType());
            reconResultRepository.saveAll(results);

            ObjectNode summary = MAPPER.createObjectNode();
            long passCount = results.stream().filter(r -> "PASS".equals(r.getStatus())).count();
            long failCount = results.size() - passCount;
            summary.put("total", results.size());
            summary.put("pass", passCount);
            summary.put("fail", failCount);

            run.setStatus("COMPLETED");
            run.setEndedAt(Instant.now());
            run.setSummaryJson(summary);
            reconRunRepository.save(run);

            return toDetailDto(run, results);
        } catch (Exception e) {
            log.warn("Recon failed: {}", e.getMessage());
            run.setStatus("FAILED");
            run.setEndedAt(Instant.now());
            run.setSummaryJson(MAPPER.createObjectNode().put("error", e.getMessage()));
            reconRunRepository.save(run);
            return toDetailDto(run, List.of());
        }
    }

    private List<ReconResult> executeRecon(Long tenantId, Long runId, String runType) {
        List<ReconResult> results = new ArrayList<>();

        if ("DOC_OPENITEM_MATCH".equals(runType)) {
            var allDocItems = fiDocItemRepository.findByTenantId(tenantId);

            for (var item : allDocItems) {
                String key = item.getBukrs() + "-" + item.getBelnr() + "-" + item.getGjahr() + "-" + item.getBuzei();
                var openOpt = fiOpenItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrAndBuzei(
                        tenantId, item.getBukrs(), item.getBelnr(), item.getGjahr(), item.getBuzei());

                if (openOpt.isEmpty()) {
                    results.add(ReconResult.builder()
                            .tenantId(tenantId).runId(runId)
                            .resourceType("DOC_OPENITEM").resourceKey(key)
                            .status("FAIL")
                            .detailJson(MAPPER.createObjectNode().put("reason", "no_open_item"))
                            .build());
                } else {
                    var open = openOpt.get();
                    BigDecimal docAmt = item.getWrbtr() != null ? item.getWrbtr() : BigDecimal.ZERO;
                    int cmp = docAmt.compareTo(open.getOpenAmount());
                    results.add(ReconResult.builder()
                            .tenantId(tenantId).runId(runId)
                            .resourceType("DOC_OPENITEM").resourceKey(key)
                            .status(cmp == 0 ? "PASS" : "FAIL")
                            .detailJson(MAPPER.createObjectNode()
                                    .put("docAmount", docAmt.toString())
                                    .put("openAmount", open.getOpenAmount().toString()))
                            .build());
                }
            }

            // Orphan: open_items without doc_item
            var allOpen = fiOpenItemRepository.findAll().stream()
                    .filter(o -> tenantId.equals(o.getTenantId()))
                    .toList();
            for (var open : allOpen) {
                String key = open.getBukrs() + "-" + open.getBelnr() + "-" + open.getGjahr() + "-" + open.getBuzei();
                if (allDocItems.stream().noneMatch(d ->
                        d.getBukrs().equals(open.getBukrs()) && d.getBelnr().equals(open.getBelnr()) &&
                                d.getGjahr().equals(open.getGjahr()) && d.getBuzei().equals(open.getBuzei()))) {
                    results.add(ReconResult.builder()
                            .tenantId(tenantId).runId(runId)
                            .resourceType("ORPHAN_OPENITEM").resourceKey(key)
                            .status("FAIL")
                            .detailJson(MAPPER.createObjectNode().put("reason", "no_doc_item"))
                            .build());
                }
            }
        } else {
            // Generic: add orphan raw_event / ingestion_errors count
            long ingestionErrorCount = ingestionErrorRepository.countByTenantId(tenantId);
            results.add(ReconResult.builder()
                    .tenantId(tenantId).runId(runId)
                    .resourceType("INGESTION_ERRORS").resourceKey("total")
                    .status(ingestionErrorCount == 0 ? "PASS" : "FAIL")
                    .detailJson(MAPPER.createObjectNode().put("count", ingestionErrorCount))
                    .build());
        }

        return results;
    }

    @Transactional(readOnly = true)
    public com.dwp.services.synapsex.dto.common.PageResponse<ReconRunListDto> listRuns(
            Long tenantId, String runType, int page, int size, String sort) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        org.springframework.data.domain.Sort sortObj = parseSort(sort, "startedAt");
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(p, s, sortObj);

        var pageResult = runType != null && !runType.isBlank()
                ? reconRunRepository.findByTenantIdAndRunTypeOrderByStartedAtDesc(tenantId, runType, pageable)
                : reconRunRepository.findByTenantIdOrderByStartedAtDesc(tenantId, pageable);

        List<ReconRunListDto> items = pageResult.getContent().stream().map(this::toListDto).collect(Collectors.toList());
        return com.dwp.services.synapsex.dto.common.PageResponse.of(items, pageResult.getTotalElements(), p, s);
    }

    private org.springframework.data.domain.Sort parseSort(String sort, String defaultField) {
        if (sort == null || sort.isBlank()) {
            return org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, defaultField);
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        boolean asc = parts.length >= 2 && "asc".equalsIgnoreCase(parts[parts.length - 1].trim());
        return org.springframework.data.domain.Sort.by(
                asc ? org.springframework.data.domain.Sort.Direction.ASC : org.springframework.data.domain.Sort.Direction.DESC, field);
    }

    @Transactional(readOnly = true)
    public Optional<ReconRunDetailDto> getRunDetail(Long tenantId, Long runId) {
        return reconRunRepository.findById(runId)
                .filter(r -> tenantId.equals(r.getTenantId()))
                .map(run -> {
                    List<ReconResult> results = reconResultRepository.findByRunIdOrderByResultIdAsc(run.getRunId());
                    return toDetailDto(run, results);
                });
    }

    private ReconRunListDto toListDto(ReconRun r) {
        return ReconRunListDto.builder()
                .runId(r.getRunId())
                .runType(r.getRunType())
                .startedAt(r.getStartedAt())
                .endedAt(r.getEndedAt())
                .status(r.getStatus())
                .summaryJson(r.getSummaryJson())
                .build();
    }

    private ReconRunDetailDto toDetailDto(ReconRun r, List<ReconResult> results) {
        return ReconRunDetailDto.builder()
                .runId(r.getRunId())
                .runType(r.getRunType())
                .startedAt(r.getStartedAt())
                .endedAt(r.getEndedAt())
                .status(r.getStatus())
                .summaryJson(r.getSummaryJson())
                .results(results.stream()
                        .map(res -> ReconRunDetailDto.ReconResultDto.builder()
                                .resultId(res.getResultId())
                                .resourceType(res.getResourceType())
                                .resourceKey(res.getResourceKey())
                                .status(res.getStatus())
                                .detailJson(res.getDetailJson())
                                .build())
                        .toList())
                .build();
    }
}
