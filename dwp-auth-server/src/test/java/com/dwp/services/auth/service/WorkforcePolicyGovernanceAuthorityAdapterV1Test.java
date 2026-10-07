package com.dwp.services.auth.service;

import static com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;

/** Closed decoder/digest and missing-owner tests only; no native or command authority. */
class WorkforcePolicyGovernanceAuthorityAdapterV1Test {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String NONCE="11111111-1111-4111-8111-111111111111";
    static final String SET="22222222-2222-4222-8222-222222222222";
    static ObjectNode request(String operation) {
        var node=JSON.createObjectNode();node.put("schemaVersion",1);node.put("operationId",operation);
        node.put("requestNonce",NONCE);node.putNull("expectedActor");node.putNull("expectedAuthContext");
        node.putObject("governanceScope").put("selectedScopeKey","scope-owner-fixture")
                .put("resourceSetId",SET).put("resourceSetVersion","0");
        node.putObject("candidate").put("kind","NONE");node.putNull("expectedRequestDigest");return node;
    }
    static ObjectNode create(String role) {
        var node=request("POLICY_CREATE_PREFLIGHT_AUTH_READ");var c=node.putObject("candidate");
        c.put("kind","CREATE_POLICY");
        c.putObject("subject").put("kind","ROLE").put("code",role).putNull("expectedRoleId").putNull("expectedRoleVersion");
        c.putObject("population").put("kind","TENANT").putNull("organizationPublicId");
        c.putArray("fieldGroups").add("DIRECTORY").add("JOB_GRADE");
        c.putArray("actionCodes").add("READ");c.putNull("validFrom");c.putNull("validTo");
        c.put("justification","Owner fixture candidate");return node;
    }
    static ObjectNode revoke(String role) {
        var node=request("POLICY_REVOKE_PREFLIGHT_AUTH_READ");var c=node.putObject("candidate");
        c.put("kind","REVOKE_POLICY");
        c.putObject("policy").put("owner","PEOPLE").put("kind","WORKFORCE_ACCESS_POLICY_EXPECTATION")
                .put("policyId","33333333-3333-4333-8333-333333333333").put("expectedVersion","0");
        c.putObject("storedSubjectExpectation").put("kind","ROLE").put("code",role)
                .putNull("expectedRoleId").putNull("expectedRoleVersion");
        c.put("reason","Historical policy withdrawal");return node;
    }
    static byte[] bytes(ObjectNode node) { return node.toString().getBytes(StandardCharsets.UTF_8); }
    @ParameterizedTest @MethodSource("validRoles")
    void nativeRoleCodesAreTrimmedButNeverUppercased(String role) {
        var typed=WorkforcePolicyGovernanceAuthorityAdapterV1.decode(bytes(create(" "+role+" ")));
        assertEquals(role,((RoleSubject)((CreateCandidate)typed.candidate()).subject()).code());
    }
    static Stream<String> validRoles() { return Stream.of("A","A.B","A-B","HR_ADMIN","A"+"B".repeat(49)); }
    @Test void allFourServerOperationsHaveAnExactTypedCandidate() {
        assertInstanceOf(NoCandidate.class,WorkforcePolicyGovernanceAuthorityAdapterV1.decode(bytes(request("POLICY_LIST_AUTH_READ"))).candidate());
        assertInstanceOf(NoCandidate.class,WorkforcePolicyGovernanceAuthorityAdapterV1.decode(bytes(request("ORGANIZATION_OPTIONS_AUTH_READ"))).candidate());
        assertInstanceOf(CreateCandidate.class,WorkforcePolicyGovernanceAuthorityAdapterV1.decode(bytes(create("A"))).candidate());
        assertInstanceOf(RevokeCandidate.class,WorkforcePolicyGovernanceAuthorityAdapterV1.decode(bytes(revoke("A"+"B".repeat(79)))).candidate());
    }
    record Invalid(String id,ObjectNode body,Code code) { }
    static Stream<Invalid> invalidRequests() {
        var rows=new ArrayList<Invalid>();
        ObjectNode n=request("POLICY_LIST_AUTH_READ");n.put("schemaVersion",true);rows.add(new Invalid("boolean-schema",n,Code.UNSUPPORTED_SCHEMA));
        n=request("POLICY_LIST_AUTH_READ");n.put("schemaVersion",2);rows.add(new Invalid("unknown-schema",n,Code.UNSUPPORTED_SCHEMA));
        n=request("POLICY_LIST_AUTH_READ");n.remove("schemaVersion");rows.add(new Invalid("missing-schema",n,Code.UNSUPPORTED_SCHEMA));
        n=request("POLICY_LIST_AUTH_READ");n.put("operationId","POLICY_CREATE");rows.add(new Invalid("unknown-operation",n,Code.OPERATION_UNREGISTERED));
        n=request("POLICY_LIST_AUTH_READ");n.put("operationId"," POLICY_LIST_AUTH_READ");rows.add(new Invalid("padded-operation",n,Code.OPERATION_UNREGISTERED));
        n=request("POLICY_LIST_AUTH_READ");n.put("entitled",true);rows.add(new Invalid("client-entitlement",n,Code.MALFORMED_REQUEST));
        n=request("POLICY_LIST_AUTH_READ");n.remove("expectedActor");rows.add(new Invalid("missing-nullable-key",n,Code.MALFORMED_REQUEST));
        n=request("POLICY_LIST_AUTH_READ");n.put("requestNonce","00000000-0000-0000-0000-000000000000");rows.add(new Invalid("zero-uuid",n,Code.MALFORMED_REQUEST));
        n=request("POLICY_LIST_AUTH_READ");n.put("requestNonce",NONCE.toUpperCase()+"x");rows.add(new Invalid("uuid-noncanonical",n,Code.MALFORMED_REQUEST));
        n=request("POLICY_LIST_AUTH_READ");((ObjectNode)n.path("governanceScope")).put("resourceSetVersion",0);rows.add(new Invalid("numeric-native-bigint",n,Code.MALFORMED_REQUEST));
        n=request("POLICY_LIST_AUTH_READ");((ObjectNode)n.path("governanceScope")).put("resourceSetVersion","9223372036854775808");rows.add(new Invalid("bigint-overflow",n,Code.MALFORMED_REQUEST));
        n=request("POLICY_LIST_AUTH_READ");((ObjectNode)n.path("governanceScope")).put("resourceSetVersion","00");rows.add(new Invalid("padded-bigint",n,Code.MALFORMED_REQUEST));
        n=create("a.b");rows.add(new Invalid("lowercase-role",n,Code.MALFORMED_REQUEST));
        n=create("A"+"B".repeat(50));rows.add(new Invalid("create-legacy80",n,Code.MALFORMED_REQUEST));
        n=create("A");((ObjectNode)n.path("candidate")).putArray("fieldGroups").add("JOB_GRADE");rows.add(new Invalid("directory-required",n,Code.MALFORMED_REQUEST));
        n=create("A");((ObjectNode)n.path("candidate")).putArray("fieldGroups").add("DIRECTORY").add("DIRECTORY");rows.add(new Invalid("duplicate-field",n,Code.MALFORMED_REQUEST));
        n=create("A");((ObjectNode)n.path("candidate")).putArray("actionCodes").add("MANAGE");rows.add(new Invalid("candidate-manage",n,Code.MALFORMED_REQUEST));
        n=create("A");((ObjectNode)n.path("candidate")).put("validFrom","2026-02-30T00:00:00Z");rows.add(new Invalid("invalid-civil-date",n,Code.MALFORMED_REQUEST));
        n=create("A");((ObjectNode)n.path("candidate")).put("validFrom","2026-09-14T00:00:00+00:00");rows.add(new Invalid("noncanonical-instant",n,Code.MALFORMED_REQUEST));
        n=create("A");((ObjectNode)n.path("candidate")).put("validFrom","2026-09-14T00:00:00Z").put("validTo","2026-09-14T00:00:00Z");rows.add(new Invalid("empty-window",n,Code.MALFORMED_REQUEST));
        n=create("A");((ObjectNode)n.path("candidate")).put("justification","  ");rows.add(new Invalid("trim-empty-reason",n,Code.MALFORMED_REQUEST));
        n=create("A");((ObjectNode)n.path("candidate").path("population")).put("kind","ORG_TREE");rows.add(new Invalid("org-without-owner-uuid",n,Code.MALFORMED_REQUEST));
        n=create("A");n.put("operationId","POLICY_LIST_AUTH_READ");rows.add(new Invalid("operation-candidate-discriminator",n,Code.MALFORMED_REQUEST));
        n=revoke("A");((ObjectNode)n.path("candidate").path("policy")).put("owner","AUTH");rows.add(new Invalid("policy-owner-relabel",n,Code.MALFORMED_REQUEST));
        return rows.stream();
    }
    @ParameterizedTest @MethodSource("invalidRequests")
    void closedRequestRejectsBeforeProviders(Invalid row) {
        var jdbc=mock(JdbcTemplate.class);var decoder=mock(JwtDecoder.class);
        var identities=mock(ProductAuthorizationIdentityEvidenceService.class);
        var evaluator=mock(ProductAuthorizationAuthorityAdapter.class);
        var registry=mock(com.dwp.services.auth.repository.ProductAuthorizationContractRepository.class);
        var tx=mock(PlatformTransactionManager.class);
        var adapter=new WorkforcePolicyGovernanceAuthorityAdapterV1(jdbc,identities,evaluator,registry,tx,decoder);
        var response=assertInstanceOf(Failure.class,adapter.evaluate(bytes(row.body()),"unused"));
        assertEquals(row.code(),response.code(),row.id());verifyNoInteractions(jdbc,decoder,identities,evaluator,registry,tx);
    }
    @Test void duplicateKeysTrailingTokensOversizeAndDeepInputAreNotAccepted() {
        String valid=request("POLICY_LIST_AUTH_READ").toString();
        for(String body:List.of(valid.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),
                valid+" {}", "["+valid+"]","{"+ "\"x\":[".repeat(25)+"0"+"]".repeat(25)+"}"," ".repeat(32769))) {
            assertThrows(WorkforcePolicyGovernanceAuthorityAdapterV1.Rejected.class,
                    ()->WorkforcePolicyGovernanceAuthorityAdapterV1.decode(body.getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void missingAdapterIsUnavailableWithoutCallingAnyAvailableProvider() {
        var jdbc=mock(JdbcTemplate.class);var decoder=mock(JwtDecoder.class);
        var response=new WorkforcePolicyGovernanceAuthorityAdapterV1(jdbc,null,null,null,null,decoder)
                .evaluate(bytes(request("POLICY_LIST_AUTH_READ")),"unused");
        assertEquals(Code.OWNER_SOURCE_UNAVAILABLE,assertInstanceOf(Failure.class,response).code());
        verifyNoInteractions(jdbc,decoder);
    }
    @Test void nativeStringRevisionsAndNullableActorPersonAreNotCoerced() {
        var n=request("POLICY_LIST_AUTH_READ");
        n.putObject("expectedActor").put("tenantId","41").put("userId","900009").put("principalPublicId",NONCE)
                .putNull("personPublicId").put("userRowVersion","9223372036854775807").put("accessRevision","0");
        n.putObject("expectedAuthContext").put("authRevision","auth-"+"a".repeat(64))
                .put("policyRevision","policy-native-opaque-v1:7:hash").put("contextKey","psc-"+"b".repeat(64));
        var typed=WorkforcePolicyGovernanceAuthorityAdapterV1.decode(bytes(n));
        assertNull(typed.expectedActor().personPublicId());assertEquals("9223372036854775807",typed.expectedActor().userRowVersion());
        assertEquals("policy-native-opaque-v1:7:hash",typed.expectedAuthContext().policyRevision());
    }
    @Test void normalizationAndFieldOrderHaveOneDigestButSemanticChangesDoNot() {
        var a=create("A.B");var b=create(" A.B ");((ObjectNode)b.path("candidate")).put("justification"," Owner fixture candidate ");
        ((ObjectNode)b.path("candidate")).putArray("fieldGroups").add("JOB_GRADE").add("DIRECTORY");
        var first=WorkforcePolicyGovernanceAuthorityAdapterV1.digests(WorkforcePolicyGovernanceAuthorityAdapterV1.decode(bytes(a)));
        var second=WorkforcePolicyGovernanceAuthorityAdapterV1.digests(WorkforcePolicyGovernanceAuthorityAdapterV1.decode(bytes(b)));
        assertEquals(first,second);assertTrue(first.request().matches("[0-9a-f]{64}"));
        ((ObjectNode)b.path("candidate")).putArray("actionCodes").add("EXPORT");
        assertNotEquals(first,WorkforcePolicyGovernanceAuthorityAdapterV1.digests(WorkforcePolicyGovernanceAuthorityAdapterV1.decode(bytes(b))));
        assertFalse(first.request().startsWith("auth-"));assertNotEquals(first.request(),first.candidate());
    }
}
