package com.dwp.services.people.organization;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class OrganizationBaselineFingerprint {

    private OrganizationBaselineFingerprint() {
    }

    static String compute(OrganizationChartDtos.OrganizationChart chart) {
        Map<UUID, String> organizationKeys = chart.organizations().stream()
                .collect(Collectors.toMap(
                        OrganizationChartDtos.Organization::organizationId,
                        OrganizationChartDtos.Organization::organizationKey));
        Map<UUID, String> assignmentKeys = chart.people().stream()
                .collect(Collectors.toMap(
                        OrganizationChartDtos.Person::personId,
                        OrganizationChartDtos.Person::assignmentKey));
        Map<UUID, String> positionKeys = chart.positions().stream()
                .collect(Collectors.toMap(
                        OrganizationChartDtos.Position::positionId,
                        OrganizationChartDtos.Position::positionKey));

        String organizations = chart.organizations().stream()
                .sorted(Comparator.comparing(OrganizationChartDtos.Organization::organizationKey))
                .map(item -> String.join(":",
                        item.organizationKey(),
                        safe(item.name()),
                        safe(item.organizationType()),
                        reference(organizationKeys, item.parentOrganizationId()),
                        safe(item.costCenterKey())))
                .collect(Collectors.joining("|"));
        String assignments = chart.people().stream()
                .sorted(Comparator.comparing(OrganizationChartDtos.Person::assignmentKey))
                .map(item -> String.join(":",
                        item.assignmentKey(),
                        safe(item.workEmail()),
                        reference(organizationKeys, item.organizationId()),
                        reference(assignmentKeys, item.managerPersonId()),
                        reference(positionKeys, item.positionId()),
                        safe(item.workerStatus())))
                .collect(Collectors.joining("|"));
        String positions = chart.positions().stream()
                .sorted(Comparator.comparing(OrganizationChartDtos.Position::positionKey))
                .map(item -> String.join(":",
                        item.positionKey(),
                        safe(item.title()),
                        reference(organizationKeys, item.organizationId()),
                        reference(positionKeys, item.reportsToPositionId()),
                        safe(item.status()),
                        safe(item.positionType()),
                        safe(item.criticality()),
                        decimal(item.budgetedFte()),
                        decimal(item.annualCostAmount()),
                        safe(item.costCurrency())))
                .collect(Collectors.joining("|"));
        String relationships = chart.relationships().stream()
                .filter(OrganizationChartDtos.Relationship::primaryRelationship)
                .filter(relationship -> "SUPERVISORY".equals(relationship.relationshipType()))
                .sorted(Comparator
                        .comparing((OrganizationChartDtos.Relationship item) ->
                                reference(organizationKeys, item.childOrganizationId()))
                        .thenComparing(item ->
                                reference(organizationKeys, item.parentOrganizationId())))
                .map(item -> reference(organizationKeys, item.childOrganizationId())
                        + ":" + reference(organizationKeys, item.parentOrganizationId()))
                .collect(Collectors.joining("|"));
        return sha256(String.join("\n", organizations, assignments, positions, relationships));
    }

    private static String reference(Map<UUID, String> values, UUID id) {
        if (id == null) return "";
        return values.getOrDefault(id, id.toString());
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
