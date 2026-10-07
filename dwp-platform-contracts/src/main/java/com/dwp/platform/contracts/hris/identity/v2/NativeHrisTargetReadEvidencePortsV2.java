package com.dwp.platform.contracts.hris.identity.v2;

import java.time.Instant;
import java.time.LocalDate;

/** Explicit trusted SERVER composition. Raw carriers are not accepted as caller authority. */
public final class NativeHrisTargetReadEvidencePortsV2 {
    private NativeHrisTargetReadEvidencePortsV2() { }
    public interface CurrentGatewayContextProvider {
        GatewayContext loadCurrent(CurrentHrisAuthorizationPortsV2.PeopleLookup lookup);
    }
    public interface CurrentAuthRoleEvidenceProvider {
        NativeHrisCurrentRoleEvidenceV2 loadCurrent(CurrentHrisAuthorizationPortsV2.PeopleLookup lookup);
    }
    public interface EmploymentDatePolicyProvider {
        EmploymentDatePolicy loadCurrent(CurrentHrisAuthorizationPortsV2.PeopleLookup lookup, Instant asOf);
    }
    /** Gateway owns current route/scope/psr; Auth must not synthesize this revision. */
    public record GatewayContext(CurrentHrisAuthorizationV2.Requirements requirements,
            long tenantId, long userId, String authRevision, String policyRevision,
            String contextKey, String decisionRevision, String selectedScopeKey,
            Instant capturedAt, Instant expiresAt) { }
    /** Purpose-bound owner date policy; no CURRENT_DATE, UTC or location/display-zone fallback. */
    public record EmploymentDatePolicy(String operationId, String purpose, String audience,
            String policyRef, String revision, Instant asOf, LocalDate localDate,
            Instant capturedAt, Instant expiresAt) { }
}
