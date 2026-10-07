package com.dwp.platform.contracts.hris.identity.v2;

import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Public UNTRUSTED owner/transport data carriers. Constructing a carrier confers no authority.
 * These are a neutral ABI.v2 candidate, NOT a wired/signed HTTP proof or new RBAC ledger.
 */
public final class CurrentHrisAuthorizationV2 {
    private CurrentHrisAuthorizationV2() { }
    public enum TargetKind { PERSON, EMPLOYMENT }
    public enum Selection { SELF, TARGET_POPULATION }
    public enum Plane { WORK, MANAGEMENT }
    public enum AccessMode { NORMAL, ELEVATED }
    public enum Decision { ALLOWED, DENIED, UNAVAILABLE }
    public enum OwnerRevisionKind { RELATIONSHIP, POPULATION, FIELD_POLICY, PURPOSE_POLICY, DYNAMIC_SOD }

    public record AuthRevision(String value) { }
    /** Exact native string; do not split, hash-to-long or invent an independent counter. */
    public record PolicyRevision(String value) { }
    public record ContextKey(String value) { }
    /** Gateway composite psr revision, not Auth's permission hash. */
    public record DecisionRevision(String value) { }
    public record PeopleRevision(OwnerRevisionKind kind, String value) { }

    /** Supplied by a trusted server operation registry, NEVER populated from request JSON. */
    public record Requirements(String operationId, String productKey, String surfaceKey,
            String routeContractKey, String purpose, String audience, Plane plane,
            AccessMode accessMode, Selection selection, TargetKind targetKind,
            Set<String> requiredPermissions, Set<String> requiredAtomicDuties,
            Set<String> fieldPaths, boolean mutation) {
        public Requirements {
            requiredPermissions = copy(requiredPermissions);
            requiredAtomicDuties = copy(requiredAtomicDuties);
            fieldPaths = copy(fieldPaths);
        }
    }

    /** A verified server invocation source must own this binding; this raw record is not proof. */
    public record Invocation(long tenantId, long userId, UUID principalPublicId,
            UUID personPublicId, long expectedUserRowVersion, long expectedAccessRevision,
            String operationId, String sessionRevision, Instant capturedAt, Instant expiresAt) { }

    /** Only an untrusted BUSINESS selector is accepted by authorize(); never an actor claim. */
    public record TargetSelector(TargetKind kind, UUID personPublicId,
            UUID workerPublicId, UUID workRelationshipPublicId, UUID assignmentPublicId) { }

    /** PERSON has no employment fields. EMPLOYMENT explicitly carries all native parent links. */
    public record TargetSnapshot(TargetKind kind, long tenantId, UUID personPublicId,
            long personVersion, String personState, UUID workerPublicId,
            UUID workerPersonPublicId, Long workerVersion, UUID workRelationshipPublicId,
            UUID relationshipWorkerPublicId, Long workRelationshipVersion,
            UUID assignmentPublicId, UUID assignmentRelationshipPublicId, Long assignmentVersion) { }

    /**
     * Trusted bridge obtains native Auth row and current Auth evaluation; Gateway owns psr.
     * grantedPermissions/AtomicDuties are exact route-scoped evaluated authorities, not a
     * union of raw groups or a bypass of native HCM/HRIS compatibility/predicate/activation.
     * No Auth reader may query People DB, and no People reader may query com_users.
     */
    public record DwpAuthoritySnapshot(Requirements requirements, AuthPersonBindingV1 actor,
            Decision decision, AuthRevision authRevision, PolicyRevision policyRevision,
            ContextKey contextKey, DecisionRevision decisionRevision, String selectedScopeKey,
            Set<String> grantedPermissions, Set<String> grantedAtomicDuties,
            boolean appEntitled, boolean staticSodAllowed, Instant capturedAt, Instant expiresAt) {
        public DwpAuthoritySnapshot {
            grantedPermissions = copy(grantedPermissions);
            grantedAtomicDuties = copy(grantedAtomicDuties);
        }
    }

    /** Fields/purpose/population/dynamic SoD are current OWNER results, never body booleans. */
    public record PeopleAuthoritySnapshot(Requirements requirements, String selectedScopeKey,
            TargetSnapshot target, PeopleRevision relationshipRevision,
            PeopleRevision populationRevision, PeopleRevision fieldPolicyRevision,
            PeopleRevision purposePolicyRevision, PeopleRevision dynamicSodRevision,
            Set<String> allowedFieldPaths, boolean populationAllowed, boolean purposeAllowed,
            boolean dynamicSodAllowed, Instant capturedAt, Instant expiresAt) {
        public PeopleAuthoritySnapshot { allowedFieldPaths = copy(allowedFieldPaths); }
    }
    private static <T> Set<T> copy(Set<T> value) { return value == null ? null : Set.copyOf(value); }
}
