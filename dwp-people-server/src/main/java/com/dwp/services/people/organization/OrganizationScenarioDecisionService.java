package com.dwp.services.people.organization;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrganizationScenarioDecisionService {

    private final OrganizationScenarioRepository repository;
    private final OrganizationChartService chartService;
    private final ObjectMapper objectMapper;
    private final AuditOutboxRecorder audit;

    public OrganizationScenarioDecisionService(
            OrganizationScenarioRepository repository,
            OrganizationChartService chartService,
            ObjectMapper objectMapper,
            AuditOutboxRecorder audit) {
        this.repository = repository;
        this.chartService = chartService;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public OrganizationScenarioDtos.DecisionPack preview(UUID scenarioId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        return evaluate(actor.tenantId(), scenarioId);
    }

    @Transactional(readOnly = true)
    public List<OrganizationScenarioDtos.ValidationRunSummary> history(UUID scenarioId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        requireScenario(actor.tenantId(), scenarioId);
        return repository.validationHistory(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.DecisionPack validate(
            UUID scenarioId,
            OrganizationScenarioDtos.ValidateScenarioRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(
                actor.tenantId(), scenarioId);
        if (scenario.version() != request.version()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario changed. Refresh it before validating the decision pack.");
        }
        return persist(actor, scenario, "MANUAL", correlationId);
    }

    @Transactional
    OrganizationScenarioDtos.DecisionPack validateForWorkflow(
            UUID scenarioId,
            String triggerType,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(
                actor.tenantId(), scenarioId);
        return persist(actor, scenario, triggerType, correlationId);
    }

    private OrganizationScenarioDtos.DecisionPack persist(
            PeopleRequestContext.Actor actor,
            OrganizationScenarioRepository.ScenarioRecord scenario,
            String triggerType,
            String correlationId) {
        OrganizationScenarioDtos.DecisionPack decision = evaluate(
                actor.tenantId(), scenario.scenarioId());
        UUID validationRunId = repository.recordValidation(
                actor.tenantId(), scenario, triggerType, decision,
                json(decision.baseline()), json(decision.proposed()), json(decision.delta()),
                json(decision.checks()), actor.userId(), correlationId);
        OrganizationScenarioDtos.DecisionPack persisted = withValidationRun(
                decision, validationRunId, Instant.now());
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action("organization.scenario.validated")
                .outcome("SUCCESS")
                .severity("BLOCKED".equals(decision.decisionState()) ? "HIGH" : "MEDIUM")
                .riskScore(Math.max(0, 100 - decision.readinessScore()))
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("organization-design")
                .targetType("ORGANIZATION_SCENARIO")
                .targetId(scenario.scenarioId().toString())
                .correlationId(correlationId)
                .metadata(Map.of(
                        "validationRunId", validationRunId,
                        "triggerType", triggerType,
                        "decisionState", decision.decisionState(),
                        "readinessScore", decision.readinessScore(),
                        "blockingIssueCount", decision.blockingIssueCount(),
                        "warningCount", decision.warningCount()))
                .retentionClass("EXTENDED")
                .build());
        return persisted;
    }

    private OrganizationScenarioDtos.DecisionPack evaluate(Long tenantId, UUID scenarioId) {
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(tenantId, scenarioId);
        OrganizationChartDtos.OrganizationChart baseline = chartService.get(
                scenario.baselineDate(), null, 12);
        OrganizationChartDtos.OrganizationChart proposed = chartService.get(
                scenario.baselineDate(), null, 12, scenarioId);
        List<OrganizationScenarioDtos.Change> changes = repository.changes(tenantId, scenarioId);
        String observedFingerprint = OrganizationBaselineFingerprint.compute(baseline);
        boolean baselineCurrent = scenario.baselineFingerprint().equals(observedFingerprint);
        List<OrganizationScenarioDtos.DecisionCheck> checks = new ArrayList<>();

        checks.add(check(
                "BASELINE_CURRENT",
                baselineCurrent ? "PASS" : "BLOCK",
                baselineCurrent ? "INFO" : "CRITICAL",
                Map.of("baselineDate", scenario.baselineDate().toString())));

        int blockedChanges = (int) changes.stream()
                .filter(change -> "BLOCKED".equals(change.validationState())).count();
        int warnedChanges = (int) changes.stream()
                .filter(change -> "WARNING".equals(change.validationState())).count();
        String changeOutcome = changes.isEmpty() || blockedChanges > 0
                ? "BLOCK" : warnedChanges > 0 ? "WARN" : "PASS";
        checks.add(check(
                "CHANGE_SET_VALID",
                changeOutcome,
                "BLOCK".equals(changeOutcome) ? "HIGH" : "WARN".equals(changeOutcome) ? "MEDIUM" : "INFO",
                Map.of(
                        "changeCount", changes.size(),
                        "blockedCount", blockedChanges,
                        "warningCount", warnedChanges)));

        Set<String> impactedOrganizations = changes.stream()
                .filter(change -> "ORGANIZATION".equals(change.targetKind()))
                .map(OrganizationScenarioDtos.Change::targetReference)
                .collect(Collectors.toCollection(HashSet::new));
        int impactedHeadcount = impactedHeadcount(baseline, impactedOrganizations);
        Map<String, OrganizationChartDtos.Position> baselinePositions = baseline.positions().stream()
                .collect(Collectors.toMap(
                        position -> position.positionId().toString(), Function.identity()));
        Map<String, OrganizationChartDtos.Position> proposedPositions = proposed.positions().stream()
                .collect(Collectors.toMap(
                        position -> position.positionId().toString(), Function.identity()));
        Set<String> impactedPositions = changes.stream()
                .filter(change -> "POSITION".equals(change.targetKind()))
                .map(OrganizationScenarioDtos.Change::targetReference)
                .collect(Collectors.toCollection(HashSet::new));
        int criticalPositionChanges = (int) impactedPositions.stream()
                .map(reference -> proposedPositions.getOrDefault(
                        reference, baselinePositions.get(reference)))
                .filter(position -> position != null)
                .filter(position -> "HIGH".equals(position.criticality())
                        || "CRITICAL".equals(position.criticality()))
                .count();
        boolean broadImpact = impactedHeadcount >= 250 || criticalPositionChanges > 0;
        checks.add(check(
                "CHANGE_IMPACT",
                broadImpact ? "WARN" : "PASS",
                broadImpact ? "HIGH" : "INFO",
                Map.of(
                        "impactedOrganizationCount", impactedOrganizations.size(),
                        "impactedHeadcount", impactedHeadcount,
                        "impactedPositionCount", impactedPositions.size(),
                        "criticalPositionCount", criticalPositionChanges)));

        int positionRoots = (int) proposed.positions().stream()
                .filter(position -> position.reportsToPositionId() == null).count();
        String hierarchyOutcome = proposed.analysis().orphanOrganizationCount() > 0
                ? "BLOCK" : positionRoots > 1 ? "WARN" : "PASS";
        checks.add(check(
                "HIERARCHY_INTEGRITY",
                hierarchyOutcome,
                "BLOCK".equals(hierarchyOutcome) ? "CRITICAL" : "WARN".equals(hierarchyOutcome) ? "MEDIUM" : "INFO",
                Map.of(
                        "orphanOrganizationCount", proposed.analysis().orphanOrganizationCount(),
                        "positionRootCount", positionRoots)));

        int criticalQualityIssues = proposed.analysis().missingManagerCount()
                + proposed.analysis().orphanOrganizationCount();
        String qualityOutcome = proposed.analysis().dataQualityScore() >= 95 ? "PASS" : "WARN";
        checks.add(check(
                "DATA_QUALITY",
                qualityOutcome,
                criticalQualityIssues > 0 ? "HIGH" : "MEDIUM",
                Map.of(
                        "score", proposed.analysis().dataQualityScore(),
                        "missingManagerCount", proposed.analysis().missingManagerCount(),
                        "missingGradeCount", proposed.analysis().missingGradeCount())));

        boolean designRegressed = proposed.analysis().healthScore() < baseline.analysis().healthScore()
                || proposed.analysis().maximumLayers() > proposed.analysis().policy().maximumLayers()
                || proposed.analysis().wideSpanManagerCount() > baseline.analysis().wideSpanManagerCount();
        checks.add(check(
                "DESIGN_POLICY",
                designRegressed ? "WARN" : "PASS",
                designRegressed ? "HIGH" : "INFO",
                Map.of(
                        "healthScore", proposed.analysis().healthScore(),
                        "maximumLayers", proposed.analysis().maximumLayers(),
                        "maximumAllowedLayers", proposed.analysis().policy().maximumLayers(),
                        "wideSpanManagerCount", proposed.analysis().wideSpanManagerCount())));

        int baselineCriticalVacancies = criticalVacancies(baseline);
        int proposedCriticalVacancies = criticalVacancies(proposed);
        boolean criticalVacancyRegressed = proposedCriticalVacancies > baselineCriticalVacancies;
        checks.add(check(
                "CRITICAL_VACANCY",
                criticalVacancyRegressed ? "WARN" : "PASS",
                criticalVacancyRegressed ? "HIGH" : "INFO",
                Map.of(
                        "baselineCriticalVacancies", baselineCriticalVacancies,
                        "proposedCriticalVacancies", proposedCriticalVacancies,
                        "delta", proposedCriticalVacancies - baselineCriticalVacancies)));

        List<OrganizationScenarioDtos.Change> financialChanges = changes.stream()
                .filter(change -> "CREATE_POSITION".equals(change.changeType())
                        || "CLOSE_POSITION".equals(change.changeType()))
                .toList();
        int pricedChanges = (int) financialChanges.stream()
                .filter(change -> change.estimatedCostDelta() != null
                        && change.costCurrency() != null
                        && !change.costCurrency().isBlank())
                .count();
        long changeCurrencyCount = financialChanges.stream()
                .map(OrganizationScenarioDtos.Change::costCurrency)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
        String currency = proposed.metrics().costCurrency();
        int unpricedPositions = (int) proposed.positions().stream()
                .filter(position -> position.annualCostAmount() == null
                        || position.costCurrency() == null
                        || position.costCurrency().isBlank())
                .count();
        int costCoveragePercent = proposed.positions().isEmpty()
                ? 100
                : (int) Math.round((proposed.positions().size() - unpricedPositions)
                        * 100.0 / proposed.positions().size());
        boolean financialCoverage = pricedChanges == financialChanges.size()
                && changeCurrencyCount <= 1
                && !"MIXED".equals(currency)
                && unpricedPositions == 0;
        checks.add(check(
                "FINANCIAL_COVERAGE",
                financialCoverage ? "PASS" : "WARN",
                financialCoverage ? "INFO" : "MEDIUM",
                Map.of(
                        "currency", currency == null ? "UNAVAILABLE" : currency,
                        "changedPositionCount", financialChanges.size(),
                        "pricedPositionCount", pricedChanges,
                        "costCoveragePercent", costCoveragePercent,
                        "unpricedPositionCount", unpricedPositions)));

        String approvalState = repository.approval(tenantId, scenarioId)
                .map(OrganizationScenarioRepository.ApprovalRecord::lifecycleState)
                .orElse("NOT_REQUESTED");
        String approvalOutcome = switch (approvalState) {
            case "APPROVED" -> "PASS";
            case "REJECTED", "EXPIRED" -> "BLOCK";
            default -> "WARN";
        };
        checks.add(check(
                "APPROVAL_GATE",
                approvalOutcome,
                "BLOCK".equals(approvalOutcome) ? "HIGH" : "PASS".equals(approvalOutcome) ? "INFO" : "MEDIUM",
                Map.of("approvalState", approvalState)));

        boolean effectiveDateValid = !scenario.effectiveDate().isBefore(LocalDate.now());
        checks.add(check(
                "EFFECTIVE_DATE",
                effectiveDateValid ? "PASS" : "BLOCK",
                effectiveDateValid ? "INFO" : "HIGH",
                Map.of("effectiveDate", scenario.effectiveDate().toString())));

        int blockingIssueCount = (int) checks.stream()
                .filter(item -> "BLOCK".equals(item.outcome())).count();
        int warningCount = (int) checks.stream()
                .filter(item -> "WARN".equals(item.outcome())).count();
        String decisionState = blockingIssueCount > 0
                ? "BLOCKED" : warningCount > 0 ? "REVIEW_REQUIRED" : "READY";
        int readinessScore = readinessScore(checks);
        OrganizationScenarioDtos.DecisionMetrics baselineMetrics = metrics(baseline);
        OrganizationScenarioDtos.DecisionMetrics proposedMetrics = metrics(proposed);

        return new OrganizationScenarioDtos.DecisionPack(
                scenario.scenarioId(), scenario.version(), scenario.lifecycleState(),
                scenario.baselineDate(), scenario.effectiveDate(), decisionState, readinessScore,
                baselineCurrent, scenario.baselineFingerprint(), observedFingerprint,
                blockingIssueCount, warningCount, baselineMetrics, proposedMetrics,
                delta(baselineMetrics, proposedMetrics), List.copyOf(checks), null, Instant.now());
    }

    private OrganizationScenarioDtos.DecisionMetrics metrics(
            OrganizationChartDtos.OrganizationChart chart) {
        return new OrganizationScenarioDtos.DecisionMetrics(
                chart.metrics().headcount(), chart.metrics().organizationCount(),
                chart.metrics().managerCount(), chart.metrics().openPositionCount(),
                scale(chart.metrics().plannedFte()), scale(chart.metrics().workforceCostAmount()),
                chart.metrics().costCurrency(), chart.analysis().averageManagerSpan(),
                chart.analysis().maximumLayers(), chart.analysis().healthScore(),
                chart.analysis().dataQualityScore());
    }

    private OrganizationScenarioDtos.DecisionMetrics delta(
            OrganizationScenarioDtos.DecisionMetrics baseline,
            OrganizationScenarioDtos.DecisionMetrics proposed) {
        return new OrganizationScenarioDtos.DecisionMetrics(
                proposed.headcount() - baseline.headcount(),
                proposed.organizationCount() - baseline.organizationCount(),
                proposed.managerCount() - baseline.managerCount(),
                proposed.openPositionCount() - baseline.openPositionCount(),
                proposed.plannedFte().subtract(baseline.plannedFte()),
                proposed.workforceCost().subtract(baseline.workforceCost()),
                proposed.costCurrency(),
                round(proposed.averageManagerSpan() - baseline.averageManagerSpan()),
                proposed.maximumLayers() - baseline.maximumLayers(),
                proposed.organizationHealthScore() - baseline.organizationHealthScore(),
                proposed.dataQualityScore() - baseline.dataQualityScore());
    }

    private OrganizationScenarioDtos.DecisionCheck check(
            String code,
            String outcome,
            String severity,
            Map<String, Object> evidence) {
        return new OrganizationScenarioDtos.DecisionCheck(
                code, outcome, severity, "SCENARIO", null, Map.copyOf(evidence));
    }

    private int criticalVacancies(OrganizationChartDtos.OrganizationChart chart) {
        return (int) chart.positions().stream()
                .filter(position -> "OPEN".equals(position.status())
                        || "PLANNED".equals(position.status()))
                .filter(position -> "HIGH".equals(position.criticality())
                        || "CRITICAL".equals(position.criticality()))
                .count();
    }

    private int impactedHeadcount(
            OrganizationChartDtos.OrganizationChart chart,
            Set<String> impactedOrganizations) {
        if (impactedOrganizations.isEmpty()) return 0;
        Map<UUID, UUID> parentByOrganization = chart.organizations().stream()
                .filter(organization -> organization.parentOrganizationId() != null)
                .collect(Collectors.toMap(
                        OrganizationChartDtos.Organization::organizationId,
                        OrganizationChartDtos.Organization::parentOrganizationId));
        return (int) chart.people().stream().filter(person -> {
            Set<UUID> visited = new HashSet<>();
            UUID current = person.organizationId();
            while (current != null && visited.add(current)) {
                if (impactedOrganizations.contains(current.toString())) return true;
                current = parentByOrganization.get(current);
            }
            return false;
        }).count();
    }

    private int readinessScore(List<OrganizationScenarioDtos.DecisionCheck> checks) {
        int penalty = checks.stream().mapToInt(check -> {
            if ("PASS".equals(check.outcome())) return 0;
            int severity = switch (check.severity()) {
                case "CRITICAL" -> 30;
                case "HIGH" -> 24;
                case "MEDIUM" -> 14;
                default -> 8;
            };
            return "BLOCK".equals(check.outcome()) ? severity : Math.max(4, severity / 2);
        }).sum();
        return Math.max(0, 100 - penalty);
    }

    private OrganizationScenarioDtos.DecisionPack withValidationRun(
            OrganizationScenarioDtos.DecisionPack decision,
            UUID validationRunId,
            Instant evaluatedAt) {
        return new OrganizationScenarioDtos.DecisionPack(
                decision.scenarioId(), decision.scenarioVersion(), decision.lifecycleState(),
                decision.baselineDate(), decision.effectiveDate(), decision.decisionState(),
                decision.readinessScore(), decision.baselineCurrent(), decision.baselineFingerprint(),
                decision.observedFingerprint(), decision.blockingIssueCount(), decision.warningCount(),
                decision.baseline(), decision.proposed(), decision.delta(), decision.checks(),
                validationRunId, evaluatedAt);
    }

    private OrganizationScenarioRepository.ScenarioRecord requireScenario(
            Long tenantId,
            UUID scenarioId) {
        return repository.scenario(tenantId, scenarioId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "Scenario not found."));
    }

    private void requirePlanner(PeopleRequestContext.Actor actor) {
        if (!actor.hasAnyRole(
                "HR_ADMIN", "PEOPLE_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN", "ADMIN")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Organization design permission is required.");
        }
    }

    private BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The organization decision pack could not be serialized.", exception);
        }
    }
}
