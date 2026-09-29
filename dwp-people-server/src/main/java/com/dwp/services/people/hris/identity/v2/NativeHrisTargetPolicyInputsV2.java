package com.dwp.services.people.hris.identity.v2;

import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationPortsV2.PeopleLookup;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.TargetSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Native READ subset only: NOT a CurrentPeopleAuthoritySnapshot, signed proof or mutation PEP. */
public final class NativeHrisTargetPolicyInputsV2 {
    private NativeHrisTargetPolicyInputsV2() { }
    public enum Code { UNAVAILABLE, CONTEXT_INVALID, ROLE_EVIDENCE_INVALID, DATE_POLICY_UNAVAILABLE,
        DATE_POLICY_INVALID, NATIVE_INVALID, TARGET_NOT_FOUND, POLICY_DENIED, UNSUPPORTED_POLICY,
        SOURCE_CHANGED, STALE, CLOCK_INVALID }
    public static final class Rejected extends RuntimeException {
        private final Code code;
        public Rejected(Code code) { super("Native HRIS read admission rejected: " + code); this.code = code; }
        public Code code() { return code; }
    }
    public interface InputsProvider {
        Inputs read(PeopleLookup lookup, Set<String> roleCodes, Instant policyAsOf, LocalDate employmentDate);
    }
    public record Organization(UUID publicId, long version, UUID parentPublicId, String state) { }
    public record Policy(UUID publicId, long tenantId, String subjectType, String subjectRef,
            String populationType, UUID organizationPublicId, boolean organizationResolved,
            Set<String> fieldGroups, Set<String> actions, Instant validFrom, Instant validTo,
            String state, long version) {
        public Policy { fieldGroups = Set.copyOf(fieldGroups); actions = Set.copyOf(actions); }
    }
    public record Inputs(TargetSnapshot target, String workerState, LocalDate relationshipStart,
            LocalDate relationshipEnd, String assignmentState, LocalDate assignmentStart,
            LocalDate assignmentEnd, String assignmentKey, int effectiveSequence,
            UUID organizationPublicId, List<Organization> ancestors, List<Policy> policies) {
        public Inputs { ancestors = List.copyOf(ancestors); policies = List.copyOf(policies); }
    }
    /** Owner-configured mapping, not request fields, production registry activation or RBAC. */
    public record ReadOperation(com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.Requirements requirements,
            java.util.Map<String, String> fieldGroups, Set<String> allowedPersonStates) {
        public ReadOperation { fieldGroups = java.util.Map.copyOf(fieldGroups); allowedPersonStates = Set.copyOf(allowedPersonStates); }
    }
    public record ReadAdmission(TargetSnapshot target, Set<String> fieldPaths,
            List<Policy> matchedPolicies, Inputs nativeInputs, Instant capturedAt, Instant expiresAt) {
        public ReadAdmission { fieldPaths = Set.copyOf(fieldPaths); matchedPolicies = List.copyOf(matchedPolicies); }
    }
}
