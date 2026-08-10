package com.dwp.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Synchronous transport used by the durable outbox relay. */
public final class HttpAuditEventPublisher implements AuditEventPublisher {

    public static final String INGEST_TOKEN_HEADER = "X-DWP-Audit-Token";
    public static final String SERVICE_NAME_HEADER = "X-DWP-Audit-Service";

    private static final Logger log = LoggerFactory.getLogger(HttpAuditEventPublisher.class);

    private final URI collectorUri;
    private final String ingestToken;
    private final String serviceName;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public HttpAuditEventPublisher(
            URI collectorUri,
            String ingestToken,
            String serviceName,
            ObjectMapper objectMapper,
            Duration requestTimeout) {
        this.collectorUri = collectorUri;
        this.ingestToken = ingestToken;
        this.serviceName = serviceName;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
    }

    @Override
    public DeliveryResult publish(List<AuditEvent> events) {
        if (events == null || events.isEmpty()) return DeliveryResult.ACCEPTED;
        try {
            HttpRequest request = HttpRequest.newBuilder(collectorUri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header(INGEST_TOKEN_HEADER, ingestToken)
                    .header(SERVICE_NAME_HEADER, serviceName)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(events)))
                    .build();
            HttpResponse<Void> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return DeliveryResult.ACCEPTED;
            }
            log.warn("Audit collector rejected batch; service={} status={} size={}",
                    serviceName, response.statusCode(), events.size());
            return response.statusCode() >= 400 && response.statusCode() < 500
                    ? DeliveryResult.REJECTED
                    : DeliveryResult.RETRYABLE_FAILURE;
        } catch (JsonProcessingException exception) {
            log.error("Audit event serialization failed; service={} size={}",
                    serviceName, events.size(), exception);
            return DeliveryResult.REJECTED;
        } catch (IOException exception) {
            log.warn("Audit collector is unavailable; service={} size={} error={}",
                    serviceName, events.size(), exception.getClass().getSimpleName());
            return DeliveryResult.RETRYABLE_FAILURE;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeliveryResult.RETRYABLE_FAILURE;
        }
    }
}
