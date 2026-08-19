package com.dwp.services.notification.realtime;

import com.dwp.services.notification.domain.NotificationModels.SyncResponse;
import com.dwp.services.notification.security.NotificationRequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationStreamService {

    private final Map<UserKey, Set<Client>> clients = new ConcurrentHashMap<>();
    private final long timeoutMillis;
    private final Counter sendFailures;

    public NotificationStreamService(
            @Value("${dwp.notification.sse-timeout:30m}") Duration timeout,
            MeterRegistry meterRegistry) {
        this.timeoutMillis = timeout.toMillis();
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
            SyncResponse catchUp) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        UserKey key = new UserKey(actor.tenantId(), actor.userId());
        Client client = new Client(UUID.randomUUID(), actor, emitter);
        clients.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(client);
        Runnable cleanup = () -> remove(key, client);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event()
                    .name("notification.connected")
                    .id(catchUp.changeVersion())
                    .data(Map.of(
                            "counterVersion", catchUp.summary().counterVersion(),
                            "changeVersion", catchUp.changeVersion(),
                            "changedIds", List.of())));
            if (!catchUp.changedIds().isEmpty()) sendChanged(client, catchUp);
        } catch (RuntimeException | IOException exception) {
            remove(key, client);
            sendFailures.increment();
        }
        return emitter;
    }

    public void dispatch(NotificationRealtimeEnvelope envelope) {
        UserKey key = new UserKey(envelope.tenantId(), envelope.userId());
        Set<Client> connected = clients.get(key);
        if (connected == null || connected.isEmpty()) return;
        Map<String, Object> payload = livePayload(
                envelope.changeVersion(),
                envelope.counterVersion(),
                envelope.changedIds().stream().map(UUID::toString).toList());
        for (Client client : List.copyOf(connected)) {
            try {
                client.emitter().send(SseEmitter.event()
                        .name("notification.changed")
                        .id(envelope.changeVersion())
                        .data(payload));
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
                        sync.changedIds().stream().map(UUID::toString).toList())));
    }

    static Map<String, Object> livePayload(
            String changeVersion,
            String counterVersion,
            List<String> changedIds) {
        return Map.of(
                "changeVersion", changeVersion,
                "counterVersion", counterVersion,
                "changedIds", List.copyOf(changedIds));
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

    private record Client(
            UUID id,
            NotificationRequestContext.Actor actor,
            SseEmitter emitter) {
    }
}
