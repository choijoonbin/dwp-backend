package com.dwp.services.people.hris.organization;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Closed response projection for the canonical HRIS organization graph query. */
public record OrganizationGraphWithPositionsV2(
        int schemaVersion,
        Instant asOf,
        LocalDate effectiveDate,
        UUID rootPublicId,
        int depth,
        String relationshipType,
        boolean positionsIncluded,
        List<OrganizationNodeV2> nodes,
        List<OrganizationPositionV2> positions) {

    public OrganizationGraphWithPositionsV2 {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("Organization graph schemaVersion must be 2.");
        }
        Objects.requireNonNull(asOf, "asOf must not be null");
        Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
        Objects.requireNonNull(rootPublicId, "rootPublicId must not be null");
        if (depth < 0 || depth > 12) {
            throw new IllegalArgumentException("Organization graph depth must be between 0 and 12.");
        }
        if (!"REPORTING".equals(relationshipType)) {
            throw new IllegalArgumentException("Organization graph relationshipType must be REPORTING.");
        }
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        positions = List.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
    }

    public record OrganizationNodeV2(
            UUID organizationPublicId,
            UUID parentPublicId,
            String name,
            int depth) {

        public OrganizationNodeV2 {
            Objects.requireNonNull(
                    organizationPublicId, "organizationPublicId must not be null");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Organization node name must not be blank.");
            }
            if (depth < 0 || depth > 12) {
                throw new IllegalArgumentException("Organization node depth is invalid.");
            }
        }
    }

    public record OrganizationPositionV2(
            UUID positionPublicId,
            String positionKey,
            String title,
            UUID organizationPublicId,
            UUID reportsToPositionPublicId,
            String status,
            String positionType,
            String criticality,
            BigDecimal budgetedFte,
            BigDecimal annualCostAmount,
            String costCurrency,
            String jobProfileName,
            String locationName,
            LocalDate availabilityDate,
            List<UUID> incumbentPersonPublicIds,
            int subordinatePositionCount) {

        public OrganizationPositionV2 {
            Objects.requireNonNull(positionPublicId, "positionPublicId must not be null");
            requireText(positionKey, "positionKey");
            requireText(title, "title");
            Objects.requireNonNull(
                    organizationPublicId, "organizationPublicId must not be null");
            requireText(status, "status");
            requireText(positionType, "positionType");
            requireText(criticality, "criticality");
            Objects.requireNonNull(budgetedFte, "budgetedFte must not be null");
            incumbentPersonPublicIds = List.copyOf(Objects.requireNonNull(
                    incumbentPersonPublicIds, "incumbentPersonPublicIds must not be null"));
            if (subordinatePositionCount < 0) {
                throw new IllegalArgumentException(
                        "subordinatePositionCount must not be negative.");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank.");
            }
        }
    }
}
