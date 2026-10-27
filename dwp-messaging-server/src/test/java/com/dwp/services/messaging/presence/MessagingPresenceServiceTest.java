package com.dwp.services.messaging.presence;

import com.dwp.services.messaging.domain.MessagingDtos;
import com.dwp.services.messaging.security.MessagingRequestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class MessagingPresenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T06:00:00Z");
    private static final String KEY = "dwp:messaging:presence:v1:42";

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void heartbeatAtomicallyRenewsTheVerifiedUsersTenantLeaseUsingRedisTime() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        when(redis.execute(
                any(RedisScript.class),
                eq(List.of(KEY)),
                any(), any(), any()))
                .thenReturn(NOW.plusSeconds(75).toEpochMilli());
        MessagingRequestContext.set(subject(42, 700));
        MessagingPresenceService service = service(redis, true, 5_000, meters);

        assertThat(service.heartbeat()).isTrue();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(
                script.capture(),
                eq(List.of(KEY)),
                eq("700"),
                eq("75000"),
                eq("150000"));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('TIME')", "ZREMRANGEBYSCORE", "ZADD", "PEXPIRE");
        assertThat(meters.get("dwp.messaging.presence.heartbeat.total")
                .tag("result", "success")
                .counter()
                .count()).isEqualTo(1);
    }

    @Test
    void heartbeatFailureIsExplicitAndObservable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        when(redis.execute(
                any(RedisScript.class),
                eq(List.of(KEY)),
                any(), any(), any()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        MessagingRequestContext.set(subject(42, 700));
        MessagingPresenceService service = service(redis, true, 5_000, meters);

        assertThat(service.heartbeat()).isFalse();
        assertThat(meters.get("dwp.messaging.presence.heartbeat.total")
                .tag("result", "failure")
                .counter()
                .count()).isEqualTo(1);
    }

    @Test
    void snapshotScoresOnlyRequestedUsersInOneBoundedBatch() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> leases = mock(ZSetOperations.class);
        when(redis.execute(any(RedisScript.class), eq(List.of(KEY))))
                .thenReturn(NOW.toEpochMilli());
        when(redis.opsForZSet()).thenReturn(leases);
        when(leases.zCard(KEY)).thenReturn(2L);
        when(leases.score(eq(KEY), any(Object[].class)))
                .thenReturn(List.of(
                        (double) NOW.plusSeconds(75).toEpochMilli(),
                        (double) NOW.minusSeconds(1).toEpochMilli()));
        MessagingPresenceService service = service(redis, true, 5_000, new SimpleMeterRegistry());

        MessagingPresenceService.PresenceSnapshot snapshot =
                service.snapshot(42, List.of(700L, 701L));

        assertThat(snapshot.authoritative()).isTrue();
        assertThat(snapshot.evaluatedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(snapshot.activeLeaseCount()).isEqualTo(2);
        assertThat(snapshot.state(700)).isEqualTo("AVAILABLE");
        assertThat(snapshot.state(701)).isEqualTo("OFFLINE");
        verify(leases).score(KEY, "700", "701");
    }

    @Test
    void candidateLookupIsBoundedAndIgnoresMalformedMembers() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> leases = mock(ZSetOperations.class);
        when(redis.execute(any(RedisScript.class), eq(List.of(KEY))))
                .thenReturn(NOW.toEpochMilli());
        when(redis.opsForZSet()).thenReturn(leases);
        when(leases.zCard(KEY)).thenReturn(4L);
        when(leases.reverseRangeByScore(
                eq(KEY),
                anyDouble(),
                eq(Double.POSITIVE_INFINITY),
                eq(0L),
                eq(32L)))
                .thenReturn(new LinkedHashSet<>(List.of("700", "bad", "-3", "701")));
        MessagingPresenceService service = service(redis, true, 5_000, new SimpleMeterRegistry());

        MessagingPresenceService.PresenceSnapshot snapshot =
                service.availableCandidates(42, 32);

        assertThat(snapshot.availableUserIds()).containsExactlyInAnyOrder(700L, 701L);
        verify(leases).reverseRangeByScore(
                eq(KEY),
                anyDouble(),
                eq(Double.POSITIVE_INFINITY),
                eq(0L),
                eq(32L));
    }

    @Test
    void redisFailureProducesUnavailableAuthorityInsteadOfSeededFallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(List.of(KEY))))
                .thenThrow(new IllegalStateException("redis unavailable"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MessagingPresenceService service = service(redis, true, 5_000, meters);
        MessagingDtos.PersonSummary seededAvailable = person(700, "AVAILABLE");

        MessagingPresenceService.PresenceSnapshot snapshot =
                service.snapshot(42, List.of(700L));
        List<MessagingDtos.PersonSummary> resolved =
                service.resolvePeople(snapshot, List.of(seededAvailable));

        assertThat(snapshot.authoritative()).isFalse();
        assertThat(resolved).extracting(MessagingDtos.PersonSummary::presenceState)
                .containsExactly("UNKNOWN");
        assertThat(service.metadata(snapshot).authority()).isEqualTo("UNAVAILABLE");
        assertThat(meters.get("dwp.messaging.presence.snapshot.total")
                .tag("result", "failure")
                .counter()
                .count()).isEqualTo(1);
    }

    @Test
    void capacityThresholdWarnsWithoutMakingSelectedUsersUnknown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> leases = mock(ZSetOperations.class);
        when(redis.execute(any(RedisScript.class), eq(List.of(KEY))))
                .thenReturn(NOW.toEpochMilli());
        when(redis.opsForZSet()).thenReturn(leases);
        when(leases.zCard(KEY)).thenReturn(3L);
        when(leases.score(eq(KEY), any(Object[].class)))
                .thenReturn(List.of((double) NOW.plusSeconds(75).toEpochMilli()));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MessagingPresenceService service = service(redis, true, 2, meters);

        MessagingPresenceService.PresenceSnapshot snapshot =
                service.snapshot(42, List.of(700L));

        assertThat(snapshot.authoritative()).isTrue();
        assertThat(snapshot.state(700)).isEqualTo("AVAILABLE");
        assertThat(meters.get("dwp.messaging.presence.capacity.exceeded")
                .counter()
                .count()).isEqualTo(1);
        assertThat(meters.get("dwp.messaging.presence.tenant.active.leases.last_observed")
                .gauge()
                .value()).isEqualTo(3);
    }

    @Test
    void disabledPresenceIsUnavailableAndDoesNotTouchRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MessagingPresenceService service = service(redis, false, 5_000, new SimpleMeterRegistry());

        assertThat(service.heartbeat()).isFalse();
        MessagingPresenceService.PresenceSnapshot snapshot =
                service.snapshot(42, List.of(700L));

        assertThat(snapshot.authoritative()).isFalse();
        assertThat(snapshot.state(700)).isEqualTo("UNKNOWN");
        verify(redis, never()).opsForZSet();
    }

    @Test
    void memberResolutionNeverUsesTheDirectoryPresenceValue() {
        MessagingPresenceService service =
                service(mock(StringRedisTemplate.class), false, 5_000, new SimpleMeterRegistry());
        MessagingDtos.MemberSummary seededAvailable = member(700, "AVAILABLE");
        MessagingDtos.MemberSummary seededAway = member(701, "AWAY");

        List<MessagingDtos.MemberSummary> authoritative = service.resolveMembers(
                MessagingPresenceService.PresenceSnapshot.authoritative(Set.of(701L)),
                List.of(seededAvailable, seededAway));

        assertThat(authoritative)
                .extracting(MessagingDtos.MemberSummary::presenceState)
                .containsExactly("OFFLINE", "AVAILABLE");
    }

    @Test
    void metadataPublishesTheAuthorityFreshnessContract() {
        MessagingPresenceService service =
                service(mock(StringRedisTemplate.class), false, 5_000, new SimpleMeterRegistry());
        OffsetDateTime evaluatedAt = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

        MessagingDtos.PresenceMetadata metadata = service.metadata(
                MessagingPresenceService.PresenceSnapshot.authoritative(
                        evaluatedAt, Set.of(), 0));

        assertThat(metadata.authority()).isEqualTo("LIVE");
        assertThat(metadata.evaluatedAt()).isEqualTo(evaluatedAt);
        assertThat(metadata.leaseTtlSeconds()).isEqualTo(75);
        assertThat(metadata.refreshAfterSeconds()).isEqualTo(20);
        assertThat(service.leaseTiming())
                .isEqualTo(new MessagingPresenceService.LeaseTiming(75, 20));
    }

    @Test
    void publishesAnAcceptedConfiguredLeaseTimingContract() {
        MessagingPresenceService service = new MessagingPresenceService(
                mock(StringRedisTemplate.class),
                true,
                Duration.ofSeconds(60),
                Duration.ofSeconds(15),
                "dwp:presence",
                10,
                new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.leaseTiming())
                .isEqualTo(new MessagingPresenceService.LeaseTiming(60, 15));
    }

    @Test
    void rejectsUnsafeLeaseConfiguration() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        assertThatIllegalArgumentException().isThrownBy(() -> new MessagingPresenceService(
                redis, true, Duration.ofSeconds(29), Duration.ofSeconds(20),
                "dwp:presence", 10, meters, clock));
        assertThatIllegalArgumentException().isThrownBy(() -> new MessagingPresenceService(
                redis, true, Duration.ofSeconds(75), Duration.ofSeconds(4),
                "dwp:presence", 10, meters, clock));
        assertThatIllegalArgumentException().isThrownBy(() -> new MessagingPresenceService(
                redis, true, Duration.ofSeconds(75), Duration.ofSeconds(75),
                "dwp:presence", 10, meters, clock));
        assertThatIllegalArgumentException().isThrownBy(() -> new MessagingPresenceService(
                redis, true, Duration.ofSeconds(30), Duration.ofSeconds(16),
                "dwp:presence", 10, meters, clock));
        assertThatIllegalArgumentException().isThrownBy(() -> new MessagingPresenceService(
                redis, true, Duration.ofMillis(75_500), Duration.ofSeconds(20),
                "dwp:presence", 10, meters, clock));
        assertThatIllegalArgumentException().isThrownBy(() -> new MessagingPresenceService(
                redis, true, Duration.ofSeconds(75), Duration.ofMillis(20_500),
                "dwp:presence", 10, meters, clock));
        assertThatIllegalArgumentException().isThrownBy(() -> new MessagingPresenceService(
                redis, true, Duration.ofSeconds(75), Duration.ofSeconds(20),
                "unsafe prefix", 10, meters, clock));
        assertThatIllegalArgumentException().isThrownBy(() -> new MessagingPresenceService(
                redis, true, Duration.ofSeconds(75), Duration.ofSeconds(20),
                "dwp:presence", 0, meters, clock));
    }

    private MessagingPresenceService service(
            StringRedisTemplate redis,
            boolean enabled,
            int activeUserWarningThreshold,
            SimpleMeterRegistry meters) {
        return new MessagingPresenceService(
                redis,
                enabled,
                Duration.ofSeconds(75),
                Duration.ofSeconds(20),
                "dwp:messaging:presence:v1",
                activeUserWarningThreshold,
                meters,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MessagingRequestContext.Subject subject(long tenantId, long userId) {
        return new MessagingRequestContext.Subject(
                userId,
                tenantId,
                UUID.randomUUID(),
                "Presence User",
                Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MESSAGING:VIEW"),
                Set.of());
    }

    private MessagingDtos.PersonSummary person(long userId, String state) {
        return new MessagingDtos.PersonSummary(
                userId,
                UUID.randomUUID(),
                "person" + userId + "@example.com",
                "Person " + userId,
                "Engineer",
                "DWP",
                state);
    }

    private MessagingDtos.MemberSummary member(long userId, String state) {
        return new MessagingDtos.MemberSummary(
                userId,
                UUID.randomUUID(),
                "Person " + userId,
                "person" + userId + "@example.com",
                "Engineer",
                "DWP",
                state,
                "MEMBER",
                "DIRECT",
                "DEFAULT",
                false,
                false,
                null,
                0,
                null,
                "PRIVATE");
    }
}
