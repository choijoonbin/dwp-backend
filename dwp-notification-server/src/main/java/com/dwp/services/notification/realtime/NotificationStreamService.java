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
    private final LongFunction<SseEmitter> emitterFactory;

    @Autowired
    public NotificationStreamService(
            @Value("${dwp.notification.sse-timeout:30m}") Duration timeout,
            MeterRegistry meterRegistry) {
        this(timeout.toMillis(), meterRegistry, SseEmitter::new);
    }

    NotificationStreamService(
            long timeoutMillis,
            MeterRegistry meterRegistry,
            LongFunction<SseEmitter> emitterFactory) {
        this.timeoutMillis = timeoutMillis;
        this.emitterFactory = emitterFactory;
        this.sendFailures = Counter.builder("dwp.notification.sse.send.failures")
                .description("SSE sends that failed because a client connection was unavailable")
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
        SseEmitter emitter = emitterFactory.apply(timeoutMillis);
        UserKey key = new UserKey(actor.tenantId(), actor.userId());
        Client client = new Client(UUID.randomUUID(), actor, emitter);
        clients.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(client);
        Runnable cleanup = () -> remove(key, client);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            String cursor = after == null || after.isBlank() ? "0" : after.trim();
            sendConnected(client, cursor);
            SyncResponse page;
            do {
                page = catchUpSource.next(cursor, 100);
                if (!page.changedIds().isEmpty()) sendChanged(client, page);
                cursor = page.changeVersion();
            } while (page.hasMore());
            completeInitialization(client);
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
                client.pending.add(envelope);
                return;
            }
            sendEnvelope(client, envelope);
        }
    }

    private void completeInitialization(Client client) throws IOException {
        synchronized (client) {
            client.pending.sort(Comparator.comparingLong(envelope ->
                    NotificationVersionCodec.nonNegative(
                            envelope.changeVersion(), "changeVersion")));
            for (NotificationRealtimeEnvelope envelope : client.pending) {
                sendEnvelope(client, envelope);
            }
            client.pending.clear();
            client.initializing = false;
        }
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
        return clients.values().stream().mapToInt(Set::size).sum();
    }

    private void remove(UserKey key, Client client) {
        Set<Client> connected = clients.get(key);
        if (connected == null) return;
        connected.remove(client);
        if (connected.isEmpty()) clients.remove(key, connected);
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
        private boolean initializing = true;

        private Client(
                UUID id,
                NotificationRequestContext.Actor actor,
                SseEmitter emitter) {
            this.id = id;
            this.actor = actor;
            this.emitter = emitter;
        }

        SseEmitter emitter() {
            return emitter;
        }
    }
}
