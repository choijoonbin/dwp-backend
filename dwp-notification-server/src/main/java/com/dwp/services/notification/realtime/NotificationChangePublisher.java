package com.dwp.services.notification.realtime;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.domain.NotificationModels.ChangeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationChangePublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final NotificationRedisChannels channels;
    private final NotificationRedisSignalCodec codec;
    private final boolean enabled;

    public NotificationChangePublisher(
            StringRedisTemplate redisTemplate,
            NotificationRedisChannels channels,
            NotificationRedisSignalCodec codec,
            @Value("${dwp.notification.realtime.redis-enabled:true}") boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.channels = channels;
        this.codec = codec;
        this.enabled = enabled;
    }

    public void publishAfterCommit(List<ChangeSignal> signals) {
        if (signals == null || signals.isEmpty()) return;
        List<NotificationRealtimeEnvelope> envelopes = coalesce(signals);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish(envelopes);
                        }
                    });
            return;
        }
        publish(envelopes);
    }

    private void publish(List<NotificationRealtimeEnvelope> envelopes) {
        if (!enabled) {
            log.debug("Notification Redis signal publishing is disabled; clients recover by REST sync");
            return;
        }
        for (NotificationRealtimeEnvelope envelope : envelopes) {
            try {
                redisTemplate.convertAndSend(
                        channels.channel(envelope.tenantId(), envelope.userId()),
                        codec.encode(envelope));
            } catch (RuntimeException exception) {
                // The database is the source of truth. A lost hint is recovered by /v1/sync.
                log.warn(
                        "Notification realtime hint publish failed; REST sync remains authoritative"
                                + " tenantId={} userId={} changeVersion={} errorType={}",
                        envelope.tenantId(),
                        envelope.userId(),
                        envelope.changeVersion(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    static List<NotificationRealtimeEnvelope> coalesce(List<ChangeSignal> signals) {
        Map<UserKey, Aggregate> aggregateByUser = new LinkedHashMap<>();
        signals.stream()
                .sorted(Comparator.comparingLong(ChangeSignal::changeVersion))
                .forEach(signal -> aggregateByUser
                        .computeIfAbsent(
                                new UserKey(signal.tenantId(), signal.userId()),
                                ignored -> new Aggregate())
                        .add(signal));
        List<NotificationRealtimeEnvelope> envelopes = new ArrayList<>();
        aggregateByUser.forEach((key, aggregate) -> envelopes.add(new NotificationRealtimeEnvelope(
                key.tenantId(),
                key.userId(),
                NotificationVersionCodec.external(aggregate.version),
                NotificationVersionCodec.external(aggregate.version),
                List.copyOf(aggregate.notificationIds))));
        return List.copyOf(envelopes);
    }

    private record UserKey(long tenantId, long userId) {
    }

    private static final class Aggregate {
        private long version;
        private final LinkedHashSet<UUID> notificationIds = new LinkedHashSet<>();

        void add(ChangeSignal signal) {
            version = Math.max(version, signal.changeVersion());
            notificationIds.add(signal.notificationId());
        }
    }
}
