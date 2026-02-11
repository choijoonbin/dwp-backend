package com.dwp.services.synapsex.service.lineage;

import com.dwp.services.synapsex.dto.lineage.LineageEdgeDto;
import com.dwp.services.synapsex.dto.lineage.LineageGraphDto;
import com.dwp.services.synapsex.dto.lineage.LineageNodeDto;
import com.dwp.services.synapsex.dto.lineage.LineageResponseDto;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import com.dwp.services.synapsex.util.DocKeyUtil;
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

    private static final String RESOURCE_TYPE_AGENT_CASE = "AGENT_CASE";

    private final SapRawEventRepository sapRawEventRepository;
    private final IngestionErrorRepository ingestionErrorRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final AgentActionRepository agentActionRepository;
    private final AgentActivityLogRepository agentActivityLogRepository;
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

    /**
     * Phase 3: 전표 기준 계보 그래프. resourceKey = docKey (bukrs-belnr-gjahr).
     * tenant_id 모든 조회에 적용. Source -> Agent -> Case -> Action 구조로 반환.
     */
    @Transactional(readOnly = true)
    public LineageGraphDto findLineageGraphByResourceKey(Long tenantId, String resourceKey) {
        DocKeyUtil.ParsedDocKey parsed = DocKeyUtil.parse(resourceKey);
        if (parsed == null) {
            throw new IllegalArgumentException("resourceKey 형식이 올바르지 않습니다. (예: bukrs-belnr-gjahr)");
        }
        String bukrs = parsed.getBukrs();
        String belnr = parsed.getBelnr();
        String gjahr = parsed.getGjahr();

        List<LineageNodeDto> nodes = new ArrayList<>();
        List<LineageEdgeDto> edges = new ArrayList<>();

        String sourceId = "source-" + resourceKey;
        Optional<FiDocHeader> headerOpt = fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, bukrs, belnr, gjahr);
        Instant sourceOccurredAt = null;
        String rawEventIdRef = null;
        if (headerOpt.isPresent()) {
            FiDocHeader h = headerOpt.get();
            sourceOccurredAt = h.getCreatedAt() != null ? h.getCreatedAt() : h.getLastChangeTs();
            if (h.getRawEventId() != null) rawEventIdRef = String.valueOf(h.getRawEventId());
        }
        nodes.add(LineageNodeDto.builder()
                .id(sourceId)
                .type("SOURCE")
                .label("Document " + resourceKey)
                .refId(rawEventIdRef)
                .occurredAt(sourceOccurredAt)
                .payload(rawEventIdRef != null ? Map.of("rawEventId", rawEventIdRef) : null)
                .build());

        List<AgentCase> cases = agentCaseRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, bukrs, belnr, gjahr);
        if (cases.isEmpty()) {
            return LineageGraphDto.builder()
                    .resourceKey(resourceKey)
                    .nodes(nodes)
                    .edges(edges)
                    .build();
        }

        List<Long> caseIds = cases.stream().map(AgentCase::getCaseId).toList();
        List<String> caseIdStrs = caseIds.stream().map(String::valueOf).toList();

        List<AgentActivityLog> activityLogs = new ArrayList<>();
        activityLogs.addAll(agentActivityLogRepository.findByTenantIdAndResourceTypeAndResourceIdInOrderByOccurredAtAsc(
                tenantId, RESOURCE_TYPE_AGENT_CASE, caseIdStrs));
        activityLogs.addAll(agentActivityLogRepository.findByTenantIdAndResourceTypeAndResourceIdInOrderByOccurredAtAsc(
                tenantId, "CASE", caseIdStrs));
        activityLogs.sort(Comparator.comparing(AgentActivityLog::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())));

        for (AgentActivityLog log : activityLogs) {
            String nodeId = "agent-" + log.getActivityId();
            Map<String, Object> payload = new HashMap<>();
            if (log.getStage() != null) payload.put("stage", log.getStage());
            if (log.getEventType() != null) payload.put("eventType", log.getEventType());
            if (log.getMetadataJson() != null && !log.getMetadataJson().isEmpty()) payload.put("metadata", log.getMetadataJson());
            nodes.add(LineageNodeDto.builder()
                    .id(nodeId)
                    .type("AGENT")
                    .label(log.getStage() != null ? log.getStage() : "Activity")
                    .refId(String.valueOf(log.getActivityId()))
                    .occurredAt(log.getOccurredAt())
                    .payload(payload.isEmpty() ? null : payload)
                    .build());
            edges.add(LineageEdgeDto.builder().fromId(sourceId).toId(nodeId).build());
            if (log.getResourceId() != null) {
                edges.add(LineageEdgeDto.builder().fromId(nodeId).toId("case-" + log.getResourceId()).build());
            }
        }

        for (AgentCase c : cases) {
            String caseNodeId = "case-" + c.getCaseId();
            nodes.add(LineageNodeDto.builder()
                    .id(caseNodeId)
                    .type("CASE")
                    .label("Case " + c.getCaseId())
                    .refId(String.valueOf(c.getCaseId()))
                    .occurredAt(c.getDetectedAt())
                    .payload(c.getDetectedAt() != null ? Map.of("detectedAt", c.getDetectedAt().toString()) : null)
                    .build());
        }

        List<AgentAction> actions = agentActionRepository.findByTenantIdAndCaseIdIn(tenantId, caseIds);
        for (AgentAction a : actions) {
            String actionNodeId = "action-" + a.getActionId();
            nodes.add(LineageNodeDto.builder()
                    .id(actionNodeId)
                    .type("ACTION")
                    .label(a.getActionType() != null ? a.getActionType() : "Action")
                    .refId(String.valueOf(a.getActionId()))
                    .occurredAt(a.getExecutedAt() != null ? a.getExecutedAt() : a.getPlannedAt())
                    .payload(buildActionPayload(a))
                    .build());
            edges.add(LineageEdgeDto.builder().fromId("case-" + a.getCaseId()).toId(actionNodeId).build());
        }

        return LineageGraphDto.builder()
                .resourceKey(resourceKey)
                .nodes(nodes)
                .edges(edges)
                .build();
    }

    private Map<String, Object> buildActionPayload(AgentAction a) {
        Map<String, Object> p = new HashMap<>();
        if (a.getActionType() != null) p.put("actionType", a.getActionType());
        if (a.getStatus() != null) p.put("status", a.getStatus().name());
        if (a.getExecutedAt() != null) p.put("executedAt", a.getExecutedAt().toString());
        return p.isEmpty() ? null : p;
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
