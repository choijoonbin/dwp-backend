package com.dwp.services.approval.workflowplanning;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.nimbusds.jose.jwk.JWKSet;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class WorkflowPlanningBoundaryTest {
    static String body(String sample) {
        return "{\"workflowRevision\":0,\"workflowSha256\":\""+"a".repeat(64)+"\",\"formVersionId\":\"00000000-0000-0000-0000-000000000001\","
                +"\"formSchemaSha256\":\""+"b".repeat(64)+"\",\"policyVersion\":1,\"policySha256\":\""+"c".repeat(64)+"\",\"managementResourceSetKey\":\"RS_STUDIO\",\"samplePayload\":"+sample+"}";
    }
    static byte[] bytes(String value) {return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    @Test void duplicateNestedFieldsUnknownCallerAuthorityAndTrailingJsonAreRejected() {
        for(String raw:new String[]{body("{\"amount\":\"1\",\"amount\":\"2\"}"),body("{}").replace("\"workflowRevision\":0","\"workflowRevision\":0,\"workflowRevision\":1"),
                body("{}").replace("\"samplePayload\":{}","\"samplePayload\":{},\"requesterUserId\":100"),body("{}")+"{}"})
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->WorkflowPlanningBody.parse(bytes(raw))).getErrorCode());
    }
    @Test void plainDecimalStringRemainsFormDataButFractionalJsonNumberCannotBypassTheTypedTransportContract() {
        var valid=WorkflowPlanningBody.parse(bytes(body("{\"amount\":\"20.125\"}")));assertEquals("20.125",valid.samplePayload().get("amount"));
        valid.selection(java.util.UUID.randomUUID(),java.util.UUID.randomUUID());
        var invalid=WorkflowPlanningBody.parse(bytes(body("{\"amount\":20.125}")));
        assertThrows(BaseException.class,()->invalid.selection(java.util.UUID.randomUUID(),java.util.UUID.randomUUID()));
    }
    @Test void publicBodyBoundsAndIntegralPinsAreNotTruncatedOrCoerced() {
        assertThrows(BaseException.class,()->WorkflowPlanningBody.parse(new byte[WorkflowPlanningProtocol.LOOKUP_MAX+1]));
        for(String version:new String[]{"-1","0.5","9007199254740992","\"0\""})
            assertThrows(BaseException.class,()->WorkflowPlanningBody.parse(bytes(body("{}").replace("\"workflowRevision\":0","\"workflowRevision\":"+version))));
    }
    @Test void allPurposeKeysAreDistinctAndPrivateAuthTrustIsForbidden() {
        com.nimbusds.jose.jwk.RSAKey owner=WorkflowPlanningSignedEndpointFixture.key("owner"),transport=WorkflowPlanningSignedEndpointFixture.key("transport"),auth=WorkflowPlanningSignedEndpointFixture.key("auth"),other=WorkflowPlanningSignedEndpointFixture.key("forbidden");
        String trusted=new JWKSet(auth.toPublicJWK()).toString(),forbidden=new JWKSet(other.toPublicJWK()).toString();
        assertDoesNotThrow(()->new WorkflowPlanningKeys(owner,transport,trusted,forbidden));
        assertThrows(BaseException.class,()->new WorkflowPlanningKeys(owner,owner,trusted,forbidden));
        assertThrows(BaseException.class,()->new WorkflowPlanningKeys(owner,transport,new JWKSet(auth).toString(false),forbidden));
        assertThrows(BaseException.class,()->new WorkflowPlanningKeys(owner,transport,trusted,new JWKSet(owner.toPublicJWK()).toString()));
        assertThrows(BaseException.class,()->new WorkflowPlanningKeys(owner,transport,trusted,forbidden,"kid:owner"));
        assertThrows(BaseException.class,()->new WorkflowPlanningKeys(owner,transport,trusted,forbidden,new JWKSet(auth.toPublicJWK()).toString()));
    }
    @Test void endpointLiteralNeverAcceptsAliasQueryUserInfoRemoteCleartextOrOtherPurposePath() throws Exception {
        try(var fixture=new WorkflowPlanningSignedEndpointFixture()) {
            var keys=new WorkflowPlanningKeys(fixture.owner,fixture.transport,new JWKSet(fixture.auth.toPublicJWK()).toString(),new JWKSet(fixture.unrelated.toPublicJWK()).toString());
            var verifier=new WorkflowPlanningAttestationVerifier(keys,Clock.systemUTC());
            for(String uri:new String[]{"http://remote.test"+WorkflowPlanningProtocol.PATH,"http://localhost"+WorkflowPlanningProtocol.PATH+"?roleId=1",
                    "http://user@localhost"+WorkflowPlanningProtocol.PATH,"http://localhost/internal/approval-workflow/runtime-authority","http://localhost/internal/approval-workflow/%61dmin-planning"})
                assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->new WorkflowPlanningAuthorityClient(URI.create(uri),verifier)).getErrorCode());
        }
    }
}
