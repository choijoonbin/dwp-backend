package com.dwp.services.auth.approvalpolicyimpact;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class PolicyImpactProofVerifierTest {
    final PolicyImpactProtocolFixture f = new PolicyImpactProtocolFixture();
    @Test void exactClosedCardinalityAndOriginalAggregateAreRetained() {
        var exchange = f.exchange(f.binding()); var proof = f.verifier().verify(exchange.body(), exchange.token());
        assertThat(proof.bindingJson().size()).isEqualTo(17);
        assertThat(proof.bindings().decisionRevision()).isEqualTo("psr-" + "b".repeat(64));
        assertThat(proof.sourceJti()).isEqualTo(exchange.sourceJti()); assertThat(proof.transportJti()).isEqualTo(exchange.transportJti());
        assertThat(exchange.token()).hasSizeLessThanOrEqualTo(2048);
    }
    @Test void aliasesAdditionalKeysAndFractionsAreDeniedBeforeAuthorityOrRedis() {
        for (var changes : List.<Map<String, Object>>of(Map.of("expectedVersion", 0.5), Map.of("expectedVersion", 9007199254740992L),
                Map.of("headSourceSha256", "a".repeat(64)), Map.of("extra", true), Map.of("method", "POST"),
                Map.of("personPublicId", "22222222-2222-4222-8222-22222222222A"),
                Map.of("routeContractKey", "route.approvals.admin.policies-impact.data"), Map.of("rolloutState", "100"),
                Map.of("path", "/v1/admin/policies/11111111-1111-4111-8111-111111111111/impact/"),
                Map.of("rawQuerySha256", PolicyImpactJson.sha("expectedVersion=00")))) {
            var binding = f.binding(); binding.putAll(changes); var exchange = f.exchange(binding);
            var port = mock(PolicyImpactAuthorityPort.class); var redis = mock(StringRedisTemplate.class);
            var service = new PolicyImpactAuthorityService(f::verifier, port, new PolicyImpactReplayStore(redis, f.clock),
                    new PolicyImpactAuthorityIssuer(f.json, f::keys, f.clock), true);
            assertThatThrownBy(() -> service.preverify(exchange.body(), exchange.token())).isInstanceOf(BaseException.class);
            verifyNoInteractions(port, redis);
        }
    }
    @Test void duplicateBodyAndDuplicateSignedClaimAreDenied() {
        var exchange = f.exchange(f.binding());
        String body = new String(exchange.body(), StandardCharsets.UTF_8);
        byte[] duplicate = body.replaceFirst("\\{", "{\"bindings\":{},").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> f.verifier().verify(duplicate, exchange.token())).isInstanceOf(BaseException.class);
        var claims = f.standard(PolicyImpactProtocol.OWNER_ISSUER, PolicyImpactProtocol.OWNER_AUDIENCE,
                PolicyImpactProtocol.OWNER_PURPOSE, exchange.sourceJti());
        claims.put("bindings", f.binding()); claims.put("bindingsSha256", f.json.digest(f.binding()));
        String duplicateClaims = new String(f.json.bytes(claims), StandardCharsets.UTF_8).replaceFirst("\\{", "{\"iss\":\"borrowed\",");
        var malformed = f.exchange(f.binding(), PolicyImpactProtocolFixture.rawToken(f.owner, duplicateClaims), exchange.sourceJti(), exchange.transportJti());
        assertThatThrownBy(() -> f.verifier().verify(malformed.body(), malformed.token())).isInstanceOf(BaseException.class);
    }
    @Test void hashContextCorrelatorAndExpiredTransportCannotBeBorrowed() {
        var exchange = f.exchange(f.binding());
        var changed = f.binding(); changed.put("contextKey", "changed");
        byte[] body = f.json.bytes(Map.of("sourceProof", f.json.parse(exchange.body()).get("sourceProof"), "bindings", changed));
        assertThatThrownBy(() -> f.verifier().verify(body, exchange.token())).isInstanceOf(BaseException.class);
        var expired = new PolicyImpactProofVerifier(f.json, f.keys(), Clock.fixed(PolicyImpactProtocolFixture.NOW.plusSeconds(30), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.verify(exchange.body(), exchange.token())).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> f.verifier().verify(exchange.body(), exchange.token() + "a")).isInstanceOf(BaseException.class);
    }
    @Test void collapsedAndForbiddenKeyMaterialCannotBeConfigured() {
        assertThatThrownBy(() -> new PolicyImpactKeys(f.json, PolicyImpactProtocolFixture.jwks(f.owner),
                PolicyImpactProtocolFixture.jwks(f.owner), f.attestation.toJSONString(), PolicyImpactProtocolFixture.jwks(f.attestation), List.of()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new PolicyImpactKeys(f.json, PolicyImpactProtocolFixture.jwks(f.owner),
                PolicyImpactProtocolFixture.jwks(f.transport), f.attestation.toJSONString(), PolicyImpactProtocolFixture.jwks(f.attestation),
                List.of(f.owner.toJSONString()))).isInstanceOf(BaseException.class);
    }
    @Test void disabledAndMissingRegistryDoNotConsumeReplayOrReadCurrentAuthority() {
        var port = mock(PolicyImpactAuthorityPort.class); var redis = mock(StringRedisTemplate.class);
        var service = new PolicyImpactAuthorityService(f::verifier, port, new PolicyImpactReplayStore(redis, f.clock),
                new PolicyImpactAuthorityIssuer(f.json, f::keys, f.clock), false);
        var exchange = f.exchange(f.binding());
        assertThatThrownBy(() -> service.preverify(exchange.body(), exchange.token())).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        verifyNoInteractions(port, redis);
        var enabled = new PolicyImpactAuthorityService(f::verifier, port, new PolicyImpactReplayStore(redis, f.clock),
                new PolicyImpactAuthorityIssuer(f.json, f::keys, f.clock), true);
        doThrow(PolicyImpactJson.unavailable()).when(port).requireRegistered();
        assertThatThrownBy(() -> enabled.evaluate(enabled.preverify(exchange.body(), exchange.token()))).isInstanceOf(BaseException.class);
        verify(port).requireRegistered(); verifyNoInteractions(redis);
        verify(port, never()).requireCurrent(any());
    }
}
