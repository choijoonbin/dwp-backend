package com.dwp.observability.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Bounded, fail-open, asynchronous batch exporter for the central history collector. */
public final class HttpApiHistoryPublisher implements ApiHistoryPublisher {

    public static final String INGEST_TOKEN_HEADER = "X-DWP-Observability-Token";
    public static final String SERVICE_NAME_HEADER = "X-DWP-Observability-Service";

    private static final Logger log = LoggerFactory.getLogger(HttpApiHistoryPublisher.class);

    private final URI collectorUri;
    private final String ingestToken;
    private final String serviceName;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final ArrayBlockingQueue<ApiHistoryEvent> queue;
    private final int batchSize;
    private final Duration flushInterval;
    private final LongAdder dropped = new LongAdder();
    private final LongAdder exportFailures = new LongAdder();
    private final Thread worker;
    private volatile boolean running = true;

    public HttpApiHistoryPublisher(
            URI collectorUri,
            String ingestToken,
            String serviceName,
            ObjectMapper objectMapper,
            int queueCapacity,
            int batchSize,
            Duration flushInterval) {
        this.collectorUri = Objects.requireNonNull(collectorUri);
        this.ingestToken = Objects.requireNonNull(ingestToken);
        this.serviceName = Objects.requireNonNull(serviceName);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.queue = new ArrayBlockingQueue<>(Math.max(128, queueCapacity));
        this.batchSize = Math.max(1, Math.min(200, batchSize));
        this.flushInterval = flushInterval == null || flushInterval.isNegative()
                ? Duration.ofSeconds(1)
                : flushInterval;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.worker = new Thread(this::drain, "dwp-api-history-exporter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void publish(ApiHistoryEvent event) {
        if (event == null || !running) return;
        if (!queue.offer(event)) {
            dropped.increment();
            long count = dropped.sum();
            if ((count & (count - 1)) == 0) {
                log.warn("API history queue is full; droppedEvents={}", count);
            }
        }
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public long exportFailureCount() {
        return exportFailures.sum();
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                ApiHistoryEvent first = queue.poll(
                        Math.max(100, flushInterval.toMillis()), TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<ApiHistoryEvent> batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                if (!send(batch)) {
                    dropped.add(batch.size());
                    exportFailures.increment();
                }
            } catch (InterruptedException exception) {
                if (!running) break;
            } catch (RuntimeException exception) {
                log.warn("API history batch export failed: {}", exception.getClass().getSimpleName());
            }
        }
    }

    private boolean send(List<ApiHistoryEvent> batch) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(batch);
        } catch (IOException exception) {
            log.warn("API history batch serialization failed; size={}", batch.size());
            return false;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(collectorUri)
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .header(INGEST_TOKEN_HEADER, ingestToken)
                        .header(SERVICE_NAME_HEADER, serviceName)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<Void> response =
                        client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return true;
                if (response.statusCode() < 500 && response.statusCode() != 429) {
                    log.warn("API history collector rejected batch; status={} size={}",
                            response.statusCode(), batch.size());
                    return false;
                }
            } catch (IOException exception) {
                if (attempt == 2) {
                    log.warn("API history collector unavailable: {}",
                            exception.getClass().getSimpleName());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (attempt < 2 && running) {
                try {
                    Thread.sleep(100L << attempt);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        log.warn("API history batch export exhausted retries; size={}", batch.size());
        return false;
    }

    @Override
    public void close() {
        running = false;
        try {
            worker.join(Math.max(2_000, flushInterval.toMillis() + 1_000));
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(500);
            }
        } catch (InterruptedException exception) {
            worker.interrupt();
            Thread.currentThread().interrupt();
        }
    }
}
