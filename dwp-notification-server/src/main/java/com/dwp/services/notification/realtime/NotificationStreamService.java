package com.dwp.services.notification.realtime;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.domain.NotificationModels.SyncResponse;
import com.dwp.services.notification.security.NotificationRequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

@Service
public class NotificationStreamService {

    private final Map<UserKey, Set<Client>> clients = new ConcurrentHashMap<>();
    private final long timeoutMillis;
    private final Counter sendFailures;
    private final Counter connectionRejections;
    private final Counter catchUpOverflows;
    private final LongFunction<SseEmitter> emitterFactory;
    private final int maximumConnections;
    private final int maximumTenantConnections;
    private final int maximumUserConnections;
    private final int maximumCatchUpBuffer;
    private final Object connectionLock = new Object();

    @Autowired
    public NotificationStreamService(
            @Value("${dwp.notification.sse-timeout:30m}") Duration timeout,
            @Value("${dwp.notification.sse-max-connections:5000}") int maximumConnections,
            @Value("${dwp.notification.sse-max-connections-per-tenant:1000}")
            int maximumTenantConnections,
            @Value("${dwp.notification.sse-max-connections-per-user:4}")
            int maximumUserConnections,
            @Value("${dwp.notification.sse-catch-up-buffer-capacity:100}")
            int maximumCatchUpBuffer,
            MeterRegistry meterRegistry) {
        this(
                timeout.toMillis(),
                meterRegistry,
                SseEmitter::new,
                maximumConnections,
                maximumTenantConnections,
                maximumUserConnections,
                maximumCatchUpBuffer);
    }

    NotificationStreamService(
            long timeoutMillis,
            MeterRegistry meterRegistry,
            LongFunction<SseEmitter> emitterFactory) {
        this(timeoutMillis, meterRegistry, emitterFactory, 5000, 1000, 4, 100);
    }

    NotificationStreamService(
            long timeoutMillis,
            MeterRegistry meterRegistry,
            LongFunction<SseEmitter> emitterFactory,
            int maximumConnections,
            int maximumTenantConnections,
            int maximumUserConnections,
            int maximumCatchUpBuffer) {
        this.timeoutMillis = timeoutMillis;
        this.emitterFactory = emitterFactory;
        this.maximumConnections = positive(maximumConnections, "maximumConnections");
        this.maximumTenantConnections = positive(
                maximumTenantConnections, "maximumTenantConnections");
        this.maximumUserConnections = positive(
                maximumUserConnections, "maximumUserConnections");
        this.maximumCatchUpBuffer = positive(maximumCatchUpBuffer, "maximumCatchUpBuffer");
        if (this.maximumTenantConnections > this.maximumConnections
                || this.maximumUserConnections > this.maximumTenantConnections) {
            throw new IllegalArgumentException(
                    "Notification SSE connection limits must narrow from global to tenant to user.");
        }
        this.sendFailures = Counter.builder("dwp.notification.sse.send.failures")
                .description("SSE sends that failed because a client connection was unavailable")
                .register(meterRegistry);
        this.connectionRejections = Counter.builder("dwp.notification.sse.connection.rejections")
                .description("Notification SSE connections rejected by capacity limits")
                .register(meterRegistry);
        this.catchUpOverflows = Counter.builder("dwp.notification.sse.catchup.overflows")
                .description("Notification SSE initial catch-up buffers that required resynchronization")
                .register(meterRegistry);
        Gauge.builder(
                        "dwp.notification.sse.connections",
                        clients,
                        connections -> connections.values().stream()
                                .mapToInt(Set::size)
                                .sum())
                .description("Currently registered notification SSE connections")
                .register(meterRegistry);
    }

    public SseEmitter open(
            NotificationRequestContext.Actor actor,
            String after,
            CatchUpSource catchUpSource) {
        return open(actor, after, null, catchUpSource);
    }

    public SseEmitter open(
            NotificationRequestContext.Actor actor,
            String after,
            UUID clientInstanceId,
            CatchUpSource catchUpSource) {
        SseEmitter emitter = emitterFactory.apply(timeoutMillis);
        UserKey key = new UserKey(actor.tenantId(), actor.userId());
        Client client = new Client(
                clientInstanceId == null ? UUID.randomUUID() : clientInstanceId,
                actor,
                emitter,
                maximumCatchUpBuffer);
        List<Client> superseded = register(key, client);
        Runnable cleanup = () -> remove(key, client);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        superseded.forEach(Client::supersede);
        try {
            String cursor = after == null || after.isBlank() ? "0" : after.trim();
            sendConnected(client, cursor);
            SyncResponse page;
            do {
                page = catchUpSource.next(cursor, 100);
                if (!page.changedIds().isEmpty()) sendChanged(client, page);
                cursor = page.changeVersion();
            } while (page.hasMore());
            if (!completeInitialization(client)) {
                remove(key, client);
                emitter.complete();
            }
        } catch (RuntimeException | IOException exception) {
            remove(key, client);
            sendFailures.increment();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void dispatch(NotificationRealtimeEnvelope envelope) {
        UserKey key = new UserKey(envelope.tenantId(), envelope.userId());
        Set<Client> connected = clients.get(key);
        if (connected == null || connected.isEmpty()) return;
        for (Client client : List.copyOf(connected)) {
            try {
                deliverOrBuffer(client, envelope);
            } catch (IOException | RuntimeException exception) {
                remove(key, client);
                sendFailures.increment();
            }
        }
    }

    private void sendChanged(Client client, SyncResponse sync) throws IOException {
        client.emitter().send(SseEmitter.event()
                .name("notification.changed")
                .id(sync.changeVersion())
                .data(livePayload(
                        sync.changeVersion(),
                        sync.summary().counterVersion(),
                        sync.changedIds().stream().map(UUID::toString).toList(),
                        List.of())));
    }

    static Map<String, Object> livePayload(
            String changeVersion,
            String counterVersion,
            List<String> changedIds,
            List<String> arrivalIds) {
        return Map.of(
                "changeVersion", changeVersion,
                "counterVersion", counterVersion,
                "changedIds", List.copyOf(changedIds),
                "arrivalIds", List.copyOf(arrivalIds));
    }

    private void sendConnected(Client client, String cursor) throws IOException {
        client.emitter().send(SseEmitter.event()
                .name("notification.connected")
                .id(cursor)
                .data(Map.of(
                        "changeVersion", cursor,
                        "changedIds", List.of(),
                        "arrivalIds", List.of())));
    }

    private void deliverOrBuffer(
            Client client,
            NotificationRealtimeEnvelope envelope) throws IOException {
        synchronized (client) {
            if (client.initializing) {
                client.buffer(envelope);
                return;
            }
            sendEnvelope(client, envelope);
        }
    }

    private boolean completeInitialization(Client client) throws IOException {
        synchronized (client) {
            if (client.overflowed) {
                sendSyncReset(client);
                client.pending.clear();
                client.initializing = false;
                catchUpOverflows.increment();
                return false;
            }
            client.pending.sort(Comparator.comparingLong(envelope ->
                    NotificationVersionCodec.nonNegative(
                            envelope.changeVersion(), "changeVersion")));
            for (NotificationRealtimeEnvelope envelope : client.pending) {
                sendEnvelope(client, envelope);
            }
            client.pending.clear();
            client.initializing = false;
            return true;
        }
    }

    private void sendSyncReset(Client client) throws IOException {
        client.emitter().send(SseEmitter.event()
                .name("notification.sync-reset")
                .data(Map.of("errorCode", "NOTIFICATION_SYNC_RESET_REQUIRED")));
    }

    private void sendEnvelope(
            Client client,
            NotificationRealtimeEnvelope envelope) throws IOException {
        client.emitter().send(SseEmitter.event()
                .name("notification.changed")
                .id(envelope.changeVersion())
                .data(livePayload(
                        envelope.changeVersion(),
                        envelope.counterVersion(),
                        envelope.changedIds().stream().map(UUID::toString).toList(),
                        envelope.arrivalIds().stream().map(UUID::toString).toList())));
    }

    @Scheduled(fixedDelayString = "${dwp.notification.sse-heartbeat:15s}")
    void heartbeat() {
        clients.forEach((key, connected) -> {
            for (Client client : List.copyOf(connected)) {
                try {
                    client.emitter().send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | RuntimeException exception) {
                    remove(key, client);
                    sendFailures.increment();
                }
            }
        });
    }

    int connectionCount() {
        synchronized (connectionLock) {
            return connectionCountUnsafe();
        }
    }

    private void remove(UserKey key, Client client) {
        synchronized (connectionLock) {
            Set<Client> connected = clients.get(key);
            if (connected == null) return;
            connected.remove(client);
            if (connected.isEmpty()) clients.remove(key, connected);
        }
    }

    private List<Client> register(UserKey key, Client client) {
        synchronized (connectionLock) {
            Set<Client> userConnections = clients.get(key);
            List<Client> superseded = userConnections == null
                    ? List.of()
                    : userConnections.stream()
                            .filter(existing -> existing.id().equals(client.id()))
                            .toList();
            int userCount = (userConnections == null ? 0 : userConnections.size())
                    - superseded.size();
            int tenantCount = clients.entrySet().stream()
                    .filter(entry -> entry.getKey().tenantId() == key.tenantId())
                    .mapToInt(entry -> entry.getValue().size())
                    .sum()
                    - superseded.size();
            int globalCount = connectionCountUnsafe() - superseded.size();
            if (globalCount >= maximumConnections
                    || tenantCount >= maximumTenantConnections
                    || userCount >= maximumUserConnections) {
                connectionRejections.increment();
                throw new NotificationStreamCapacityException();
            }
            Set<Client> registered = clients.computeIfAbsent(
                    key, ignored -> ConcurrentHashMap.newKeySet());
            registered.removeAll(superseded);
            registered.add(client);
            return superseded;
        }
    }

    private int connectionCountUnsafe() {
        return clients.values().stream().mapToInt(Set::size).sum();
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive.");
        return value;
    }

    private record UserKey(long tenantId, long userId) {
    }

    @FunctionalInterface
    public interface CatchUpSource {
        SyncResponse next(String after, int limit);
    }

    private static final class Client {
        private final UUID id;
        private final NotificationRequestContext.Actor actor;
        private final SseEmitter emitter;
        private final List<NotificationRealtimeEnvelope> pending = new ArrayList<>();
        private final int maximumPending;
        private boolean initializing = true;
        private boolean overflowed;

        private Client(
                UUID id,
                NotificationRequestContext.Actor actor,
                SseEmitter emitter,
                int maximumPending) {
            this.id = id;
            this.actor = actor;
            this.emitter = emitter;
            this.maximumPending = maximumPending;
        }

        SseEmitter emitter() {
            return emitter;
        }

        UUID id() {
            return id;
        }

        void supersede() {
            emitter.complete();
        }

        void buffer(NotificationRealtimeEnvelope envelope) {
            if (overflowed) return;
            if (pending.size() >= maximumPending) {
                overflowed = true;
                pending.clear();
                return;
            }
            pending.add(envelope);
        }
    }
}
