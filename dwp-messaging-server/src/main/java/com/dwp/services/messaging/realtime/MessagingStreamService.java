package com.dwp.services.messaging.realtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessagingStreamService {

    private static final Logger log = LoggerFactory.getLogger(MessagingStreamService.class);
    private static final int REPLAY_BATCH_SIZE = 200;
    private static final int MAX_REPLAY_EVENTS = 2_000;

    private final MessagingRealtimeRepository events;
    private final Map<Long, Set<Client>> clientsByTenant = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> recentTypingSignals = new ConcurrentHashMap<>();
    private final long timeoutMillis;

    public MessagingStreamService(
            MessagingRealtimeRepository events,
            @Value("${dwp.messaging.sse-timeout:30m}") Duration timeout) {
        this.events = events;
        this.timeoutMillis = timeout.toMillis();
    }

    public SseEmitter open(MessagingRequestContext.Subject subject, String cursor) {
        long initialCursor = cursor == null || cursor.isBlank()
                ? events.latestVisibleSequence(subject)
                : parseCursor(cursor);
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Client client = new Client(UUID.randomUUID(), subject, emitter, initialCursor);
        Runnable cleanup = () -> remove(client);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        try {
            client.connected();
            clientsByTenant.computeIfAbsent(subject.tenantId(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(client);
            catchUp(client, events.latestTenantSequence(subject.tenantId()));
        } catch (IOException | RuntimeException exception) {
            remove(client);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void wakeUp(MessagingRealtimeSignal signal) {
        Set<Client> clients = clientsByTenant.get(signal.tenantId());
        if (clients == null || clients.isEmpty()) return;
        for (Client client : List.copyOf(clients)) {
            if (client.scanCursor() >= signal.sequence()) continue;
            try {
                catchUp(client, signal.sequence());
            } catch (IOException exception) {
                remove(client);
            } catch (RuntimeException exception) {
                log.warn(
                        "Messaging durable realtime catch-up failed; the SSE connection remains open"
                                + " tenantId={} userId={} eventSequence={} errorType={}",
                        client.subject().tenantId(),
                        client.subject().userId(),
                        signal.eventSequence(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    public void dispatchTyping(MessagingTypingSignal signal) {
        Instant deduplicationDeadline = Instant.now().plusSeconds(30);
        if (recentTypingSignals.putIfAbsent(signal.signalId(), deduplicationDeadline) != null) return;
        Set<Client> clients = clientsByTenant.get(signal.tenantId());
        if (clients == null || clients.isEmpty()) return;
        for (Client client : List.copyOf(clients)) {
            if (client.subject().userId() == signal.userId()) continue;
            try {
                if (!events.isActiveConversationMember(
                        signal.tenantId(), signal.conversationId(), client.subject().userId())) {
                    continue;
                }
                client.typing(signal);
            } catch (IOException exception) {
                remove(client);
            } catch (RuntimeException exception) {
                log.warn(
                        "Messaging typing recipient validation failed; the SSE connection remains open"
                                + " tenantId={} userId={} conversationId={} errorType={}",
                        client.subject().tenantId(),
                        client.subject().userId(),
                        signal.conversationId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(fixedDelayString = "${dwp.messaging.sse-heartbeat:15s}")
    void heartbeat() {
        Instant now = Instant.now();
        recentTypingSignals.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        clientsByTenant.forEach((tenantId, clients) -> {
            long targetSequence;
            try {
                targetSequence = events.latestTenantSequence(tenantId);
            } catch (RuntimeException exception) {
                log.warn(
                        "Messaging heartbeat high-water query failed; retrying on the next heartbeat"
                                + " tenantId={} errorType={}",
                        tenantId,
                        exception.getClass().getSimpleName());
                targetSequence = -1;
            }
            for (Client client : List.copyOf(clients)) {
                if (targetSequence >= 0 && client.scanCursor() < targetSequence) {
                    try {
                        catchUp(client, targetSequence);
                    } catch (RuntimeException exception) {
                        // Redis Pub/Sub is intentionally best effort. A periodic durable-log read closes
                        // a missed-signal window once PostgreSQL becomes available again.
                        log.warn(
                                "Messaging heartbeat catch-up failed; retrying on the next heartbeat"
                                        + " tenantId={} userId={} errorType={}",
                                client.subject().tenantId(),
                                client.subject().userId(),
                                exception.getClass().getSimpleName());
                    } catch (IOException exception) {
                        remove(client);
                        continue;
                    }
                }
                try {
                    client.heartbeat();
                } catch (IOException | RuntimeException exception) {
                    remove(client);
                }
            }
        });
    }

    int connectionCount() {
        return clientsByTenant.values().stream().mapToInt(Set::size).sum();
    }

    static long parseCursor(String cursor) {
        try {
            long parsed = Long.parseLong(cursor.trim());
            if (parsed < 0) throw new NumberFormatException("negative cursor");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "The realtime cursor must be a non-negative integer.");
        }
    }

    private void catchUp(Client client, long targetSequence) throws IOException {
        if (targetSequence <= client.scanCursor()) return;
        int replayed = 0;
        while (replayed < MAX_REPLAY_EVENTS) {
            List<MessagingRealtimeEvent> batch = events.eventsBetween(
                    client.subject(), client.scanCursor(), targetSequence, REPLAY_BATCH_SIZE);
            if (batch.isEmpty()) {
                client.advanceScanCursor(targetSequence);
                return;
            }
            for (MessagingRealtimeEvent event : batch) client.deliver(event);
            replayed += batch.size();
            if (batch.size() < REPLAY_BATCH_SIZE) {
                client.advanceScanCursor(targetSequence);
                return;
            }
        }
        long latest = events.latestVisibleSequence(client.subject());
        client.resyncRequired(latest);
        client.advanceScanCursor(targetSequence);
    }

    private void remove(Client client) {
        Set<Client> clients = clientsByTenant.get(client.subject().tenantId());
        if (clients == null) return;
        clients.remove(client);
        if (clients.isEmpty()) clientsByTenant.remove(client.subject().tenantId(), clients);
    }

    private static final class Client {
        private final UUID id;
        private final MessagingRequestContext.Subject subject;
        private final SseEmitter emitter;
        private long cursor;
        private long scanCursor;

        private Client(
                UUID id,
                MessagingRequestContext.Subject subject,
                SseEmitter emitter,
                long cursor) {
            this.id = id;
            this.subject = subject;
            this.emitter = emitter;
            this.cursor = cursor;
            this.scanCursor = cursor;
        }

        synchronized void connected() throws IOException {
            emitter.send(SseEmitter.event()
                    .name("messaging.connected")
                    .id(Long.toString(cursor))
                    .data(Map.of("cursor", Long.toString(cursor), "connectionId", id.toString())));
        }

        synchronized void deliver(MessagingRealtimeEvent event) throws IOException {
            if (event.sequence() > cursor) send(event);
            scanCursor = Math.max(scanCursor, event.sequence());
        }

        synchronized void resyncRequired(long latest) throws IOException {
            emitter.send(SseEmitter.event()
                    .name("messaging.resync-required")
                    .id(Long.toString(latest))
                    .data(Map.of("cursor", Long.toString(latest))));
            cursor = latest;
        }

        synchronized void heartbeat() throws IOException {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        }

        synchronized void typing(MessagingTypingSignal signal) throws IOException {
            emitter.send(SseEmitter.event()
                    .name("TYPING_CHANGED")
                    .data(signal));
        }

        private void send(MessagingRealtimeEvent event) throws IOException {
            emitter.send(SseEmitter.event()
                    .name(event.eventType())
                    .id(event.cursor())
                    .data(event));
            cursor = event.sequence();
        }

        synchronized long scanCursor() {
            return scanCursor;
        }

        synchronized void advanceScanCursor(long sequence) {
            scanCursor = Math.max(scanCursor, sequence);
        }

        MessagingRequestContext.Subject subject() {
            return subject;
        }

        SseEmitter emitter() {
            return emitter;
        }
    }
}
