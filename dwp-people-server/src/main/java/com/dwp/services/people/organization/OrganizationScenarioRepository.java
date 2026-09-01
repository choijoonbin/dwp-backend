package com.dwp.services.people.organization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Repository
public class OrganizationScenarioRepository extends OrganizationScenarioValidationRepository {
    public OrganizationScenarioRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    public record ScenarioRecord(
            UUID scenarioId,
            String scenarioKey,
            String name,
            LocalDate baselineDate,
            LocalDate effectiveDate,
            String baselineFingerprint,
            String lifecycleState,
            Long ownerUserId,
            long version) {
    }

    public record ApprovalRecord(
            UUID approvalId,
            UUID scenarioId,
            String requiredRoleCode,
            boolean separationOfDuties,
            String lifecycleState,
            Long requestedBy,
            Instant expiresAt,
            long version) {
    }

    public record OrganizationRecord(
            long internalId,
            UUID publicId,
            String name,
            UUID parentPublicId,
            String parentName) {
    }

    public record MoveRecord(UUID changeId, UUID organizationId, UUID newParentId) {
    }

    public record PositionRecord(
            long internalId,
            UUID publicId,
            String title,
            UUID parentPublicId,
            String parentTitle) {
    }

    public record PositionMoveRecord(UUID changeId, UUID positionId, UUID newParentId) {
    }

    public record PositionCreateRecord(
            UUID changeId,
            UUID positionId,
            String positionKey,
            String title,
            UUID organizationId,
            UUID parentPositionId,
            String positionType,
            String criticality,
            BigDecimal budgetedFte,
            BigDecimal annualCostAmount,
            String costCurrency,
            LocalDate availabilityDate) {
    }

    public record PositionCloseRecord(UUID changeId, UUID positionId) {
    }

    public record PositionPlanningRecord(
            long internalId,
            UUID publicId,
            String key,
            String title,
            UUID organizationPublicId,
            String organizationName,
            UUID parentPublicId,
            String parentTitle,
            String status,
            String type,
            String criticality,
            BigDecimal budgetedFte,
            BigDecimal annualCostAmount,
            String costCurrency,
            LocalDate availabilityDate,
            LocalDate validFrom,
            int incumbentCount,
            int subordinateCount) {
    }

}
