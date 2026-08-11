package com.dwp.services.people.organization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrganizationChartService {

    private static final OrganizationChartDtos.DesignPolicy DEFAULT_POLICY =
            new OrganizationChartDtos.DesignPolicy(3, 9, 7, 20.0, 15.0);

    private final OrganizationChartRepository repository;
    private final OrganizationScenarioRepository scenarioRepository;

    public OrganizationChartService(
            OrganizationChartRepository repository,
            OrganizationScenarioRepository scenarioRepository) {
        this.repository = repository;
        this.scenarioRepository = scenarioRepository;
    }

    @Transactional(readOnly = true)
    public OrganizationChartDtos.OrganizationChart get(
            LocalDate requestedAsOf,
            UUID requestedRoot,
            int requestedDepth) {
        return get(requestedAsOf, requestedRoot, requestedDepth, null);
    }

    @Transactional(readOnly = true)
    public OrganizationChartDtos.OrganizationChart getDirectory(
            LocalDate requestedAsOf,
            UUID requestedRoot,
            int requestedDepth) {
        return directoryProjection(get(requestedAsOf, requestedRoot, requestedDepth, null));
    }

    @Transactional(readOnly = true)
    public OrganizationChartDtos.OrganizationChart get(
            LocalDate requestedAsOf,
            UUID requestedRoot,
            int requestedDepth,
            UUID scenarioId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        OrganizationScenarioRepository.ScenarioRecord scenario = scenarioId == null
                ? null
                : scenarioRepository.scenario(actor.tenantId(), scenarioId)
                        .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "Scenario not found."));
        LocalDate asOf = requestedAsOf == null
                ? (scenario == null ? LocalDate.now() : scenario.baselineDate())
                : requestedAsOf;
        int depth = Math.min(12, Math.max(1, requestedDepth));

        List<OrganizationChartRepository.OrganizationRow> allOrganizations =
                repository.organizations(actor.tenantId(), asOf);
        if (scenario != null) {
            allOrganizations = applyScenarioMoves(
                    allOrganizations,
                    scenarioRepository.moves(actor.tenantId(), scenario.scenarioId()));
        }
        if (allOrganizations.isEmpty()) {
            throw new BaseException(ErrorCode.NOT_FOUND, "No active organization is available.");
        }

        Map<UUID, OrganizationChartRepository.OrganizationRow> organizationById = allOrganizations
                .stream()
                .collect(Collectors.toMap(
                        OrganizationChartRepository.OrganizationRow::publicId,
                        Function.identity()));
        UUID effectiveRoot = requestedRoot;
        OrganizationChartRepository.OrganizationRow root = selectRoot(
                allOrganizations, organizationById, effectiveRoot);
        Set<UUID> includedOrganizationIds = descendants(root.publicId(), allOrganizations, depth);
        List<OrganizationChartRepository.OrganizationRow> organizations = allOrganizations.stream()
                .filter(organization -> includedOrganizationIds.contains(organization.publicId()))
                .toList();

        List<OrganizationChartRepository.PersonRow> allPersonRows =
                repository.people(actor.tenantId(), asOf);
        List<OrganizationChartRepository.PersonRow> personRows = allPersonRows.stream()
                .filter(person -> includedOrganizationIds.contains(person.organizationPublicId()))
                .toList();
        Map<String, OrganizationChartRepository.PersonRow> personByAssignment = allPersonRows.stream()
                .filter(person -> person.assignmentKey() != null)
                .collect(Collectors.toMap(
                        OrganizationChartRepository.PersonRow::assignmentKey,
                        Function.identity(),
                        (first, ignored) -> first));
        Map<String, Integer> reportCountByAssignment = new HashMap<>();
        allPersonRows.stream()
                .map(OrganizationChartRepository.PersonRow::managerAssignmentKey)
                .filter(manager -> manager != null && !manager.isBlank())
                .forEach(manager -> reportCountByAssignment.merge(manager, 1, Integer::sum));

        boolean canViewWorkerNumber = actor.hasAnyRole(
                "ADMIN", "HR_ADMIN", "PEOPLE_ADMIN");
        List<OrganizationChartDtos.Person> people = personRows.stream()
                .map(person -> toPerson(
                        person,
                        personByAssignment,
                        reportCountByAssignment,
                        canViewWorkerNumber))
                .toList();
        Map<UUID, OrganizationChartDtos.Person> peopleById = people.stream()
                .collect(Collectors.toMap(OrganizationChartDtos.Person::personId, Function.identity()));
        Map<UUID, List<OrganizationChartDtos.Person>> peopleByOrganization = people.stream()
                .collect(Collectors.groupingBy(OrganizationChartDtos.Person::organizationId));

        List<OrganizationChartRepository.PositionRow> allPositionRows =
                repository.positions(actor.tenantId(), asOf);
        if (scenario != null) {
            allPositionRows = applyScenarioPositionCreates(
                    allPositionRows,
                    scenarioRepository.positionCreates(actor.tenantId(), scenario.scenarioId()));
            allPositionRows = applyScenarioPositionMoves(
                    allPositionRows,
                    scenarioRepository.positionMoves(actor.tenantId(), scenario.scenarioId()));
            allPositionRows = applyScenarioPositionCloses(
                    allPositionRows,
                    scenarioRepository.positionCloses(actor.tenantId(), scenario.scenarioId()));
        }
        List<OrganizationChartRepository.PositionRow> positionRows = allPositionRows
                .stream()
                .filter(position -> includedOrganizationIds.contains(position.organizationPublicId()))
                .toList();
        Map<UUID, List<UUID>> incumbentIdsByPosition = people.stream()
                .filter(person -> person.positionId() != null)
                .collect(Collectors.groupingBy(
                        OrganizationChartDtos.Person::positionId,
                        Collectors.mapping(OrganizationChartDtos.Person::personId, Collectors.toList())));
        Map<UUID, Integer> subordinatePositionCount = new HashMap<>();
        positionRows.stream()
                .map(OrganizationChartRepository.PositionRow::reportsToPositionPublicId)
                .filter(parentId -> parentId != null)
                .forEach(parentId -> subordinatePositionCount.merge(parentId, 1, Integer::sum));
        List<OrganizationChartDtos.Position> positions = positionRows.stream()
                .map(position -> toPosition(
                        position,
                        incumbentIdsByPosition,
                        subordinatePositionCount))
                .toList();
        Set<UUID> effectivelyOpenPositionIds = positionRows.stream()
                .filter(position -> isVacantPosition(
                        effectivePositionStatus(position, incumbentIdsByPosition)))
                .map(OrganizationChartRepository.PositionRow::publicId)
                .collect(Collectors.toSet());
        List<OrganizationChartDtos.OpenPosition> openPositions = positionRows.stream()
                .filter(position -> effectivelyOpenPositionIds.contains(position.publicId()))
                .map(this::toOpenPosition)
                .toList();
        Map<UUID, Integer> openPositionsByOrganization = openPositions.stream()
                .collect(Collectors.toMap(
                        OrganizationChartDtos.OpenPosition::organizationId,
                        ignored -> 1,
                        Integer::sum));

        Map<UUID, List<UUID>> children = organizations.stream()
                .filter(organization -> organization.parentPublicId() != null)
                .collect(Collectors.groupingBy(
                        OrganizationChartRepository.OrganizationRow::parentPublicId,
                        Collectors.mapping(
                                OrganizationChartRepository.OrganizationRow::publicId,
                                Collectors.toList())));
        Map<UUID, Integer> layerByOrganization = organizationLayers(root.publicId(), children);
        Map<UUID, Integer> totalHeadcount = new HashMap<>();
        Map<UUID, Integer> directHeadcount = peopleByOrganization.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size()));
        OrganizationChartDtos.DesignPolicy policy = designPolicy(actor.tenantId());

        List<OrganizationChartDtos.Organization> organizationDtos = organizations.stream()
                .map(organization -> toOrganization(
                        organization,
                        children,
                        peopleById,
                        peopleByOrganization,
                        directHeadcount,
                        totalHeadcount,
                        openPositionsByOrganization,
                        layerByOrganization,
                        policy))
                .toList();

        List<OrganizationChartDtos.Relationship> relationships = new ArrayList<>();
        organizations.stream()
                .filter(organization -> organization.parentPublicId() != null)
                .filter(organization -> includedOrganizationIds.contains(organization.parentPublicId()))
                .map(organization -> new OrganizationChartDtos.Relationship(
                        organization.publicId(),
                        organization.parentPublicId(),
                        "SUPERVISORY",
                        true))
                .forEach(relationships::add);
        repository.relationships(actor.tenantId(), asOf).stream()
                .filter(relationship -> !"SUPERVISORY".equals(relationship.type()))
                .filter(relationship -> includedOrganizationIds.contains(relationship.childPublicId()))
                .filter(relationship -> includedOrganizationIds.contains(relationship.parentPublicId()))
                .map(relationship -> new OrganizationChartDtos.Relationship(
                        relationship.childPublicId(),
                        relationship.parentPublicId(),
                        relationship.type(),
                        relationship.primary()))
                .forEach(relationships::add);

        int activeHeadcount = countPeople(people, "ACTIVE", null);
        int onLeaveHeadcount = countPeople(people, "LEAVE", null);
        int contingentHeadcount = countPeople(people, null, "CONTINGENT");
        int managerCount = (int) people.stream()
                .filter(person -> person.directReportCount() > 0)
                .count();
        int locationCount = (int) people.stream()
                .map(OrganizationChartDtos.Person::locationKey)
                .filter(location -> location != null && !location.isBlank())
                .distinct()
                .count();
        BigDecimal plannedFte = positions.stream()
                .map(OrganizationChartDtos.Position::budgetedFte)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal workforceCost = positions.stream()
                .map(OrganizationChartDtos.Position::annualCostAmount)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = positionCurrency(positions);
        OrganizationChartDtos.Analysis analysis = analyze(
                people,
                organizationDtos,
                openPositions.size(),
                policy);

        return new OrganizationChartDtos.OrganizationChart(
                asOf,
                new OrganizationChartDtos.Company(
                        root.publicId(), root.key(), root.name(), root.description()),
                scenario == null ? null : new OrganizationChartDtos.ScenarioProjection(
                        scenario.scenarioId(),
                        scenario.name(),
                        scenario.lifecycleState(),
                        scenario.baselineDate(),
                        scenario.effectiveDate(),
                        scenarioRepository.changes(actor.tenantId(), scenario.scenarioId()).size(),
                        scenario.version()),
                new OrganizationChartDtos.Metrics(
                        people.size(),
                        activeHeadcount,
                        onLeaveHeadcount,
                        contingentHeadcount,
                        organizationDtos.size(),
                        managerCount,
                        openPositions.size(),
                        locationCount,
                        plannedFte.setScale(2, RoundingMode.HALF_UP),
                        workforceCost.setScale(2, RoundingMode.HALF_UP),
                        currency),
                analysis,
                organizationDtos,
                people,
                positions,
                List.copyOf(relationships),
                openPositions);
    }

    private OrganizationChartDtos.OrganizationChart directoryProjection(
            OrganizationChartDtos.OrganizationChart chart) {
        List<OrganizationChartDtos.Organization> organizations = chart.organizations().stream()
                .map(organization -> new OrganizationChartDtos.Organization(
                        organization.organizationId(),
                        organization.organizationKey(),
                        organization.name(),
                        organization.shortName(),
                        organization.organizationType(),
                        organization.organizationTypeName(),
                        organization.parentOrganizationId(),
                        organization.description(),
                        null,
                        organization.colorToken(),
                        organization.directHeadcount(),
                        organization.totalHeadcount(),
                        organization.managerCount(),
                        0,
                        organization.childOrganizationCount(),
                        organization.leaderPersonId(),
                        organization.directMemberIds(),
                        organization.layerDepth(),
                        organization.averageManagerSpan(),
                        organization.contingentHeadcount(),
                        organization.healthStatus(),
                        List.of()))
                .toList();
        List<OrganizationChartDtos.Person> people = chart.people().stream()
                .map(person -> new OrganizationChartDtos.Person(
                        person.personId(),
                        "directory:" + person.personId(),
                        person.displayName(),
                        person.workEmail(),
                        person.businessTitle(),
                        person.jobProfileName(),
                        null,
                        null,
                        0,
                        person.managementLevel(),
                        person.organizationId(),
                        person.managerPersonId(),
                        person.managerReferenceMissing(),
                        null,
                        null,
                        null,
                        person.workerType(),
                        person.workerStatus(),
                        person.locationKey(),
                        person.locationName(),
                        person.directReportCount(),
                        BigDecimal.ZERO))
                .toList();
        OrganizationChartDtos.Metrics metrics = chart.metrics();
        return new OrganizationChartDtos.OrganizationChart(
                chart.asOf(),
                chart.company(),
                null,
                new OrganizationChartDtos.Metrics(
                        metrics.headcount(),
                        metrics.activeHeadcount(),
                        metrics.onLeaveHeadcount(),
                        metrics.contingentHeadcount(),
                        metrics.organizationCount(),
                        metrics.managerCount(),
                        0,
                        metrics.locationCount(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null),
                chart.analysis(),
                organizations,
                people,
                List.of(),
                chart.relationships(),
                List.of());
    }

    private List<OrganizationChartRepository.OrganizationRow> applyScenarioMoves(
            List<OrganizationChartRepository.OrganizationRow> organizations,
            List<OrganizationScenarioRepository.MoveRecord> moves) {
        Map<UUID, UUID> targetParentByOrganization = moves.stream()
                .collect(Collectors.toMap(
                        OrganizationScenarioRepository.MoveRecord::organizationId,
                        OrganizationScenarioRepository.MoveRecord::newParentId,
                        (first, second) -> second));
        return organizations.stream()
                .map(organization -> new OrganizationChartRepository.OrganizationRow(
                        organization.internalId(),
                        organization.publicId(),
                        organization.key(),
                        organization.name(),
                        organization.shortName(),
                        organization.type(),
                        organization.typeName(),
                        organization.typeRank(),
                        organization.rootCandidate(),
                        organization.description(),
                        organization.costCenterKey(),
                        organization.colorToken(),
                        targetParentByOrganization.getOrDefault(
                                organization.publicId(), organization.parentPublicId()),
                        organization.leaderPublicId()))
                .toList();
    }

    private List<OrganizationChartRepository.PositionRow> applyScenarioPositionMoves(
            List<OrganizationChartRepository.PositionRow> positions,
            List<OrganizationScenarioRepository.PositionMoveRecord> moves) {
        Map<UUID, UUID> targetParentByPosition = moves.stream()
                .collect(Collectors.toMap(
                        OrganizationScenarioRepository.PositionMoveRecord::positionId,
                        OrganizationScenarioRepository.PositionMoveRecord::newParentId,
                        (first, second) -> second));
        return positions.stream()
                .map(position -> new OrganizationChartRepository.PositionRow(
                        position.publicId(),
                        position.key(),
                        position.title(),
                        position.organizationPublicId(),
                        targetParentByPosition.getOrDefault(
                                position.publicId(), position.reportsToPositionPublicId()),
                        position.status(),
                        position.type(),
                        position.criticality(),
                        position.budgetedFte(),
                        position.annualCostAmount(),
                        position.costCurrency(),
                        position.jobProfileName(),
                        position.locationName(),
                        position.availabilityDate()))
                .toList();
    }

    private List<OrganizationChartRepository.PositionRow> applyScenarioPositionCreates(
            List<OrganizationChartRepository.PositionRow> positions,
            List<OrganizationScenarioRepository.PositionCreateRecord> creates) {
        if (creates.isEmpty()) return positions;
        List<OrganizationChartRepository.PositionRow> projected = new ArrayList<>(positions);
        creates.stream()
                .map(create -> new OrganizationChartRepository.PositionRow(
                        create.positionId(), create.positionKey(), create.title(),
                        create.organizationId(), create.parentPositionId(), "PLANNED",
                        create.positionType(), create.criticality(), create.budgetedFte(),
                        create.annualCostAmount(), create.costCurrency(), null, null,
                        create.availabilityDate()))
                .forEach(projected::add);
        return List.copyOf(projected);
    }

    private List<OrganizationChartRepository.PositionRow> applyScenarioPositionCloses(
            List<OrganizationChartRepository.PositionRow> positions,
            List<OrganizationScenarioRepository.PositionCloseRecord> closes) {
        Set<UUID> closedPositionIds = closes.stream()
                .map(OrganizationScenarioRepository.PositionCloseRecord::positionId)
                .collect(Collectors.toSet());
        return positions.stream()
                .filter(position -> !closedPositionIds.contains(position.publicId()))
                .toList();
    }

    private OrganizationChartRepository.OrganizationRow selectRoot(
            List<OrganizationChartRepository.OrganizationRow> organizations,
            Map<UUID, OrganizationChartRepository.OrganizationRow> byId,
            UUID requestedRoot) {
        if (requestedRoot != null) {
            OrganizationChartRepository.OrganizationRow selected = byId.get(requestedRoot);
            if (selected == null) throw new BaseException(ErrorCode.NOT_FOUND);
            return selected;
        }
        return organizations.stream()
                .filter(organization -> organization.parentPublicId() == null)
                .sorted(Comparator
                        .comparing(OrganizationChartRepository.OrganizationRow::rootCandidate)
                        .reversed()
                        .thenComparing(
                                organization -> organization.typeRank() == null
                                        ? Integer.MAX_VALUE
                                        : organization.typeRank())
                        .thenComparing(OrganizationChartRepository.OrganizationRow::key))
                .findFirst()
                .orElse(organizations.get(0));
    }

    private Set<UUID> descendants(
            UUID rootId,
            List<OrganizationChartRepository.OrganizationRow> organizations,
            int maximumDepth) {
        Map<UUID, List<UUID>> children = organizations.stream()
                .filter(organization -> organization.parentPublicId() != null)
                .collect(Collectors.groupingBy(
                        OrganizationChartRepository.OrganizationRow::parentPublicId,
                        Collectors.mapping(
                                OrganizationChartRepository.OrganizationRow::publicId,
                                Collectors.toList())));
        Set<UUID> included = new LinkedHashSet<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(rootId, 0));
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (!included.add(current.id()) || current.depth() >= maximumDepth) continue;
            children.getOrDefault(current.id(), List.of()).forEach(child ->
                    queue.addLast(new NodeDepth(child, current.depth() + 1)));
        }
        return Set.copyOf(included);
    }

    private Map<UUID, Integer> organizationLayers(UUID rootId, Map<UUID, List<UUID>> children) {
        Map<UUID, Integer> layers = new HashMap<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(rootId, 1));
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (layers.putIfAbsent(current.id(), current.depth()) != null) continue;
            children.getOrDefault(current.id(), List.of()).forEach(child ->
                    queue.addLast(new NodeDepth(child, current.depth() + 1)));
        }
        return layers;
    }

    private OrganizationChartDtos.Person toPerson(
            OrganizationChartRepository.PersonRow person,
            Map<String, OrganizationChartRepository.PersonRow> byAssignment,
            Map<String, Integer> reportCounts,
            boolean canViewWorkerNumber) {
        OrganizationChartRepository.PersonRow manager = person.managerAssignmentKey() == null
                ? null
                : byAssignment.get(person.managerAssignmentKey());
        return new OrganizationChartDtos.Person(
                person.publicId(),
                person.assignmentKey(),
                person.displayName(),
                person.workEmail(),
                person.businessTitle(),
                person.jobProfileName(),
                person.gradeKey(),
                person.gradeName(),
                person.gradeOrder(),
                person.managementLevel(),
                person.organizationPublicId(),
                manager == null ? null : manager.publicId(),
                person.managerAssignmentKey() != null && manager == null,
                person.positionPublicId(),
                person.positionKey(),
                canViewWorkerNumber ? person.workerNumber() : mask(person.workerNumber()),
                person.workerType(),
                person.workerStatus(),
                person.locationKey(),
                person.locationName(),
                reportCounts.getOrDefault(person.assignmentKey(), 0),
                person.fullTimeEquivalent());
    }

    private OrganizationChartDtos.Position toPosition(
            OrganizationChartRepository.PositionRow position,
            Map<UUID, List<UUID>> incumbentIdsByPosition,
            Map<UUID, Integer> subordinatePositionCount) {
        return new OrganizationChartDtos.Position(
                position.publicId(),
                position.key(),
                position.title(),
                position.organizationPublicId(),
                position.reportsToPositionPublicId(),
                effectivePositionStatus(position, incumbentIdsByPosition),
                position.type(),
                position.criticality(),
                position.budgetedFte(),
                position.annualCostAmount(),
                position.costCurrency(),
                position.jobProfileName(),
                position.locationName(),
                position.availabilityDate(),
                incumbentIdsByPosition.getOrDefault(position.publicId(), List.of()),
                subordinatePositionCount.getOrDefault(position.publicId(), 0));
    }

    private String effectivePositionStatus(
            OrganizationChartRepository.PositionRow position,
            Map<UUID, List<UUID>> incumbentIdsByPosition) {
        if ("FILLED".equals(position.status())
                && incumbentIdsByPosition.getOrDefault(position.publicId(), List.of()).isEmpty()) {
            return "OPEN";
        }
        return position.status();
    }

    private boolean isVacantPosition(String status) {
        return "OPEN".equals(status) || "PLANNED".equals(status);
    }

    private OrganizationChartDtos.OpenPosition toOpenPosition(
            OrganizationChartRepository.PositionRow position) {
        return new OrganizationChartDtos.OpenPosition(
                position.publicId(),
                position.key(),
                position.title(),
                position.organizationPublicId(),
                position.jobProfileName(),
                position.locationName(),
                position.availabilityDate(),
                position.budgetedFte(),
                position.annualCostAmount(),
                position.costCurrency(),
                position.criticality());
    }

    private OrganizationChartDtos.Organization toOrganization(
            OrganizationChartRepository.OrganizationRow organization,
            Map<UUID, List<UUID>> children,
            Map<UUID, OrganizationChartDtos.Person> peopleById,
            Map<UUID, List<OrganizationChartDtos.Person>> peopleByOrganization,
            Map<UUID, Integer> directHeadcount,
            Map<UUID, Integer> totalHeadcount,
            Map<UUID, Integer> openPositionsByOrganization,
            Map<UUID, Integer> layerByOrganization,
            OrganizationChartDtos.DesignPolicy policy) {
        List<OrganizationChartDtos.Person> directMembers = peopleByOrganization
                .getOrDefault(organization.publicId(), List.of())
                .stream()
                .sorted(personRank())
                .toList();
        OrganizationChartDtos.Person explicitLeader = organization.leaderPublicId() == null
                ? null
                : peopleById.get(organization.leaderPublicId());
        UUID leaderId = explicitLeader != null
                ? explicitLeader.personId()
                : directMembers.isEmpty() ? null : directMembers.get(0).personId();
        Set<UUID> subtree = descendantsOf(organization.publicId(), children);
        List<OrganizationChartDtos.Person> subtreePeople = subtree.stream()
                .flatMap(organizationId -> peopleByOrganization
                        .getOrDefault(organizationId, List.of())
                        .stream())
                .toList();
        List<Integer> managerSpans = subtreePeople.stream()
                .map(OrganizationChartDtos.Person::directReportCount)
                .filter(count -> count > 0)
                .toList();
        int subtreeManagerCount = managerSpans.size();
        double averageSpan = average(managerSpans);
        int contingentHeadcount = (int) subtreePeople.stream()
                .filter(person -> "CONTINGENT".equals(person.workerType()))
                .count();
        int subtreeOpenPositions = subtree.stream()
                .mapToInt(id -> openPositionsByOrganization.getOrDefault(id, 0))
                .sum();
        List<String> healthSignals = organizationHealthSignals(
                layerByOrganization.getOrDefault(organization.publicId(), 1),
                subtreePeople.size(),
                contingentHeadcount,
                subtreeOpenPositions,
                managerSpans,
                averageSpan,
                policy);
        String healthStatus = OrganizationHealthPolicy.status(healthSignals);
        return new OrganizationChartDtos.Organization(
                organization.publicId(),
                organization.key(),
                organization.name(),
                organization.shortName(),
                organization.type(),
                organization.typeName(),
                organization.parentPublicId(),
                organization.description(),
                organization.costCenterKey(),
                organization.colorToken(),
                directMembers.size(),
                totalHeadcount(
                        organization.publicId(),
                        children,
                        directHeadcount,
                        totalHeadcount,
                        new HashSet<>()),
                subtreeManagerCount,
                subtreeOpenPositions,
                children.getOrDefault(organization.publicId(), List.of()).size(),
                leaderId,
                directMembers.stream().map(OrganizationChartDtos.Person::personId).toList(),
                layerByOrganization.getOrDefault(organization.publicId(), 1),
                round(averageSpan),
                contingentHeadcount,
                healthStatus,
                healthSignals);
    }

    private Comparator<OrganizationChartDtos.Person> personRank() {
        return Comparator
                .comparingInt(OrganizationChartDtos.Person::directReportCount)
                .thenComparingInt(OrganizationChartDtos.Person::jobGradeOrder)
                .reversed()
                .thenComparing(OrganizationChartDtos.Person::displayName);
    }

    private Set<UUID> descendantsOf(UUID root, Map<UUID, List<UUID>> children) {
        Set<UUID> result = new LinkedHashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (!result.add(current)) continue;
            queue.addAll(children.getOrDefault(current, List.of()));
        }
        return result;
    }

    private List<String> organizationHealthSignals(
            int layer,
            int headcount,
            int contingent,
            int vacancies,
            List<Integer> managerSpans,
            double averageSpan,
            OrganizationChartDtos.DesignPolicy policy) {
        List<String> signals = new ArrayList<>();
        if (!managerSpans.isEmpty() && averageSpan < policy.minimumManagerSpan()) {
            signals.add("NARROW_SPAN");
        }
        if (managerSpans.stream().anyMatch(span -> span > policy.maximumManagerSpan())) {
            signals.add("WIDE_SPAN");
        }
        if (layer > policy.maximumLayers()) signals.add("EXCESS_LAYERS");
        if (percentage(contingent, headcount) > policy.maximumContingentPercent()) {
            signals.add("HIGH_CONTINGENT");
        }
        if (percentage(vacancies, headcount + vacancies) > policy.maximumVacancyPercent()) {
            signals.add("HIGH_VACANCY");
        }
        return List.copyOf(signals);
    }

    private OrganizationChartDtos.Analysis analyze(
            List<OrganizationChartDtos.Person> people,
            List<OrganizationChartDtos.Organization> organizations,
            int openPositionCount,
            OrganizationChartDtos.DesignPolicy policy) {
        List<Integer> spans = people.stream()
                .map(OrganizationChartDtos.Person::directReportCount)
                .filter(span -> span > 0)
                .toList();
        int narrow = (int) spans.stream().filter(span -> span < policy.minimumManagerSpan()).count();
        int wide = (int) spans.stream().filter(span -> span > policy.maximumManagerSpan()).count();
        int single = (int) spans.stream().filter(span -> span == 1).count();
        int missingManager = (int) people.stream()
                .filter(OrganizationChartDtos.Person::managerReferenceMissing)
                .count();
        int missingGrade = (int) people.stream()
                .filter(person -> person.jobGradeKey() == null || person.jobGradeKey().isBlank())
                .count();
        int orphanOrganizations = (int) organizations.stream()
                .filter(organization -> organization.parentOrganizationId() == null)
                .skip(1)
                .count();
        int maximumLayers = reportingLayers(people);
        double managerRatio = percentage(spans.size(), people.size());
        int contingent = countPeople(people, null, "CONTINGENT");
        double contingentRatio = percentage(contingent, people.size());

        List<OrganizationChartDtos.AnalysisSignal> signals = new ArrayList<>();
        addSignal(signals, "NARROW_SPAN", "WARNING", narrow, null);
        addSignal(signals, "WIDE_SPAN", "CRITICAL", wide, null);
        addSignal(signals, "SINGLE_REPORT_MANAGER", "WARNING", single, null);
        addSignal(signals, "MISSING_MANAGER", "CRITICAL", missingManager, null);
        addSignal(signals, "MISSING_GRADE", "WARNING", missingGrade, null);
        addSignal(signals, "ORPHAN_ORGANIZATION", "CRITICAL", orphanOrganizations, null);
        if (maximumLayers > policy.maximumLayers()) {
            addSignal(
                    signals,
                    "EXCESS_LAYERS",
                    "CRITICAL",
                    maximumLayers - policy.maximumLayers(),
                    null);
        }
        if (contingentRatio > policy.maximumContingentPercent()) {
            addSignal(signals, "HIGH_CONTINGENT", "WARNING", contingent, null);
        }
        double vacancyRatio = percentage(openPositionCount, people.size() + openPositionCount);
        if (vacancyRatio > policy.maximumVacancyPercent()) {
            addSignal(signals, "HIGH_VACANCY", "WARNING", openPositionCount, null);
        }

        int qualityPenalty = missingManager * 8 + missingGrade * 4 + orphanOrganizations * 15;
        int healthPenalty = narrow * 3 + wide * 6 + single * 2
                + Math.max(0, maximumLayers - policy.maximumLayers()) * 5;
        if (contingentRatio > policy.maximumContingentPercent()) healthPenalty += 8;
        if (vacancyRatio > policy.maximumVacancyPercent()) healthPenalty += 8;
        return new OrganizationChartDtos.Analysis(
                Math.max(0, 100 - healthPenalty),
                Math.max(0, 100 - qualityPenalty),
                round(average(spans)),
                maximumLayers,
                round(managerRatio),
                round(contingentRatio),
                narrow,
                wide,
                single,
                missingManager,
                missingGrade,
                orphanOrganizations,
                policy,
                List.copyOf(signals));
    }

    private int reportingLayers(List<OrganizationChartDtos.Person> people) {
        Map<UUID, List<UUID>> reports = people.stream()
                .filter(person -> person.managerPersonId() != null)
                .collect(Collectors.groupingBy(
                        OrganizationChartDtos.Person::managerPersonId,
                        Collectors.mapping(OrganizationChartDtos.Person::personId, Collectors.toList())));
        Set<UUID> ids = people.stream()
                .map(OrganizationChartDtos.Person::personId)
                .collect(Collectors.toSet());
        List<UUID> roots = people.stream()
                .filter(person -> person.managerPersonId() == null
                        || !ids.contains(person.managerPersonId()))
                .map(OrganizationChartDtos.Person::personId)
                .toList();
        int maximum = people.isEmpty() ? 0 : 1;
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        roots.forEach(root -> queue.add(new NodeDepth(root, 1)));
        Set<UUID> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (!visited.add(current.id())) continue;
            maximum = Math.max(maximum, current.depth());
            reports.getOrDefault(current.id(), List.of()).forEach(report ->
                    queue.addLast(new NodeDepth(report, current.depth() + 1)));
        }
        return maximum;
    }

    private OrganizationChartDtos.DesignPolicy designPolicy(Long tenantId) {
        Optional<OrganizationChartRepository.DesignPolicyRow> optional =
                repository.designPolicy(tenantId);
        if (optional == null || optional.isEmpty()) return DEFAULT_POLICY;
        OrganizationChartRepository.DesignPolicyRow policy = optional.get();
        return new OrganizationChartDtos.DesignPolicy(
                policy.minimumManagerSpan(),
                policy.maximumManagerSpan(),
                policy.maximumLayers(),
                policy.maximumContingentPercent().doubleValue(),
                policy.maximumVacancyPercent().doubleValue());
    }

    private String positionCurrency(List<OrganizationChartDtos.Position> positions) {
        List<String> currencies = positions.stream()
                .map(OrganizationChartDtos.Position::costCurrency)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (currencies.isEmpty()) return null;
        return currencies.size() == 1 ? currencies.get(0) : "MIXED";
    }

    private int totalHeadcount(
            UUID organizationId,
            Map<UUID, List<UUID>> children,
            Map<UUID, Integer> directHeadcount,
            Map<UUID, Integer> memo,
            Set<UUID> visiting) {
        Integer cached = memo.get(organizationId);
        if (cached != null) return cached;
        if (!visiting.add(organizationId)) return directHeadcount.getOrDefault(organizationId, 0);
        int total = directHeadcount.getOrDefault(organizationId, 0);
        for (UUID child : children.getOrDefault(organizationId, List.of())) {
            total += totalHeadcount(child, children, directHeadcount, memo, visiting);
        }
        visiting.remove(organizationId);
        memo.put(organizationId, total);
        return total;
    }

    private int countPeople(
            List<OrganizationChartDtos.Person> people,
            String status,
            String type) {
        return (int) people.stream()
                .filter(person -> status == null || status.equals(person.workerStatus()))
                .filter(person -> type == null || type.equals(person.workerType()))
                .count();
    }

    private double average(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private double percentage(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (numerator * 100.0) / denominator;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private void addSignal(
            List<OrganizationChartDtos.AnalysisSignal> signals,
            String code,
            String severity,
            int count,
            UUID organizationId) {
        if (count > 0) {
            signals.add(new OrganizationChartDtos.AnalysisSignal(
                    code, severity, count, organizationId));
        }
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return value;
        int suffixLength = Math.min(4, value.length());
        return "******" + value.substring(value.length() - suffixLength);
    }

    private record NodeDepth(UUID id, int depth) {
    }
}
