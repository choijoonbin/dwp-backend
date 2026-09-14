package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Clock;
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

class ApprovalWorkflowRoleReplayRedisTest {
    @Test void actualRedisEnforcesCrossReplicaSingleUseBodyConflictAndIndependentRoleNamespace() throws Exception {
        ApprovalWorkflowRoleProofVerifierTest.keys();
        try (var container = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)) {
            container.start();
            var a = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
            var b = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
            a.afterPropertiesSet(); b.afterPropertiesSet();
            try {
                var firstRedis = new StringRedisTemplate(a); var secondRedis = new StringRedisTemplate(b);
                var first = new ApprovalWorkflowRoleReplayStore(firstRedis, ApprovalWorkflowRoleProofVerifierTest.CLOCK);
                var second = new ApprovalWorkflowRoleReplayStore(secondRedis, ApprovalWorkflowRoleProofVerifierTest.CLOCK);
                var binding = ApprovalWorkflowRoleProofVerifierTest.binding(); var claims = ApprovalWorkflowRoleProofVerifierTest.claims(binding);
                var verifier = new ApprovalWorkflowRoleProofVerifier(ApprovalWorkflowRoleProofVerifierTest.JSON,
                        ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                        ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                        ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey), ApprovalWorkflowRoleProofVerifierTest.CLOCK);
                String token = ApprovalWorkflowRoleProofVerifierTest.sign(claims, ApprovalWorkflowRoleProofVerifierTest.roleKey);
                String body = ApprovalWorkflowRoleProofVerifierTest.body(token, binding);
                var proof = verifier.verify(ApprovalWorkflowRoleProofVerifierTest.transport(body), body);
                String userKey = "dwp:auth:approval-form-user-proof:v1:" + proof.proofId();
                firstRedis.opsForValue().set(userKey, "untouched-user-purpose");
                var ready = new CountDownLatch(1); var accepted = new AtomicInteger(); var rejected = new AtomicInteger();
                var one = CompletableFuture.runAsync(() -> consume(ready, first, proof, accepted, rejected));
                var two = CompletableFuture.runAsync(() -> consume(ready, second, proof, accepted, rejected));
                ready.countDown(); CompletableFuture.allOf(one, two).get();
                assertEquals(1, accepted.get()); assertEquals(1, rejected.get());
                String key = "dwp:auth:approval-workflow-role-proof:v1:{42}:" + proof.proofId();
                String transportKey = "dwp:auth:approval-workflow-role-transport:v1:{42}:" + proof.transportId();
                assertEquals(proof.requestDigest(), secondRedis.opsForValue().get(key));
                assertEquals(proof.bodySha256(), secondRedis.opsForValue().get(transportKey));
                assertTrue(secondRedis.getExpire(key) > 0 && secondRedis.getExpire(key) <= 30);
                assertEquals("untouched-user-purpose", secondRedis.opsForValue().get(userKey));
                binding.put("requestVersion", 1); var changed = ApprovalWorkflowRoleProofVerifierTest.claims(binding);
                changed.put("jti", proof.proofId().toString()); token = ApprovalWorkflowRoleProofVerifierTest.sign(changed, ApprovalWorkflowRoleProofVerifierTest.roleKey);
                body = ApprovalWorkflowRoleProofVerifierTest.body(token, binding);
                var conflict = verifier.verify(ApprovalWorkflowRoleProofVerifierTest.transport(body), body);
                assertEquals(ErrorCode.FORBIDDEN, assertThrows(BaseException.class, () -> second.consume(conflict)).getErrorCode());
                assertFalse(Boolean.TRUE.equals(firstRedis.hasKey("dwp:auth:approval-workflow-role-transport:v1:{42}:" + conflict.transportId())));
                var reusedTransport = new ApprovalWorkflowRoleProofVerifier.VerifiedProof(proof.binding(), java.util.UUID.randomUUID(),
                        proof.requestDigest(), proof.expiresAt(), proof.transportId(), proof.bodySha256());
                assertEquals(ErrorCode.FORBIDDEN, assertThrows(BaseException.class, () -> second.consume(reusedTransport)).getErrorCode());
                assertFalse(Boolean.TRUE.equals(firstRedis.hasKey("dwp:auth:approval-workflow-role-proof:v1:{42}:" + reusedTransport.proofId())));
                var expiring = new ApprovalWorkflowRoleReplayStore(firstRedis, Clock.fixed(proof.expiresAt().minusMillis(100), ZoneOffset.UTC));
                var another = new ApprovalWorkflowRoleProofVerifier.VerifiedProof(proof.binding(), java.util.UUID.randomUUID(),
                        proof.requestDigest(), proof.expiresAt(), java.util.UUID.randomUUID(), proof.bodySha256());
                expiring.consume(another); String shortKey = "dwp:auth:approval-workflow-role-proof:v1:{42}:" + another.proofId();
                Instant deadline = Instant.now().plusSeconds(3);
                while (Boolean.TRUE.equals(firstRedis.hasKey(shortKey)) && Instant.now().isBefore(deadline)) Thread.sleep(20);
                assertFalse(Boolean.TRUE.equals(firstRedis.hasKey(shortKey)));
                var expired = new ApprovalWorkflowRoleReplayStore(firstRedis, Clock.fixed(proof.expiresAt(), ZoneOffset.UTC));
                assertEquals(ErrorCode.FORBIDDEN, assertThrows(BaseException.class, () -> expired.consume(another)).getErrorCode());
            } finally { a.destroy(); b.destroy(); }
        }
    }

    private void consume(CountDownLatch ready, ApprovalWorkflowRoleReplayStore store, ApprovalWorkflowRoleProofVerifier.VerifiedProof proof,
            AtomicInteger accepted, AtomicInteger rejected) {
        try { ready.await(); store.consume(proof); accepted.incrementAndGet(); }
        catch (BaseException exception) { if (exception.getErrorCode() != ErrorCode.FORBIDDEN) throw exception; rejected.incrementAndGet(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
