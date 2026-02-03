package com.dwp.services.synapsex.service.lineage;

import com.dwp.services.synapsex.dto.lineage.LineageResponseDto;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Phase 1 Lineage / Evidence Viewer
 * caseId, docKey, rawEventId, partyId 중 최소 1개로 journey 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageQueryService {

    private static final List<String> JOURNEY_NODES = List.of(
            "SAP Raw Event",
            "Ingestion & Normalization",
            "AI Feature Extraction",
            "Anomaly Scoring",
            "Case Created",
            "Action Executed"
    );

    private final SapRawEventRepository sapRawEventRepository;
    private final IngestionErrorRepository ingestionErrorRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final AgentActionRepository agentActionRepository;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final BpPartyRepository bpPartyRepository;

    @Transactional(readOnly = true)
    public LineageResponseDto findLineage(Long tenantId, LineageQuery query) {
        if (query.getCaseId() == null && query.getDocKey() == null
                && query.getRawEventId() == null && query.getPartyId() == null) {
            throw new IllegalArgumentException("최소 1개의 쿼리 파라미터가 필요합니다: caseId, docKey, rawEventId, partyId");
        }

        Set<Long> rawEventIds = new HashSet<>();
        Set<Long> caseIds = new HashSet<>();
        Set<String> docKeys = new HashSet<>();
        Long partyId = query.getPartyId();

        if (query.getCaseId() != null) {
            agentCaseRepository.findByCaseIdAndTenantId(query.getCaseId(), tenantId)
                    .ifPresent(c -> {
                        caseIds.add(c.getCaseId());
                        if (c.getBukrs() != null && c.getBelnr() != null && c.getGjahr() != null) {
                            docKeys.add(c.getBukrs() + "-" + c.getBelnr() + "-" + c.getGjahr());
                        }
                    });
        }
        if (query.getDocKey() != null && !query.getDocKey().isBlank()) {
            docKeys.add(query.getDocKey());
        }
        if (query.getRawEventId() != null) {
            rawEventIds.add(query.getRawEventId());
        }
        if (query.getPartyId() != null) {
            bpPartyRepository.findById(query.getPartyId())
                    .filter(p -> tenantId.equals(p.getTenantId()))
                    .ifPresent(p -> {
                        if (p.getRawEventId() != null) rawEventIds.add(p.getRawEventId());
                    });
        }

        for (String dk : docKeys) {
            String[] parts = dk.split("-", 3);
            if (parts.length >= 3) {
                fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                                tenantId, parts[0], parts[1], parts[2])
                        .filter(h -> h.getRawEventId() != null)
                        .ifPresent(h -> rawEventIds.add(h.getRawEventId()));
            }
        }

        for (Long cid : caseIds) {
            agentCaseRepository.findById(cid)
                    .filter(c -> tenantId.equals(c.getTenantId()))
                    .filter(c -> c.getBukrs() != null && c.getBelnr() != null && c.getGjahr() != null)
                    .ifPresent(c -> fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                                    tenantId, c.getBukrs(), c.getBelnr(), c.getGjahr())
                            .filter(h -> h.getRawEventId() != null)
                            .ifPresent(h -> rawEventIds.add(h.getRawEventId())));
        }

        Map<String, String> timestamps = buildTimestamps(tenantId, rawEventIds, caseIds);
        LineageResponseDto.EvidencePanelDto evidencePanel = buildEvidencePanel(
                tenantId, rawEventIds, caseIds, query.getCaseId());

        LineageResponseDto.AsOfSnapshotDto asOfSnapshot = null;
        LineageResponseDto.AsOfSnapshotDto currentSnapshot = null;
        Boolean timeTravelDegraded = null;

        if (query.getAsOf() != null) {
            timeTravelDegraded = true;
            currentSnapshot = LineageResponseDto.AsOfSnapshotDto.builder()
                    .asOfTimestamp(Instant.now().toString())
                    .partySnapshot(partyId != null ? buildPartySnapshot(tenantId, partyId) : null)
                    .docSnapshot(!docKeys.isEmpty() ? buildDocSnapshot(tenantId, docKeys.iterator().next()) : null)
                    .build();
            asOfSnapshot = LineageResponseDto.AsOfSnapshotDto.builder()
                    .asOfTimestamp(query.getAsOf().toString())
                    .partySnapshot(partyId != null ? buildPartySnapshot(tenantId, partyId) : null)
                    .docSnapshot(!docKeys.isEmpty() ? buildDocSnapshot(tenantId, docKeys.iterator().next()) : null)
                    .build();
        }

        return LineageResponseDto.builder()
                .journeyNodes(JOURNEY_NODES)
                .timestamps(timestamps)
                .evidencePanel(evidencePanel)
                .asOfSnapshot(asOfSnapshot)
                .currentSnapshot(currentSnapshot)
                .timeTravelDegraded(timeTravelDegraded)
                .build();
    }

    private Map<String, String> buildTimestamps(Long tenantId, Set<Long> rawEventIds, Set<Long> caseIds) {
        Map<String, String> ts = new LinkedHashMap<>();

        if (!rawEventIds.isEmpty()) {
            sapRawEventRepository.findAllById(rawEventIds).stream()
                    .filter(e -> tenantId.equals(e.getTenantId()))
                    .map(SapRawEvent::getCreatedAt)
                    .filter(Objects::nonNull)
                    .min(Instant::compareTo)
                    .ifPresent(at -> ts.put("SAP Raw Event", at.toString()));
        }

        Optional<Instant> ingestionAt = rawEventIds.stream()
                .flatMap(reid -> ingestionErrorRepository.findByRawEventId(reid).stream())
                .map(IngestionError::getCreatedAt)
                .min(Instant::compareTo);
        ingestionAt.ifPresent(at -> ts.put("Ingestion & Normalization", at.toString()));

        ts.put("AI Feature Extraction", null);
        ts.put("Anomaly Scoring", null);

        Optional<Instant> caseAt = caseIds.stream()
                .flatMap(cid -> agentCaseRepository.findById(cid).stream())
                .filter(c -> tenantId.equals(c.getTenantId()))
                .map(AgentCase::getDetectedAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo);
        caseAt.ifPresent(at -> ts.put("Case Created", at.toString()));

        Optional<Instant> actionAt = caseIds.stream()
                .flatMap(cid -> agentActionRepository.findByTenantIdAndCaseId(tenantId, cid).stream())
                .map(AgentAction::getExecutedAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo);
        actionAt.ifPresent(at -> ts.put("Action Executed", at.toString()));

        return ts;
    }

    private LineageResponseDto.EvidencePanelDto buildEvidencePanel(
            Long tenantId, Set<Long> rawEventIds, Set<Long> caseIds, Long primaryCaseId) {

        LineageResponseDto.IngestionErrorsSummaryDto ingestionSummary = null;
        if (!rawEventIds.isEmpty()) {
            Long reid = rawEventIds.iterator().next();
            List<IngestionError> errors = ingestionErrorRepository.findByRawEventId(reid);
            ingestionSummary = LineageResponseDto.IngestionErrorsSummaryDto.builder()
                    .rawEventId(reid)
                    .errorCount(errors.size())
                    .errors(errors.stream()
                            .limit(20)
                            .map(e -> LineageResponseDto.IngestionErrorItemDto.builder()
                                    .id(e.getId())
                                    .errorCode(e.getErrorCode())
                                    .errorDetail(e.getErrorDetail())
                                    .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                                    .build())
                            .toList())
                    .build();
        }

        LineageResponseDto.CaseEvidenceDto caseEvidence;
        if (primaryCaseId != null) {
            caseEvidence = agentCaseRepository.findByCaseIdAndTenantId(primaryCaseId, tenantId)
                    .map(c -> LineageResponseDto.CaseEvidenceDto.builder()
                            .caseId(c.getCaseId())
                            .evidenceJson(c.getEvidenceJson())
                            .ragRefsJson(c.getRagRefsJson())
                            .build())
                    .orElse(null);
        } else if (!caseIds.isEmpty()) {
            caseEvidence = agentCaseRepository.findById(caseIds.iterator().next())
                    .filter(c -> tenantId.equals(c.getTenantId()))
                    .map(c -> LineageResponseDto.CaseEvidenceDto.builder()
                            .caseId(c.getCaseId())
                            .evidenceJson(c.getEvidenceJson())
                            .ragRefsJson(c.getRagRefsJson())
                            .build())
                    .orElse(null);
        } else {
            caseEvidence = null;
        }

        LineageResponseDto.StatisticalEvidenceDto statisticalEvidence =
                LineageResponseDto.StatisticalEvidenceDto.builder()
                        .description("Statistical evidence (mock)")
                        .metrics(Map.of("anomalyScore", 0.0, "confidence", 0.0))
                        .build();

        return LineageResponseDto.EvidencePanelDto.builder()
                .ingestionErrors(ingestionSummary)
                .caseEvidence(caseEvidence)
                .statisticalEvidence(statisticalEvidence)
                .build();
    }

    private Object buildPartySnapshot(Long tenantId, Long partyId) {
        return bpPartyRepository.findById(partyId)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .map(p -> Map.of(
                        "partyId", p.getPartyId(),
                        "partyCode", p.getPartyCode() != null ? p.getPartyCode() : "",
                        "nameDisplay", p.getNameDisplay() != null ? p.getNameDisplay() : "",
                        "lastChangeTs", p.getLastChangeTs() != null ? p.getLastChangeTs().toString() : ""))
                .orElse(null);
    }

    private Object buildDocSnapshot(Long tenantId, String docKey) {
        String[] parts = docKey.split("-", 3);
        if (parts.length < 3) return null;
        return fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                        tenantId, parts[0], parts[1], parts[2])
                .map(h -> Map.of(
                        "bukrs", h.getBukrs(),
                        "belnr", h.getBelnr(),
                        "gjahr", h.getGjahr(),
                        "lastChangeTs", h.getLastChangeTs() != null ? h.getLastChangeTs().toString() : ""))
                .orElse(null);
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LineageQuery {
        private Long caseId;
        private String docKey;
        private Long rawEventId;
        private Long partyId;
        private Instant asOf;
    }
}
