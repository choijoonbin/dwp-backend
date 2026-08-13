package com.dwp.core.autoconfig;

import com.dwp.core.event.DomainEventConsumerFactory;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventInboxRepository;
import com.dwp.core.event.DomainEventOutboxRelay;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventPublisher;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

/** Shared event-delivery control plane; transport remains fail-closed by default. */
@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnClass(NamedParameterJdbcTemplate.class)
@ConditionalOnBean({NamedParameterJdbcTemplate.class, PlatformTransactionManager.class})
public class CoreDomainEventAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DomainEventContractRegistry domainEventContractRegistry() {
        return new DomainEventContractRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventOutboxRepository domainEventOutboxRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        return new DomainEventOutboxRepository(jdbc, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventInboxRepository domainEventInboxRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        return new DomainEventInboxRepository(jdbc, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventRecorder domainEventRecorder(
            DomainEventOutboxRepository repository,
            DomainEventContractRegistry contracts,
            ObjectMapper objectMapper) {
        return new DomainEventRecorder(repository, contracts, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventConsumerFactory domainEventConsumerFactory(
            DomainEventInboxRepository inbox,
            DomainEventContractRegistry contracts,
            PlatformTransactionManager transactionManager,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${dwp.events.service-instance:${HOSTNAME:local}}") String serviceInstance,
            @Value("${dwp.events.consumer-lease:PT30S}") Duration lease,
            @Value("${dwp.events.maximum-attempts:20}") int maximumAttempts) {
        return new DomainEventConsumerFactory(
                inbox, contracts, transactionManager, serviceName, serviceInstance,
                lease, maximumAttempts);
    }

    @Bean(name = "domainEventPublisher")
    @ConditionalOnMissingBean(name = "domainEventPublisher")
    public DomainEventPublisher domainEventPublisher() {
        return DomainEventPublisher.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventOutboxRelay domainEventOutboxRelay(
            DomainEventOutboxRepository repository,
            DomainEventPublisher publisher,
            @Value("${dwp.events.transport-enabled:false}") boolean enabled,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${dwp.events.service-instance:${HOSTNAME:local}}") String serviceInstance,
            @Value("${dwp.events.batch-size:100}") int batchSize,
            @Value("${dwp.events.publisher-lease-seconds:30}") int leaseSeconds,
            @Value("${dwp.events.maximum-attempts:20}") int maximumAttempts,
            @Value("${dwp.events.poll-interval:PT2S}") Duration pollInterval) {
        return new DomainEventOutboxRelay(
                repository,
                publisher,
                enabled,
                DomainEventOutboxRelay.workerId(serviceName, serviceInstance),
                batchSize,
                leaseSeconds,
                maximumAttempts,
                pollInterval);
    }
}
