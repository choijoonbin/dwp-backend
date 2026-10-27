package com.dwp.services.people.organization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Applies scenario changes to the effective organization chart rows. */
final class OrganizationChartScenarioProjection {

    static List<OrganizationChartRepository.OrganizationRow> applyScenarioMoves(
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

    static List<OrganizationChartRepository.PositionRow> applyScenarioPositionMoves(
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

    static List<OrganizationChartRepository.PositionRow> applyScenarioPositionCreates(
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

    static List<OrganizationChartRepository.PositionRow> applyScenarioPositionCloses(
            List<OrganizationChartRepository.PositionRow> positions,
            List<OrganizationScenarioRepository.PositionCloseRecord> closes) {
        Set<UUID> closedPositionIds = closes.stream()
                .map(OrganizationScenarioRepository.PositionCloseRecord::positionId)
                .collect(Collectors.toSet());
        return positions.stream()
                .filter(position -> !closedPositionIds.contains(position.publicId()))
                .toList();
    }
}
