package com.dwp.services.people.organization;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OrganizationChartDtos {

    private OrganizationChartDtos() {
    }

    public record OrganizationChart(
            LocalDate asOf,
            Company company,
            Metrics metrics,
            List<Organization> organizations,
            List<Person> people,
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
            int locationCount) {
    }

    public record Organization(
            UUID organizationId,
            String organizationKey,
            String name,
            String shortName,
            String organizationType,
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
            List<UUID> directMemberIds) {
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
            String workerNumber,
            String workerType,
            String workerStatus,
            String locationKey,
            String locationName,
            int directReportCount) {
    }

    public record Relationship(
            UUID childOrganizationId,
            UUID parentOrganizationId,
            String relationshipType,
            boolean primaryRelationship) {
    }

    public record OpenPosition(
            String positionKey,
            String title,
            UUID organizationId,
            String jobProfileName,
            String locationName,
            LocalDate availabilityDate) {
    }
}
