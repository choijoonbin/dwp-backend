package com.dwp.services.people.organization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrganizationChartService {

    private final OrganizationChartRepository repository;

    public OrganizationChartService(OrganizationChartRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public OrganizationChartDtos.OrganizationChart get(
            LocalDate requestedAsOf,
            UUID requestedRoot,
            int requestedDepth) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        LocalDate asOf = requestedAsOf == null ? LocalDate.now() : requestedAsOf;
        int depth = Math.min(10, Math.max(1, requestedDepth));

        List<OrganizationChartRepository.OrganizationRow> allOrganizations =
                repository.organizations(actor.tenantId(), asOf);
        if (allOrganizations.isEmpty()) {
            throw new BaseException(ErrorCode.NOT_FOUND, "No active organization is available.");
        }

        Map<UUID, OrganizationChartRepository.OrganizationRow> organizationById = allOrganizations
                .stream()
                .collect(Collectors.toMap(
                        OrganizationChartRepository.OrganizationRow::publicId,
                        Function.identity()));
        OrganizationChartRepository.OrganizationRow root = selectRoot(
                allOrganizations, organizationById, requestedRoot);
        Set<UUID> includedOrganizationIds = descendants(root.publicId(), allOrganizations, depth);
        List<OrganizationChartRepository.OrganizationRow> organizations = allOrganizations.stream()
                .filter(organization -> includedOrganizationIds.contains(organization.publicId()))
                .toList();

        List<OrganizationChartRepository.PersonRow> personRows = repository
                .people(actor.tenantId(), asOf)
                .stream()
                .filter(person -> includedOrganizationIds.contains(person.organizationPublicId()))
                .toList();
        Map<String, OrganizationChartRepository.PersonRow> personByAssignment = personRows.stream()
                .filter(person -> person.assignmentKey() != null)
                .collect(Collectors.toMap(
                        OrganizationChartRepository.PersonRow::assignmentKey,
                        Function.identity(),
                        (first, ignored) -> first));
        Map<String, Integer> reportCountByAssignment = new HashMap<>();
        personRows.stream()
                .map(OrganizationChartRepository.PersonRow::managerAssignmentKey)
                .filter(manager -> manager != null && !manager.isBlank())
                .forEach(manager -> reportCountByAssignment.merge(manager, 1, Integer::sum));

        boolean canViewWorkerNumber = actor.hasAnyRole(
                "ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN", "HR_ADMIN");
        List<OrganizationChartDtos.Person> people = personRows.stream()
                .map(person -> toPerson(
                        person,
                        personByAssignment,
                        reportCountByAssignment,
                        canViewWorkerNumber))
                .toList();
        Map<UUID, List<OrganizationChartDtos.Person>> peopleByOrganization = people.stream()
                .collect(Collectors.groupingBy(OrganizationChartDtos.Person::organizationId));

        List<OrganizationChartRepository.OpenPositionRow> openPositionRows = repository
                .openPositions(actor.tenantId())
                .stream()
                .filter(position -> includedOrganizationIds.contains(position.organizationPublicId()))
                .toList();
        Map<UUID, Integer> openPositionsByOrganization = openPositionRows.stream()
                .collect(Collectors.toMap(
                        OrganizationChartRepository.OpenPositionRow::organizationPublicId,
                        ignored -> 1,
                        Integer::sum));

        Map<UUID, List<UUID>> children = organizations.stream()
                .filter(organization -> organization.parentPublicId() != null)
                .collect(Collectors.groupingBy(
                        OrganizationChartRepository.OrganizationRow::parentPublicId,
                        Collectors.mapping(
                                OrganizationChartRepository.OrganizationRow::publicId,
                                Collectors.toList())));
        Map<UUID, Integer> totalHeadcount = new HashMap<>();
        Map<UUID, Integer> directHeadcount = peopleByOrganization.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size()));

        List<OrganizationChartDtos.Organization> organizationDtos = organizations.stream()
                .map(organization -> toOrganization(
                        organization,
                        children,
                        peopleByOrganization,
                        directHeadcount,
                        totalHeadcount,
                        openPositionsByOrganization))
                .toList();

        List<OrganizationChartDtos.Relationship> relationships = repository
                .relationships(actor.tenantId(), asOf)
                .stream()
                .filter(relationship -> includedOrganizationIds.contains(relationship.childPublicId()))
                .filter(relationship -> includedOrganizationIds.contains(relationship.parentPublicId()))
                .map(relationship -> new OrganizationChartDtos.Relationship(
                        relationship.childPublicId(),
                        relationship.parentPublicId(),
                        relationship.type(),
                        relationship.primary()))
                .toList();
        List<OrganizationChartDtos.OpenPosition> openPositions = openPositionRows.stream()
                .map(position -> new OrganizationChartDtos.OpenPosition(
                        position.positionKey(),
                        position.title(),
                        position.organizationPublicId(),
                        position.jobProfileName(),
                        position.locationName(),
                        position.availabilityDate()))
                .toList();

        int activeHeadcount = (int) people.stream()
                .filter(person -> "ACTIVE".equals(person.workerStatus()))
                .count();
        int onLeaveHeadcount = (int) people.stream()
                .filter(person -> "LEAVE".equals(person.workerStatus()))
                .count();
        int contingentHeadcount = (int) people.stream()
                .filter(person -> "CONTINGENT".equals(person.workerType()))
                .count();
        int managerCount = (int) people.stream()
                .filter(person -> person.directReportCount() > 0)
                .count();
        int locationCount = (int) people.stream()
                .map(OrganizationChartDtos.Person::locationKey)
                .filter(location -> location != null && !location.isBlank())
                .distinct()
                .count();

        return new OrganizationChartDtos.OrganizationChart(
                asOf,
                new OrganizationChartDtos.Company(
                        root.publicId(), root.key(), root.name(), root.description()),
                new OrganizationChartDtos.Metrics(
                        people.size(),
                        activeHeadcount,
                        onLeaveHeadcount,
                        contingentHeadcount,
                        organizationDtos.size(),
                        managerCount,
                        openPositions.size(),
                        locationCount),
                organizationDtos,
                people,
                relationships,
                openPositions);
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
                .filter(organization -> "COMPANY".equals(organization.type()))
                .filter(organization -> organization.parentPublicId() == null)
                .findFirst()
                .orElseGet(() -> organizations.stream()
                        .filter(organization -> organization.parentPublicId() == null)
                        .findFirst()
                        .orElse(organizations.get(0)));
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
                canViewWorkerNumber ? person.workerNumber() : mask(person.workerNumber()),
                person.workerType(),
                person.workerStatus(),
                person.locationKey(),
                person.locationName(),
                reportCounts.getOrDefault(person.assignmentKey(), 0));
    }

    private OrganizationChartDtos.Organization toOrganization(
            OrganizationChartRepository.OrganizationRow organization,
            Map<UUID, List<UUID>> children,
            Map<UUID, List<OrganizationChartDtos.Person>> peopleByOrganization,
            Map<UUID, Integer> directHeadcount,
            Map<UUID, Integer> totalHeadcount,
            Map<UUID, Integer> openPositionsByOrganization) {
        List<OrganizationChartDtos.Person> directMembers = peopleByOrganization
                .getOrDefault(organization.publicId(), List.of())
                .stream()
                .sorted(Comparator
                        .comparingInt(OrganizationChartDtos.Person::directReportCount)
                        .thenComparingInt(OrganizationChartDtos.Person::jobGradeOrder)
                        .reversed()
                        .thenComparing(OrganizationChartDtos.Person::displayName))
                .toList();
        UUID leaderId = directMembers.isEmpty() ? null : directMembers.get(0).personId();
        int managerCount = (int) directMembers.stream()
                .filter(person -> person.directReportCount() > 0)
                .count();
        return new OrganizationChartDtos.Organization(
                organization.publicId(),
                organization.key(),
                organization.name(),
                organization.shortName(),
                organization.type(),
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
                managerCount,
                openPositionsByOrganization.getOrDefault(organization.publicId(), 0),
                children.getOrDefault(organization.publicId(), List.of()).size(),
                leaderId,
                directMembers.stream().map(OrganizationChartDtos.Person::personId).toList());
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

    private String mask(String value) {
        if (value == null || value.isBlank()) return value;
        int suffixLength = Math.min(4, value.length());
        return "******" + value.substring(value.length() - suffixLength);
    }

    private record NodeDepth(UUID id, int depth) {
    }
}
