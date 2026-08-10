package com.dwp.services.people.organization;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrganizationIntelligenceService {

    private final OrganizationChartService chartService;

    public OrganizationIntelligenceService(OrganizationChartService chartService) {
        this.chartService = chartService;
    }

    @Transactional(readOnly = true)
    public OrganizationIntelligenceDtos.Intelligence get(
            LocalDate requestedAsOf,
            LocalDate requestedCompareTo,
            UUID rootOrganizationId,
            int requestedDepth) {
        return get(requestedAsOf, requestedCompareTo, rootOrganizationId, requestedDepth, null);
    }

    @Transactional(readOnly = true)
    public OrganizationIntelligenceDtos.Intelligence get(
            LocalDate requestedAsOf,
            LocalDate requestedCompareTo,
            UUID rootOrganizationId,
            int requestedDepth,
            UUID scenarioId) {
        LocalDate asOf = requestedAsOf == null ? LocalDate.now() : requestedAsOf;
        LocalDate compareTo = requestedCompareTo == null ? asOf.minusMonths(1) : requestedCompareTo;
        OrganizationChartDtos.OrganizationChart current = chartService.get(
                asOf, rootOrganizationId, requestedDepth, scenarioId);
        OrganizationChartDtos.OrganizationChart previous = chartService.get(
                scenarioId == null ? compareTo : asOf, rootOrganizationId, requestedDepth, null);

        List<OrganizationIntelligenceDtos.DataQualityIssue> qualityIssues = qualityIssues(current);
        List<OrganizationIntelligenceDtos.OrganizationHealth> organizationHealth = current.organizations()
                .stream()
                .map(organization -> organizationHealth(current, organization))
                .sorted(Comparator
                        .comparingInt(OrganizationIntelligenceDtos.OrganizationHealth::healthScore)
                        .thenComparing(OrganizationIntelligenceDtos.OrganizationHealth::organizationName))
                .toList();
        List<OrganizationIntelligenceDtos.Change> changes = changes(previous, current);
        List<Integer> spans = current.people().stream()
                .map(OrganizationChartDtos.Person::directReportCount)
                .filter(count -> count > 0)
                .sorted()
                .toList();
        OrganizationIntelligenceDtos.HealthSummary health = new OrganizationIntelligenceDtos.HealthSummary(
                current.analysis().maximumLayers(),
                current.analysis().averageManagerSpan(), median(spans),
                current.analysis().wideSpanManagerCount(),
                current.analysis().singleReportManagerCount(),
                current.analysis().missingManagerCount(),
                current.analysis().orphanOrganizationCount(),
                current.metrics().openPositionCount(),
                current.analysis().contingentRatioPercent(),
                current.analysis().healthScore(),
                current.analysis().dataQualityScore(),
                (int) organizationHealth.stream().filter(item -> !"HEALTHY".equals(item.riskState())).count(),
                (int) organizationHealth.stream().filter(item -> "CRITICAL".equals(item.riskState())).count(),
                (int) organizationHealth.stream().filter(item -> "ATTENTION".equals(item.riskState())).count());
        OrganizationIntelligenceDtos.ComparisonSummary comparison = comparison(
                previous, current, changes);
        return new OrganizationIntelligenceDtos.Intelligence(
                current.asOf(), scenarioId == null ? compareTo : current.asOf(), health, comparison, organizationHealth,
                changes.stream().limit(250).toList(), qualityIssues.stream().limit(250).toList());
    }

    private OrganizationIntelligenceDtos.OrganizationHealth organizationHealth(
            OrganizationChartDtos.OrganizationChart chart,
            OrganizationChartDtos.Organization organization) {
        double contingentRatio = percentage(
                organization.contingentHeadcount(), organization.totalHeadcount());
        int overloaded = (int) chart.people().stream()
                .filter(person -> person.organizationId().equals(organization.organizationId()))
                .filter(person -> person.directReportCount() > chart.analysis().policy().maximumManagerSpan())
                .count();
        int score = OrganizationHealthPolicy.score(organization.healthSignals());
        return new OrganizationIntelligenceDtos.OrganizationHealth(
                organization.organizationId(), organization.name(), organization.organizationType(),
                organization.layerDepth(),
                organization.directHeadcount(), organization.totalHeadcount(), organization.managerCount(),
                organization.averageManagerSpan(), overloaded, organization.openPositionCount(), contingentRatio,
                score, organization.healthStatus(), organization.healthSignals());
    }

    private List<OrganizationIntelligenceDtos.DataQualityIssue> qualityIssues(
            OrganizationChartDtos.OrganizationChart chart) {
        List<OrganizationIntelligenceDtos.DataQualityIssue> issues = new ArrayList<>();
        chart.people().stream()
                .filter(OrganizationChartDtos.Person::managerReferenceMissing)
                .forEach(person -> issues.add(new OrganizationIntelligenceDtos.DataQualityIssue(
                        "BROKEN_MANAGER_REFERENCE", "HIGH", "PERSON", person.personId().toString(),
                        person.displayName(), "The effective manager assignment cannot be resolved.")));
        chart.people().stream()
                .filter(person -> person.jobGradeKey() == null || person.jobGradeKey().isBlank())
                .forEach(person -> issues.add(new OrganizationIntelligenceDtos.DataQualityIssue(
                        "MISSING_JOB_GRADE", "MEDIUM", "PERSON", person.personId().toString(),
                        person.displayName(), "The current assignment has no effective job grade.")));
        List<OrganizationChartDtos.Position> positionRoots = chart.positions().stream()
                .filter(position -> position.reportsToPositionId() == null)
                .sorted(Comparator
                        .comparingInt(OrganizationChartDtos.Position::subordinatePositionCount)
                        .reversed()
                        .thenComparing(OrganizationChartDtos.Position::positionKey))
                .toList();
        positionRoots.stream().skip(1).forEach(position -> issues.add(
                new OrganizationIntelligenceDtos.DataQualityIssue(
                        "DISCONNECTED_POSITION", "HIGH", "POSITION",
                        position.positionId().toString(), position.title(),
                        "The position is disconnected from the primary position hierarchy.")));
        chart.organizations().stream()
                .filter(organization -> organization.parentOrganizationId() == null)
                .filter(organization -> !organization.organizationId().equals(chart.company().organizationId()))
                .forEach(organization -> issues.add(new OrganizationIntelligenceDtos.DataQualityIssue(
                        "DISCONNECTED_ORGANIZATION", "CRITICAL", "ORGANIZATION",
                        organization.organizationId().toString(), organization.name(),
                        "The organization is outside the effective supervisory hierarchy.")));
        Map<UUID, Long> primaryParents = chart.relationships().stream()
                .filter(OrganizationChartDtos.Relationship::primaryRelationship)
                .filter(relationship -> "SUPERVISORY".equals(relationship.relationshipType()))
                .collect(Collectors.groupingBy(
                        OrganizationChartDtos.Relationship::childOrganizationId,
                        Collectors.counting()));
        primaryParents.forEach((organizationId, count) -> {
            if (count <= 1) return;
            String name = chart.organizations().stream()
                    .filter(item -> item.organizationId().equals(organizationId))
                    .map(OrganizationChartDtos.Organization::name).findFirst().orElse(organizationId.toString());
            issues.add(new OrganizationIntelligenceDtos.DataQualityIssue(
                    "MULTIPLE_PRIMARY_PARENTS", "CRITICAL", "ORGANIZATION",
                    organizationId.toString(), name,
                    "More than one primary supervisory relationship is effective on the same date."));
        });
        cycleNodes(chart).forEach(organizationId -> {
            String name = chart.organizations().stream()
                    .filter(item -> item.organizationId().equals(organizationId))
                    .map(OrganizationChartDtos.Organization::name).findFirst().orElse(organizationId.toString());
            issues.add(new OrganizationIntelligenceDtos.DataQualityIssue(
                    "ORGANIZATION_CYCLE", "CRITICAL", "ORGANIZATION",
                    organizationId.toString(), name, "A cycle exists in the effective supervisory hierarchy."));
        });
        return issues;
    }

    private List<OrganizationIntelligenceDtos.Change> changes(
            OrganizationChartDtos.OrganizationChart previous,
            OrganizationChartDtos.OrganizationChart current) {
        List<OrganizationIntelligenceDtos.Change> changes = new ArrayList<>();
        Map<UUID, OrganizationChartDtos.Organization> previousOrganizations = previous.organizations().stream()
                .collect(Collectors.toMap(OrganizationChartDtos.Organization::organizationId, Function.identity()));
        Map<UUID, OrganizationChartDtos.Organization> currentOrganizations = current.organizations().stream()
                .collect(Collectors.toMap(OrganizationChartDtos.Organization::organizationId, Function.identity()));
        currentOrganizations.forEach((id, organization) -> {
            OrganizationChartDtos.Organization old = previousOrganizations.get(id);
            if (old == null) {
                changes.add(change("ORGANIZATION_ADDED", "ORGANIZATION", id.toString(),
                        organization.name(), null, organization.organizationType(), "ATTENTION"));
            } else if (!java.util.Objects.equals(
                    old.parentOrganizationId(), organization.parentOrganizationId())) {
                changes.add(change("ORGANIZATION_MOVED", "ORGANIZATION", id.toString(),
                        organization.name(), organizationName(previousOrganizations, old.parentOrganizationId()),
                        organizationName(currentOrganizations, organization.parentOrganizationId()), "CRITICAL"));
            } else if (!old.name().equals(organization.name())) {
                changes.add(change("ORGANIZATION_RENAMED", "ORGANIZATION", id.toString(),
                        organization.name(), old.name(), organization.name(), "ATTENTION"));
            }
        });
        previousOrganizations.forEach((id, organization) -> {
            if (!currentOrganizations.containsKey(id)) {
                changes.add(change("ORGANIZATION_REMOVED", "ORGANIZATION", id.toString(),
                        organization.name(), organization.organizationType(), null, "CRITICAL"));
            }
        });

        Map<UUID, OrganizationChartDtos.Person> previousPeople = previous.people().stream()
                .collect(Collectors.toMap(OrganizationChartDtos.Person::personId, Function.identity()));
        Map<UUID, OrganizationChartDtos.Person> currentPeople = current.people().stream()
                .collect(Collectors.toMap(OrganizationChartDtos.Person::personId, Function.identity()));
        currentPeople.forEach((id, person) -> {
            OrganizationChartDtos.Person old = previousPeople.get(id);
            if (old == null) {
                changes.add(change("PERSON_JOINED_SCOPE", "PERSON", id.toString(), person.displayName(),
                        null, organizationName(currentOrganizations, person.organizationId()), "ATTENTION"));
                return;
            }
            if (!old.organizationId().equals(person.organizationId())) {
                changes.add(change("PERSON_MOVED", "PERSON", id.toString(), person.displayName(),
                        organizationName(previousOrganizations, old.organizationId()),
                        organizationName(currentOrganizations, person.organizationId()), "ATTENTION"));
            }
            if (!java.util.Objects.equals(old.managerPersonId(), person.managerPersonId())) {
                changes.add(change("MANAGER_CHANGED", "PERSON", id.toString(), person.displayName(),
                        personName(previousPeople, old.managerPersonId()),
                        personName(currentPeople, person.managerPersonId()), "ATTENTION"));
            }
        });
        previousPeople.forEach((id, person) -> {
            if (!currentPeople.containsKey(id)) {
                changes.add(change("PERSON_LEFT_SCOPE", "PERSON", id.toString(), person.displayName(),
                        organizationName(previousOrganizations, person.organizationId()), null, "ATTENTION"));
            }
        });

        Set<String> previousPositions = previous.openPositions().stream()
                .map(OrganizationChartDtos.OpenPosition::positionKey).collect(Collectors.toSet());
        Set<String> currentPositions = current.openPositions().stream()
                .map(OrganizationChartDtos.OpenPosition::positionKey).collect(Collectors.toSet());
        current.openPositions().stream()
                .filter(position -> !previousPositions.contains(position.positionKey()))
                .forEach(position -> changes.add(change(
                        "POSITION_OPENED", "POSITION", position.positionKey(), position.title(),
                        null, organizationName(currentOrganizations, position.organizationId()), "ATTENTION")));
        previous.openPositions().stream()
                .filter(position -> !currentPositions.contains(position.positionKey()))
                .forEach(position -> changes.add(change(
                        "POSITION_FILLED_OR_CLOSED", "POSITION", position.positionKey(), position.title(),
                        organizationName(previousOrganizations, position.organizationId()), null, "HEALTHY")));
        return changes;
    }

    private OrganizationIntelligenceDtos.ComparisonSummary comparison(
            OrganizationChartDtos.OrganizationChart previous,
            OrganizationChartDtos.OrganizationChart current,
            List<OrganizationIntelligenceDtos.Change> changes) {
        return new OrganizationIntelligenceDtos.ComparisonSummary(
                current.metrics().headcount() - previous.metrics().headcount(),
                current.metrics().organizationCount() - previous.metrics().organizationCount(),
                current.metrics().managerCount() - previous.metrics().managerCount(),
                current.metrics().openPositionCount() - previous.metrics().openPositionCount(),
                countChanges(changes, "PERSON_MOVED"), countChanges(changes, "MANAGER_CHANGED"),
                countChanges(changes, "ORGANIZATION_MOVED"), changes.size(),
                decimal(current.metrics().plannedFte()).subtract(decimal(previous.metrics().plannedFte())),
                decimal(current.metrics().workforceCostAmount())
                        .subtract(decimal(previous.metrics().workforceCostAmount())),
                current.metrics().costCurrency(),
                round(current.analysis().averageManagerSpan()
                        - previous.analysis().averageManagerSpan()),
                current.analysis().maximumLayers() - previous.analysis().maximumLayers(),
                current.analysis().healthScore() - previous.analysis().healthScore(),
                current.analysis().dataQualityScore() - previous.analysis().dataQualityScore());
    }

    private Set<UUID> cycleNodes(OrganizationChartDtos.OrganizationChart chart) {
        Map<UUID, UUID> parentByChild = chart.organizations().stream()
                .filter(organization -> organization.parentOrganizationId() != null)
                .collect(Collectors.toMap(
                        OrganizationChartDtos.Organization::organizationId,
                        OrganizationChartDtos.Organization::parentOrganizationId,
                        (first, ignored) -> first));
        Set<UUID> cycles = new HashSet<>();
        for (UUID start : parentByChild.keySet()) {
            Set<UUID> path = new HashSet<>();
            UUID current = start;
            while (current != null && path.add(current)) current = parentByChild.get(current);
            if (current != null) cycles.add(current);
        }
        return cycles;
    }

    private OrganizationIntelligenceDtos.Change change(
            String type,
            String entityType,
            String entityId,
            String entityName,
            String from,
            String to,
            String risk) {
        return new OrganizationIntelligenceDtos.Change(
                type, entityType, entityId, entityName, from, to, risk);
    }

    private int countChanges(List<OrganizationIntelligenceDtos.Change> changes, String type) {
        return (int) changes.stream().filter(change -> type.equals(change.changeType())).count();
    }

    private String organizationName(
            Map<UUID, OrganizationChartDtos.Organization> organizations,
            UUID id) {
        if (id == null) return null;
        OrganizationChartDtos.Organization organization = organizations.get(id);
        return organization == null ? id.toString() : organization.name();
    }

    private String personName(Map<UUID, OrganizationChartDtos.Person> people, UUID id) {
        if (id == null) return null;
        OrganizationChartDtos.Person person = people.get(id);
        return person == null ? id.toString() : person.displayName();
    }

    private double median(List<Integer> values) {
        if (values.isEmpty()) return 0;
        List<Integer> ordered = values.stream().sorted().toList();
        int middle = ordered.size() / 2;
        return ordered.size() % 2 == 0
                ? round((ordered.get(middle - 1) + ordered.get(middle)) / 2.0)
                : ordered.get(middle);
    }

    private double percentage(long numerator, long denominator) {
        return denominator == 0 ? 0 : round(numerator * 100.0 / denominator);
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

}
