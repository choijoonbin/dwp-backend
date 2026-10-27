package com.dwp.services.people.organization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.PeopleRequestContext;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Scenario access, publish evidence, and changed-organization rules. */
final class OrganizationScenarioRules {

    static List<UUID> changedOrganizations(
            OrganizationChartDtos.OrganizationChart effectiveChart,
            OrganizationChartDtos.OrganizationChart projectedChart,
            List<OrganizationScenarioRepository.MoveRecord> moves,
            List<OrganizationScenarioRepository.PositionMoveRecord> positionMoves,
            List<OrganizationScenarioRepository.PositionCreateRecord> positionCreates,
            List<OrganizationScenarioRepository.PositionCloseRecord> positionCloses) {
        Map<UUID, OrganizationChartDtos.Organization> organizationById =
                effectiveChart.organizations().stream().collect(Collectors.toMap(
                        OrganizationChartDtos.Organization::organizationId,
                        Function.identity(),
                        (first, ignored) -> first));
        Map<UUID, UUID> organizationByPosition = java.util.stream.Stream.concat(
                        effectiveChart.positions().stream(),
                        projectedChart.positions().stream())
                .collect(Collectors.toMap(
                        OrganizationChartDtos.Position::positionId,
                        OrganizationChartDtos.Position::organizationId,
                        (first, ignored) -> first));
        Map<UUID, OrganizationChartDtos.Position> effectivePositionById =
                effectiveChart.positions().stream().collect(Collectors.toMap(
                        OrganizationChartDtos.Position::positionId,
                        Function.identity(),
                        (first, ignored) -> first));
        Set<UUID> changed = new LinkedHashSet<>();
        moves.forEach(move -> {
            changed.add(move.organizationId());
            changed.add(move.newParentId());
            OrganizationChartDtos.Organization current = organizationById.get(
                    move.organizationId());
            if (current != null && current.parentOrganizationId() != null) {
                changed.add(current.parentOrganizationId());
            }
        });
        positionCreates.forEach(create -> {
            changed.add(create.organizationId());
            addPositionOrganization(changed, organizationByPosition,
                    create.parentPositionId());
        });
        positionMoves.forEach(move -> {
            addPositionAndParentOrganizations(
                    changed, organizationByPosition, effectivePositionById,
                    move.positionId());
            addPositionOrganization(changed, organizationByPosition, move.newParentId());
        });
        positionCloses.forEach(close -> addPositionAndParentOrganizations(
                changed, organizationByPosition, effectivePositionById,
                close.positionId()));
        if (changed.isEmpty()) {
            throw new IllegalStateException(
                    "Published organization scenario has no changed organization identity.");
        }
        return changed.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
    }

    static void addPositionOrganization(
            Set<UUID> changed,
            Map<UUID, UUID> organizationByPosition,
            UUID positionId) {
        UUID organizationId = organizationByPosition.get(positionId);
        if (organizationId != null) changed.add(organizationId);
    }

    static void addPositionAndParentOrganizations(
            Set<UUID> changed,
            Map<UUID, UUID> organizationByPosition,
            Map<UUID, OrganizationChartDtos.Position> effectivePositionById,
            UUID positionId) {
        addPositionOrganization(changed, organizationByPosition, positionId);
        OrganizationChartDtos.Position position = effectivePositionById.get(positionId);
        if (position != null && position.reportsToPositionId() != null) {
            addPositionOrganization(
                    changed, organizationByPosition, position.reportsToPositionId());
        }
    }

    static void requirePlanner(PeopleRequestContext.Actor actor) {
        if (HcmPepContext.current() == null
                && !actor.hasAnyRole("HR_ADMIN", "ADMIN")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Organization design permission is required.");
        }
    }

    static void requireViewer(PeopleRequestContext.Actor actor) {
        if (HcmPepContext.current() == null
                && !actor.hasAnyRole("HR_ADMIN", "PEOPLE_ADMIN", "ADMIN")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Workforce data permission is required.");
        }
    }

    static void requireOwnerOrAdministrator(
            PeopleRequestContext.Actor actor,
            OrganizationScenarioRepository.ScenarioRecord scenario) {
        if (!scenario.ownerUserId().equals(actor.userId())
                && HcmPepContext.current() == null
                && !actor.hasAnyRole("HR_ADMIN", "ADMIN")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Only the owner can edit this scenario.");
        }
    }

    static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The scenario changed. Refresh and try again.");
        }
    }

    static void ensureNotBlocked(OrganizationScenarioDtos.DecisionPack decision) {
        if (decision.blockingIssueCount() > 0) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The organization decision pack contains blocking validation findings.");
        }
    }

    static UUID evidenceId(OrganizationScenarioDtos.DecisionPack decision) {
        if (decision.validationRunId() == null) {
            throw new IllegalStateException("Persisted organization validation evidence is required.");
        }
        return decision.validationRunId();
    }
}
