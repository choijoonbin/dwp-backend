package com.dwp.services.notification.realtime;

import com.dwp.services.notification.cursor.NotificationCursorCodec;
import com.dwp.services.notification.domain.NotificationModels.ChangeSignal;
import com.dwp.services.notification.domain.NotificationModels.SyncResponse;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class NotificationStreamService {

    private final Map<UserKey, Set<Client>> clients = new ConcurrentHashMap<>();
    private final NotificationCursorCodec cursorCodec;
    private final long timeoutMillis;

    public NotificationStreamService(
            NotificationCursorCodec cursorCodec,
            @Value("${dwp.notification.sse-timeout:30m}") Duration timeout) {
        this.cursorCodec = cursorCodec;
        this.timeoutMillis = timeout.toMillis();
    }

    public SseEmitter open(
            NotificationRequestContext.Actor actor,
            Supplier<SyncResponse> durableCatchUp) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        UserKey key = new UserKey(actor.tenantId(), actor.userId());
        Client client = new Client(UUID.randomUUID(), actor, emitter);
        clients.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(client);
        Runnable cleanup = () -> remove(key, client);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            SyncResponse sync = durableCatchUp.get();
            emitter.send(SseEmitter.event()
                    .name("notification.connected")
                    .id(sync.changeVersion())
                    .data(Map.of(
                            "counterVersion", sync.summary().counterVersion(),
                            "changeVersion", highestVersion(sync))));
            if (!sync.changedIds().isEmpty()) sendChanged(client, sync);
        } catch (RuntimeException | IOException exception) {
            remove(key, client);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publishAfterCommit(List<ChangeSignal> signals) {
        if (signals.isEmpty()) return;
        List<ChangeSignal> immutableSignals = List.copyOf(signals);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish(immutableSignals);
                        }
                    });
            return;
        }
        publish(immutableSignals);
    }

    private void publish(List<ChangeSignal> signals) {
        for (ChangeSignal signal : signals) {
            UserKey key = new UserKey(signal.tenantId(), signal.userId());
            Set<Client> connected = clients.get(key);
            if (connected == null || connected.isEmpty()) continue;
            for (Client client : List.copyOf(connected)) {
                try {
                    String cursor = cursorCodec.encodeChangeVersion(
                            client.actor(), signal.changeVersion());
                    client.emitter().send(SseEmitter.event()
                            .name("notification.changed")
                            .id(cursor)
                            .data(livePayload(
                                    cursor,
                                    Long.toString(signal.changeVersion()),
                                    List.of(signal.notificationId().toString()))));
                } catch (IOException | RuntimeException exception) {
                    remove(key, client);
                    client.emitter().complete();
                }
            }
        }
    }

    private void sendChanged(Client client, SyncResponse sync) throws IOException {
        client.emitter().send(SseEmitter.event()
                .name("notification.changed")
                .id(sync.changeVersion())
                .data(livePayload(
                        highestVersion(sync),
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

    private String highestVersion(SyncResponse sync) {
        return sync.changeVersion();
    }

    @Scheduled(fixedDelayString = "${dwp.notification.sse-heartbeat:15s}")
    void heartbeat() {
        clients.forEach((key, connected) -> {
            for (Client client : List.copyOf(connected)) {
                try {
                    client.emitter().send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | RuntimeException exception) {
                    remove(key, client);
                    client.emitter().complete();
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
