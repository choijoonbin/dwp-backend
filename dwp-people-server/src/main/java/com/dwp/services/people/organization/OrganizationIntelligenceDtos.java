package com.dwp.services.people.organization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OrganizationIntelligenceDtos {

    private OrganizationIntelligenceDtos() {
    }

    public record Intelligence(
            LocalDate asOf,
            LocalDate compareTo,
            HealthSummary health,
            ComparisonSummary comparison,
            List<OrganizationHealth> organizations,
            List<Change> changes,
            List<DataQualityIssue> dataQualityIssues) {
    }

    public record HealthSummary(
            int maximumLayers,
            double averageManagerSpan,
            double medianManagerSpan,
            int overloadedManagers,
            int singleReportManagers,
            int managerReferenceIssues,
            int disconnectedOrganizations,
            int openPositions,
            double contingentRatioPct,
            int organizationHealthScore,
            int dataQualityScore,
            int organizationsAtRisk,
            int criticalOrganizations,
            int attentionOrganizations) {
    }

    public record ComparisonSummary(
            int headcountDelta,
            int organizationDelta,
            int managerDelta,
            int openPositionDelta,
            int peopleMoved,
            int managerChanges,
            int organizationMoves,
            int totalChanges,
            BigDecimal plannedFteDelta,
            BigDecimal workforceCostDelta,
            String costCurrency,
            double averageManagerSpanDelta,
            int maximumLayersDelta,
            int organizationHealthScoreDelta,
            int dataQualityScoreDelta) {
    }

    public record OrganizationHealth(
            UUID organizationId,
            String organizationName,
            String organizationType,
            int layer,
            int directHeadcount,
            int totalHeadcount,
            int managerCount,
            double averageManagerSpan,
            int overloadedManagerCount,
            int openPositionCount,
            double contingentRatioPct,
            int healthScore,
            String riskState,
            List<String> signals) {
    }

    public record Change(
            String changeType,
            String entityType,
            String entityId,
            String entityName,
            String fromValue,
            String toValue,
            String riskState) {
    }

    public record DataQualityIssue(
            String issueCode,
            String severity,
            String entityType,
            String entityId,
            String entityName,
            String message) {
    }
}
