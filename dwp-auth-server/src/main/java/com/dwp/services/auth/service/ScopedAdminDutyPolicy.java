package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Scope and SoD semantics for DB-owned specialist duty evidence. */
final class ScopedAdminDutyPolicy {

    private static final String APPROVAL_PRODUCT = "approvals";
    private static final String APPROVAL_ADMIN_SURFACE = "approvals.admin";

    private ScopedAdminDutyPolicy() {
    }

    static boolean requiresScopedDuty(
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        return APPROVAL_PRODUCT.equals(capability.productKey())
                && APPROVAL_ADMIN_SURFACE.equals(capability.surfaceKey())
                && !"LEGACY_OVERSIGHT".equals(capability.responsibilityRequirement());
    }

    static List<ScopedAdminDutyEvidenceService.EffectiveDuty> matchingDuties(
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.CapabilityContract capability,
            String productResourceKey) {
        return identity.scopedDuties().stream()
                .filter(duty -> capability.productKey().equals(duty.productKey()))
                .filter(duty -> capability.resourceKey().equals(duty.resourceKey()))
                .filter(duty -> productResourceKey != null
                        && productResourceKey.equals(duty.productResourceKey()))
                .filter(duty -> duty.containsResource(duty.productResourceKey()))
                .filter(duty -> duty.grants(
                        capability.contractKey(), capability.resolvedCapabilityCode()))
                .sorted(Comparator.comparing(
                                ScopedAdminDutyEvidenceService.EffectiveDuty::resourceSetKey)
                        .thenComparing(
                                ScopedAdminDutyEvidenceService.EffectiveDuty::dutyCode)
                        .thenComparing(
                                ScopedAdminDutyEvidenceService.EffectiveDuty::assignmentId))
                .toList();
    }

    static List<AppGovernanceDtos.ResourceRole> matchingResponsibilities(
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.CapabilityContract capability,
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties,
            String productResourceKey) {
        if (duties.isEmpty()) return List.of();
        String required = capability.requiredResponsibilityCode();
        if (required == null && "REQUIRED".equals(capability.responsibilityRequirement())) {
            required = "APP_CONFIG_ADMIN";
        }
        if (required == null) return List.of();
        String requiredCode = required;
        return identity.responsibilities().stream()
                .filter(role -> requiredCode.equals(role.responsibilityCode()))
                .filter(role -> duties.stream().anyMatch(duty ->
                        duty.resourceSetId().equals(role.resourceSetId())
                                && duty.resourceSetKey().equals(role.resourceSetKey())
                                && productResourceKey.equals(role.resourceKey())))
                .toList();
    }

    static boolean staticSodConflict(
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> matching,
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> all) {
        return matching.stream().anyMatch(target -> all.stream()
                .filter(other -> !target.assignmentId().equals(other.assignmentId()))
                .filter(other -> target.conflictingDutyCodes().contains(other.dutyCode()))
                .anyMatch(other -> ScopedAdminDutyEvidenceService.overlaps(target, other)));
    }

    static OffsetDateTime validUntil(
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties,
            List<AppGovernanceDtos.ResourceRole> responsibilities) {
        return java.util.stream.Stream.concat(
                        duties.stream().map(
                                ScopedAdminDutyEvidenceService.EffectiveDuty::validTo),
                        responsibilities.stream().map(AppGovernanceDtos.ResourceRole::validTo))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    static List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties,
            List<AppGovernanceDtos.ResourceRole> responsibilities,
            boolean readOnly) {
        Map<String, ScopeEvidence> byKey = new LinkedHashMap<>();
        duties.forEach(duty -> {
            List<AppGovernanceDtos.ResourceRole> matching = responsibilities.stream()
                    .filter(role -> duty.resourceSetId().equals(role.resourceSetId()))
                    .filter(role -> duty.resourceSetKey().equals(role.resourceSetKey()))
                    .toList();
            if (!responsibilities.isEmpty() && matching.isEmpty()) return;
            OffsetDateTime until = matching.isEmpty()
                    ? duty.validTo()
                    : validUntil(List.of(duty), matching);
            String key = ProductAuthorizationAuthoritySupport.scopeKey(
                    request, duty.resourceSetKey(), "RESOURCE_SET");
            byKey.merge(key, new ScopeEvidence(duty.resourceSetKey(), until),
                    (left, right) -> new ScopeEvidence(
                            left.resourceSetKey(), earlier(left.validUntil(), right.validUntil())));
        });
        boolean single = byKey.size() == 1;
        return byKey.entrySet().stream()
                .map(entry -> new ProductSurfaceAuthorityDtos.EffectiveScope(
                        entry.getKey(), "RESOURCE_SET", "Assigned scope", single,
                        readOnly, entry.getValue().validUntil()))
                .toList();
    }

    private static OffsetDateTime earlier(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private record ScopeEvidence(String resourceSetKey, OffsetDateTime validUntil) {
    }
}
