package com.dwp.core.autoconfig;

import com.dwp.core.event.DomainEventPublisher;
import com.dwp.core.event.KafkaDomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;

@AutoConfiguration(after = KafkaAutoConfiguration.class, before = CoreDomainEventAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnBean(KafkaTemplate.class)
@ConditionalOnProperty(name = "dwp.events.transport", havingValue = "kafka")
public class KafkaDomainEventAutoConfiguration {

    @Bean(name = "domainEventPublisher")
    @ConditionalOnMissingBean(name = "domainEventPublisher")
    DomainEventPublisher kafkaDomainEventPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            @Value("${dwp.events.topic:dwp.domain-events.v1}") String topic,
            @Value("${dwp.events.publish-timeout:PT5S}") Duration publishTimeout) {
        return new KafkaDomainEventPublisher(kafka, objectMapper, topic, publishTimeout);
    }
}
