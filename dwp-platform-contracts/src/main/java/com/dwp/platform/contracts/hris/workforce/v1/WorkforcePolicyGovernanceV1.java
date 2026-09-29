package com.dwp.platform.contracts.hris.workforce.v1;

import java.util.List;

/**
 * S1 AUTH READ ONLY ABI. Every publicly constructible carrier is UNTRUSTED data.
 * It is not a signed owner proof, mutation/consume permit, or People target proof.
 * Native BIGINT values travel as canonical decimal strings; revisions stay opaque.
 */
public final class WorkforcePolicyGovernanceV1 {
    private WorkforcePolicyGovernanceV1() { }
    public static final String PATH = "/internal/auth/v2/workforce-policy-governance/evaluate";
    public static final String BOUNDARY = "READ_ONLY_NOT_COMMAND_PERMIT";
    public static final String CAPABILITY = "hcm.workforce-policy.governance-read";
    public static final String DUTY = "WORKFORCE_POLICY_GOVERNANCE_READ";
    public static final String SOD = "SOD-HRIS-WORKFORCE-POLICY-AUTH-READ-V1";
    public enum Operation {
        POLICY_LIST_AUTH_READ, ORGANIZATION_OPTIONS_AUTH_READ,
        POLICY_CREATE_PREFLIGHT_AUTH_READ, POLICY_REVOKE_PREFLIGHT_AUTH_READ
    }
    public enum Code {
        MALFORMED_REQUEST, UNSUPPORTED_SCHEMA, OPERATION_UNREGISTERED, CALLER_UNAUTHORIZED,
        ACTOR_JWT_INVALID, ACTOR_INACTIVE, TENANT_INACTIVE, PROVIDER_PLANE_DENIED,
        ACTOR_LABEL_MISMATCH, ACTOR_STALE, AUTH_CONTEXT_STALE, SCOPE_MISMATCH,
        PERMISSION_DENIED, DUTY_UNREGISTERED, DUTY_DENIED, SOD_POLICY_UNREGISTERED,
        SOD_CONFLICT, SUBJECT_NOT_FOUND, SUBJECT_INACTIVE, SUBJECT_STALE,
        ROLE_CODE_INVALID, BODY_DIGEST_MISMATCH, CLOCK_REGRESSION, EVIDENCE_EXPIRED,
        OWNER_SOURCE_UNAVAILABLE
    }
    public enum FieldGroup { DIRECTORY, WORKER_IDENTIFIERS, EMPLOYMENT, JOB_GRADE }
    public enum Action { READ, EXPORT }
    public enum PopulationKind { TENANT, ORG_UNIT, ORG_TREE }
    public record ActorExpectation(String tenantId, String userId, String principalPublicId,
            String personPublicId, String userRowVersion, String accessRevision) { }
    public record AuthContextExpectation(String authRevision, String policyRevision, String contextKey) { }
    public record GovernanceScopeExpectation(String selectedScopeKey, String resourceSetId,
            String resourceSetVersion) { }
    public sealed interface Subject permits RoleSubject, UserSubject { }
    public record RoleSubject(String kind, String code, String expectedRoleId,
            String expectedRoleVersion) implements Subject { }
    public record UserSubject(String kind, String userId, String expectedPrincipalPublicId,
            String expectedUserRowVersion) implements Subject { }
    public record Population(String kind, String organizationPublicId) { }
    public sealed interface Candidate permits NoCandidate, CreateCandidate, RevokeCandidate { }
    public record NoCandidate(String kind) implements Candidate { }
    public record CreateCandidate(String kind, Subject subject, Population population,
            List<String> fieldGroups, List<String> actionCodes, String validFrom, String validTo,
            String justification) implements Candidate {
        public CreateCandidate { fieldGroups=List.copyOf(fieldGroups);actionCodes=List.copyOf(actionCodes); }
    }
    /** These are caller expectations, NOT the native People before row. */
    public record PolicyOwnerExpectation(String owner, String kind, String policyId,
            String expectedVersion) { }
    public record RevokeCandidate(String kind, PolicyOwnerExpectation policy,
            Subject storedSubjectExpectation, String reason) implements Candidate { }
    public record Request(int schemaVersion, Operation operationId, String requestNonce,
            ActorExpectation expectedActor, AuthContextExpectation expectedAuthContext,
            GovernanceScopeExpectation governanceScope, Candidate candidate,
            String expectedRequestDigest) { }
    public record ActorFact(String tenantId, String userId, String principalPublicId,
            String personPublicId, String userRowVersion, String accessRevision,
            String identityPlane, String status) { }
    public record TenantFact(String tenantId, String publicId, String status,
            String version, String updatedAt) { }
    public record SessionFact(String sessionId, String sessionFamilyId, String tokenIdDigest,
            String nativeIssuedAt, String jwtExpiresAt, String absoluteExpiresAt,
            String idleExpiresAt, String supersededAt, String supersededExpiresAt, String state) { }
    public record AuthScopeFact(String owner, String resourceSetId, String resourceSetKey,
            String resourceSetVersion, String rootResource, String scopeKey,
            String rootMemberVersion) { }
    public sealed interface SubjectFact permits NativeRoleFact, NativeUserFact,
            LegacySubjectFact, NoSubjectFact { }
    public record NativeRoleFact(String resolution, String tenantId, String roleId, String code,
            String roleType, String status, String roleVersion, String roleUpdatedAt,
            String builtinRoleCode, String catalogFamily, String catalogState,
            String catalogUpdatedAt) implements SubjectFact { }
    public record NativeUserFact(String resolution, String tenantId, String userId,
            String principalPublicId, String personPublicId, String status,
            String identityPlane, String userRowVersion) implements SubjectFact { }
    public record LegacySubjectFact(String resolution, String code, String sourceKind,
            String nativeRoleFact, String peopleRefetch) implements SubjectFact {
        public LegacySubjectFact {
            if (nativeRoleFact != null) {
                throw new IllegalArgumentException("historical role facts cannot assert native proof");
            }
        }
    }
    public record NoSubjectFact(String resolution) implements SubjectFact { }
    public record DutyFact(String dutyCode, String assignmentId, String assignmentVersion,
            String resourceSetId, String resourceSetVersion, String validTo,
            String evidenceRevision) { }
    public record AuthPolicyFact(String authRevision, String policyRevision, String contextKey,
            String capabilityContractKey, String resolvedPermission, List<DutyFact> dutyEvidence,
            String knownSodPolicyId, String sodResult, String revalidateAt) {
        public AuthPolicyFact { dutyEvidence=List.copyOf(dutyEvidence); }
    }
    public sealed interface Response permits Success, Failure { }
    /** Raw response remains UNTRUSTED after serialization/transport. */
    public record Success(int schemaVersion, String kind, String boundary, String result,
            String evidenceId, String requestNonce, Operation operationId,
            String requestDigest, String candidateDigest, String capturedAt, String issuedAt,
            String expiresAt, ActorFact actor, TenantFact tenant, SessionFact session,
            AuthScopeFact scope, AuthPolicyFact authPolicy, SubjectFact subject,
            String peopleOwnerValidation, String ownerService, String audience,
            String purpose) implements Response { }
    public record Failure(int schemaVersion, String kind, String boundary, String result,
            String requestNonce, Operation operationId, Code code) implements Response { }
    public static Failure denied(Code code) {
        return new Failure(1,"AUTH_WORKFORCE_POLICY_READ_ERROR_V1",BOUNDARY,
                code==Code.OWNER_SOURCE_UNAVAILABLE?"UNAVAILABLE":"DENIED",null,null,code);
    }
}
