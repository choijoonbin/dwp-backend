package com.dwp.core.autoconfig;

import com.dwp.audit.AuditEventPublisher;
import com.dwp.audit.HttpAuditEventPublisher;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.audit.AuditOutboxRelay;
import com.dwp.core.audit.AuditOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.net.URI;
import java.time.Duration;

/** Configures the transactional audit outbox for database-backed DWP services. */
@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnClass(NamedParameterJdbcTemplate.class)
@ConditionalOnBean(NamedParameterJdbcTemplate.class)
public class CoreAuditAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CoreAuditAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AuditOutboxRepository auditOutboxRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        return new AuditOutboxRepository(jdbc, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditOutboxRecorder auditOutboxRecorder(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${dwp.audit.service-instance:${HOSTNAME:local}}") String serviceInstance,
            @Value("${dwp.audit.environment:${DWP_ENVIRONMENT:local}}") String environment) {
        return new AuditOutboxRecorder(
                jdbc, objectMapper, serviceName, serviceInstance, environment);
    }

    @Bean(name = "auditEventPublisher")
    @ConditionalOnMissingBean(name = "auditEventPublisher")
    public AuditEventPublisher auditEventPublisher(
            ObjectMapper objectMapper,
            @Value("${dwp.audit.collector-url:}") String collectorUrl,
            @Value("${dwp.audit.ingest-token:}") String ingestToken,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${dwp.audit.request-timeout:PT3S}") Duration requestTimeout) {
        if (collectorUrl.isBlank() || ingestToken.isBlank()) {
            log.info("DWP audit relay transport is disabled or not configured");
            return AuditEventPublisher.NOOP;
        }
        return new HttpAuditEventPublisher(
                URI.create(collectorUrl.trim()), ingestToken.trim(), serviceName,
                objectMapper, requestTimeout);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditOutboxRelay auditOutboxRelay(
            AuditOutboxRepository repository,
            AuditEventPublisher publisher,
            @Value("${dwp.audit.collector-url:}") String collectorUrl,
            @Value("${dwp.audit.ingest-token:}") String ingestToken,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${dwp.audit.service-instance:${HOSTNAME:local}}") String serviceInstance,
            @Value("${dwp.audit.batch-size:100}") int batchSize,
            @Value("${dwp.audit.lease-seconds:30}") int leaseSeconds,
            @Value("${dwp.audit.maximum-attempts:20}") int maximumAttempts,
            @Value("${dwp.audit.poll-interval:PT2S}") Duration pollInterval,
            @Value("${dwp.audit.outbox-retention-days:7}") int outboxRetentionDays) {
        return new AuditOutboxRelay(
                repository,
                publisher,
                !collectorUrl.isBlank() && !ingestToken.isBlank(),
                AuditOutboxRelay.workerId(serviceName, serviceInstance),
                batchSize,
                leaseSeconds,
                maximumAttempts,
                pollInterval,
                outboxRetentionDays);
    }
}
