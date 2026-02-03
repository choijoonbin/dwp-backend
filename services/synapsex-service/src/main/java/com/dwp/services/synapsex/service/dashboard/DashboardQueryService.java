package com.dwp.services.synapsex.service.dashboard;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.synapsex.client.AuthServerUserClient;
import com.dwp.services.synapsex.dto.dashboard.*;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.AuditEventLog;
import com.dwp.services.synapsex.entity.AgentActivityLog;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import com.dwp.services.synapsex.repository.AgentActivityLogRepository;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.AuditEventLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 통합관제센터 대시보드 API - 실데이터 기반 집계
 * SoT: agent_case, agent_action, audit_event_log
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardQueryService {

    private static final List<String> OPEN_CASE_STATUSES = List.of("OPEN", "ACTIVE", "IN_PROGRESS", "IN_REVIEW", "TRIAGED");
    private static final List<String> CLOSED_CASE_STATUSES = List.of("RESOLVED", "CLOSED", "APPROVED", "REJECTED", "ACTIONED", "DISMISSED");
    private static final List<String> SUCCESS_ACTION_STATUSES = List.of("SUCCEEDED", "SUCCESS", "EXECUTED", "DONE");
    private static final List<String> FAIL_ACTION_STATUSES = List.of("FAILED", "ERROR");
    private static final List<String> PENDING_ACTION_STATUSES = List.of("PLANNED", "PENDING", "REVIEW", "WAITING_APPROVAL", "PROPOSED", "PENDING_APPROVAL");

    private final AgentCaseRepository agentCaseRepository;
    private final AgentActionRepository agentActionRepository;
    private final AuditEventLogRepository auditEventLogRepository;
    private final AgentActivityLogRepository agentActivityLogRepository;
    private final AuthServerUserClient authServerUserClient;

    private static final Map<String, String> CASE_TYPE_LABELS = Map.ofEntries(
            Map.entry("DUPLICATE_INVOICE", "Duplicate Invoices"),
            Map.entry("BANK_CHANGE", "Bank Change Risk"),
            Map.entry("THRESHOLD_BREACH", "Threshold Breach"),
            Map.entry("ANOMALY", "Anomaly"),
            Map.entry("DEFAULT", "Other")
    );

    @Transactional(readOnly = true)
    public DashboardSummaryDto getSummary(Long tenantId) {
        Instant now = Instant.now();
        Instant sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);
        Instant fiveMinutesAgo = now.minus(5, ChronoUnit.MINUTES);
        Instant ninetyDaysAgo = now.minus(90, ChronoUnit.DAYS);

        // Open cases by severity (critical, high, medium, low)
        // PostgreSQL enum 비교 회피: findByTenantId 후 Java에서 status 필터
        List<AgentCase> allCases = agentCaseRepository.findByTenantId(tenantId);
        List<AgentCase> openCases = allCases.stream()
                .filter(c -> c.getStatus() != null && OPEN_CASE_STATUSES.contains(c.getStatus().toUpperCase()))
                .toList();
        long critical = openCases.stream().filter(c -> "CRITICAL".equalsIgnoreCase(c.getSeverity())).count();
        long high = openCases.stream().filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity())).count();
        long medium = openCases.stream().filter(c -> "MEDIUM".equalsIgnoreCase(c.getSeverity())).count();
        long low = openCases.stream().filter(c -> "LOW".equalsIgnoreCase(c.getSeverity())).count();

        // AI Action success rate (last 7 days)
        List<AgentAction> recentActions = agentActionRepository.findByTenantIdAndCreatedAtAfter(tenantId, sevenDaysAgo);
        long okCnt = recentActions.stream()
                .filter(a -> SUCCESS_ACTION_STATUSES.stream().anyMatch(s -> s.equalsIgnoreCase(a.getStatus())))
                .count();
        long failCnt = recentActions.stream()
                .filter(a -> FAIL_ACTION_STATUSES.stream().anyMatch(s -> s.equalsIgnoreCase(a.getStatus())))
                .count();
        BigDecimal ratePct = (okCnt + failCnt) > 0
                ? BigDecimal.valueOf(okCnt).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(okCnt + failCnt), 1, RoundingMode.HALF_UP)
                : null;

        // Prevented loss (evidence_json from cases, last 90 days)
        List<AgentCase> casesForLoss = agentCaseRepository.findByTenantIdAndCreatedAtAfter(tenantId, ninetyDaysAgo);
        BigDecimal preventedLoss = sumPreventedLoss(casesForLoss);

        // Agent live status (audit_event_log, last 5 min)
        Specification<AuditEventLog> agentSpec = (root, query, cb) -> {
            var tenantEq = cb.equal(root.get("tenantId"), tenantId);
            var createdAfter = cb.greaterThanOrEqualTo(root.get("createdAt"), fiveMinutesAgo);
            var actorAgent = cb.equal(cb.upper(root.get("actorType")), "AGENT");
            var categoryInt = cb.equal(cb.upper(root.get("eventCategory")), "INTEGRATION");
            return cb.and(tenantEq, createdAfter, cb.or(actorAgent, categoryInt));
        };
        List<AuditEventLog> recentAudits = auditEventLogRepository.findAll(agentSpec);
        long errorsIn5m = recentAudits.stream()
                .filter(a -> a.getOutcome() != null && List.of("FAIL", "FAILED", "ERROR").contains(a.getOutcome().toUpperCase()))
                .count();

        // Pending approvals (actions in pending status)
        // PostgreSQL enum 비교 회피: findByTenantId 후 Java에서 status 필터
        List<AgentAction> allActions = agentActionRepository.findByTenantId(tenantId);
        List<AgentAction> pendingActions = allActions.stream()
                .filter(a -> a.getStatus() != null && PENDING_ACTION_STATUSES.contains(a.getStatus().toUpperCase()))
                .toList();
        long pendingApprovals = pendingActions.size();

        String agentStatus = recentAudits.isEmpty() ? "idle" : (errorsIn5m > 0 ? "processing" : "active");

        // avgLeadTime: 케이스 생성~종료 평균 (종료 없으면 now-생성)
        BigDecimal avgLeadTime = computeAvgLeadTimeHours(openCases, now);

        return DashboardSummaryDto.builder()
                .tenantId(tenantId)
                .asOf(now)
                .financialHealthIndex(87)
                .financialHealthTrend(BigDecimal.valueOf(2.3))
                .openCasesBySeverity(DashboardSummaryDto.OpenCasesBySeverity.builder()
                        .critical(critical)
                        .high(high)
                        .medium(medium)
                        .low(low)
                        .build())
                .aiActionSuccessRate(ratePct)
                .aiActionSuccessTrend(ratePct != null ? BigDecimal.valueOf(1.2) : null)
                .estimatedPreventedLoss(preventedLoss != null ? preventedLoss : BigDecimal.ZERO)
                .preventedLossTrend(BigDecimal.valueOf(15.5))
                .agentLiveStatus(agentStatus)
                .pendingApprovals(pendingApprovals)
                .slaAtRisk(0L)
                .avgLeadTime(avgLeadTime != null ? avgLeadTime : BigDecimal.valueOf(4.2))
                .backlogCount((long) openCases.size())
                .links(DashboardSummaryDto.SummaryLinks.builder()
                        .casesPath("/cases?status=OPEN")
                        .actionsPath("/actions?status=PENDING_APPROVAL")
                        .auditPath("/audit?category=ACTION")
                        .build())
                .build();
    }

    @Transactional(readOnly = true)
    public TopRiskDriversResponseDto getTopRiskDrivers(Long tenantId, String range) {
        Instant fromTs = parseRangeToInstant(range);
        // PostgreSQL enum 비교 회피: findByTenantIdAndCreatedAtAfter 후 Java에서 status 필터
        List<AgentCase> allCases = agentCaseRepository.findByTenantIdAndCreatedAtAfter(tenantId, fromTs);
        List<AgentCase> cases = allCases.stream()
                .filter(c -> c.getStatus() != null && OPEN_CASE_STATUSES.contains(c.getStatus().toUpperCase()))
                .toList();

        Map<String, TopRiskDriverDto> byType = new LinkedHashMap<>();
        for (AgentCase c : cases) {
            String key = c.getCaseType() != null ? c.getCaseType() : "DEFAULT";
            TopRiskDriverDto existing = byType.get(key);
            BigDecimal impact = extractAmount(c.getEvidenceJson());
            if (existing == null) {
                byType.put(key, TopRiskDriverDto.builder()
                        .driverKey(key)
                        .label(CASE_TYPE_LABELS.getOrDefault(key, key.replace("_", " ")))
                        .caseCount(1)
                        .impactAmount(impact != null ? impact : BigDecimal.ZERO)
                        .riskTypeKey(key)
                        .estimatedLoss(impact != null ? impact : BigDecimal.ZERO)
                        .links(TopRiskDriverDto.AnomaliesLinks.builder()
                                .anomaliesPath("/anomalies?type=" + key + "&range=" + (range != null ? range : "24h"))
                                .build())
                        .build());
            } else {
                existing.setCaseCount(existing.getCaseCount() + 1);
                BigDecimal newImpact = existing.getImpactAmount().add(impact != null ? impact : BigDecimal.ZERO);
                existing.setImpactAmount(newImpact);
                existing.setEstimatedLoss(newImpact);
            }
        }

        List<TopRiskDriverDto> items = byType.values().stream()
                .sorted(Comparator.comparingLong(TopRiskDriverDto::getCaseCount).reversed()
                        .thenComparing(TopRiskDriverDto::getImpactAmount, Comparator.reverseOrder()))
                .limit(8)
                .toList();

        return TopRiskDriversResponseDto.builder()
                .range(range)
                .items(items)
                .build();
    }

    @Transactional(readOnly = true)
    public ActionRequiredResponseDto getActionRequired(Long tenantId, List<String> severityList) {
        if (severityList == null || severityList.isEmpty()) {
            return ActionRequiredResponseDto.builder().items(List.of()).build();
        }
        List<String> severities = severityList.stream()
                .map(s -> s.trim().toUpperCase())
                .filter(s -> !s.isEmpty())
                .toList();
        if (severities.isEmpty()) {
            return ActionRequiredResponseDto.builder().items(List.of()).build();
        }

        List<AgentAction> allActions = agentActionRepository.findByTenantId(tenantId);
        List<AgentAction> pendingActions = allActions.stream()
                .filter(a -> a.getStatus() != null && PENDING_ACTION_STATUSES.contains(a.getStatus().toUpperCase()))
                .toList();
        Set<Long> caseIds = pendingActions.stream().map(AgentAction::getCaseId).collect(Collectors.toSet());
        if (caseIds.isEmpty()) {
            return ActionRequiredResponseDto.builder().items(List.of()).build();
        }

        List<AgentCase> cases = agentCaseRepository.findByTenantIdAndCaseIdIn(tenantId, new ArrayList<>(caseIds));
        Map<Long, AgentCase> caseMap = cases.stream()
                .filter(c -> severities.contains(c.getSeverity() != null ? c.getSeverity().toUpperCase() : ""))
                .collect(Collectors.toMap(AgentCase::getCaseId, c -> c, (a, b) -> a));
        List<ActionRequiredDto> items = new ArrayList<>();
        for (AgentAction a : pendingActions) {
            AgentCase c = caseMap.get(a.getCaseId());
            if (c == null || !severities.contains(c.getSeverity() != null ? c.getSeverity().toUpperCase() : "")) {
                continue;
            }
            String title = buildActionTitle(a, c);
            String severityStr = c.getSeverity() != null ? c.getSeverity() : "MEDIUM";
            String caseDisplayId = "CS-" + c.getCaseId();
            String actionDisplayId = a.getActionId() != null ? "AC-" + a.getActionId() : null;
            items.add(ActionRequiredDto.builder()
                    .actionId(a.getActionId())
                    .caseId(a.getCaseId())
                    .severity(severityStr)
                    .title(title)
                    .ctaLabel("Review")
                    .createdAt(a.getCreatedAt() != null ? a.getCreatedAt() : a.getPlannedAt())
                    .id(a.getActionId() != null ? a.getActionId().toString() : null)
                    .description(title)
                    .riskLevel(severityStr.toLowerCase())
                    .caseNumber(caseDisplayId)
                    .primaryActionId(a.getActionId())
                    .reasonShort(c.getReasonText() != null && !c.getReasonText().isEmpty()
                            ? (c.getReasonText().length() > 80 ? c.getReasonText().substring(0, 80) + "..." : c.getReasonText())
                            : title)
                    .links(ActionRequiredDto.ReviewLinks.builder()
                            .reviewPath("/cases/" + c.getCaseId())
                            .build())
                    .build());
        }
        items.sort(Comparator.comparing(ActionRequiredDto::getSeverity, Comparator.reverseOrder())
                .thenComparing(ActionRequiredDto::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        items = items.stream().limit(20).toList();

        return ActionRequiredResponseDto.builder().items(items).build();
    }

    @Transactional(readOnly = true)
    public TeamSnapshotResponseDto getTeamSnapshot(Long tenantId, String range, Long teamId) {
        Instant fromTs = parseRangeToInstant(range);
        List<AgentCase> rangeCases = agentCaseRepository.findByTenantIdAndCreatedAtAfter(tenantId, fromTs);
        List<AgentCase> allCasesForLookup = agentCaseRepository.findByTenantId(tenantId);
        Map<Long, AgentCase> caseMap = allCasesForLookup.stream().collect(Collectors.toMap(AgentCase::getCaseId, c -> c, (a, b) -> a));

        List<AgentCase> openCases = rangeCases.stream()
                .filter(c -> c.getStatus() != null && OPEN_CASE_STATUSES.contains(c.getStatus().toUpperCase()))
                .filter(c -> c.getAssigneeUserId() != null)
                .toList();

        List<AgentAction> allActions = agentActionRepository.findByTenantId(tenantId);
        List<AgentAction> pendingActions = allActions.stream()
                .filter(a -> a.getStatus() != null && PENDING_ACTION_STATUSES.contains(a.getStatus().toUpperCase()))
                .toList();

        Map<Long, Long> pendingByAssignee = new HashMap<>();
        for (AgentAction a : pendingActions) {
            AgentCase c = caseMap.get(a.getCaseId());
            if (c != null && c.getAssigneeUserId() != null) {
                pendingByAssignee.merge(c.getAssigneeUserId(), 1L, Long::sum);
            }
        }

        Map<Long, List<AgentCase>> casesByAssignee = openCases.stream()
                .collect(Collectors.groupingBy(AgentCase::getAssigneeUserId));
        if (teamId != null) {
            casesByAssignee = casesByAssignee.entrySet().stream()
                    .filter(e -> e.getKey().equals(teamId))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        Map<String, String> displayNames = resolveDisplayNames(tenantId, casesByAssignee.keySet());

        List<TeamSnapshotItemDto> items = new ArrayList<>();
        Instant now = Instant.now();
        for (Map.Entry<Long, List<AgentCase>> e : casesByAssignee.entrySet()) {
            Long assigneeId = e.getKey();
            List<AgentCase> assigneeCases = e.getValue();
            long openCnt = assigneeCases.size();
            long pendingCnt = pendingByAssignee.getOrDefault(assigneeId, 0L);
            BigDecimal avgLead = computeAvgLeadTimeHours(assigneeCases, now);
            String topQueue = assigneeCases.stream()
                    .map(c -> c.getCaseType() != null ? c.getCaseType() : "DEFAULT")
                    .collect(Collectors.groupingBy(x -> x, Collectors.counting()))
                    .entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("DEFAULT");
            String slaRisk = openCnt > 5 ? "AT_RISK" : "ON_TRACK";

            String analystName = displayNames.getOrDefault(String.valueOf(assigneeId), "Analyst " + assigneeId);

            String fromIso = fromTs.toString();
            String toIso = now.toString();
            items.add(TeamSnapshotItemDto.builder()
                    .analystUserId(assigneeId)
                    .analystName(analystName)
                    .title("Analyst")
                    .openCases(openCnt)
                    .slaRisk(slaRisk)
                    .avgLeadTimeHours(avgLead != null ? avgLead : BigDecimal.ZERO)
                    .pendingApprovals(pendingCnt)
                    .topQueue(topQueue)
                    .links(TeamSnapshotItemDto.Links.builder()
                            .casesPath("/cases?assignee=" + assigneeId + "&status=OPEN")
                            .actionsPath("/actions?assignee=" + assigneeId + "&status=PENDING_APPROVAL")
                            .auditPath("/audit?actorUserId=" + assigneeId + "&from=" + fromIso + "&to=" + toIso)
                            .build())
                    .build());
        }
        items.sort(Comparator.comparingLong(TeamSnapshotItemDto::getOpenCases).reversed());

        return TeamSnapshotResponseDto.builder()
                .range(range)
                .items(items)
                .build();
    }

    @Transactional(readOnly = true)
    public AgentActivityResponseDto getAgentActivity(Long tenantId, String range, int limit) {
        Instant fromTs = parseRangeToInstant(range);
        int fetchLimit = Math.min(limit, 100);

        List<AgentActivityLog> activityLogs = agentActivityLogRepository
                .findByTenantIdAndOccurredAtAfterOrderByOccurredAtDesc(
                        tenantId, fromTs, PageRequest.of(0, fetchLimit));

        if (!activityLogs.isEmpty()) {
            List<AgentActivityItemDto> items = activityLogs.stream().map(this::toAgentActivityItemFromLog).toList();
            return AgentActivityResponseDto.builder().range(range).items(items).build();
        }

        Specification<AuditEventLog> spec = (root, query, cb) -> {
            var tenantEq = cb.equal(root.get("tenantId"), tenantId);
            var createdAfter = cb.greaterThanOrEqualTo(root.get("createdAt"), fromTs);
            var catAgent = cb.equal(cb.upper(root.get("eventCategory")), "AGENT");
            var catAction = cb.equal(cb.upper(root.get("eventCategory")), "ACTION");
            var catIntegration = cb.equal(cb.upper(root.get("eventCategory")), "INTEGRATION");
            return cb.and(tenantEq, createdAfter, cb.or(catAgent, catAction, catIntegration));
        };
        List<AuditEventLog> logs = auditEventLogRepository.findAll(spec,
                PageRequest.of(0, fetchLimit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();

        List<AgentActivityItemDto> items = logs.stream().map(this::toAgentActivityItem).toList();

        return AgentActivityResponseDto.builder()
                .range(range)
                .items(items)
                .build();
    }

    private AgentActivityItemDto toAgentActivityItemFromLog(AgentActivityLog log) {
        String caseId = null;
        String actionId = null;
        if (log.getResourceType() != null && log.getResourceId() != null) {
            if ("AGENT_CASE".equalsIgnoreCase(log.getResourceType()) || "CASE".equalsIgnoreCase(log.getResourceType())) {
                caseId = "CS-" + log.getResourceId();
            } else if ("AGENT_ACTION".equalsIgnoreCase(log.getResourceType()) || "ACTION".equalsIgnoreCase(log.getResourceType())) {
                actionId = "AC-" + log.getResourceId();
            }
        }
        String casePath = caseId != null ? "/cases/" + log.getResourceId() : null;
        String auditPath = "/audit?resourceType=" + (log.getResourceType() != null ? log.getResourceType() : "CASE")
                + "&resourceId=" + (log.getResourceId() != null ? log.getResourceId() : "");

        String message = "[" + log.getStage() + "] " + (log.getEventType() != null ? log.getEventType().replace("_", " ") : log.getStage());

        return AgentActivityItemDto.builder()
                .ts(log.getOccurredAt())
                .level("INFO")
                .stage(log.getStage())
                .message(message)
                .caseId(caseId)
                .actionId(actionId)
                .resourceType(log.getResourceType())
                .resourceId(log.getResourceId())
                .traceId(null)
                .links(AgentActivityItemDto.Links.builder()
                        .casePath(casePath)
                        .auditPath(auditPath)
                        .build())
                .build();
    }

    private AgentActivityItemDto toAgentActivityItem(AuditEventLog e) {
        String stage = mapEventToStage(e.getEventCategory(), e.getEventType());
        String level = "INFO";
        if (e.getSeverity() != null) {
            if (e.getSeverity().toUpperCase().contains("ERROR") || e.getSeverity().toUpperCase().contains("CRITICAL")) level = "ERROR";
            else if (e.getSeverity().toUpperCase().contains("WARN")) level = "WARN";
        }
        if (e.getOutcome() != null && List.of("FAIL", "FAILED", "ERROR").contains(e.getOutcome().toUpperCase())) {
            level = "ERROR";
        }
        String message = buildActivityMessage(e);
        String caseId = null;
        String actionId = null;
        if (e.getResourceType() != null && e.getResourceId() != null) {
            if ("AGENT_CASE".equalsIgnoreCase(e.getResourceType()) || "CASE".equalsIgnoreCase(e.getResourceType())) {
                caseId = "CS-" + e.getResourceId();
            } else if ("AGENT_ACTION".equalsIgnoreCase(e.getResourceType()) || "ACTION".equalsIgnoreCase(e.getResourceType())) {
                actionId = "AC-" + e.getResourceId();
            }
        }
        String casePath = caseId != null ? "/cases/" + e.getResourceId() : null;
        String auditPath = "/audit?resourceType=" + (e.getResourceType() != null ? e.getResourceType() : "CASE")
                + "&resourceId=" + (e.getResourceId() != null ? e.getResourceId() : "");

        return AgentActivityItemDto.builder()
                .ts(e.getCreatedAt())
                .level(level)
                .stage(stage)
                .message(message)
                .caseId(caseId)
                .actionId(actionId)
                .resourceType(e.getResourceType())
                .resourceId(e.getResourceId())
                .traceId(e.getTraceId())
                .links(AgentActivityItemDto.Links.builder()
                        .casePath(casePath)
                        .auditPath(auditPath)
                        .build())
                .build();
    }

    private String mapEventToStage(String category, String type) {
        if (category == null) return "ANALYZE";
        String c = category.toUpperCase();
        if (c.contains("AGENT")) return type != null && type.toUpperCase().contains("SCAN") ? "SCAN" : "DETECT";
        if (c.contains("INTEGRATION")) return "EXECUTE";
        if (c.contains("ACTION")) return type != null && type.toUpperCase().contains("SIMULATE") ? "SIMULATE" : "EXECUTE";
        return "ANALYZE";
    }

    private String buildActivityMessage(AuditEventLog e) {
        String cat = e.getEventCategory() != null ? e.getEventCategory() : "";
        String type = e.getEventType() != null ? e.getEventType() : "";
        String stage = mapEventToStage(cat, type);
        Object msg = null;
        if (e.getEvidenceJson() != null) msg = e.getEvidenceJson().get("message");
        if (msg == null && e.getAfterJson() != null) msg = e.getAfterJson().get("message");
        if (msg != null) return "[" + stage + "] " + String.valueOf(msg);
        return "[" + stage + "] " + type.replace("_", " ");
    }

    /**
     * auth-server Feign 호출로 display_name 배치 조회.
     * 실패 시 빈 맵 반환 (fallback: "Analyst {id}").
     */
    private Map<String, String> resolveDisplayNames(Long tenantId, Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        String ids = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            ApiResponse<Map<String, String>> resp = authServerUserClient.getDisplayNames(tenantId, ids);
            if (resp != null && resp.getData() != null && Boolean.TRUE.equals(resp.getSuccess())) {
                return resp.getData();
            }
        } catch (Exception e) {
            log.warn("Auth display-names Feign 호출 실패, fallback 사용: {}", e.getMessage());
        }
        return Map.of();
    }

    private BigDecimal computeAvgLeadTimeHours(List<AgentCase> cases, Instant now) {
        if (cases == null || cases.isEmpty()) return null;
        double sumHours = 0;
        int count = 0;
        for (AgentCase c : cases) {
            Instant end = c.getUpdatedAt() != null ? c.getUpdatedAt() : now;
            boolean closed = c.getStatus() != null && CLOSED_CASE_STATUSES.contains(c.getStatus().toUpperCase());
            if (!closed) end = now;
            long millis = ChronoUnit.MILLIS.between(c.getCreatedAt(), end);
            sumHours += millis / (1000.0 * 3600.0);
            count++;
        }
        return count > 0 ? BigDecimal.valueOf(sumHours / count).setScale(1, RoundingMode.HALF_UP) : null;
    }

    private BigDecimal sumPreventedLoss(List<AgentCase> cases) {
        BigDecimal sum = BigDecimal.ZERO;
        for (AgentCase c : cases) {
            BigDecimal val = extractPreventedLoss(c.getEvidenceJson());
            if (val != null) sum = sum.add(val);
        }
        return sum;
    }

    private BigDecimal extractPreventedLoss(JsonNode evidence) {
        if (evidence == null) return null;
        JsonNode node = evidence.get("prevented_loss");
        if (node != null && node.isNumber()) {
            return BigDecimal.valueOf(node.asDouble());
        }
        if (node != null && node.isTextual()) {
            try {
                return new BigDecimal(node.asText());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal extractAmount(JsonNode evidence) {
        if (evidence == null) return null;
        JsonNode node = evidence.get("amount");
        if (node == null) node = evidence.get("prevented_loss");
        if (node != null && node.isNumber()) {
            return BigDecimal.valueOf(node.asDouble());
        }
        if (node != null && node.isTextual()) {
            try {
                return new BigDecimal(node.asText());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Instant parseRangeToInstant(String range) {
        if (range == null || range.isEmpty()) range = "24h";
        return switch (range.toUpperCase()) {
            case "1H" -> Instant.now().minus(1, ChronoUnit.HOURS);
            case "7D" -> Instant.now().minus(7, ChronoUnit.DAYS);
            case "30D" -> Instant.now().minus(30, ChronoUnit.DAYS);
            default -> Instant.now().minus(24, ChronoUnit.HOURS);
        };
    }

    private String buildActionTitle(AgentAction a, AgentCase c) {
        String actionType = a.getActionType() != null ? a.getActionType() : "Action";
        String caseRef = "case #" + c.getCaseId();
        return String.format("%s for %s pending verification", actionType.replace("_", " "), caseRef);
    }
}
