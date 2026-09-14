package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.informationreplay.*;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Auth DB/Redis/filter/HTTP negative gate. Approval DATA context and historical admission are fixtures; genuine v9 activation is not asserted. */
@Testcontainers
class InformationReceiptActualAuthHttpTest {
    private static final String HARNESS="com.dwp.services.auth.service.InformationReplayActualAuthHarness";
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static RSAKey key(String id) throws Exception {return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();}
    @Test void genuineSealedEightCannotAuthorizeReceiptButActualSignedClientAndAuthRejectTransportSubstitution() throws Exception {
        if(!Boolean.getBoolean("dwp.workflow.cross-service-gate")) throw new IllegalStateException("The coherent actual Auth harness gate is required; absence is not success, skip, or activation.");
        Class<?> type;
        try {type=Class.forName(HARNESS);} catch(ClassNotFoundException missing) {throw new IllegalStateException("Actual Auth receipt harness is absent; the cross-service gate is NOT verified.",missing);}
        var owner=key("receipt-cross-owner");var transport=key("receipt-cross-transport");var attestation=key("receipt-cross-auth");
        Object fixture=type.getConstructor(RSAKey.class,RSAKey.class,RSAKey.class,long.class).newInstance(owner,transport,attestation,8L);
        var setup=new ApprovalInformationReceiptSourcePostgresTest();
        try(var close=(AutoCloseable)fixture) {
            ApprovalWorkflowInformationAdmissionPostgresTest.keys();setup.initialize(PG);
            assertEquals(8L,call(fixture,"registryVersion"));assertTrue(((String)call(fixture,"sealedRegistryChecksum")).matches("[0-9a-f]{64}"));
            var keys=new InformationReplayKeys(owner,transport,new JWKSet(attestation.toPublicJWK()).toString(),
                    new JWKSet(key("receipt-cross-prohibited").toPublicJWK()).toString());
            var issuer=new InformationReplayProofIssuer(keys,Clock.systemUTC());
            URI endpoint=(URI)call(fixture,"endpoint");
            var client=new InformationReplayAuthorityClient(endpoint,new InformationReplayAttestationVerifier(keys,Clock.systemUTC()));
            var seal=setup.capture();var exchange=issuer.issue(seal,seal.deadline());
            var jdbc=(JdbcTemplate)call(fixture,"jdbc");var redis=(StringRedisTemplate)call(fixture,"redis");
            String approvalBefore=setup.database(),authBefore=authorityRows(jdbc);var noncesBefore=redis.keys("*");
            assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->client.evaluate(exchange)).getErrorCode());
            assertEquals(approvalBefore,setup.database());assertEquals(authBefore,authorityRows(jdbc));assertEquals(noncesBefore,redis.keys("*"));
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
            var altered=new WorkflowRuntimeJson().parse(exchange.body(),350000);
            var alteredOwner=(com.fasterxml.jackson.databind.node.ObjectNode)altered.get("bindings").get("owner");
            alteredOwner.put("actorId",alteredOwner.get("actorId").longValue()+1);
            byte[] tampered=new WorkflowRuntimeJson().bytes(altered);
            var wrongBody=post(http,endpoint,tampered,"X-DWP-Approval-Information-Replay-Token",exchange.token());
            assertEquals(403,wrongBody.statusCode());
            var wrongPurposeHeader=post(http,endpoint,exchange.body(),"X-DWP-Approval-Workflow-Runtime-Token",exchange.token());
            assertEquals(401,wrongPurposeHeader.statusCode());
            assertEquals(approvalBefore,setup.database());assertEquals(authBefore,authorityRows(jdbc));assertEquals(noncesBefore,redis.keys("*"));
        } finally {setup.clear();}
    }
    private static HttpResponse<String> post(HttpClient http,URI endpoint,byte[] body,String header,String token) throws Exception {
        return http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .header("X-DWP-Service-Identity","dwp-approval-server").header(header,token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String authorityRows(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object('users',(SELECT jsonb_agg(to_jsonb(u) ORDER BY user_id) FROM com_users u),
                    'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY role_member_id) FROM com_role_members m),
                    'grants',(SELECT jsonb_agg(to_jsonb(g) ORDER BY principal_resource_grant_id) FROM com_principal_resource_grants g))::text
                """,String.class);
    }
    private static Object call(Object target,String name) throws Exception {
        try {return target.getClass().getMethod(name).invoke(target);} catch(InvocationTargetException error) {
            if(error.getCause() instanceof Exception cause) throw cause;throw error;
        }
    }
}
