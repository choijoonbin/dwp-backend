package com.dwp.gateway.config;

import com.dwp.observability.api.ApiHistoryPublisher;
import com.dwp.observability.api.HttpApiHistoryPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

@Configuration
public class ApiHistoryGatewayConfiguration {

    @Bean(destroyMethod = "close")
    ApiHistoryPublisher gatewayApiHistoryPublisher(
            ObjectMapper objectMapper,
            @Value("${dwp.observability.api-history.enabled:true}") boolean enabled,
            @Value("${dwp.observability.api-history.collector-url:}") String collectorUrl,
            @Value("${dwp.observability.api-history.ingest-token:}") String ingestToken,
            @Value("${spring.application.name:dwp-gateway}") String serviceName,
            @Value("${dwp.observability.api-history.queue-capacity:4096}") int queueCapacity,
            @Value("${dwp.observability.api-history.batch-size:100}") int batchSize,
            @Value("${dwp.observability.api-history.flush-interval:PT1S}") Duration flushInterval) {
        if (!enabled || collectorUrl.isBlank() || ingestToken.isBlank()) {
            return ApiHistoryPublisher.NOOP;
        }
        return new HttpApiHistoryPublisher(
                URI.create(collectorUrl.trim()),
                ingestToken.trim(),
                serviceName,
                objectMapper,
                queueCapacity,
                batchSize,
                flushInterval);
    }
}
