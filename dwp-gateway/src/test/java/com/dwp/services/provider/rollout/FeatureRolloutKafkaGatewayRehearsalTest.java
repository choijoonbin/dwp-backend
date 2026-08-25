package com.dwp.services.provider.rollout;

import com.dwp.gateway.productsurface.FeatureRolloutDecisionCache;
import com.dwp.gateway.productsurface.FeatureRolloutInvalidationConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Opt-in local transport rehearsal; the deterministic unit contracts always run in CI. */
class FeatureRolloutKafkaGatewayRehearsalTest {

    private static final String ENABLE_ENV = "DWP_RUN_KAFKA_ROLLOUT_REHEARSAL";
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-25T03:00:00Z");

    @Test
    void providerAndGatewayShareTheExactTopicContract() {
        assertThat(FeatureRolloutInvalidationConsumer.TOPIC)
                .isEqualTo(KafkaFeatureRolloutDecisionEventPublisher.DEFAULT_TOPIC)
                .isEqualTo("dwp.feature-rollout.decision.changed.v1");
    }

    @Test
    void relaysOutboxEventThroughKafkaAndInvalidatesGatewayCache() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv(ENABLE_ENV)),
                "Local Kafka rollout rehearsal is opt-in");
        String bootstrap = System.getenv().getOrDefault(
                "DWP_KAFKA_REHEARSAL_BOOTSTRAP_SERVERS", "localhost:9092");
        assertProvisioned(bootstrap);

        String flag = "ux.product-surfaces.hcm.v1";
        UUID eventId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        FeatureRolloutDecisionOutboxRepository.DecisionEvent event =
                new FeatureRolloutDecisionOutboxRepository.DecisionEvent(
                        eventId, null, "ALL", flag, 12L, "PAUSED", 1, OCCURRED_AT);
        FeatureRolloutDecisionOutboxRepository repository =
                mock(FeatureRolloutDecisionOutboxRepository.class);
        when(repository.claim("local-rehearsal", 1, Duration.ofSeconds(30)))
                .thenReturn(List.of(event));

        Map<String, Object> producerProperties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        DefaultKafkaProducerFactory<String, String> producerFactory =
                new DefaultKafkaProducerFactory<>(producerProperties);
        KafkaTemplate<String, String> kafka = new KafkaTemplate<>(producerFactory);
        KafkaFeatureRolloutDecisionEventPublisher publisher =
                new KafkaFeatureRolloutDecisionEventPublisher(
                        kafka,
                        new ObjectMapper().findAndRegisterModules(),
                        KafkaFeatureRolloutDecisionEventPublisher.DEFAULT_TOPIC,
                        Duration.ofSeconds(10));
        FeatureRolloutOutboxRelay relay = new FeatureRolloutOutboxRelay(
                repository,
                publisher,
                Clock.fixed(OCCURRED_AT, ZoneOffset.UTC),
                true,
                "local-rehearsal",
                1,
                3,
                Duration.ofSeconds(30));

        Map<String, Object> consumerProperties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "dwp-rollout-rehearsal-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> transport =
                     new KafkaConsumer<>(consumerProperties)) {
            transport.subscribe(List.of(
                    FeatureRolloutInvalidationConsumer.TOPIC));
            awaitAssignment(transport);

            relay.pollOnce();
            ConsumerRecord<String, String> delivery = awaitEvent(transport, eventId);

            FeatureRolloutDecisionCache cache =
                    new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
            cache.put(42L, new FeatureRolloutDecisionCache.FlagDecision(
                    flag,
                    true,
                    "ROLLOUT_MATCH",
                    "rev-00000000000000000011",
                    "internal",
                    OCCURRED_AT.minusSeconds(1),
                    true));
            assertThat(cache.current(42L, flag)).isPresent();

            FeatureRolloutInvalidationConsumer invalidation =
                    new FeatureRolloutInvalidationConsumer(
                            cache, new ObjectMapper().findAndRegisterModules());
            invalidation.onMessage(delivery.value());

            assertThat(delivery.key()).isEqualTo(flag);
            assertThat(cache.current(42L, flag)).isEmpty();
            verify(repository).markPublished(List.of(eventId));
        } finally {
            producerFactory.destroy();
        }
    }

    private void assertProvisioned(String bootstrap) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            assertThat(admin.listTopics().names().get(Duration.ofSeconds(10).toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS))
                    .contains(KafkaFeatureRolloutDecisionEventPublisher.DEFAULT_TOPIC);
        }
    }

    private void awaitAssignment(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (consumer.assignment().isEmpty() && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(100));
        }
        assertThat(consumer.assignment()).isNotEmpty();
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(consumer::position);
    }

    private ConsumerRecord<String, String> awaitEvent(
            KafkaConsumer<String, String> consumer,
            UUID eventId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        ObjectMapper objectMapper = new ObjectMapper();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                if (eventId.toString().equals(
                        objectMapper.readTree(record.value()).path("eventId").asText())) {
                    return record;
                }
            }
        }
        throw new AssertionError("Rollout invalidation event was not delivered by Kafka");
    }
}
