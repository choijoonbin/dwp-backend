package com.dwp.services.auth.service;

import static com.dwp.services.auth.service.ApprovalFormUserProofTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ApprovalFormUserProofReplayStoreTest {
    @Test
    void separateReplicasShareAtomicSingleUseJtiAndRejectConflictingDigest() {
        var redis = mock(StringRedisTemplate.class); var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String, String> shared = new ConcurrentHashMap<>();
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation ->
                shared.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
        var one = new ApprovalFormUserProofReplayStore(redis, CLOCK);
        var two = new ApprovalFormUserProofReplayStore(redis, CLOCK);
        var verifier = verifier(); var proof = verifier.verify(sign(claims("SEARCH", "a".repeat(64))), "SEARCH", "a".repeat(64));
        one.consume(proof);
        reject(() -> two.consume(proof), ErrorCode.FORBIDDEN);
        var conflicting = verifier.verify(sign(claims("RESOLVE", "b".repeat(64))), "RESOLVE", "b".repeat(64));
        reject(() -> two.consume(conflicting), ErrorCode.FORBIDDEN);
        assertThat(shared).containsOnlyKeys("dwp:auth:approval-form-user-proof:v1:" + proof.proofId());
        verify(values, times(3)).setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(30)));
    }

    @Test
    void redisFailureAndUnknownSetNxResultFailClosed() {
        var redis = mock(StringRedisTemplate.class); var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var store = new ApprovalFormUserProofReplayStore(redis, CLOCK);
        var proof = verifier().verify(sign(claims("SEARCH", "a".repeat(64))), "SEARCH", "a".repeat(64));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new IllegalStateException("offline"));
        reject(() -> store.consume(proof), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);
        reject(() -> store.consume(proof), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void expiryBoundaryNeverUsesRedisAndTtlRemainsBoundToSignedExpiry() {
        var redis = mock(StringRedisTemplate.class);
        var store = new ApprovalFormUserProofReplayStore(redis, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
        var proof = verifier().verify(sign(claims("SEARCH", "a".repeat(64))), "SEARCH", "a".repeat(64));
        reject(() -> store.consume(proof), ErrorCode.FORBIDDEN);
        verifyNoInteractions(redis);
    }

    private void reject(Runnable task, ErrorCode expected) {
        assertThatThrownBy(task::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
