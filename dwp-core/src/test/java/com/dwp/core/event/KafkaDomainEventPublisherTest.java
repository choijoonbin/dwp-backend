package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaDomainEventPublisherTest {

    @Test
    void publishesWithAnAggregateStablePartitionKey() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        DomainEventEnvelope event = DomainEventEnvelope.create(
                "dwp-people-server", "com.dwp.people.changed", 1, 7L,
                "person", "42", 1, "correlation", null, null,
                JsonNodeFactory.instance.objectNode().put("personId", 42));
        var publisher = new KafkaDomainEventPublisher(
                kafka, new ObjectMapper().findAndRegisterModules(),
                "dwp.domain-events.v1", Duration.ofSeconds(1));

        publisher.publish(List.of(event));

        assertThat(KafkaDomainEventPublisher.partitionKey(event))
                .isEqualTo("7|dwp-people-server|person|42");
        verify(kafka).send(
                org.mockito.ArgumentMatchers.eq("dwp.domain-events.v1"),
                org.mockito.ArgumentMatchers.eq("7|dwp-people-server|person|42"),
                anyString());
    }
}
