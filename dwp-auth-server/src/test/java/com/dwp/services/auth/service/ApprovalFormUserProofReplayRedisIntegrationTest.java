package com.dwp.services.auth.service;

import static com.dwp.services.auth.service.ApprovalFormUserProofTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class ApprovalFormUserProofReplayRedisIntegrationTest {
    @Test
    void actualDisposableRedisEnforcesCrossReplicaAtomicJtiConflictAndExpiryTtl() throws Exception {
        try (var container = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)) {
            container.start();
            var firstConnection = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
            var secondConnection = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
            firstConnection.afterPropertiesSet(); secondConnection.afterPropertiesSet();
            try {
                var firstRedis = new StringRedisTemplate(firstConnection); var secondRedis = new StringRedisTemplate(secondConnection);
                var first = new ApprovalFormUserProofReplayStore(firstRedis, CLOCK);
                var second = new ApprovalFormUserProofReplayStore(secondRedis, CLOCK);
                var proof = verifier().verify(sign(claims("SEARCH", "a".repeat(64))), "SEARCH", "a".repeat(64));
                var start = new CountDownLatch(1); var accepted = new AtomicInteger(); var rejected = new AtomicInteger();
                Runnable consumeOne = () -> consume(start, first, proof, accepted, rejected);
                Runnable consumeTwo = () -> consume(start, second, proof, accepted, rejected);
                var one = CompletableFuture.runAsync(consumeOne); var two = CompletableFuture.runAsync(consumeTwo);
                start.countDown(); CompletableFuture.allOf(one, two).get();
                assertThat(accepted.get()).isEqualTo(1); assertThat(rejected.get()).isEqualTo(1);
                String key = "dwp:auth:approval-form-user-proof:v1:" + proof.proofId();
                assertThat(firstRedis.opsForValue().get(key)).isEqualTo(proof.requestDigest());
                assertThat(secondRedis.getExpire(key)).isBetween(1L, 30L);
                var conflict = verifier().verify(sign(claims("RESOLVE", "b".repeat(64))), "RESOLVE", "b".repeat(64));
                assertThatThrownBy(() -> second.consume(conflict)).isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
                var mutation = new ApprovalFormReferenceProofVerifier(verifier()).verify(sign(referenceClaims(
                        "route.approvals.work.request-draft-update.action", 0, "d".repeat(64))), "d".repeat(64));
                first.consume(mutation);
                String mutationKey = "dwp:auth:approval-form-reference-proof:v1:" + mutation.proofId();
                assertThat(secondRedis.opsForValue().get(mutationKey)).isEqualTo(mutation.requestDigest());
                assertThat(secondRedis.getExpire(mutationKey)).isBetween(1L, 30L);
                assertThat(firstRedis.opsForValue().get(key)).isEqualTo(proof.requestDigest());
                assertThatThrownBy(() -> second.consume(mutation)).isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
                var claims = claims("SEARCH", "c".repeat(64)); claims.put("jti", java.util.UUID.randomUUID().toString());
                var shortProof = verifier().verify(sign(claims), "SEARCH", "c".repeat(64));
                var nearExpiry = Clock.fixed(shortProof.expiresAt().minusMillis(100), ZoneOffset.UTC);
                new ApprovalFormUserProofReplayStore(firstRedis, nearExpiry).consume(shortProof);
                String shortKey = "dwp:auth:approval-form-user-proof:v1:" + shortProof.proofId();
                Instant deadline = Instant.now().plusSeconds(3);
                while (Boolean.TRUE.equals(firstRedis.hasKey(shortKey)) && Instant.now().isBefore(deadline)) Thread.sleep(20);
                assertThat(firstRedis.hasKey(shortKey)).isFalse();
                var expired = new ApprovalFormUserProofReplayStore(firstRedis, Clock.fixed(shortProof.expiresAt(), ZoneOffset.UTC));
                assertThatThrownBy(() -> expired.consume(shortProof)).isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
                assertThat(firstRedis.hasKey(shortKey)).isFalse();
            } finally {
                firstConnection.destroy(); secondConnection.destroy();
            }
        }
    }

    private void consume(CountDownLatch start, ApprovalFormUserProofReplayStore store,
            ApprovalFormUserSourceProofVerifier.VerifiedSourceProof proof, AtomicInteger accepted, AtomicInteger rejected) {
        try {
            start.await(); store.consume(proof); accepted.incrementAndGet();
        } catch (BaseException exception) {
            if (exception.getErrorCode() != ErrorCode.FORBIDDEN) throw exception;
            rejected.incrementAndGet();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
        }
    }
}
