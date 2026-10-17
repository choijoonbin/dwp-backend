package com.dwp.services.people.hris.organization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.organization.OrganizationChartDtos;
import com.dwp.services.people.organization.OrganizationChartService;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Deterministic read adapter over the existing organization chart engine. */
@Service
public class OrganizationGraphService {

    static final String REPORTING = "REPORTING";
    static final String INVALID_QUERY = "INVALID_ORGANIZATION_GRAPH_QUERY";
    static final String UNSUPPORTED_MODEL = "UNSUPPORTED_RELATIONSHIP_MODEL";
    static final String AUTHORIZATION_DENIED = "OPAQUE_AUTHORIZATION_DENIED";
    static final String GRAPH_NOT_FOUND = "ORGANIZATION_GRAPH_NOT_FOUND";

    private static final Set<String> UNSUPPORTED_RELATIONSHIPS = Set.of("LEGAL", "COST");
    private static final Comparator<UUID> UUID_ORDER =
            Comparator.comparing(UUID::toString);
    private static final Comparator<String> UNICODE_CODE_POINT_ORDER =
            OrganizationGraphService::compareCodePoints;

    private final OrganizationChartService chartService;
    private final WorkforceAccessPolicyService accessPolicies;

    public OrganizationGraphService(
            OrganizationChartService chartService,
            WorkforceAccessPolicyService accessPolicies) {
        this.chartService = chartService;
        this.accessPolicies = accessPolicies;
    }

    @Transactional(readOnly = true)
    public OrganizationGraphWithPositionsV2 query(
            Instant asOf,
            UUID rootPublicId,
            Integer depth,
            String relationshipType,
            Boolean includePositions) {
        validate(asOf, rootPublicId, depth, relationshipType, includePositions);
        if (includePositions) requirePositionAuthority();

        LocalDate effectiveDate = LocalDate.ofInstant(asOf, ZoneOffset.UTC);
        int engineDepth = depth == 0 ? 1 : depth;
        OrganizationChartDtos.OrganizationChart chart;
        try {
            chart = chartService.get(effectiveDate, rootPublicId, engineDepth);
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw problem(HttpStatus.NOT_FOUND, GRAPH_NOT_FOUND,
                        "The requested organization graph is not available.");
            }
            if (exception.getErrorCode() == ErrorCode.FORBIDDEN) {
                throw denied();
            }
            throw exception;
        }

        return project(asOf, effectiveDate, rootPublicId, depth, includePositions, chart);
    }

    private void validate(
            Instant asOf,
            UUID rootPublicId,
            Integer depth,
            String relationshipType,
            Boolean includePositions) {
        if (asOf == null || rootPublicId == null || depth == null
                || includePositions == null || relationshipType == null
                || relationshipType.isBlank()) {
            throw invalid();
        }
        if (depth < 0 || depth > 12) throw invalid();
        if (UNSUPPORTED_RELATIONSHIPS.contains(relationshipType)) {
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, UNSUPPORTED_MODEL,
                    "The requested organization relationship model is not supported.");
        }
        if (!REPORTING.equals(relationshipType)) throw invalid();
    }

    private void requirePositionAuthority() {
        try {
            WorkforceAccessPolicyService.Decision decision = accessPolicies.require("READ");
            if (!decision.field("EMPLOYMENT")) throw denied();
        } catch (OrganizationGraphProblem problem) {
            throw problem;
        } catch (BaseException exception) {
            throw denied();
        }
    }

    private OrganizationGraphWithPositionsV2 project(
            Instant asOf,
            LocalDate effectiveDate,
            UUID rootPublicId,
            int requestedDepth,
            boolean includePositions,
            OrganizationChartDtos.OrganizationChart chart) {
        Map<UUID, OrganizationChartDtos.Organization> organizations = chart.organizations()
                .stream()
                .collect(Collectors.toMap(
                        OrganizationChartDtos.Organization::organizationId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        if (!organizations.containsKey(rootPublicId)) {
            throw problem(HttpStatus.NOT_FOUND, GRAPH_NOT_FOUND,
                    "The requested organization graph is not available.");
        }

        Map<UUID, List<UUID>> children = new HashMap<>();
        organizations.values().stream()
                .filter(organization -> organization.parentOrganizationId() != null)
                .filter(organization -> organizations.containsKey(
                        organization.parentOrganizationId()))
                .forEach(organization -> children.computeIfAbsent(
                        organization.parentOrganizationId(), ignored -> new ArrayList<>())
                        .add(organization.organizationId()));

        Map<UUID, Integer> relativeDepths = relativeDepths(rootPublicId, children);
        List<OrganizationGraphWithPositionsV2.OrganizationNodeV2> nodes = organizations.values()
                .stream()
                .filter(organization -> relativeDepths.containsKey(
                        organization.organizationId()))
                .filter(organization -> relativeDepths.get(
                        organization.organizationId()) <= requestedDepth)
                .map(organization -> new OrganizationGraphWithPositionsV2.OrganizationNodeV2(
                        organization.organizationId(),
                        organization.organizationId().equals(rootPublicId)
                                ? null : parentWithinProjection(
                                        organization.parentOrganizationId(), relativeDepths,
                                        requestedDepth),
                        organization.name(),
                        relativeDepths.get(organization.organizationId())))
                .sorted(Comparator
                        .comparingInt(OrganizationGraphWithPositionsV2.OrganizationNodeV2::depth)
                        .thenComparing(
                                OrganizationGraphWithPositionsV2.OrganizationNodeV2::organizationPublicId,
                                UUID_ORDER))
                .toList();
        Set<UUID> nodeIds = nodes.stream()
                .map(OrganizationGraphWithPositionsV2.OrganizationNodeV2::organizationPublicId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<OrganizationGraphWithPositionsV2.OrganizationPositionV2> positions =
                includePositions ? positions(chart.positions(), nodeIds) : List.of();

        return new OrganizationGraphWithPositionsV2(
                2, asOf, effectiveDate, rootPublicId, requestedDepth, REPORTING,
                includePositions, nodes, positions);
    }

    private UUID parentWithinProjection(
            UUID parentPublicId,
            Map<UUID, Integer> relativeDepths,
            int requestedDepth) {
        if (parentPublicId == null) return null;
        Integer parentDepth = relativeDepths.get(parentPublicId);
        return parentDepth != null && parentDepth <= requestedDepth ? parentPublicId : null;
    }

    private Map<UUID, Integer> relativeDepths(
            UUID rootPublicId,
            Map<UUID, List<UUID>> children) {
        Map<UUID, Integer> depths = new HashMap<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(rootPublicId, 0));
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() > 12 || depths.putIfAbsent(
                    current.publicId(), current.depth()) != null) continue;
            children.getOrDefault(current.publicId(), List.of()).stream()
                    .sorted(UUID_ORDER)
                    .forEach(child -> queue.addLast(
                            new NodeDepth(child, current.depth() + 1)));
        }
        return Map.copyOf(depths);
    }

    private List<OrganizationGraphWithPositionsV2.OrganizationPositionV2> positions(
            List<OrganizationChartDtos.Position> source,
            Set<UUID> nodeIds) {
        List<OrganizationChartDtos.Position> included = source.stream()
                .filter(position -> nodeIds.contains(position.organizationId()))
                .toList();
        Set<UUID> includedPositionIds = included.stream()
                .map(OrganizationChartDtos.Position::positionId)
                .collect(Collectors.toSet());
        Map<UUID, Integer> subordinateCounts = new HashMap<>();
        included.stream()
                .map(OrganizationChartDtos.Position::reportsToPositionId)
                .filter(includedPositionIds::contains)
                .forEach(parent -> subordinateCounts.merge(parent, 1, Integer::sum));

        return included.stream()
                .map(position -> new OrganizationGraphWithPositionsV2.OrganizationPositionV2(
                        position.positionId(),
                        position.positionKey(),
                        position.title(),
                        position.organizationId(),
                        includedPositionIds.contains(position.reportsToPositionId())
                                ? position.reportsToPositionId() : null,
                        position.status(),
                        position.positionType(),
                        position.criticality(),
                        position.budgetedFte(),
                        position.annualCostAmount(),
                        position.costCurrency(),
                        position.jobProfileName(),
                        position.locationName(),
                        position.availabilityDate(),
                        position.incumbentPersonIds().stream()
                                .distinct().sorted(UUID_ORDER).toList(),
                        subordinateCounts.getOrDefault(position.positionId(), 0)))
                .sorted(Comparator
                        .comparing(
                                OrganizationGraphWithPositionsV2.OrganizationPositionV2::organizationPublicId,
                                UUID_ORDER)
                        .thenComparing(
                                OrganizationGraphWithPositionsV2.OrganizationPositionV2::positionKey,
                                UNICODE_CODE_POINT_ORDER)
                        .thenComparing(
                                OrganizationGraphWithPositionsV2.OrganizationPositionV2::positionPublicId,
                                UUID_ORDER))
                .toList();
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            int compared = Integer.compare(leftCodePoint, rightCodePoint);
            if (compared != 0) return compared;
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }

    private OrganizationGraphProblem invalid() {
        return problem(HttpStatus.BAD_REQUEST, INVALID_QUERY,
                "The organization graph query is invalid.");
    }

    private OrganizationGraphProblem denied() {
        return problem(HttpStatus.FORBIDDEN, AUTHORIZATION_DENIED,
                "The requested organization graph is not available.");
    }

    private OrganizationGraphProblem problem(
            HttpStatus status,
            String code,
            String message) {
        return new OrganizationGraphProblem(status, code, message);
    }

    private record NodeDepth(UUID publicId, int depth) {
    }
}
