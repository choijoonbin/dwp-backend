package com.dwp.services.people.organization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Pure graph constraints shared by scenario editing, submit, and publish. */
final class OrganizationScenarioGraphValidator {

    private OrganizationScenarioGraphValidator() {
    }

    static void validateOrganizations(
            OrganizationChartDtos.OrganizationChart chart,
            List<OrganizationScenarioRepository.MoveRecord> moves) {
        Set<UUID> organizations = chart.organizations().stream()
                .map(OrganizationChartDtos.Organization::organizationId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, UUID> parents = new HashMap<>();
        chart.organizations().forEach(organization -> {
            if (organization.parentOrganizationId() != null) {
                parents.put(organization.organizationId(), organization.parentOrganizationId());
            }
        });
        for (OrganizationScenarioRepository.MoveRecord move : moves) {
            if (!organizations.contains(move.organizationId())
                    || !organizations.contains(move.newParentId())) {
                throw invalid("Every scenario target must belong to the effective organization scope.");
            }
            if (move.organizationId().equals(chart.company().organizationId())) {
                throw invalid("The company root cannot be moved.");
            }
            parents.put(move.organizationId(), move.newParentId());
        }
        requireAcyclic(organizations, parents,
                "The proposed scenario creates a supervisory cycle.");
    }

    static void validatePositionMoves(
            OrganizationChartDtos.OrganizationChart chart,
            List<OrganizationScenarioRepository.PositionMoveRecord> moves) {
        Set<UUID> positions = chart.positions().stream()
                .map(OrganizationChartDtos.Position::positionId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, UUID> parents = new HashMap<>();
        chart.positions().forEach(position -> {
            if (position.reportsToPositionId() != null) {
                parents.put(position.positionId(), position.reportsToPositionId());
            }
        });
        for (OrganizationScenarioRepository.PositionMoveRecord move : moves) {
            if (!positions.contains(move.positionId())
                    || !positions.contains(move.newParentId())) {
                throw invalid("Every position scenario target must belong to the effective organization scope.");
            }
            parents.put(move.positionId(), move.newParentId());
        }
        requireAcyclic(positions, parents,
                "The proposed scenario creates a position hierarchy cycle.");
    }

    static void validateProjectedPositions(OrganizationChartDtos.OrganizationChart chart) {
        Set<UUID> positions = chart.positions().stream()
                .map(OrganizationChartDtos.Position::positionId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, UUID> parents = new HashMap<>();
        chart.positions().forEach(position -> {
            UUID parent = position.reportsToPositionId();
            if (parent == null) return;
            if (!positions.contains(parent)) {
                throw invalid("The proposed scenario leaves a position reporting to a closed or unavailable parent.");
            }
            parents.put(position.positionId(), parent);
        });
        requireAcyclic(positions, parents,
                "The proposed scenario creates a position hierarchy cycle.");
    }

    private static void requireAcyclic(
            Set<UUID> nodes, Map<UUID, UUID> parents, String message) {
        for (UUID node : nodes) {
            Set<UUID> path = new HashSet<>();
            UUID current = node;
            while (current != null) {
                if (!path.add(current)) throw invalid(message);
                current = parents.get(current);
            }
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
