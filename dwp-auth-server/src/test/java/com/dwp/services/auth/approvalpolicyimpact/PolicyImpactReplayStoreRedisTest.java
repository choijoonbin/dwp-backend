package com.dwp.services.auth.approvalpolicyimpact;

import static org.assertj.core.api.Assertions.*;

import com.dwp.core.exception.BaseException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PolicyImpactReplayStoreRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)
            .withLabel("dwp.approval.owner", "apr15-policyimpact-source-replay");
    final PolicyImpactProtocolFixture fixture = new PolicyImpactProtocolFixture();
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    @BeforeEach void setup() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379)); factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
    }
    @AfterEach void cleanup() { factory.destroy(); }
    PolicyImpactProofVerifier.Verified proof() {
        var exchange = fixture.exchange(fixture.binding()); return fixture.verifier().verify(exchange.body(), exchange.token());
    }
    @Test void actualRedisAtomicallyAllowsOneOfTwentyReplicaAttemptsAtActualDutyExpiry() throws Exception {
        var proof = proof(); var one = new PolicyImpactReplayStore(redis, fixture.clock); var two = new PolicyImpactReplayStore(redis, fixture.clock);
        one.requireReady(); var deadline = PolicyImpactProtocolFixture.NOW.plusSeconds(9);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var attempts = java.util.stream.IntStream.range(0, 20).mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                try { (index % 2 == 0 ? one : two).consume(proof, deadline); return true; } catch (BaseException used) { return false; }
            }, pool)).toList();
            assertThat(attempts.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count()).isEqualTo(1);
        }
        String prefix = "dwp:approval-policy-impact:{42:" + PolicyImpactProtocol.OWNER_PURPOSE + "}:";
        for (String key : List.of(prefix + "owner:" + proof.sourceJti(), prefix + "transport:" + proof.transportJti())) {
            assertThat(redis.opsForValue().get(key)).isEqualTo("used");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 9000L);
        }
    }
    @Test void usedOwnerDoesNotLeaveAnOrphanTransportAndOriginalTwoKeysRemainSingleUse() {
        var proof = proof(); var store = new PolicyImpactReplayStore(redis, fixture.clock);
        store.consume(proof, PolicyImpactProtocolFixture.NOW.plusSeconds(6));
        // Build a genuinely signed owner with the reused JTI; the replay is not an invalid-crypto substitute.
        var owner = fixture.standard(PolicyImpactProtocol.OWNER_ISSUER, PolicyImpactProtocol.OWNER_AUDIENCE, PolicyImpactProtocol.OWNER_PURPOSE, proof.sourceJti());
        owner.put("bindings", fixture.binding()); owner.put("bindingsSha256", fixture.json.digest(fixture.binding()));
        var newTransport = fixture.exchange(fixture.binding(), fixture.token(fixture.owner, owner), proof.sourceJti(), java.util.UUID.randomUUID().toString());
        var reused = fixture.verifier().verify(newTransport.body(), newTransport.token());
        assertThatThrownBy(() -> store.consume(reused, PolicyImpactProtocolFixture.NOW.plusSeconds(6))).isInstanceOf(BaseException.class);
        String key = "dwp:approval-policy-impact:{42:" + PolicyImpactProtocol.OWNER_PURPOSE + "}:transport:" + reused.transportJti();
        assertThat(redis.hasKey(key)).isFalse();
    }
    @Test void missingRedisAndExpandedExpiryFailClosed() {
        var proof = proof(); assertThatThrownBy(() -> new PolicyImpactReplayStore(null, fixture.clock).requireReady()).isInstanceOf(BaseException.class);
        var store = new PolicyImpactReplayStore(redis, fixture.clock);
        assertThatThrownBy(() -> store.consume(proof, proof.expiresAt().plusSeconds(1))).isInstanceOf(BaseException.class);
    }
}
