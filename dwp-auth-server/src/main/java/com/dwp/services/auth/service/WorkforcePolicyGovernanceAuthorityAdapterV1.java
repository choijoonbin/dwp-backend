package com.dwp.services.auth.service;

import static com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.*;
import static com.dwp.services.auth.service.ProductAuthorizationAuthoritySupport.scopeKey;

import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Current Auth S1 READ evidence only. No People DB, mutation, command permit or fence.
 * Raw output is not trusted transport. Native HRIS registry missing means denial.
 * Every phase opens REQUIRES_NEW so JPA identities are not reused from a prior context.
 */
@Service
public final class WorkforcePolicyGovernanceAuthorityAdapterV1 {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(24).maxStringLength(32768).build());
    }
    private static final JsonNode REQUEST_SCHEMA = schema("""
{"$schema":"http://json-schema.org/draft-07/schema#","$id":"urn:dwp:hris:workforce:auth-read-request:v1:proposal","type":"object","additionalProperties":false,"properties":{"schemaVersion":{"const":1,"type":"integer"},"operationId":{"enum":["POLICY_LIST_AUTH_READ","ORGANIZATION_OPTIONS_AUTH_READ","POLICY_CREATE_PREFLIGHT_AUTH_READ","POLICY_REVOKE_PREFLIGHT_AUTH_READ"]},"requestNonce":{"$ref":"#/definitions/UUID"},"expectedActor":{"anyOf":[{"$ref":"#/definitions/ActorExpectation"},{"type":"null"}]},"expectedAuthContext":{"anyOf":[{"$ref":"#/definitions/AuthContextExpectation"},{"type":"null"}]},"governanceScope":{"$ref":"#/definitions/GovernanceScopeExpectation"},"candidate":{"oneOf":[{"$ref":"#/definitions/NoCandidate"},{"$ref":"#/definitions/CreateCandidate"},{"$ref":"#/definitions/RevokeCandidate"}]},"expectedRequestDigest":{"anyOf":[{"$ref":"#/definitions/Sha256"},{"type":"null"}]}},"required":["schemaVersion","operationId","requestNonce","expectedActor","expectedAuthContext","governanceScope","candidate","expectedRequestDigest"],"definitions":{"UUID":{"type":"string","pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},"ActorExpectation":{"type":"object","additionalProperties":false,"properties":{"tenantId":{"$ref":"#/definitions/PositiveBigint"},"userId":{"$ref":"#/definitions/PositiveBigint"},"principalPublicId":{"$ref":"#/definitions/UUID"},"personPublicId":{"anyOf":[{"$ref":"#/definitions/UUID"},{"type":"null"}]},"userRowVersion":{"$ref":"#/definitions/NonnegativeBigint"},"accessRevision":{"$ref":"#/definitions/NonnegativeBigint"}},"required":["tenantId","userId","principalPublicId","personPublicId","userRowVersion","accessRevision"]},"PositiveBigint":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},"NonnegativeBigint":{"type":"string","pattern":"^(0|[1-9][0-9]{0,18})$"},"AuthContextExpectation":{"type":"object","additionalProperties":false,"properties":{"authRevision":{"type":"string","pattern":"^auth-[0-9a-f]{64}$"},"policyRevision":{"type":"string","minLength":1,"maxLength":240},"contextKey":{"type":"string","pattern":"^psc-[0-9a-f]{64}$"}},"required":["authRevision","policyRevision","contextKey"]},"GovernanceScopeExpectation":{"type":"object","additionalProperties":false,"properties":{"selectedScopeKey":{"type":"string","minLength":1,"maxLength":500},"resourceSetId":{"$ref":"#/definitions/UUID"},"resourceSetVersion":{"$ref":"#/definitions/NonnegativeBigint"}},"required":["selectedScopeKey","resourceSetId","resourceSetVersion"]},"NoCandidate":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"NONE"}},"required":["kind"]},"CreateCandidate":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"CREATE_POLICY"},"subject":{"$ref":"#/definitions/Subject"},"population":{"$ref":"#/definitions/Population"},"fieldGroups":{"$ref":"#/definitions/FieldGroups"},"actionCodes":{"$ref":"#/definitions/ActionCodes"},"validFrom":{"anyOf":[{"$ref":"#/definitions/Instant"},{"type":"null"}]},"validTo":{"anyOf":[{"$ref":"#/definitions/Instant"},{"type":"null"}]},"justification":{"type":"string","minLength":1,"maxLength":1000}},"required":["kind","subject","population","fieldGroups","actionCodes","validFrom","validTo","justification"]},"Subject":{"oneOf":[{"$ref":"#/definitions/RoleSubject"},{"$ref":"#/definitions/UserSubject"}]},"RoleSubject":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"ROLE"},"code":{"type":"string","pattern":"^[A-Z][A-Z0-9_.-]{0,49}$"},"expectedRoleId":{"anyOf":[{"$ref":"#/definitions/PositiveBigint"},{"type":"null"}]},"expectedRoleVersion":{"anyOf":[{"$ref":"#/definitions/NonnegativeBigint"},{"type":"null"}]}},"required":["kind","code","expectedRoleId","expectedRoleVersion"]},"UserSubject":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"USER"},"userId":{"$ref":"#/definitions/PositiveBigint"},"expectedPrincipalPublicId":{"anyOf":[{"$ref":"#/definitions/UUID"},{"type":"null"}]},"expectedUserRowVersion":{"anyOf":[{"$ref":"#/definitions/NonnegativeBigint"},{"type":"null"}]}},"required":["kind","userId","expectedPrincipalPublicId","expectedUserRowVersion"]},"Population":{"oneOf":[{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"TENANT"},"organizationPublicId":{"type":"null"}},"required":["kind","organizationPublicId"]},{"type":"object","additionalProperties":false,"properties":{"kind":{"enum":["ORG_UNIT","ORG_TREE"]},"organizationPublicId":{"$ref":"#/definitions/UUID"}},"required":["kind","organizationPublicId"]}]},"FieldGroups":{"type":"array","uniqueItems":true,"minItems":1,"maxItems":4,"contains":{"const":"DIRECTORY"},"items":{"enum":["DIRECTORY","WORKER_IDENTIFIERS","EMPLOYMENT","JOB_GRADE"]}},"ActionCodes":{"type":"array","uniqueItems":true,"minItems":1,"maxItems":2,"items":{"enum":["READ","EXPORT"]}},"Instant":{"type":"string","format":"date-time","pattern":"Z$"},"RevokeCandidate":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"REVOKE_POLICY"},"policy":{"$ref":"#/definitions/PolicyOwnerExpectation"},"storedSubjectExpectation":{"$ref":"#/definitions/StoredSubjectExpectation"},"reason":{"type":"string","minLength":1,"maxLength":1000}},"required":["kind","policy","storedSubjectExpectation","reason"]},"PolicyOwnerExpectation":{"type":"object","additionalProperties":false,"properties":{"owner":{"const":"PEOPLE"},"kind":{"const":"WORKFORCE_ACCESS_POLICY_EXPECTATION"},"policyId":{"$ref":"#/definitions/UUID"},"expectedVersion":{"$ref":"#/definitions/NonnegativeBigint"}},"required":["owner","kind","policyId","expectedVersion"]},"StoredSubjectExpectation":{"oneOf":[{"$ref":"#/definitions/HistoricalRoleSubject"},{"$ref":"#/definitions/UserSubject"}]},"HistoricalRoleSubject":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"ROLE"},"code":{"anyOf":[{"type":"string","pattern":"^[A-Z][A-Z0-9_.-]{0,49}$"},{"type":"string","pattern":"^[A-Z][A-Z0-9_]{1,79}$"}]},"expectedRoleId":{"anyOf":[{"$ref":"#/definitions/PositiveBigint"},{"type":"null"}]},"expectedRoleVersion":{"anyOf":[{"$ref":"#/definitions/NonnegativeBigint"},{"type":"null"}]}},"required":["kind","code","expectedRoleId","expectedRoleVersion"]},"Sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"}},"allOf":[{"if":{"properties":{"operationId":{"enum":["POLICY_LIST_AUTH_READ","ORGANIZATION_OPTIONS_AUTH_READ"]}}},"then":{"properties":{"candidate":{"$ref":"#/definitions/NoCandidate"}}}},{"if":{"properties":{"operationId":{"const":"POLICY_CREATE_PREFLIGHT_AUTH_READ"}}},"then":{"properties":{"candidate":{"$ref":"#/definitions/CreateCandidate"}}}},{"if":{"properties":{"operationId":{"const":"POLICY_REVOKE_PREFLIGHT_AUTH_READ"}}},"then":{"properties":{"candidate":{"$ref":"#/definitions/RevokeCandidate"}}}}]}
""");
    private static final JsonNode RESPONSE_SCHEMA = schema("""
{"$schema":"http://json-schema.org/draft-07/schema#","$id":"urn:dwp:hris:workforce:auth-read-response:v1:proposal","definitions":{"PositiveBigint":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},"NonnegativeBigint":{"type":"string","pattern":"^(0|[1-9][0-9]{0,18})$"},"UUID":{"type":"string","pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},"Sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"},"Instant":{"type":"string","format":"date-time","pattern":"Z$"},"LocalTimestamp":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\\\.[0-9]{1,9})?$"},"ActorExpectation":{"type":"object","additionalProperties":false,"properties":{"tenantId":{"$ref":"#/definitions/PositiveBigint"},"userId":{"$ref":"#/definitions/PositiveBigint"},"principalPublicId":{"$ref":"#/definitions/UUID"},"personPublicId":{"anyOf":[{"$ref":"#/definitions/UUID"},{"type":"null"}]},"userRowVersion":{"$ref":"#/definitions/NonnegativeBigint"},"accessRevision":{"$ref":"#/definitions/NonnegativeBigint"}},"required":["tenantId","userId","principalPublicId","personPublicId","userRowVersion","accessRevision"]},"AuthContextExpectation":{"type":"object","additionalProperties":false,"properties":{"authRevision":{"type":"string","pattern":"^auth-[0-9a-f]{64}$"},"policyRevision":{"type":"string","minLength":1,"maxLength":240},"contextKey":{"type":"string","pattern":"^psc-[0-9a-f]{64}$"}},"required":["authRevision","policyRevision","contextKey"]},"GovernanceScopeExpectation":{"type":"object","additionalProperties":false,"properties":{"selectedScopeKey":{"type":"string","minLength":1,"maxLength":500},"resourceSetId":{"$ref":"#/definitions/UUID"},"resourceSetVersion":{"$ref":"#/definitions/NonnegativeBigint"}},"required":["selectedScopeKey","resourceSetId","resourceSetVersion"]},"RoleSubject":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"ROLE"},"code":{"type":"string","pattern":"^[A-Z][A-Z0-9_.-]{0,49}$"},"expectedRoleId":{"anyOf":[{"$ref":"#/definitions/PositiveBigint"},{"type":"null"}]},"expectedRoleVersion":{"anyOf":[{"$ref":"#/definitions/NonnegativeBigint"},{"type":"null"}]}},"required":["kind","code","expectedRoleId","expectedRoleVersion"]},"HistoricalRoleSubject":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"ROLE"},"code":{"anyOf":[{"type":"string","pattern":"^[A-Z][A-Z0-9_.-]{0,49}$"},{"type":"string","pattern":"^[A-Z][A-Z0-9_]{1,79}$"}]},"expectedRoleId":{"anyOf":[{"$ref":"#/definitions/PositiveBigint"},{"type":"null"}]},"expectedRoleVersion":{"anyOf":[{"$ref":"#/definitions/NonnegativeBigint"},{"type":"null"}]}},"required":["kind","code","expectedRoleId","expectedRoleVersion"]},"UserSubject":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"USER"},"userId":{"$ref":"#/definitions/PositiveBigint"},"expectedPrincipalPublicId":{"anyOf":[{"$ref":"#/definitions/UUID"},{"type":"null"}]},"expectedUserRowVersion":{"anyOf":[{"$ref":"#/definitions/NonnegativeBigint"},{"type":"null"}]}},"required":["kind","userId","expectedPrincipalPublicId","expectedUserRowVersion"]},"Subject":{"oneOf":[{"$ref":"#/definitions/RoleSubject"},{"$ref":"#/definitions/UserSubject"}]},"StoredSubjectExpectation":{"oneOf":[{"$ref":"#/definitions/HistoricalRoleSubject"},{"$ref":"#/definitions/UserSubject"}]},"Population":{"oneOf":[{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"TENANT"},"organizationPublicId":{"type":"null"}},"required":["kind","organizationPublicId"]},{"type":"object","additionalProperties":false,"properties":{"kind":{"enum":["ORG_UNIT","ORG_TREE"]},"organizationPublicId":{"$ref":"#/definitions/UUID"}},"required":["kind","organizationPublicId"]}]},"FieldGroups":{"type":"array","uniqueItems":true,"minItems":1,"maxItems":4,"contains":{"const":"DIRECTORY"},"items":{"enum":["DIRECTORY","WORKER_IDENTIFIERS","EMPLOYMENT","JOB_GRADE"]}},"ActionCodes":{"type":"array","uniqueItems":true,"minItems":1,"maxItems":2,"items":{"enum":["READ","EXPORT"]}},"NoCandidate":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"NONE"}},"required":["kind"]},"CreateCandidate":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"CREATE_POLICY"},"subject":{"$ref":"#/definitions/Subject"},"population":{"$ref":"#/definitions/Population"},"fieldGroups":{"$ref":"#/definitions/FieldGroups"},"actionCodes":{"$ref":"#/definitions/ActionCodes"},"validFrom":{"anyOf":[{"$ref":"#/definitions/Instant"},{"type":"null"}]},"validTo":{"anyOf":[{"$ref":"#/definitions/Instant"},{"type":"null"}]},"justification":{"type":"string","minLength":1,"maxLength":1000}},"required":["kind","subject","population","fieldGroups","actionCodes","validFrom","validTo","justification"]},"PolicyOwnerExpectation":{"type":"object","additionalProperties":false,"properties":{"owner":{"const":"PEOPLE"},"kind":{"const":"WORKFORCE_ACCESS_POLICY_EXPECTATION"},"policyId":{"$ref":"#/definitions/UUID"},"expectedVersion":{"$ref":"#/definitions/NonnegativeBigint"}},"required":["owner","kind","policyId","expectedVersion"]},"RevokeCandidate":{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"REVOKE_POLICY"},"policy":{"$ref":"#/definitions/PolicyOwnerExpectation"},"storedSubjectExpectation":{"$ref":"#/definitions/StoredSubjectExpectation"},"reason":{"type":"string","minLength":1,"maxLength":1000}},"required":["kind","policy","storedSubjectExpectation","reason"]},"ActorFact":{"type":"object","additionalProperties":false,"properties":{"tenantId":{"$ref":"#/definitions/PositiveBigint"},"userId":{"$ref":"#/definitions/PositiveBigint"},"principalPublicId":{"$ref":"#/definitions/UUID"},"personPublicId":{"anyOf":[{"$ref":"#/definitions/UUID"},{"type":"null"}]},"identityPlane":{"const":"TENANT"},"status":{"const":"ACTIVE"},"userRowVersion":{"$ref":"#/definitions/NonnegativeBigint"},"accessRevision":{"$ref":"#/definitions/NonnegativeBigint"}},"required":["tenantId","userId","principalPublicId","personPublicId","identityPlane","status","userRowVersion","accessRevision"]},"TenantFact":{"type":"object","additionalProperties":false,"properties":{"tenantId":{"$ref":"#/definitions/PositiveBigint"},"publicId":{"$ref":"#/definitions/UUID"},"status":{"const":"ACTIVE"},"version":{"$ref":"#/definitions/NonnegativeBigint"},"updatedAt":{"$ref":"#/definitions/LocalTimestamp"}},"required":["tenantId","publicId","status","version","updatedAt"]},"SessionFact":{"type":"object","additionalProperties":false,"properties":{"sessionId":{"$ref":"#/definitions/UUID"},"sessionFamilyId":{"$ref":"#/definitions/UUID"},"tokenIdDigest":{"$ref":"#/definitions/Sha256"},"nativeIssuedAt":{"$ref":"#/definitions/Instant"},"jwtExpiresAt":{"$ref":"#/definitions/Instant"},"absoluteExpiresAt":{"$ref":"#/definitions/Instant"},"idleExpiresAt":{"$ref":"#/definitions/Instant"},"supersededAt":{"anyOf":[{"$ref":"#/definitions/Instant"},{"type":"null"}]},"supersededExpiresAt":{"anyOf":[{"$ref":"#/definitions/Instant"},{"type":"null"}]},"state":{"enum":["ACTIVE_CURRENT","ACTIVE_SUPERSEDED_GRACE"]}},"required":["sessionId","sessionFamilyId","tokenIdDigest","nativeIssuedAt","jwtExpiresAt","absoluteExpiresAt","idleExpiresAt","supersededAt","supersededExpiresAt","state"]},"AuthScopeFact":{"type":"object","additionalProperties":false,"properties":{"owner":{"const":"AUTH"},"resourceSetId":{"$ref":"#/definitions/UUID"},"resourceSetKey":{"const":"RS_HCM_CONFIG"},"resourceSetVersion":{"$ref":"#/definitions/NonnegativeBigint"},"rootResource":{"const":"APP.HCM"},"scopeKey":{"type":"string","minLength":1,"maxLength":500},"rootMemberVersion":{"$ref":"#/definitions/NonnegativeBigint"}},"required":["owner","resourceSetId","resourceSetKey","resourceSetVersion","rootResource","scopeKey","rootMemberVersion"]},"NativeRoleFact":{"type":"object","additionalProperties":false,"properties":{"resolution":{"const":"NATIVE_ROLE"},"tenantId":{"$ref":"#/definitions/PositiveBigint"},"roleId":{"$ref":"#/definitions/PositiveBigint"},"code":{"type":"string","pattern":"^[A-Z][A-Z0-9_.-]{0,49}$"},"roleType":{"enum":["CUSTOM","SYSTEM"]},"status":{"enum":["ACTIVE","INACTIVE"]},"roleVersion":{"$ref":"#/definitions/NonnegativeBigint"},"roleUpdatedAt":{"$ref":"#/definitions/LocalTimestamp"},"builtinRoleCode":{"anyOf":[{"type":"string","pattern":"^[A-Z][A-Z0-9_.-]{0,49}$"},{"type":"null"}]},"catalogFamily":{"anyOf":[{"enum":["PLATFORM","TENANT","WORKSPACE","PEOPLE","AUDIT"]},{"type":"null"}]},"catalogState":{"anyOf":[{"const":"ACTIVE"},{"type":"null"}]},"catalogUpdatedAt":{"anyOf":[{"$ref":"#/definitions/Instant"},{"type":"null"}]}},"required":["resolution","tenantId","roleId","code","roleType","status","roleVersion","roleUpdatedAt","builtinRoleCode","catalogFamily","catalogState","catalogUpdatedAt"]},"NativeUserFact":{"type":"object","additionalProperties":false,"properties":{"resolution":{"const":"NATIVE_USER"},"tenantId":{"$ref":"#/definitions/PositiveBigint"},"userId":{"$ref":"#/definitions/PositiveBigint"},"principalPublicId":{"$ref":"#/definitions/UUID"},"personPublicId":{"anyOf":[{"$ref":"#/definitions/UUID"},{"type":"null"}]},"status":{"enum":["ACTIVE","INACTIVE"]},"identityPlane":{"const":"TENANT"},"userRowVersion":{"$ref":"#/definitions/NonnegativeBigint"}},"required":["resolution","tenantId","userId","principalPublicId","personPublicId","status","identityPlane","userRowVersion"]},"LegacySubjectFact":{"type":"object","additionalProperties":false,"properties":{"resolution":{"const":"HISTORICAL_ROLE_EXPECTATION_NOT_NATIVE_PROOF"},"code":{"anyOf":[{"type":"string","pattern":"^[A-Z][A-Z0-9_.-]{0,49}$"},{"type":"string","pattern":"^[A-Z][A-Z0-9_]{1,79}$"}]},"sourceKind":{"const":"VALIDATED_REQUEST_EXPECTATION"},"nativeRoleFact":{"type":"null"},"peopleRefetch":{"const":"REQUIRED"}},"required":["resolution","code","sourceKind","nativeRoleFact","peopleRefetch"]},"NoSubjectFact":{"type":"object","additionalProperties":false,"properties":{"resolution":{"const":"NO_SUBJECT_FOR_READ"}},"required":["resolution"]},"SubjectFact":{"oneOf":[{"$ref":"#/definitions/NativeRoleFact"},{"$ref":"#/definitions/NativeUserFact"},{"$ref":"#/definitions/LegacySubjectFact"},{"$ref":"#/definitions/NoSubjectFact"}]},"DutyFact":{"type":"object","additionalProperties":false,"properties":{"dutyCode":{"type":"string","minLength":1,"maxLength":80},"assignmentId":{"$ref":"#/definitions/UUID"},"assignmentVersion":{"$ref":"#/definitions/NonnegativeBigint"},"resourceSetId":{"$ref":"#/definitions/UUID"},"resourceSetVersion":{"$ref":"#/definitions/NonnegativeBigint"},"evidenceRevision":{"type":"string","minLength":1,"maxLength":240},"validTo":{"anyOf":[{"$ref":"#/definitions/Instant"},{"type":"null"}]}},"required":["dutyCode","assignmentId","assignmentVersion","resourceSetId","resourceSetVersion","evidenceRevision","validTo"]},"AuthPolicyFact":{"type":"object","additionalProperties":false,"properties":{"authRevision":{"type":"string","pattern":"^auth-[0-9a-f]{64}$"},"policyRevision":{"type":"string","minLength":1,"maxLength":240},"contextKey":{"type":"string","pattern":"^psc-[0-9a-f]{64}$"},"capabilityContractKey":{"const":"hcm.workforce-policy.governance-read"},"resolvedPermission":{"const":"ADMIN.WORKFORCE_ACCESS:MANAGE"},"dutyEvidence":{"type":"array","minItems":1,"maxItems":100,"items":{"$ref":"#/definitions/DutyFact"}},"knownSodPolicyId":{"const":"SOD-HRIS-WORKFORCE-POLICY-AUTH-READ-V1"},"sodResult":{"const":"AUTH_SCOPE_CHECKED_NOT_PEOPLE_COMMAND_SOD"},"revalidateAt":{"$ref":"#/definitions/Instant"}},"required":["authRevision","policyRevision","contextKey","capabilityContractKey","resolvedPermission","dutyEvidence","knownSodPolicyId","sodResult","revalidateAt"]}},"oneOf":[{"type":"object","additionalProperties":false,"properties":{"schemaVersion":{"const":1,"type":"integer"},"kind":{"const":"AUTH_WORKFORCE_POLICY_READ_EVIDENCE_V1"},"boundary":{"const":"READ_ONLY_NOT_COMMAND_PERMIT"},"result":{"const":"VERIFIED_AUTH_READ_EVIDENCE"},"evidenceId":{"$ref":"#/definitions/UUID"},"requestNonce":{"$ref":"#/definitions/UUID"},"operationId":{"enum":["POLICY_LIST_AUTH_READ","ORGANIZATION_OPTIONS_AUTH_READ","POLICY_CREATE_PREFLIGHT_AUTH_READ","POLICY_REVOKE_PREFLIGHT_AUTH_READ"]},"requestDigest":{"$ref":"#/definitions/Sha256"},"candidateDigest":{"$ref":"#/definitions/Sha256"},"capturedAt":{"$ref":"#/definitions/Instant"},"issuedAt":{"$ref":"#/definitions/Instant"},"expiresAt":{"$ref":"#/definitions/Instant"},"actor":{"$ref":"#/definitions/ActorFact"},"tenant":{"$ref":"#/definitions/TenantFact"},"session":{"$ref":"#/definitions/SessionFact"},"scope":{"$ref":"#/definitions/AuthScopeFact"},"authPolicy":{"$ref":"#/definitions/AuthPolicyFact"},"subject":{"$ref":"#/definitions/SubjectFact"},"peopleOwnerValidation":{"const":"NOT_PERFORMED_PEOPLE_NATIVE_REFETCH_REQUIRED"},"ownerService":{"const":"dwp-auth-server"},"audience":{"const":"dwp-people-server"},"purpose":{"const":"HRIS_WORKFORCE_POLICY_GOVERNANCE_AUTH_READ"}},"required":["schemaVersion","kind","boundary","result","evidenceId","requestNonce","operationId","requestDigest","candidateDigest","capturedAt","issuedAt","expiresAt","actor","tenant","session","scope","authPolicy","subject","peopleOwnerValidation","ownerService","audience","purpose"]},{"type":"object","additionalProperties":false,"properties":{"schemaVersion":{"const":1,"type":"integer"},"kind":{"const":"AUTH_WORKFORCE_POLICY_READ_ERROR_V1"},"boundary":{"const":"READ_ONLY_NOT_COMMAND_PERMIT"},"result":{"enum":["DENIED","UNAVAILABLE"]},"requestNonce":{"anyOf":[{"$ref":"#/definitions/UUID"},{"type":"null"}]},"operationId":{"anyOf":[{"enum":["POLICY_LIST_AUTH_READ","ORGANIZATION_OPTIONS_AUTH_READ","POLICY_CREATE_PREFLIGHT_AUTH_READ","POLICY_REVOKE_PREFLIGHT_AUTH_READ"]},{"type":"null"}]},"code":{"enum":["MALFORMED_REQUEST","UNSUPPORTED_SCHEMA","OPERATION_UNREGISTERED","CALLER_UNAUTHORIZED","ACTOR_JWT_INVALID","ACTOR_INACTIVE","TENANT_INACTIVE","PROVIDER_PLANE_DENIED","ACTOR_LABEL_MISMATCH","ACTOR_STALE","AUTH_CONTEXT_STALE","SCOPE_MISMATCH","PERMISSION_DENIED","DUTY_UNREGISTERED","DUTY_DENIED","SOD_POLICY_UNREGISTERED","SOD_CONFLICT","SUBJECT_NOT_FOUND","SUBJECT_INACTIVE","SUBJECT_STALE","ROLE_CODE_INVALID","BODY_DIGEST_MISMATCH","CLOCK_REGRESSION","EVIDENCE_EXPIRED","OWNER_SOURCE_UNAVAILABLE"]}},"required":["schemaVersion","kind","boundary","result","requestNonce","operationId","code"]}]}
""");
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final Code code;
        private Rejected(Code code) { super("Workforce policy Auth read rejected: "+code,null,false,false);this.code=code; }
        public Code code() { return code; }
    }
    private final JdbcTemplate jdbc;
    private final ProductAuthorizationIdentityEvidenceService identities;
    private final ProductAuthorizationAuthorityAdapter evaluator;
    private final ProductAuthorizationContractRepository registry;
    private final PlatformTransactionManager transactions;
    private final JwtDecoder decoder;
    private final Clock clock;
    @Autowired
    public WorkforcePolicyGovernanceAuthorityAdapterV1(JdbcTemplate jdbc,
            ProductAuthorizationIdentityEvidenceService identities,
            ProductAuthorizationAuthorityAdapter evaluator,
            ProductAuthorizationContractRepository registry,
            PlatformTransactionManager transactions, JwtDecoder decoder) {
        this(jdbc,identities,evaluator,registry,transactions,decoder,Clock.systemUTC());
    }
    WorkforcePolicyGovernanceAuthorityAdapterV1(JdbcTemplate jdbc,
            ProductAuthorizationIdentityEvidenceService identities,
            ProductAuthorizationAuthorityAdapter evaluator,
            ProductAuthorizationContractRepository registry,
            PlatformTransactionManager transactions, JwtDecoder decoder, Clock clock) {
        this.jdbc=jdbc;this.identities=identities;this.evaluator=evaluator;this.registry=registry;
        this.transactions=transactions;this.decoder=decoder;this.clock=clock;
    }
    public Response evaluate(byte[] body, String bearerToken) {
        try {
            Request request=decode(body);
            if(jdbc==null||identities==null||evaluator==null||registry==null
                    ||transactions==null||decoder==null||clock==null)throw reject(Code.OWNER_SOURCE_UNAVAILABLE);
            if(bearerToken==null||bearerToken.isBlank()||bearerToken.length()>16384)throw reject(Code.ACTOR_JWT_INVALID);
            Instant captured=now(null);
            Snapshot first=phase(request,bearerToken,captured);
            Instant afterFirst=now(captured);live(first,afterFirst);
            Snapshot second=phase(request,bearerToken,afterFirst);
            Instant issued=now(afterFirst);live(first,issued);live(second,issued);
            if(!sameAuthority(first,second))throw reject(Code.AUTH_CONTEXT_STALE);
            Instant expiry=minimum(captured.plusSeconds(30),first.expiry,second.expiry);
            if(!expiry.isAfter(issued))throw reject(Code.EVIDENCE_EXPIRED);
            Digests digests=digests(request);
            if(request.expectedRequestDigest()!=null&&!request.expectedRequestDigest().equals(digests.request))
                throw reject(Code.BODY_DIGEST_MISMATCH);
            return new VerifiedRead(first,captured,issued,expiry,request,digests).carrier();
        } catch(Rejected denied) { return WorkforcePolicyGovernanceV1.denied(denied.code()); }
        catch(Exception unavailable) { return WorkforcePolicyGovernanceV1.denied(Code.OWNER_SOURCE_UNAVAILABLE); }
    }
    private Instant now(Instant previous) {
        Instant current=clock.instant();
        if(current==null||(previous!=null&&current.isBefore(previous)))throw reject(Code.CLOCK_REGRESSION);
        return current;
    }
    private Snapshot phase(Request request,String token,Instant previous) {
        var tx=new TransactionTemplate(transactions);
        tx.setReadOnly(true);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(10);
        return tx.execute(status -> load(request,token,previous));
    }
    private record Snapshot(ActorFact actor,TenantFact tenant,SessionFact session,
            AuthScopeFact scope,AuthPolicyFact policy,SubjectFact subject,
            List<Map<String,Object>> nativeSources,Instant expiry) { }
    private static final class VerifiedRead {
        private final Snapshot snapshot;
        private final Instant captured,issued,expiry;
        private final Request request;
        private final Digests digests;
        private VerifiedRead(Snapshot s,Instant c,Instant i,Instant e,Request r,Digests d) {
            snapshot=s;captured=c;issued=i;expiry=e;request=r;digests=d;
        }
        private Success carrier() {
            Success result=new Success(1,"AUTH_WORKFORCE_POLICY_READ_EVIDENCE_V1",BOUNDARY,
                    "VERIFIED_AUTH_READ_EVIDENCE",UUID.randomUUID().toString(),request.requestNonce(),
                    request.operationId(),digests.request,digests.candidate,captured.toString(),issued.toString(),
                    expiry.toString(),snapshot.actor,snapshot.tenant,snapshot.session,snapshot.scope,
                    snapshot.policy,snapshot.subject,"NOT_PERFORMED_PEOPLE_NATIVE_REFETCH_REQUIRED",
                    "dwp-auth-server","dwp-people-server","HRIS_WORKFORCE_POLICY_GOVERNANCE_AUTH_READ");
            validate(JSON.valueToTree(result),RESPONSE_SCHEMA,RESPONSE_SCHEMA);
            return result;
        }
    }
    private static void live(Snapshot snapshot,Instant current) {
        if(snapshot==null||snapshot.expiry==null||!snapshot.expiry.isAfter(current))
            throw reject(Code.EVIDENCE_EXPIRED);
    }
    private Snapshot load(Request request,String token,Instant previous) {
        Jwt jwt;
        try { jwt=decoder.decode(token); }catch(Exception invalid){throw reject(Code.ACTOR_JWT_INVALID);}
        Instant current=now(previous);
        if(jwt==null||!(jwt.getClaims().get("tenant_id") instanceof String)
                ||jwt.getExpiresAt()==null||!jwt.getExpiresAt().isAfter(current)
                ||jwt.getIssuedAt()==null||jwt.getIssuedAt().isAfter(current))
            throw reject(Code.ACTOR_JWT_INVALID);
        long tenant=bigint(jwt.getClaimAsString("tenant_id"),true);
        long user=bigint(jwt.getSubject(),true);
        var sources=new ArrayList<Map<String,Object>>();
        Map<String,Object> actorRow=one(ACTOR_SQL,Code.ACTOR_INACTIVE,tenant,user);sources.add(actorRow);
        current=now(current);
        if(!"TENANT".equals(actorRow.get("identity_plane")))throw reject(Code.PROVIDER_PLANE_DENIED);
        if(!"ACTIVE".equals(actorRow.get("status")))throw reject(Code.ACTOR_INACTIVE);
        ActorFact actor=new ActorFact(decimal(actorRow,"tenant_id"),decimal(actorRow,"user_id"),
                uuid(actorRow,"public_id"),nullableUuid(actorRow,"person_public_id"),
                decimal(actorRow,"version"),decimal(actorRow,"access_revision"),"TENANT","ACTIVE");
        if(request.expectedActor()!=null) {
            var e=request.expectedActor();
            if(!Objects.equals(e.tenantId(),actor.tenantId())||!Objects.equals(e.userId(),actor.userId())
                    ||!Objects.equals(e.principalPublicId(),actor.principalPublicId())
                    ||!Objects.equals(e.personPublicId(),actor.personPublicId()))throw reject(Code.ACTOR_LABEL_MISMATCH);
            if(!e.userRowVersion().equals(actor.userRowVersion())||!e.accessRevision().equals(actor.accessRevision()))
                throw reject(Code.ACTOR_STALE);
        }
        Map<String,Object> tenantRow=one(TENANT_SQL,Code.TENANT_INACTIVE,tenant);sources.add(tenantRow);
        if(!"ACTIVE".equals(tenantRow.get("status")))throw reject(Code.TENANT_INACTIVE);
        TenantFact tenantFact=new TenantFact(decimal(tenantRow,"tenant_id"),uuid(tenantRow,"public_id"),
                "ACTIVE",decimal(tenantRow,"version"),local(tenantRow,"updated_at"));
        String jti=jwt.getId();
        if(jti==null||jti.isBlank()||jti.length()>64)throw reject(Code.ACTOR_JWT_INVALID);
        var sessionRow=one(SESSION_SQL,Code.ACTOR_JWT_INVALID,tenant,user,jti);sources.add(sessionRow);
        current=now(current);
        Instant sessionExpiry=minimum(instant(sessionRow,"expires_at"),instant(sessionRow,"idle_expires_at"),jwt.getExpiresAt());
        Instant superseded=nullableInstant(sessionRow,"superseded_at");
        Instant grace=nullableInstant(sessionRow,"superseded_expires_at");
        if(sessionRow.get("revoked_at")!=null||(superseded!=null&&grace==null)
                ||!uuid(sessionRow,"session_family_id").equals(jwt.getClaimAsString("sid"))
                ||instant(sessionRow,"issued_at").getEpochSecond()!=jwt.getIssuedAt().getEpochSecond())
            throw reject(Code.ACTOR_JWT_INVALID);
        if(superseded!=null)sessionExpiry=minimum(sessionExpiry,grace);
        if(!sessionExpiry.isAfter(current))throw reject(Code.EVIDENCE_EXPIRED);
        SessionFact session=new SessionFact(uuid(sessionRow,"session_id"),uuid(sessionRow,"session_family_id"),
                sha(jti.getBytes(StandardCharsets.UTF_8)),time(sessionRow,"issued_at"),jwt.getExpiresAt().toString(),
                time(sessionRow,"expires_at"),time(sessionRow,"idle_expires_at"),
                nullableTime(sessionRow,"superseded_at"),nullableTime(sessionRow,"superseded_expires_at"),
                superseded==null?"ACTIVE_CURRENT":"ACTIVE_SUPERSEDED_GRACE");
        var scopeRow=one(SCOPE_SQL,Code.SCOPE_MISMATCH,tenant,UUID.fromString(request.governanceScope().resourceSetId()));
        sources.add(scopeRow);
        if(!request.governanceScope().resourceSetVersion().equals(decimal(scopeRow,"version")))throw reject(Code.SCOPE_MISMATCH);
        var evaluationRequest=new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant,user,"hcm","hcm.management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,null,null,null,null,null,List.of());
        String scopeKey=scopeKey(evaluationRequest,"RS_HCM_CONFIG","RESOURCE_SET");
        if(!scopeKey.equals(request.governanceScope().selectedScopeKey()))throw reject(Code.SCOPE_MISMATCH);
        AuthScopeFact scope=new AuthScopeFact("AUTH",uuid(scopeRow,"resource_set_id"),"RS_HCM_CONFIG",
                decimal(scopeRow,"version"),"APP.HCM",scopeKey,decimal(scopeRow,"member_version"));
        var stored=registry.findActive("product-surfaces").orElseThrow(()->reject(Code.OPERATION_UNREGISTERED));
        var contract=registry.loadContract(stored);
        var capabilities=contract.capabilities()==null?List.<com.dwp.services.auth.dto.ProductAuthorizationContractDtos.CapabilityContract>of():contract.capabilities();
        var matches=capabilities.stream().filter(c->CAPABILITY.equals(c.contractKey())&&"ACTIVE".equals(c.lifecycleState())).toList();
        if(matches.size()!=1)throw reject(Code.OPERATION_UNREGISTERED);
        var capability=matches.getFirst();
        if(!"hcm".equals(capability.productKey())||!"hcm.management".equals(capability.surfaceKey())
                ||!"ADMIN.WORKFORCE_ACCESS:MANAGE".equals(capability.resolvedCapabilityCode())
                ||!"ADMIN.WORKFORCE_ACCESS".equals(capability.resourceKey())||!"MANAGE".equals(capability.action())
                ||!capability.requiresProductEntitlement()||!"APP_RESOURCE_SET:RS_HCM_CONFIG".equals(capability.scopeResolver()))
            throw reject(Code.OPERATION_UNREGISTERED);
        if(!SOD.equals(capability.sodPolicyId()))throw reject(Code.SOD_POLICY_UNREGISTERED);
        var nativeCatalog=rows(DUTY_CATALOG_SQL,DUTY);
        if(nativeCatalog.size()!=1)throw reject(Code.DUTY_UNREGISTERED);
        sources.addAll(nativeCatalog);
        var conflicts=rows(CONFLICTS_SQL,DUTY,DUTY);
        if(conflicts.isEmpty()||conflicts.stream().anyMatch(r->!SOD.equals(r.get("sod_policy_id"))))
            throw reject(Code.SOD_POLICY_UNREGISTERED);
        sources.addAll(conflicts);
        current=now(current);
        var identity=identities.load(tenant,user);
        current=now(current);
        if(!identity.hasPermission("ADMIN.WORKFORCE_ACCESS:MANAGE"))throw reject(Code.PERMISSION_DENIED);
        var duties=ScopedAdminDutyPolicy.matchingDuties(identity,capability,"APP.HCM").stream()
                .filter(d->DUTY.equals(d.dutyCode())&&d.resourceSetId().toString().equals(scope.resourceSetId())).toList();
        if(duties.isEmpty())throw reject(Code.DUTY_DENIED);
        if(ScopedAdminDutyPolicy.staticSodConflict(duties,identity.scopedDuties()))throw reject(Code.SOD_CONFLICT);
        var result=evaluator.evaluate(evaluationRequest);
        current=now(current);
        if(result.decision()!=ProductSurfaceAuthorityDtos.Decision.ALLOWED
                ||!identity.revision().equals(result.authRevision())
                ||result.effectiveGrants().stream().noneMatch(g->g instanceof ProductSurfaceAuthorityDtos.CapabilityGrant c
                    &&CAPABILITY.equals(c.capabilityContractKey())&&c.scopeKeys().contains(scopeKey)
                    &&c.activationState()==ProductSurfaceAuthorityDtos.ActivationState.ACTIVE))
            throw reject(Code.PERMISSION_DENIED);
        if(request.expectedAuthContext()!=null) {
            var e=request.expectedAuthContext();
            if(!e.authRevision().equals(result.authRevision())||!e.policyRevision().equals(result.policyRevision())
                    ||!e.contextKey().equals(result.contextKey()))throw reject(Code.AUTH_CONTEXT_STALE);
        }
        var sourceRoles=roleRows(tenant,user,current);sources.addAll(sourceRoles);
        Set<String> codes=new HashSet<>();
        for(var row:sourceRoles) {
            String code=(String)row.get("code");
            if(code==null||!code.matches("[A-Z][A-Z0-9_.-]{0,49}")||code.startsWith("PROVIDER_")
                    ||"PROVIDER".equals(row.get("catalog_family")))throw reject(Code.PROVIDER_PLANE_DENIED);
            if(row.get("builtin_role_code")!=null&&!"ACTIVE".equals(row.get("catalog_state")))throw reject(Code.PROVIDER_PLANE_DENIED);
            codes.add(code);
        }
        if(!codes.equals(identity.roles()))throw reject(Code.AUTH_CONTEXT_STALE);
        sources.addAll(rows(POLICY_STAMPS_SQL));
        var nativeDuties=rows(EFFECTIVE_DUTY_STAMPS_SQL,tenant,user);sources.addAll(nativeDuties);
        sources.addAll(permissionRows(tenant,user,sourceRoles,current));
        SubjectFact subject=subject(request,tenant,sources);
        current=now(current);
        var dutyFacts=duties.stream().map(d->new DutyFact(d.dutyCode(),d.assignmentId().toString(),
                dutyVersion(nativeDuties,d.assignmentId(),"assignment_version"),d.resourceSetId().toString(),
                dutyVersion(nativeDuties,d.assignmentId(),"resource_set_version"),
                d.validTo()==null?null:d.validTo().toInstant().toString(),d.evidenceRevision())).toList();
        Instant expiry=minimum(sessionExpiry,result.revalidateAt().toInstant());
        if(result.validUntil()!=null)expiry=minimum(expiry,result.validUntil().toInstant());
        for(var d:duties)if(d.validTo()!=null)expiry=minimum(expiry,d.validTo().toInstant());
        for(var row:sources)for(String key:List.of("valid_to","expires_at"))
            if(row.get(key) instanceof Instant end)expiry=minimum(expiry,end);
        if(!expiry.isAfter(current))throw reject(Code.EVIDENCE_EXPIRED);
        AuthPolicyFact policy=new AuthPolicyFact(result.authRevision(),result.policyRevision(),result.contextKey(),
                CAPABILITY,"ADMIN.WORKFORCE_ACCESS:MANAGE",dutyFacts,SOD,
                "AUTH_SCOPE_CHECKED_NOT_PEOPLE_COMMAND_SOD",result.revalidateAt().toInstant().toString());
        return new Snapshot(actor,tenantFact,session,scope,policy,subject,List.copyOf(sources),expiry);
    }
    private static final String ACTOR_SQL="""
            SELECT tenant_id,user_id,public_id,person_public_id,identity_plane,status,version,access_revision,updated_at
            FROM public.com_users WHERE tenant_id=? AND user_id=?
            """;
    private static final String TENANT_SQL="SELECT tenant_id,public_id,status,version,updated_at FROM public.com_tenants WHERE tenant_id=?";
    private static final String SESSION_SQL="""
            SELECT session_id,session_family_id,issued_at,expires_at,idle_expires_at,superseded_at,
                   superseded_expires_at,revoked_at,authenticated_at,assurance_acr,assurance_amr
            FROM public.sys_auth_sessions WHERE tenant_id=? AND user_id=? AND token_id=?
            """;
    private static final String SCOPE_SQL="""
            SELECT s.resource_set_id,s.version,s.updated_at,m.resource_set_member_id,m.version member_version,
                   m.updated_at member_stamp,r.resource_id,r.updated_at resource_stamp
            FROM public.com_admin_resource_sets s
            JOIN public.com_admin_resource_set_members m ON m.tenant_id=s.tenant_id AND m.resource_set_id=s.resource_set_id
              AND m.resource_type='APP' AND m.resource_key='APP.HCM' AND m.lifecycle_state='ACTIVE'
            JOIN public.com_resources r ON r.tenant_id=m.tenant_id AND r.type=m.resource_type AND r.key=m.resource_key AND r.enabled
            WHERE s.tenant_id=? AND s.resource_set_id=? AND s.resource_set_key='RS_HCM_CONFIG' AND s.lifecycle_state='ACTIVE'
            """;
    private static final String DUTY_CATALOG_SQL="""
            SELECT c.duty_code,c.product_key,c.product_resource_key,c.resource_key,c.lifecycle_state,c.version,c.updated_at,
                   p.capability_contract_key,p.resolved_capability_code
            FROM public.sys_admin_scoped_duty_catalog c
            JOIN public.sys_admin_scoped_duty_capabilities p ON p.duty_code=c.duty_code
            WHERE c.duty_code=? AND c.product_key='hcm' AND c.product_resource_key='APP.HCM'
              AND c.resource_key='ADMIN.WORKFORCE_ACCESS' AND c.lifecycle_state='ACTIVE'
              AND p.capability_contract_key='hcm.workforce-policy.governance-read'
              AND p.resolved_capability_code='ADMIN.WORKFORCE_ACCESS:MANAGE'
            """;
    private static final String CONFLICTS_SQL="""
            SELECT left_duty_code,right_duty_code,sod_policy_id,lifecycle_state,version,updated_at
            FROM public.sys_admin_scoped_duty_conflicts
            WHERE (left_duty_code=? OR right_duty_code=?) AND lifecycle_state='ACTIVE'
            ORDER BY left_duty_code,right_duty_code LIMIT 501
            """;
    private static final String POLICY_STAMPS_SQL="""
            SELECT p.bundle_key,p.bundle_id,p.revision,p.activated_at,b.version,b.bundle_status,
                   b.schema_version,b.checksum,b.approved_by,b.approved_at,b.activated_at bundle_activated_at
            FROM public.auth_product_authorization_active p JOIN public.auth_product_authorization_bundle b
              ON b.bundle_id=p.bundle_id AND b.bundle_key=p.bundle_key WHERE p.bundle_key='product-surfaces'
            """;
    private static final String EFFECTIVE_DUTY_STAMPS_SQL="""
            SELECT scoped_duty_assignment_id,duty_code,capability_contract_key,resolved_capability_code,
                   conflicting_duty_code,resource_set_id,resource_set_key,assignment_version,resource_set_version,
                   resource_member_version,responsibility_version,subject_source_type,subject_source_ref,
                   member_resource_type,member_resource_key,evidence_revision,valid_to
            FROM public.auth_effective_scoped_duties WHERE tenant_id=? AND user_id=?
            ORDER BY scoped_duty_assignment_id,capability_contract_key,member_resource_type,member_resource_key,conflicting_duty_code LIMIT 501
            """;
    private static final String ROLE_BASE="""
            SELECT r.role_id,r.code,r.role_type,r.status,r.version role_version,r.updated_at role_stamp,
                   r.builtin_role_code,c.role_family catalog_family,c.lifecycle_state catalog_state,c.updated_at catalog_stamp,
            """;
    private static final String ROLE_CATALOG=" LEFT JOIN public.sys_builtin_role_catalog c ON c.role_code=r.builtin_role_code ";
    private static final String DIRECT_ROLES=ROLE_BASE+"""
            'DIRECT' source_kind,m.role_member_id source_id,m.updated_at source_stamp,m.created_at source_created_at
            FROM public.com_role_members m JOIN public.com_roles r ON r.tenant_id=m.tenant_id AND r.role_id=m.role_id
            """+ROLE_CATALOG+" WHERE m.tenant_id=? AND m.user_id=? AND r.status='ACTIVE' ORDER BY r.role_id,m.role_member_id LIMIT 101";
    private static final String GROUP_ROLES=ROLE_BASE+"""
            'GROUP' source_kind,a.group_role_assignment_id source_id,a.version source_version,a.updated_at source_stamp,
            a.valid_from,a.valid_to,g.group_id,g.version group_version,g.revision group_revision,g.updated_at group_stamp,
            m.group_member_id membership_id,m.updated_at membership_stamp,a.scope_type,a.scope_ref
            FROM public.com_group_role_assignments a
            JOIN public.com_group_members m ON m.tenant_id=a.tenant_id AND m.group_id=a.group_id AND m.user_id=?
            JOIN public.com_groups g ON g.tenant_id=m.tenant_id AND g.group_id=m.group_id AND g.status='ACTIVE'
            JOIN public.com_roles r ON r.tenant_id=a.tenant_id AND r.role_id=a.role_id AND r.status='ACTIVE'
            """+ROLE_CATALOG+"""
            WHERE a.tenant_id=? AND a.lifecycle_state='ACTIVE' AND a.assignment_type='ACTIVE'
              AND a.scope_type='TENANT' AND a.scope_ref IS NULL
              AND (a.valid_from IS NULL OR a.valid_from<=?) AND (a.valid_to IS NULL OR a.valid_to>?)
            ORDER BY r.role_id,a.group_role_assignment_id,m.group_member_id LIMIT 101
            """;
    private static final String PRIVILEGED_ROLES=ROLE_BASE+"""
            'PRIVILEGED' source_kind,p.active_privileged_grant_id source_id,p.updated_at source_stamp,
            p.activated_at valid_from,p.expires_at valid_to,p.scope_type,p.scope_ref
            FROM public.com_active_privileged_grants p
            JOIN public.com_roles r ON r.tenant_id=p.tenant_id AND r.role_id=p.role_id AND r.status='ACTIVE'
            """+ROLE_CATALOG+"""
            WHERE p.tenant_id=? AND p.user_id=? AND p.scope_type='TENANT' AND p.scope_ref IS NULL
              AND p.revoked_at IS NULL AND p.activated_at<=? AND p.expires_at>?
            ORDER BY r.role_id,p.active_privileged_grant_id LIMIT 101
            """;
    private static final String RECIPIENT_ROLE_SQL="""
            SELECT r.tenant_id,r.role_id,r.code,r.role_type,r.status,r.version,r.updated_at,r.builtin_role_code,
                   c.role_family,c.lifecycle_state catalog_state,c.updated_at catalog_stamp
            FROM public.com_roles r LEFT JOIN public.sys_builtin_role_catalog c ON c.role_code=r.builtin_role_code
            WHERE r.tenant_id=? AND r.code=?
            """;
    private List<Map<String,Object>> roleRows(long tenant,long user,Instant current) {
        var result=new ArrayList<Map<String,Object>>();
        result.addAll(rows(DIRECT_ROLES,tenant,user));result.addAll(rows(GROUP_ROLES,user,tenant,current,current));
        result.addAll(rows(PRIVILEGED_ROLES,tenant,user,current,current));
        if(result.size()>100)throw reject(Code.OWNER_SOURCE_UNAVAILABLE);
        return List.copyOf(result);
    }
    private List<Map<String,Object>> permissionRows(long tenant,long user,List<Map<String,Object>> roles,Instant current) {
        var result=new ArrayList<Map<String,Object>>();
        var ids=roles.stream().map(r->(Number)r.get("role_id")).map(Number::longValue).distinct().sorted().toList();
        for(long role:ids)result.addAll(rows("""
                SELECT a.role_permission_id,a.role_id,a.resource_id,a.permission_id,a.effect,a.updated_at,
                       r.key,r.enabled,r.updated_at resource_stamp,p.code,p.updated_at permission_stamp
                FROM public.com_role_permissions a JOIN public.com_resources r ON r.resource_id=a.resource_id
                JOIN public.com_permissions p ON p.permission_id=a.permission_id
                WHERE a.tenant_id=? AND a.role_id=? ORDER BY a.role_permission_id LIMIT 501
                """,tenant,role));
        result.addAll(rows("""
                SELECT p.principal_resource_grant_id,p.version,p.updated_at,p.lifecycle_state,p.valid_from,p.valid_to,
                       p.principal_type,p.principal_ref,p.resource_id,p.permission_id,p.effect,
                       r.key,r.enabled,r.updated_at resource_stamp,c.code,c.updated_at permission_stamp,
                       m.group_member_id,m.updated_at membership_stamp,g.version group_version,g.updated_at group_stamp
                FROM public.com_principal_resource_grants p
                JOIN public.com_resources r ON r.tenant_id=p.tenant_id AND r.resource_id=p.resource_id
                JOIN public.com_permissions c ON c.permission_id=p.permission_id
                LEFT JOIN public.com_group_members m ON p.principal_type='GROUP'
                  AND m.tenant_id=p.tenant_id AND m.group_id::text=p.principal_ref AND m.user_id=?
                LEFT JOIN public.com_groups g ON g.tenant_id=m.tenant_id AND g.group_id=m.group_id
                WHERE p.tenant_id=? AND p.lifecycle_state='ACTIVE' AND p.revoked_at IS NULL
                  AND p.valid_from<=? AND (p.valid_to IS NULL OR p.valid_to>?)
                  AND ((p.principal_type='USER' AND p.principal_ref=?)
                  OR (p.principal_type='GROUP' AND m.group_member_id IS NOT NULL AND g.status='ACTIVE'))
                ORDER BY p.principal_resource_grant_id LIMIT 501
                """,user,tenant,current,current,Long.toString(user)));
        result.addAll(rows("""
                SELECT a.admin_role_assignment_id,a.responsibility_code,a.resource_set_id,a.version,a.updated_at,
                       a.valid_from,a.valid_to,s.version set_version,s.updated_at set_stamp,
                       m.resource_set_member_id,m.version member_version,m.updated_at member_stamp,
                       g.version group_version,g.updated_at group_stamp,gm.group_member_id,gm.updated_at membership_stamp
                FROM public.com_admin_role_assignments a
                JOIN public.com_admin_resource_sets s ON s.tenant_id=a.tenant_id AND s.resource_set_id=a.resource_set_id
                JOIN public.com_admin_resource_set_members m ON m.tenant_id=s.tenant_id AND m.resource_set_id=s.resource_set_id
                LEFT JOIN public.com_group_members gm ON a.principal_type='GROUP' AND gm.tenant_id=a.tenant_id
                  AND gm.group_id::text=a.principal_ref AND gm.user_id=?
                LEFT JOIN public.com_groups g ON g.tenant_id=gm.tenant_id AND g.group_id=gm.group_id
                WHERE a.tenant_id=? AND a.lifecycle_state='ACTIVE' AND s.lifecycle_state='ACTIVE' AND m.lifecycle_state='ACTIVE'
                  AND (a.valid_from IS NULL OR a.valid_from<=?) AND (a.valid_to IS NULL OR a.valid_to>?)
                  AND ((a.principal_type='USER' AND a.principal_ref=?) OR (a.principal_type='GROUP' AND g.status='ACTIVE'))
                ORDER BY a.admin_role_assignment_id,m.resource_set_member_id LIMIT 501
                """,user,tenant,current,current,Long.toString(user)));
        return result;
    }
    private List<Map<String,Object>> rows(String sql,Object... args) {
        return jdbc.query(connection-> {
            var statement=connection.prepareStatement(sql);statement.setQueryTimeout(5);
            for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i] instanceof Instant t?Timestamp.from(t):args[i]);
            return statement;
        },(row,index)->{
            if(index>=500||(sql.contains("LIMIT 101")&&index>=100))throw reject(Code.OWNER_SOURCE_UNAVAILABLE);
            var result=new LinkedHashMap<String,Object>();var metadata=row.getMetaData();
            for(int i=1;i<=metadata.getColumnCount();i++) {
                String type=metadata.getColumnTypeName(i);
                Object value=type.equals("timestamp")?row.getObject(i,LocalDateTime.class)
                        :type.equals("timestamptz")?row.getObject(i,OffsetDateTime.class):row.getObject(i);
                if(value instanceof OffsetDateTime offset)value=offset.toInstant();
                result.put(metadata.getColumnLabel(i),value);
            }
            return Collections.unmodifiableMap(result);
        });
    }
    private Map<String,Object> one(String sql,Code missing,Object... args) {
        var result=rows(sql,args);if(result.size()!=1)throw reject(missing);return result.getFirst();
    }
    private static String decimal(Map<String,Object> row,String key) {
        Object value=row.get(key);if(!(value instanceof Number number)||number.longValue()<0)throw reject(Code.OWNER_SOURCE_UNAVAILABLE);
        return Long.toString(number.longValue());
    }
    private static String uuid(Map<String,Object> row,String key) {
        String value=nullableUuid(row,key);if(value==null)throw reject(Code.OWNER_SOURCE_UNAVAILABLE);return value;
    }
    private static String nullableUuid(Map<String,Object> row,String key) {
        Object value=row.get(key);if(value==null)return null;
        if(!(value instanceof UUID id)||id.equals(new UUID(0,0)))throw reject(Code.OWNER_SOURCE_UNAVAILABLE);return id.toString();
    }
    private static String local(Map<String,Object> row,String key) {
        Object value=row.get(key);if(!(value instanceof LocalDateTime date))throw reject(Code.OWNER_SOURCE_UNAVAILABLE);return date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
    private static Instant instant(Map<String,Object> row,String key) {
        Instant value=nullableInstant(row,key);if(value==null)throw reject(Code.OWNER_SOURCE_UNAVAILABLE);return value;
    }
    private static Instant nullableInstant(Map<String,Object> row,String key) {
        Object value=row.get(key);if(value==null)return null;
        if(!(value instanceof Instant time))throw reject(Code.OWNER_SOURCE_UNAVAILABLE);return time;
    }
    private static String time(Map<String,Object> row,String key) { return instant(row,key).toString(); }
    private static String nullableTime(Map<String,Object> row,String key) {
        Instant value=nullableInstant(row,key);return value==null?null:value.toString();
    }
    private static Instant minimum(Instant... values) {
        if(Arrays.stream(values).anyMatch(Objects::isNull))throw reject(Code.OWNER_SOURCE_UNAVAILABLE);
        return Arrays.stream(values).min(Comparator.naturalOrder()).orElseThrow();
    }
    private static Rejected reject(Code code) { return new Rejected(code); }
    static Request decode(byte[] body) {
        if(body==null||body.length==0||body.length>32768)throw reject(Code.MALFORMED_REQUEST);
        try {
            JsonNode root=JSON.readTree(body);
            if(root==null||!root.isObject())throw reject(Code.MALFORMED_REQUEST);
            if(!root.path("schemaVersion").isIntegralNumber()||root.path("schemaVersion").intValue()!=1)
                throw reject(Code.UNSUPPORTED_SCHEMA);
            try { Operation.valueOf(root.path("operationId").textValue()); }
            catch(Exception unknown){throw reject(Code.OPERATION_UNREGISTERED);}
            ObjectNode normalized=((ObjectNode)root).deepCopy();
            JsonNode candidate=normalized.path("candidate");
            if(candidate.isObject()) {
                for(String key:List.of("justification","reason"))if(candidate.path(key).isTextual())
                    ((ObjectNode)candidate).put(key,candidate.path(key).textValue().trim());
                for(String key:List.of("subject","storedSubjectExpectation"))
                    if(candidate.path(key).path("code").isTextual())
                        ((ObjectNode)candidate.path(key)).put("code",candidate.path(key).path("code").textValue().trim());
            }
            validate(normalized,REQUEST_SCHEMA,REQUEST_SCHEMA);
            Candidate typed;
            if("CREATE_POLICY".equals(candidate.path("kind").asText())) {
                var create=(ObjectNode)candidate;
                var fields=strings(create.path("fieldGroups"));var actions=strings(create.path("actionCodes"));
                fields.sort(String::compareTo);actions.sort(String::compareTo);
                String from=nullableString(create.get("validFrom")),to=nullableString(create.get("validTo"));
                if(from!=null&&to!=null&&!Instant.parse(to).isAfter(Instant.parse(from)))throw reject(Code.MALFORMED_REQUEST);
                typed=new CreateCandidate("CREATE_POLICY",subjectRequest(create.path("subject")),
                        JSON.treeToValue(create.path("population"),Population.class),fields,actions,from,to,
                        create.path("justification").textValue());
            } else if("REVOKE_POLICY".equals(candidate.path("kind").asText())) {
                typed=new RevokeCandidate("REVOKE_POLICY",JSON.treeToValue(candidate.path("policy"),PolicyOwnerExpectation.class),
                        subjectRequest(candidate.path("storedSubjectExpectation")),candidate.path("reason").textValue());
            } else typed=new NoCandidate("NONE");
            return new Request(1,Operation.valueOf(normalized.path("operationId").textValue()),
                    normalized.path("requestNonce").textValue(),
                    normalized.path("expectedActor").isNull()?null:JSON.treeToValue(normalized.path("expectedActor"),ActorExpectation.class),
                    normalized.path("expectedAuthContext").isNull()?null:JSON.treeToValue(normalized.path("expectedAuthContext"),AuthContextExpectation.class),
                    JSON.treeToValue(normalized.path("governanceScope"),GovernanceScopeExpectation.class),
                    typed,nullableString(normalized.get("expectedRequestDigest")));
        }catch(Rejected denied){throw denied;}catch(Exception malformed){throw reject(Code.MALFORMED_REQUEST);}
    }
    private static Subject subjectRequest(JsonNode node) throws com.fasterxml.jackson.core.JsonProcessingException {
        return "ROLE".equals(node.path("kind").asText())?JSON.treeToValue(node,RoleSubject.class):JSON.treeToValue(node,UserSubject.class);
    }
    private static ArrayList<String> strings(JsonNode node) {
        var result=new ArrayList<String>();node.forEach(v->result.add(v.textValue()));return result;
    }
    private static String nullableString(JsonNode node) { return node==null||node.isNull()?null:node.textValue(); }
    private SubjectFact subject(Request request,long tenant,List<Map<String,Object>> sources) {
        Subject expected;
        if(request.candidate() instanceof NoCandidate)return new NoSubjectFact("NO_SUBJECT_FOR_READ");
        if(request.candidate() instanceof RevokeCandidate revoke) {
            expected=revoke.storedSubjectExpectation();
            if(expected instanceof RoleSubject role)return new LegacySubjectFact(
                    "HISTORICAL_ROLE_EXPECTATION_NOT_NATIVE_PROOF",role.code(),"VALIDATED_REQUEST_EXPECTATION",null,"REQUIRED");
        }else expected=((CreateCandidate)request.candidate()).subject();
        if(expected instanceof RoleSubject role) {
            var row=one(RECIPIENT_ROLE_SQL,Code.SUBJECT_NOT_FOUND,tenant,role.code());sources.add(row);
            if(!"ACTIVE".equals(row.get("status")))throw reject(Code.SUBJECT_INACTIVE);
            String family=(String)row.get("role_family"),builtin=(String)row.get("builtin_role_code");
            if(role.code().startsWith("PROVIDER_")||"PROVIDER".equals(family))throw reject(Code.PROVIDER_PLANE_DENIED);
            if(builtin!=null&&!"ACTIVE".equals(row.get("catalog_state")))throw reject(Code.SUBJECT_INACTIVE);
            if(role.expectedRoleId()!=null&&!role.expectedRoleId().equals(decimal(row,"role_id")))throw reject(Code.SUBJECT_STALE);
            if(role.expectedRoleVersion()!=null&&!role.expectedRoleVersion().equals(decimal(row,"version")))throw reject(Code.SUBJECT_STALE);
            return new NativeRoleFact("NATIVE_ROLE",decimal(row,"tenant_id"),decimal(row,"role_id"),(String)row.get("code"),
                    (String)row.get("role_type"),"ACTIVE",decimal(row,"version"),local(row,"updated_at"),builtin,
                    family,(String)row.get("catalog_state"),nullableTime(row,"catalog_stamp"));
        }
        var target=(UserSubject)expected;
        var row=one(ACTOR_SQL,Code.SUBJECT_NOT_FOUND,tenant,bigint(target.userId(),true));sources.add(row);
        if(!"TENANT".equals(row.get("identity_plane")))throw reject(Code.PROVIDER_PLANE_DENIED);
        if(request.candidate() instanceof CreateCandidate&&!"ACTIVE".equals(row.get("status")))throw reject(Code.SUBJECT_INACTIVE);
        if(!Set.of("ACTIVE","INACTIVE").contains(row.get("status")))throw reject(Code.SUBJECT_INACTIVE);
        if(target.expectedPrincipalPublicId()!=null&&!target.expectedPrincipalPublicId().equals(uuid(row,"public_id")))throw reject(Code.SUBJECT_STALE);
        if(target.expectedUserRowVersion()!=null&&!target.expectedUserRowVersion().equals(decimal(row,"version")))throw reject(Code.SUBJECT_STALE);
        return new NativeUserFact("NATIVE_USER",decimal(row,"tenant_id"),decimal(row,"user_id"),uuid(row,"public_id"),
                nullableUuid(row,"person_public_id"),(String)row.get("status"),"TENANT",decimal(row,"version"));
    }
    static record Digests(String request,String candidate) { }
    static Digests digests(Request request) {
        ObjectNode node=JSON.valueToTree(request);node.remove("expectedRequestDigest");
        return new Digests(canonicalDigest(Map.of("canonicalVersion","DWP_WORKFORCE_REQUEST_V1","request",node)),
                canonicalDigest(Map.of("canonicalVersion","DWP_WORKFORCE_CANDIDATE_V1",
                        "operationId",request.operationId().name(),"candidate",request.candidate())));
    }
    private static String canonicalDigest(Object value) {
        try { return sha(JSON.writeValueAsBytes(sorted(JSON.valueToTree(value)))); }
        catch(Exception failure){throw reject(Code.OWNER_SOURCE_UNAVAILABLE);}
    }
    private static JsonNode sorted(JsonNode node) {
        if(node.isObject()) {
            var result=JSON.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);
            names.forEach(key->result.set(key,sorted(node.get(key))));return result;
        }
        if(node.isArray()) { var result=JSON.createArrayNode();node.forEach(item->result.add(sorted(item)));return result; }
        return node;
    }
    private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch(Exception failure){throw reject(Code.OWNER_SOURCE_UNAVAILABLE);}
    }
    private static String dutyVersion(List<Map<String,Object>> rows,UUID id,String field) {
        var values=rows.stream().filter(r->id.equals(r.get("scoped_duty_assignment_id")))
                .map(r->decimal(r,field)).distinct().toList();
        if(values.size()!=1)throw reject(Code.OWNER_SOURCE_UNAVAILABLE);return values.getFirst();
    }
    private static boolean sameAuthority(Snapshot a,Snapshot b) {
        return a.actor.equals(b.actor)&&a.tenant.equals(b.tenant)&&a.session.equals(b.session)&&a.scope.equals(b.scope)
                &&a.subject.equals(b.subject)&&a.nativeSources.equals(b.nativeSources)
                &&a.policy.authRevision().equals(b.policy.authRevision())
                &&a.policy.policyRevision().equals(b.policy.policyRevision())
                &&a.policy.contextKey().equals(b.policy.contextKey())
                &&a.policy.dutyEvidence().equals(b.policy.dutyEvidence());
    }
    private static JsonNode schema(String text) {
        try { return JSON.readTree(text); }catch(Exception error){throw new ExceptionInInitializerError("Invalid fixed S1 schema");}
    }
    private static long bigint(String text,boolean positive) {
        try {
            if(text==null||!text.matches(positive?"[1-9][0-9]{0,18}":"0|[1-9][0-9]{0,18}"))throw reject(Code.MALFORMED_REQUEST);
            long result=Long.parseLong(text);if(result<(positive?1:0))throw reject(Code.MALFORMED_REQUEST);return result;
        }catch(Rejected denied){throw denied;}catch(Exception invalid){throw reject(Code.MALFORMED_REQUEST);}
    }
    private static boolean accepts(JsonNode value,JsonNode schema,JsonNode root) {
        try { validate(value,schema,root);return true; }catch(Rejected invalid){return false;}
    }
    private static void validate(JsonNode value,JsonNode spec,JsonNode root) {
        if(spec.has("$ref")) {
            String ref=spec.get("$ref").textValue();if(!ref.startsWith("#/definitions/"))throw reject(Code.OWNER_SOURCE_UNAVAILABLE);
            String type=ref.substring("#/definitions/".length());
            if(!root.path("definitions").has(type))throw reject(Code.OWNER_SOURCE_UNAVAILABLE);
            validate(value,root.path("definitions").get(type),root);
            try {
                if("UUID".equals(type)&&(!UUID.fromString(value.textValue()).toString().equals(value.textValue())
                        ||new UUID(0,0).toString().equals(value.textValue())))throw reject(Code.MALFORMED_REQUEST);
                if("PositiveBigint".equals(type))bigint(value.textValue(),true);
                if("NonnegativeBigint".equals(type))bigint(value.textValue(),false);
                if("LocalTimestamp".equals(type)&&!LocalDateTime.parse(value.textValue()).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME).equals(value.textValue()))throw reject(Code.MALFORMED_REQUEST);
            }catch(Rejected denied){throw denied;}catch(Exception invalid){throw reject(Code.MALFORMED_REQUEST);}
            return;
        }
        Set<String> keywords=Set.of("$schema","$id","definitions","type","additionalProperties","properties","required","allOf","if","then",
                "anyOf","oneOf","format","pattern","const","enum","minLength","maxLength","minItems","maxItems","uniqueItems","contains","items");
        spec.fieldNames().forEachRemaining(k->{if(!keywords.contains(k))throw reject(Code.OWNER_SOURCE_UNAVAILABLE);});
        if(spec.has("const")&&!spec.get("const").equals(value))throw reject(Code.MALFORMED_REQUEST);
        if(spec.has("enum")) { boolean found=false;for(var option:spec.get("enum"))if(option.equals(value))found=true;if(!found)throw reject(Code.MALFORMED_REQUEST); }
        for(String key:List.of("anyOf","oneOf"))if(spec.has(key)) {
            long count=0;for(var option:spec.get(key))if(accepts(value,option,root))count++;
            if(count==0||("oneOf".equals(key)&&count!=1))throw reject(Code.MALFORMED_REQUEST);
        }
        if(spec.has("type")) {
            boolean valid=switch(spec.get("type").textValue()) {
                case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isTextual();
                case "integer"->value.isIntegralNumber();case "null"->value.isNull();default->false;
            };if(!valid)throw reject(Code.MALFORMED_REQUEST);
        }
        if(value.isObject()) {
            for(var key:spec.path("required"))if(!value.has(key.textValue()))throw reject(Code.MALFORMED_REQUEST);
            var properties=spec.path("properties");
            if(spec.has("additionalProperties")&&!spec.get("additionalProperties").booleanValue())
                value.fieldNames().forEachRemaining(k->{if(!properties.has(k))throw reject(Code.MALFORMED_REQUEST);});
            properties.fields().forEachRemaining(e->{if(value.has(e.getKey()))validate(value.get(e.getKey()),e.getValue(),root);});
        }
        if(value.isArray()) {
            if(spec.has("minItems")&&value.size()<spec.get("minItems").intValue()
                    ||spec.has("maxItems")&&value.size()>spec.get("maxItems").intValue())throw reject(Code.MALFORMED_REQUEST);
            if(spec.path("uniqueItems").asBoolean()) { var unique=new HashSet<JsonNode>();for(var item:value)if(!unique.add(item))throw reject(Code.MALFORMED_REQUEST); }
            if(spec.has("items"))for(var item:value)validate(item,spec.get("items"),root);
            if(spec.has("contains")) { boolean found=false;for(var item:value)if(accepts(item,spec.get("contains"),root))found=true;if(!found)throw reject(Code.MALFORMED_REQUEST); }
        }
        if(value.isTextual()) {
            String text=value.textValue();int length=text.codePointCount(0,text.length());
            if(spec.has("minLength")&&length<spec.get("minLength").intValue()
                    ||spec.has("maxLength")&&length>spec.get("maxLength").intValue())throw reject(Code.MALFORMED_REQUEST);
            if(spec.has("pattern")&&!java.util.regex.Pattern.compile(spec.get("pattern").textValue()).matcher(text).find())throw reject(Code.MALFORMED_REQUEST);
            if(spec.has("format")) {
                if(!"date-time".equals(spec.get("format").textValue()))throw reject(Code.OWNER_SOURCE_UNAVAILABLE);
                try { if(!Instant.parse(text).toString().equals(text))throw reject(Code.MALFORMED_REQUEST); }
                catch(Rejected denied){throw denied;}catch(Exception invalid){throw reject(Code.MALFORMED_REQUEST);}
            }
        }
        if(spec.has("allOf"))for(var rule:spec.get("allOf")) {
            if(rule.has("if")) { if(accepts(value,rule.get("if"),root))validate(value,rule.get("then"),root); }
            else validate(value,rule,root);
        }
    }
}
