package com.dwp.services.messaging.presence;

import com.dwp.services.messaging.security.MessagingRequestContext;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_REDIS_HOST", matches = ".+")
class MessagingPresenceRedisIntegrationTest {

    private static final long TENANT_ID = 86_001;
    private static final long OTHER_TENANT_ID = 86_002;
    private static final long USER_ID = 76_001;
    private static final long OTHER_USER_ID = 76_002;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private String keyPrefix;
    private SimpleMeterRegistry meters;
    private MessagingPresenceService service;

    @BeforeAll
    static void connectToRedis() {
        String host = System.getenv("DWP_MESSAGING_INTEGRATION_REDIS_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_REDIS_PORT", "6379"));
        String password = System.getenv("DWP_MESSAGING_INTEGRATION_REDIS_PASSWORD");
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            server.setPassword(RedisPassword.of(password));
        }
        connectionFactory = new LettuceConnectionFactory(server, clientConfiguration(Duration.ofSeconds(2)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        keyPrefix = "dwp:test:messaging:presence:" + UUID.randomUUID();
        meters = new SimpleMeterRegistry();
        service = service(redis, meters, 5_000);
        subject(TENANT_ID, USER_ID);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(Set.of(key(TENANT_ID), key(OTHER_TENANT_ID)));
        MessagingRequestContext.clear();
        meters.close();
    }

    @Test
    void heartbeatAndSnapshotUseARealTenantIsolatedLease() {
        assertThat(service.heartbeat()).isTrue();

        MessagingPresenceService.PresenceSnapshot candidates =
                service.availableCandidates(TENANT_ID, 8);
        MessagingPresenceService.PresenceSnapshot otherTenant =
                service.availableCandidates(OTHER_TENANT_ID, 8);
        MessagingPresenceService.PresenceSnapshot resolved =
                service.snapshot(TENANT_ID, Set.of(USER_ID, OTHER_USER_ID));

        assertThat(candidates.authoritative()).isTrue();
        assertThat(candidates.availableUserIds()).containsExactly(USER_ID);
        assertThat(candidates.activeLeaseCount()).isEqualTo(1);
        assertThat(otherTenant.authoritative()).isTrue();
        assertThat(otherTenant.availableUserIds()).isEmpty();
        assertThat(resolved.state(USER_ID)).isEqualTo("AVAILABLE");
        assertThat(resolved.state(OTHER_USER_ID)).isEqualTo("OFFLINE");
        assertThat(redis.getExpire(key(TENANT_ID), TimeUnit.MILLISECONDS))
                .isBetween(1L, Duration.ofSeconds(150).toMillis());
    }

    @Test
    void expiredScoresAreRemovedBeforeTheyCanReportAvailability() {
        assertThat(service.heartbeat()).isTrue();
        redis.opsForZSet().add(key(TENANT_ID), Long.toString(USER_ID), 0);

        MessagingPresenceService.PresenceSnapshot snapshot =
                service.snapshot(TENANT_ID, Set.of(USER_ID));

        assertThat(snapshot.authoritative()).isTrue();
        assertThat(snapshot.state(USER_ID)).isEqualTo("OFFLINE");
        assertThat(redis.opsForZSet().zCard(key(TENANT_ID))).isZero();
    }

    @Test
    void capacityThresholdIsObservableWithoutReturningAPartialSnapshot() {
        double future = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        redis.opsForZSet().add(key(TENANT_ID), Long.toString(USER_ID), future);
        redis.opsForZSet().add(key(TENANT_ID), Long.toString(OTHER_USER_ID), future);
        redis.opsForZSet().add(key(TENANT_ID), Long.toString(OTHER_USER_ID + 1), future);
        service = service(redis, meters, 2);

        MessagingPresenceService.PresenceSnapshot snapshot =
                service.snapshot(TENANT_ID, Set.of(USER_ID, OTHER_USER_ID, OTHER_USER_ID + 1));

        assertThat(snapshot.authoritative()).isTrue();
        assertThat(snapshot.availableUserIds())
                .containsExactlyInAnyOrder(USER_ID, OTHER_USER_ID, OTHER_USER_ID + 1);
        assertThat(meters.get("dwp.messaging.presence.capacity.exceeded").counter().count())
                .isEqualTo(1);
    }

    @Test
    void boundedCandidatesPreferTheMostRecentlyRenewedLeases() {
        double now = System.currentTimeMillis();
        redis.opsForZSet().add(key(TENANT_ID), Long.toString(USER_ID), now + 30_000);
        redis.opsForZSet().add(key(TENANT_ID), Long.toString(OTHER_USER_ID), now + 45_000);
        redis.opsForZSet().add(key(TENANT_ID), Long.toString(OTHER_USER_ID + 1), now + 60_000);

        MessagingPresenceService.PresenceSnapshot snapshot =
                service.availableCandidates(TENANT_ID, 2);

        assertThat(snapshot.availableUserIds())
                .containsExactlyInAnyOrder(OTHER_USER_ID, OTHER_USER_ID + 1);
        assertThat(snapshot.availableUserIds()).doesNotContain(USER_ID);
    }

    @Test
    void unreachableRedisFailsClosedForHeartbeatAndReads() throws Exception {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }
        RedisStandaloneConfiguration server =
                new RedisStandaloneConfiguration("127.0.0.1", unavailablePort);
        LettuceConnectionFactory unavailableFactory = new LettuceConnectionFactory(
                server,
                clientConfiguration(Duration.ofMillis(150)));
        unavailableFactory.afterPropertiesSet();
        unavailableFactory.start();
        StringRedisTemplate unavailableRedis = new StringRedisTemplate(unavailableFactory);
        unavailableRedis.afterPropertiesSet();
        MessagingPresenceService unavailable = new MessagingPresenceService(
                unavailableRedis,
                true,
                Duration.ofSeconds(75),
                Duration.ofSeconds(20),
                keyPrefix + ":unavailable",
                5_000,
                meters,
                Clock.systemUTC());

        try {
            assertThat(unavailable.heartbeat()).isFalse();
            assertThat(unavailable.snapshot(TENANT_ID, Set.of(USER_ID)).authoritative()).isFalse();
        } finally {
            unavailableFactory.destroy();
        }
    }

    private MessagingPresenceService service(
            StringRedisTemplate template,
            SimpleMeterRegistry registry,
            int warningThreshold) {
        return new MessagingPresenceService(
                template,
                true,
                Duration.ofSeconds(75),
                Duration.ofSeconds(20),
                keyPrefix,
                warningThreshold,
                registry,
                Clock.systemUTC());
    }

    private static LettuceClientConfiguration clientConfiguration(Duration timeout) {
        SocketOptions sockets = SocketOptions.builder()
                .connectTimeout(timeout)
                .build();
        ClientOptions options = ClientOptions.builder()
                .socketOptions(sockets)
                .build();
        return LettuceClientConfiguration.builder()
                .commandTimeout(timeout)
                .shutdownTimeout(Duration.ZERO)
                .clientOptions(options)
                .build();
    }

    private void subject(long tenantId, long userId) {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                userId,
                tenantId,
                UUID.randomUUID(),
                "Presence Integration User",
                Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MESSAGING:VIEW"),
                Set.of()));
    }

    private String key(long tenantId) {
        return keyPrefix + ":" + tenantId;
    }
}
