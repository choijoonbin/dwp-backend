package com.dwp.services.auth.informationreplay;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Real Redis primitives only. No current Auth or installed Approval authority positive is inferred. */
class InformationReplayRedisTest {
    @Test void atomicOneUseAndSourceTtlWorkAcrossIndependentReplicas() throws Exception {
        var fixture = new InformationReplayProofFixture();
        try (var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)) {
            redis.start();
            var first = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379)); first.afterPropertiesSet();
            var second = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379)); second.afterPropertiesSet();
            try {
                var template = new StringRedisTemplate(first); var replica = new InformationReplayReplayStore(new StringRedisTemplate(second));
                var store = new InformationReplayReplayStore(template); store.requireReady(); replica.requireReady();
                var exchange = fixture.exchange(fixture.bindings("REPLY"));
                var proof = new InformationReplayProofVerifier(fixture.json, fixture.keys, Clock.systemUTC()).verify(exchange.body(), exchange.token());
                Instant deadline = Instant.now().plusSeconds(12); store.consume(proof, deadline);
                String prefix = "dwp:auth:information-replay:v1:{1:" + InformationReplayProtocol.OWNER_PURPOSE + "}:";
                for (String key : List.of(prefix + "owner:" + proof.sourceJti(), prefix + "transport:" + proof.transportJti())) {
                    Long ttl = template.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
                    assertNotNull(ttl); assertTrue(ttl > 0 && ttl <= 12000);
                }
                assertEquals(ErrorCode.FORBIDDEN, assertThrows(BaseException.class, () -> replica.consume(proof, deadline)).getErrorCode());
                var retry = fixture.exchange(fixture.bindings("REPLY"));
                var fresh = new InformationReplayProofVerifier(fixture.json, fixture.keys, Clock.systemUTC()).verify(retry.body(), retry.token());
                assertNotEquals(proof.sourceJti(), fresh.sourceJti()); store.consume(fresh, Instant.now().plusSeconds(10));
            } finally { second.destroy(); first.destroy(); }
        }
    }
    @Test void actualRedisFailurePreventsAllCurrentAuthorityReads() throws Exception {
        var fixture = new InformationReplayProofFixture();
        var config = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                .commandTimeout(java.time.Duration.ofMillis(300)).clientOptions(io.lettuce.core.ClientOptions.builder()
                    .socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(java.time.Duration.ofMillis(300)).build()).build()).build();
        var connection = new LettuceConnectionFactory(new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", 1), config);
        connection.afterPropertiesSet();
        try {
            var exchange = fixture.exchange(fixture.bindings("REQUEST_INFO")); var reads = new java.util.concurrent.atomic.AtomicInteger();
            var service = new InformationReplayAuthorityService(true, new InformationReplayProofVerifier(fixture.json, fixture.keys, Clock.systemUTC()),
                    proof -> { reads.incrementAndGet(); throw new AssertionError("Redis failure must precede current DB reads."); },
                    new InformationReplayReplayStore(new StringRedisTemplate(connection)), new InformationReplayAttestationIssuer(fixture.keys, fixture.json), fixture.json);
            assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class, () -> service.evaluate(exchange.body(), exchange.token())).getErrorCode());
            assertEquals(0, reads.get());
        } finally { connection.destroy(); }
    }
}
