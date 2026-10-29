package com.dwp.services.messaging.presence;

import com.dwp.services.messaging.domain.MessagingDtos;
import com.dwp.services.messaging.security.MessagingRequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Tenant-isolated, ephemeral Messaging presence.
 *
 * <p>The directory projection is deliberately not a presence authority. A user is available only
 * while an authenticated browser continues to renew this Redis lease. Missing leases are offline;
 * a disabled or unavailable Redis authority produces UNKNOWN rather than a fabricated fallback.
 */
@Service
public final class MessagingPresenceService implements MessagingPresenceLeaseControl {

    private static final Logger log = LoggerFactory.getLogger(MessagingPresenceService.class);
    private static final Pattern SAFE_KEY_PREFIX = Pattern.compile("[A-Za-z0-9._:-]{3,120}");
    private static final Duration MIN_TTL = Duration.ofSeconds(30);
    private static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final Duration MIN_REFRESH_AFTER = Duration.ofSeconds(5);
    private static final Duration CLIENT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CLIENT_RENEWAL_SAFETY_MARGIN = Duration.ofSeconds(5);
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_CANDIDATE_LIMIT = 256;
    private static final int SCORE_BATCH_SIZE = 500;

    private static final DefaultRedisScript<Long> HEARTBEAT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('TIME')
            local now = (tonumber(current[1]) * 1000) + math.floor(tonumber(current[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            local expires = now + tonumber(ARGV[2])
            redis.call('ZADD', KEYS[1], expires, ARGV[1])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]))
            return expires
            """, Long.class);

    private static final DefaultRedisScript<Long> CLEANUP_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('TIME')
            local now = (tonumber(current[1]) * 1000) + math.floor(tonumber(current[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            return now
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final Duration ttl;
    private final Duration refreshAfter;
    private final String keyPrefix;
    private final int activeUserWarningThreshold;
    private final MessagingPresenceSharingPolicy sharingPolicy;
    private final Clock fallbackClock;
    private final Counter heartbeatSuccess;
    private final Counter heartbeatFailure;
    private final Counter snapshotSuccess;
    private final Counter snapshotFailure;
    private final Counter capacityExceeded;
    private final Timer heartbeatLatency;
    private final Timer snapshotLatency;
    private final AtomicLong observedActiveLeases = new AtomicLong();
    private final AtomicLong lastHeartbeatFailureLogAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastSnapshotFailureLogAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastCapacityLogAt = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public MessagingPresenceService(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${dwp.messaging.presence.enabled:true}") boolean enabled,
            @Value("${dwp.messaging.presence.ttl:75s}") Duration ttl,
            @Value("${dwp.messaging.presence.refresh-after:20s}") Duration refreshAfter,
            @Value("${dwp.messaging.presence.key-prefix:dwp:messaging:presence:v1}")
            String keyPrefix,
            @Value("${dwp.messaging.presence.active-user-warning-threshold:5000}")
            int activeUserWarningThreshold,
            MessagingPresenceSharingPolicy sharingPolicy) {
        this(
                redisTemplate,
                enabled,
                ttl,
                refreshAfter,
                keyPrefix,
                activeUserWarningThreshold,
                sharingPolicy,
                meterRegistry,
                Clock.systemUTC());
    }

    MessagingPresenceService(
            StringRedisTemplate redisTemplate,
            boolean enabled,
            Duration ttl,
            Duration refreshAfter,
            String keyPrefix,
            int activeUserWarningThreshold,
            MeterRegistry meterRegistry,
            Clock fallbackClock) {
        this(
                redisTemplate,
                enabled,
                ttl,
                refreshAfter,
                keyPrefix,
                activeUserWarningThreshold,
                MessagingPresenceSharingPolicy.ALLOW_ALL,
                meterRegistry,
                fallbackClock);
    }

    MessagingPresenceService(
            StringRedisTemplate redisTemplate,
            boolean enabled,
            Duration ttl,
            Duration refreshAfter,
            String keyPrefix,
            int activeUserWarningThreshold,
            MessagingPresenceSharingPolicy sharingPolicy,
            MeterRegistry meterRegistry,
            Clock fallbackClock) {
        if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Messaging presence TTL must be 30 seconds to 5 minutes.");
        }
        if (refreshAfter == null
                || refreshAfter.compareTo(MIN_REFRESH_AFTER) < 0
                || refreshAfter.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException(
                    "Messaging presence refresh interval must be at least 5 seconds and less than its TTL.");
        }
        if (ttl.getNano() != 0 || refreshAfter.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Messaging presence TTL and refresh interval must use whole seconds.");
        }
        Duration renewalWindow = refreshAfter
                .plus(CLIENT_REQUEST_TIMEOUT.multipliedBy(2))
                .plus(CLIENT_RENEWAL_SAFETY_MARGIN);
        if (renewalWindow.compareTo(ttl) > 0) {
            throw new IllegalArgumentException(
                    "Messaging presence TTL must cover the refresh interval, one failed request, one retry, and the safety margin.");
        }
        String normalizedPrefix = keyPrefix == null ? "" : keyPrefix.trim();
        if (!SAFE_KEY_PREFIX.matcher(normalizedPrefix).matches()) {
            throw new IllegalArgumentException("Messaging presence Redis key prefix is invalid.");
        }
        if (activeUserWarningThreshold < 1 || activeUserWarningThreshold > 50_000) {
            throw new IllegalArgumentException(
                    "Messaging active presence warning threshold must be between 1 and 50000.");
        }
        MeterRegistry resolvedMeters = meterRegistry == null ? Metrics.globalRegistry : meterRegistry;
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.ttl = ttl;
        this.refreshAfter = refreshAfter;
        this.keyPrefix = normalizedPrefix;
        this.activeUserWarningThreshold = activeUserWarningThreshold;
        this.sharingPolicy = sharingPolicy == null
                ? MessagingPresenceSharingPolicy.ALLOW_ALL : sharingPolicy;
        this.fallbackClock = fallbackClock;
        this.heartbeatSuccess = counter(
                resolvedMeters,
                "dwp.messaging.presence.heartbeat.total",
                "success",
                "Presence heartbeat attempts accepted after durable privacy enforcement");
        this.heartbeatFailure = counter(
                resolvedMeters,
                "dwp.messaging.presence.heartbeat.total",
                "failure",
                "Presence heartbeat attempts that could not renew a Redis lease");
        this.snapshotSuccess = counter(
                resolvedMeters,
                "dwp.messaging.presence.snapshot.total",
                "success",
                "Presence reads resolved from the Redis authority");
        this.snapshotFailure = counter(
                resolvedMeters,
                "dwp.messaging.presence.snapshot.total",
                "failure",
                "Presence reads that returned unavailable authority");
        this.capacityExceeded = Counter.builder("dwp.messaging.presence.capacity.exceeded")
                .description("Presence reads observing more active leases than the configured warning threshold")
                .register(resolvedMeters);
        this.heartbeatLatency = timer(
                resolvedMeters,
                "dwp.messaging.presence.redis.duration",
                "heartbeat",
                "Redis latency for Messaging presence operations");
        this.snapshotLatency = timer(
                resolvedMeters,
                "dwp.messaging.presence.redis.duration",
                "snapshot",
                "Redis latency for Messaging presence operations");
        Gauge.builder(
                        "dwp.messaging.presence.tenant.active.leases.last_observed",
                        observedActiveLeases,
                        AtomicLong::doubleValue)
                .description(
                        "Active lease count from the tenant most recently evaluated; no tenant tag is recorded")
                .register(resolvedMeters);
    }

    public static MessagingPresenceService unavailable() {
        return new MessagingPresenceService(
                null,
                false,
                Duration.ofSeconds(75),
                Duration.ofSeconds(20),
                "dwp:messaging:presence:v1",
                5_000,
                Metrics.globalRegistry,
                Clock.systemUTC());
    }

    public boolean enabled() {
        return enabled;
    }

    /** Renews only the verified subject's server-timed presence lease. */
    public boolean heartbeat() {
        if (!enabled) {
            heartbeatFailure.increment();
            return false;
        }
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        long startedAt = System.nanoTime();
        try {
            boolean accepted = sharingPolicy.enforce(
                    subject.tenantId(),
                    subject.userId(),
                    () -> renew(subject.tenantId(), subject.userId()),
                    () -> withdraw(subject.tenantId(), subject.userId()));
            if (!accepted) {
                throw new IllegalStateException("Presence privacy could not be enforced.");
            }
            heartbeatSuccess.increment();
            return true;
        } catch (RuntimeException exception) {
            // A policy lookup failure must not fall through to lease renewal. Remove any older
            // lease when Redis is still reachable; if Redis is also unavailable, reads already
            // fail with UNAVAILABLE rather than projecting a fabricated live state.
            withdraw(subject.tenantId(), subject.userId());
            heartbeatFailure.increment();
            sampledWarning(
                    lastHeartbeatFailureLogAt,
                    "Messaging presence heartbeat unavailable; lease was not renewed",
                    exception);
            return false;
        } finally {
            heartbeatLatency.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private boolean renew(long tenantId, long userId) {
        Long expiresAt = redisTemplate.execute(
                HEARTBEAT_SCRIPT,
                List.of(key(tenantId)),
                Long.toString(userId),
                Long.toString(ttl.toMillis()),
                Long.toString(ttl.multipliedBy(2).toMillis()));
        if (expiresAt == null) {
            throw new IllegalStateException("Presence heartbeat did not return its lease expiry.");
        }
        return true;
    }

    /**
     * Removes a single subject lease. Absence is success; disabled presence has no lease authority
     * to contact and is therefore already withdrawn.
     */
    @Override
    public boolean withdraw(long tenantId, long userId) {
        if (!enabled) return true;
        try {
            redisTemplate.opsForZSet().remove(key(tenantId), Long.toString(userId));
            return true;
        } catch (RuntimeException exception) {
            sampledWarning(
                    lastHeartbeatFailureLogAt,
                    "Messaging presence lease withdrawal unavailable",
                    exception);
            return false;
        }
    }

    /** Timing contract returned after an authoritative lease renewal. */
    public LeaseTiming leaseTiming() {
        return new LeaseTiming(ttl.toSeconds(), refreshAfter.toSeconds());
    }

    /** Returns a bounded set of currently available users for home suggestions. */
    public PresenceSnapshot availableCandidates(long tenantId, int limit) {
        int resolvedLimit = Math.min(Math.max(limit, 1), MAX_CANDIDATE_LIMIT);
        return read(tenantId, (leases, key, evaluatedAtMillis) -> {
            Set<String> members = leases.reverseRangeByScore(
                    key,
                    Math.nextUp((double) evaluatedAtMillis),
                    Double.POSITIVE_INFINITY,
                    0,
                    resolvedLimit);
            Set<Long> availableUserIds = new LinkedHashSet<>();
            if (members != null) {
                for (String member : members) {
                    Long userId = positiveLong(member);
                    if (userId != null) availableUserIds.add(userId);
                }
            }
            return availableUserIds;
        });
    }

    /** Resolves only the requested directory results instead of loading a tenant-wide snapshot. */
    public PresenceSnapshot snapshot(long tenantId, Collection<Long> userIds) {
        List<Long> resolvedUserIds = userIds == null
                ? List.of()
                : userIds.stream()
                        .filter(userId -> userId != null && userId > 0)
                        .distinct()
                        .toList();
        return read(tenantId, (leases, key, evaluatedAtMillis) -> {
            Set<Long> availableUserIds = new LinkedHashSet<>();
            for (int start = 0; start < resolvedUserIds.size(); start += SCORE_BATCH_SIZE) {
                List<Long> batch = resolvedUserIds.subList(
                        start,
                        Math.min(start + SCORE_BATCH_SIZE, resolvedUserIds.size()));
                Object[] members = batch.stream().map(String::valueOf).toArray();
                List<Double> scores = leases.score(key, members);
                if (scores == null || scores.size() != batch.size()) {
                    throw new IllegalStateException("Presence score result did not match its requested members.");
                }
                for (int index = 0; index < batch.size(); index++) {
                    Double score = scores.get(index);
                    if (score != null && score > evaluatedAtMillis) {
                        availableUserIds.add(batch.get(index));
                    }
                }
            }
            return availableUserIds;
        });
    }

    public MessagingDtos.PresenceMetadata metadata(PresenceSnapshot snapshot) {
        return new MessagingDtos.PresenceMetadata(
                snapshot.authoritative() ? "LIVE" : "UNAVAILABLE",
                snapshot.evaluatedAt(),
                ttl.toSeconds(),
                refreshAfter.toSeconds());
    }

    public List<MessagingDtos.PersonSummary> resolvePeople(
            PresenceSnapshot snapshot,
            List<MessagingDtos.PersonSummary> people) {
        return people.stream().map(person -> new MessagingDtos.PersonSummary(
                person.userId(),
                person.personPublicId(),
                person.emailAddress(),
                person.displayName(),
                person.jobTitle(),
                person.organizationName(),
                snapshot.state(person.userId()))).toList();
    }

    public List<MessagingDtos.PersonSummary> resolvePeople(
            long tenantId,
            List<MessagingDtos.PersonSummary> people) {
        return resolvePeople(
                snapshot(tenantId, people.stream().map(MessagingDtos.PersonSummary::userId).toList()),
                people);
    }

    public List<MessagingDtos.MemberSummary> resolveMembers(
            PresenceSnapshot snapshot,
            List<MessagingDtos.MemberSummary> members) {
        return members.stream().map(member -> new MessagingDtos.MemberSummary(
                member.userId(),
                member.personPublicId(),
                member.displayName(),
                member.emailAddress(),
                member.jobTitle(),
                member.organizationName(),
                snapshot.state(member.userId()),
                member.memberRole(),
                member.membershipSource(),
                member.notificationLevel(),
                member.favorite(),
                member.pinned(),
                member.lastReadMessageId(),
                member.lastReadSequence(),
                member.lastReadAt(),
                member.readReceiptVisibility())).toList();
    }

    public List<MessagingDtos.MemberSummary> resolveMembers(
            long tenantId,
            List<MessagingDtos.MemberSummary> members) {
        return resolveMembers(
                snapshot(tenantId, members.stream().map(MessagingDtos.MemberSummary::userId).toList()),
                members);
    }

    private PresenceSnapshot read(long tenantId, PresenceReader reader) {
        OffsetDateTime fallbackEvaluatedAt = OffsetDateTime.now(fallbackClock);
        if (!enabled) {
            snapshotFailure.increment();
            return PresenceSnapshot.unavailable(fallbackEvaluatedAt);
        }
        long startedAt = System.nanoTime();
        try {
            String key = key(tenantId);
            Long evaluatedAtMillis = redisTemplate.execute(CLEANUP_SCRIPT, List.of(key));
            if (evaluatedAtMillis == null) {
                throw new IllegalStateException("Presence authority did not return its server time.");
            }
            ZSetOperations<String, String> leases = redisTemplate.opsForZSet();
            Long activeCount = leases.zCard(key);
            long resolvedActiveCount = activeCount == null ? 0 : Math.max(0, activeCount);
            observeCapacity(resolvedActiveCount);
            Set<Long> availableUserIds = reader.read(leases, key, evaluatedAtMillis);
            snapshotSuccess.increment();
            return PresenceSnapshot.authoritative(
                    OffsetDateTime.ofInstant(Instant.ofEpochMilli(evaluatedAtMillis), ZoneOffset.UTC),
                    availableUserIds,
                    resolvedActiveCount);
        } catch (RuntimeException exception) {
            snapshotFailure.increment();
            sampledWarning(
                    lastSnapshotFailureLogAt,
                    "Messaging presence snapshot unavailable; returning UNKNOWN states",
                    exception);
            return PresenceSnapshot.unavailable(fallbackEvaluatedAt);
        } finally {
            snapshotLatency.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private void observeCapacity(long activeCount) {
        observedActiveLeases.set(activeCount);
        if (activeCount <= activeUserWarningThreshold) return;
        capacityExceeded.increment();
        long now = fallbackClock.millis();
        if (claimLogWindow(lastCapacityLogAt, now)) {
            log.warn(
                    "Messaging presence active leases exceeded the configured warning threshold"
                            + " activeLeases={} warningThreshold={}",
                    activeCount,
                    activeUserWarningThreshold);
        }
    }

    private void sampledWarning(AtomicLong lastLoggedAt, String message, RuntimeException exception) {
        long now = fallbackClock.millis();
        if (claimLogWindow(lastLoggedAt, now)) {
            log.warn("{} errorType={}", message, exception.getClass().getSimpleName());
        }
    }

    private boolean claimLogWindow(AtomicLong lastLoggedAt, long now) {
        while (true) {
            long previous = lastLoggedAt.get();
            if (previous != Long.MIN_VALUE
                    && now >= previous
                    && now - previous < FAILURE_LOG_INTERVAL.toMillis()) {
                return false;
            }
            if (lastLoggedAt.compareAndSet(previous, now)) return true;
        }
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String key(long tenantId) {
        return keyPrefix + ":" + tenantId;
    }

    private Counter counter(
            MeterRegistry meters,
            String name,
            String result,
            String description) {
        return Counter.builder(name)
                .tag("result", result)
                .description(description)
                .register(meters);
    }

    private Timer timer(
            MeterRegistry meters,
            String name,
            String operation,
            String description) {
        return Timer.builder(name)
                .tag("operation", operation)
                .description(description)
                .register(meters);
    }

    @FunctionalInterface
    private interface PresenceReader {
        Set<Long> read(
                ZSetOperations<String, String> leases,
                String key,
                long evaluatedAtMillis);
    }

    public record PresenceSnapshot(
            boolean authoritative,
            OffsetDateTime evaluatedAt,
            Set<Long> availableUserIds,
            long activeLeaseCount) {

        public PresenceSnapshot {
            availableUserIds = Set.copyOf(availableUserIds);
        }

        public static PresenceSnapshot authoritative(
                OffsetDateTime evaluatedAt,
                Set<Long> availableUserIds,
                long activeLeaseCount) {
            return new PresenceSnapshot(true, evaluatedAt, availableUserIds, activeLeaseCount);
        }

        public static PresenceSnapshot authoritative(Set<Long> availableUserIds) {
            return authoritative(OffsetDateTime.now(ZoneOffset.UTC), availableUserIds, availableUserIds.size());
        }

        public static PresenceSnapshot unavailable(OffsetDateTime evaluatedAt) {
            return new PresenceSnapshot(false, evaluatedAt, Set.of(), 0);
        }

        public static PresenceSnapshot unavailable() {
            return unavailable(OffsetDateTime.now(ZoneOffset.UTC));
        }

        public String state(long userId) {
            if (!authoritative) return "UNKNOWN";
            return availableUserIds.contains(userId) ? "AVAILABLE" : "OFFLINE";
        }

        public boolean available(long userId) {
            return authoritative && availableUserIds.contains(userId);
        }
    }

    public record LeaseTiming(long leaseTtlSeconds, long refreshAfterSeconds) {
    }
}
