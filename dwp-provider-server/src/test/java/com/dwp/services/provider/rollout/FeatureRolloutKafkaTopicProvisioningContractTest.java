package com.dwp.services.provider.rollout;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureRolloutKafkaTopicProvisioningContractTest {

    @Test
    void provisionsTheExactSharedTopicWhenBrokerAutoCreationIsDisabled()
            throws Exception {
        Path root = repositoryRoot();
        String compose = Files.readString(root.resolve("docker-compose.yml"));
        String kafkaInit = kafkaInit(compose);
        String normalized = kafkaInit.replaceAll("\\s+", " ");

        assertThat(compose).contains("KAFKA_AUTO_CREATE_TOPICS_ENABLE: \"false\"");
        assertThat(occurrences(kafkaInit,
                "--topic " + KafkaFeatureRolloutDecisionEventPublisher.DEFAULT_TOPIC))
                .isEqualTo(1);
        assertThat(normalized).contains(
                "--topic " + KafkaFeatureRolloutDecisionEventPublisher.DEFAULT_TOPIC
                        + " --partitions 12 --replication-factor 1"
                        + " --config cleanup.policy=delete"
                        + " --config min.insync.replicas=1");
    }

    @Test
    void providerAndGatewayDefaultsUseTheProvisionedTopic() throws Exception {
        Path root = repositoryRoot();
        String topic = KafkaFeatureRolloutDecisionEventPublisher.DEFAULT_TOPIC;
        String provider = Files.readString(
                root.resolve("dwp-provider-server/src/main/resources/application.yml"));
        String gateway = Files.readString(
                root.resolve("dwp-gateway/src/main/resources/application.yml"));

        assertThat(provider).contains(
                "${DWP_PRODUCT_SURFACE_ROLLOUT_TOPIC:" + topic + "}");
        assertThat(gateway).contains(
                "${DWP_PRODUCT_SURFACE_ROLLOUT_INVALIDATION_TOPIC:" + topic + "}");
    }

    private Path repositoryRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("docker-compose.yml"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IOException("Cannot locate repository docker-compose.yml");
        }
        return candidate;
    }

    private String kafkaInit(String compose) {
        int start = compose.indexOf("  kafka-init:");
        int end = compose.indexOf("\nvolumes:", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return compose.substring(start, end);
    }

    private long occurrences(String source, String expected) {
        long count = 0;
        int offset = 0;
        while ((offset = source.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }
}
