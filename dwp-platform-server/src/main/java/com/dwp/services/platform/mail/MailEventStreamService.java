package com.dwp.services.platform.mail;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
class MailEventStreamService {

    private final MailEventStreamRepository events;
    private final MailMetrics metrics;
    private final ConcurrentHashMap<UUID, Client> clients = new ConcurrentHashMap<>();
    private final AtomicInteger activeClients = new AtomicInteger();
    private final int maximumClients;
    private final int batchSize;
    private final long timeoutMillis;
    private final long heartbeatMillis;
    private final Clock clock;

    @Autowired
    MailEventStreamService(
            MailEventStreamRepository events,
            MailMetrics metrics,
            @Value("${dwp.platform.mail.event-stream.maximum-clients:200}")
            int maximumClients,
            @Value("${dwp.platform.mail.event-stream.batch-size:250}")
            int batchSize,
            @Value("${dwp.platform.mail.event-stream.timeout-ms:300000}")
            long timeoutMillis,
            @Value("${dwp.platform.mail.event-stream.heartbeat-ms:15000}")
            long heartbeatMillis) {
        this(events, metrics, maximumClients, batchSize, timeoutMillis,
                heartbeatMillis, Clock.systemUTC());
    }

    MailEventStreamService(
            MailEventStreamRepository events,
            MailMetrics metrics,
            int maximumClients,
            int batchSize,
            long timeoutMillis,
            long heartbeatMillis,
            Clock clock) {
        this.events = events;
        this.metrics = metrics;
        this.maximumClients = positive(maximumClients, "maximumClients");
        this.batchSize = positive(batchSize, "batchSize");
        this.timeoutMillis = positive(timeoutMillis, "timeoutMillis");
        this.heartbeatMillis = positive(heartbeatMillis, "heartbeatMillis");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    SseEmitter connect(
            long tenantId,
            long userId,
            long after,
            OffsetDateTime trustedRevalidateAt) {
        requireIdentity(tenantId, userId, after);
        long now = clock.millis();
        StreamLease lease = lease(trustedRevalidateAt, now);
        long leaseMillis = lease.millis();
        if (activeClients.incrementAndGet() > maximumClients) {
            activeClients.decrementAndGet();
            metrics.eventStream("rejected");
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "MAIL_EVENT_CLIENT_LIMIT_REACHED");
        }
        UUID clientId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(leaseMillis);
        Client client = new Client(
                clientId, tenantId, userId, emitter,
                new AtomicLong(after), new AtomicLong(now),
                saturatedAdd(now, leaseMillis), lease.authorityBound(),
                new AtomicBoolean());
        clients.put(clientId, client);
        emitter.onCompletion(() -> remove(client, "completed"));
        emitter.onTimeout(() -> {
            remove(client, "timeout");
            emitter.complete();
        });
        emitter.onError(error -> remove(client, "error"));
        metrics.eventStream("connected");
        try {
            sendAvailable(client);
            if (!client.closed().get() && leaseActive(client)) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            }
        } catch (RuntimeException | IOException failure) {
            remove(client, "error");
            emitter.completeWithError(failure);
        }
        return emitter;
    }

    CatchUp catchUp(long tenantId, long userId, long after) {
        requireIdentity(tenantId, userId, after);
        List<MailEventStreamRepository.EventEnvelope> rows =
                events.catchUp(tenantId, userId, after, batchSize + 1);
        boolean hasMore = rows.size() > batchSize;
        List<MailEventStreamRepository.EventEnvelope> visible = hasMore
                ? List.copyOf(rows.subList(0, batchSize)) : List.copyOf(rows);
        long nextAfter = visible.isEmpty()
                ? after : visible.getLast().sequence();
        metrics.eventCatchUp(visible.size());
        return new CatchUp(visible, nextAfter, hasMore);
    }

    @Scheduled(fixedDelayString = "${dwp.platform.mail.event-stream.poll-delay-ms:1000}")
    void poll() {
        long now = clock.millis();
        for (Client client : clients.values()) {
            if (!leaseActive(client)) {
                expire(client);
                continue;
            }
            try {
                int sent = sendAvailable(client);
                if (sent == 0 && leaseActive(client)
                        && now - client.lastWriteMillis().get() >= heartbeatMillis) {
                    client.emitter().send(SseEmitter.event().comment("heartbeat"));
                    client.lastWriteMillis().set(now);
                    metrics.eventStream("heartbeat");
                }
            } catch (RuntimeException | IOException failure) {
                remove(client, "error");
                client.emitter().completeWithError(failure);
            }
        }
    }

    private int sendAvailable(Client client) throws IOException {
        if (!leaseActive(client)) {
            expire(client);
            return 0;
        }
        List<MailEventStreamRepository.EventEnvelope> rows = events.catchUp(
                client.tenantId(), client.userId(), client.after().get(), batchSize);
        int sent = 0;
        for (MailEventStreamRepository.EventEnvelope row : rows) {
            if (!leaseActive(client)) {
                expire(client);
                return sent;
            }
            client.emitter().send(SseEmitter.event()
                    .id(Long.toString(row.sequence()))
                    .name("mail")
                    .data(row));
            client.after().set(row.sequence());
            client.lastWriteMillis().set(clock.millis());
            sent++;
        }
        if (sent > 0) metrics.eventStream("event");
        return sent;
    }

    private void remove(Client client, String outcome) {
        if (!client.closed().compareAndSet(false, true)) return;
        clients.remove(client.id(), client);
        activeClients.decrementAndGet();
        metrics.eventStream(outcome);
    }

    private boolean leaseActive(Client client) {
        return !client.closed().get() && clock.millis() < client.expiresAtMillis();
    }

    private void expire(Client client) {
        remove(client, client.authorityBound() ? "authority_expired" : "timeout");
        client.emitter().complete();
    }

    private StreamLease lease(OffsetDateTime trustedRevalidateAt, long now) {
        if (trustedRevalidateAt == null) return new StreamLease(timeoutMillis, false);
        long authorityMillis;
        try {
            authorityMillis = Math.subtractExact(
                    trustedRevalidateAt.toInstant().toEpochMilli(), now);
        } catch (ArithmeticException invalid) {
            authorityMillis = Long.MAX_VALUE;
        }
        if (authorityMillis <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "MAIL_EVENT_AUTHORITY_EXPIRED");
        }
        return new StreamLease(
                Math.min(timeoutMillis, authorityMillis),
                authorityMillis <= timeoutMillis);
    }

    private long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private void requireIdentity(long tenantId, long userId, long after) {
        if (tenantId <= 0 || userId <= 0 || after < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "MAIL_EVENT_CURSOR_INVALID");
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    record CatchUp(
            List<MailEventStreamRepository.EventEnvelope> events,
            long nextAfter,
            boolean hasMore) { }

    private record StreamLease(long millis, boolean authorityBound) { }

    private record Client(
            UUID id,
            long tenantId,
            long userId,
            SseEmitter emitter,
            AtomicLong after,
            AtomicLong lastWriteMillis,
            long expiresAtMillis,
            boolean authorityBound,
            AtomicBoolean closed) { }
}
