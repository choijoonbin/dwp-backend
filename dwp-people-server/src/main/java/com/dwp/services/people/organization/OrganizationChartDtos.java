package com.dwp.services.people.organization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OrganizationChartDtos {

    private OrganizationChartDtos() {
    }

    public record OrganizationChart(
            LocalDate asOf,
            Company company,
            ScenarioProjection scenario,
            Metrics metrics,
            Analysis analysis,
            List<Organization> organizations,
            List<Person> people,
            List<Position> positions,
            List<Relationship> relationships,
            List<OpenPosition> openPositions) {
    }

    public record Company(
            UUID organizationId,
            String organizationKey,
            String name,
            String description) {
    }

    public record Metrics(
            int headcount,
            int activeHeadcount,
            int onLeaveHeadcount,
            int contingentHeadcount,
            int organizationCount,
            int managerCount,
            int openPositionCount,
            int locationCount,
            BigDecimal plannedFte,
            BigDecimal workforceCostAmount,
            String costCurrency) {
    }

    public record Organization(
            UUID organizationId,
            String organizationKey,
            String name,
            String shortName,
            String organizationType,
            String organizationTypeName,
            UUID parentOrganizationId,
            String description,
            String costCenterKey,
            String colorToken,
            int directHeadcount,
            int totalHeadcount,
            int managerCount,
            int openPositionCount,
            int childOrganizationCount,
            UUID leaderPersonId,
            List<UUID> directMemberIds,
            int layerDepth,
            double averageManagerSpan,
            int contingentHeadcount,
            String healthStatus,
            List<String> healthSignals) {
    }

    public record Person(
            UUID personId,
            String assignmentKey,
            String displayName,
            String workEmail,
            String businessTitle,
            String jobProfileName,
            String jobGradeKey,
            String jobGradeName,
            int jobGradeOrder,
            String managementLevel,
            UUID organizationId,
            UUID managerPersonId,
            boolean managerReferenceMissing,
            UUID positionId,
            String positionKey,
            String workerNumber,
            String workerType,
            String workerStatus,
            String locationKey,
            String locationName,
            int directReportCount,
            BigDecimal fullTimeEquivalent) {
    }

    public record Position(
            UUID positionId,
            String positionKey,
            String title,
            UUID organizationId,
            UUID reportsToPositionId,
            String status,
            String positionType,
            String criticality,
            BigDecimal budgetedFte,
            BigDecimal annualCostAmount,
            String costCurrency,
            String jobProfileName,
            String locationName,
            LocalDate availabilityDate,
            List<UUID> incumbentPersonIds,
            int subordinatePositionCount) {
    }

    public record Relationship(
            UUID childOrganizationId,
            UUID parentOrganizationId,
            String relationshipType,
            boolean primaryRelationship) {
    }

    public record OpenPosition(
            UUID positionId,
            String positionKey,
            String title,
            UUID organizationId,
            String jobProfileName,
            String locationName,
            LocalDate availabilityDate,
            BigDecimal budgetedFte,
            BigDecimal annualCostAmount,
            String costCurrency,
            String criticality) {
    }

    public record ScenarioProjection(
            UUID scenarioId,
            String name,
            String lifecycleState,
            LocalDate baseAsOf,
            LocalDate effectiveDate,
            int activeChangeCount,
            long version) {
    }

    public record Analysis(
            int healthScore,
            int dataQualityScore,
            double averageManagerSpan,
            int maximumLayers,
            double managerRatioPercent,
            double contingentRatioPercent,
            int narrowSpanManagerCount,
            int wideSpanManagerCount,
            int singleReportManagerCount,
            int missingManagerCount,
            int missingGradeCount,
            int orphanOrganizationCount,
            DesignPolicy policy,
            List<AnalysisSignal> signals) {
    }

    public record DesignPolicy(
            int minimumManagerSpan,
            int maximumManagerSpan,
            int maximumLayers,
            double maximumContingentPercent,
            double maximumVacancyPercent) {
    }

    public record AnalysisSignal(
            String code,
            String severity,
            int count,
            UUID organizationId) {
    }
}
