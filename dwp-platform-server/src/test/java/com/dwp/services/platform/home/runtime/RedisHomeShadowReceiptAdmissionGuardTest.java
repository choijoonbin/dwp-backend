package com.dwp.services.platform.home.runtime;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

class RedisHomeShadowReceiptAdmissionGuardTest {

    private static final String SECRET =
            "wave6-test-only-shared-privacy-secret-32-bytes";

    @Test
    void admitsAtomicallyAcrossReplicasWithPrivacyBoundsAndFailSafeOutage() {
        try (GenericContainer<?> redis = new GenericContainer<>(
                DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)) {
            redis.start();
            RedisClient client = RedisClient.create("redis://" + redis.getHost()
                    + ":" + redis.getMappedPort(6379));
            StatefulRedisConnection<String, String> firstConnection = client.connect();
            StatefulRedisConnection<String, String> secondConnection = client.connect();
            try {
                RedisHomeShadowReceiptAdmissionGuard first = guard(firstConnection, 2, 2, 2);
                RedisHomeShadowReceiptAdmissionGuard second = guard(secondConnection, 2, 2, 2);
                HomeShadowReceiptController.ShadowReceiptRequest match = request(
                        HomeReadModelShadowComparator.ShadowOutcome.MATCH,
                        Set.of(HomeReadModelShadowComparator.ShadowReason.MATCH), 0);

                assertThat(first.admit(71, 82, "decision-revision-private", match))
                        .isEqualTo(HomeShadowReceiptAdmissionGuard.Admission.ADMITTED);
                assertThat(second.admit(71, 82, "decision-revision-private", match))
                        .isEqualTo(HomeShadowReceiptAdmissionGuard.Admission.DUPLICATE);
                assertThat(second.admit(71, 82, "decision-revision-private", request(
                        HomeReadModelShadowComparator.ShadowOutcome.MISMATCH,
                        Set.of(HomeReadModelShadowComparator.ShadowReason.AUTHORITY), 1)))
                        .isEqualTo(HomeShadowReceiptAdmissionGuard.Admission.ADMITTED);
                assertThat(first.admit(71, 82, "decision-revision-private", request(
                        HomeReadModelShadowComparator.ShadowOutcome.MISMATCH,
                        Set.of(HomeReadModelShadowComparator.ShadowReason.STRUCTURE), 2)))
                        .isEqualTo(HomeShadowReceiptAdmissionGuard.Admission.RATE_LIMITED);

                List<String> keys = firstConnection.sync().keys("*");
                String stored = String.join("|", keys)
                        + firstConnection.sync().zrange(
                        "dwp:platform:home-shadow-admission:v1:{home-shadow}:receipts",
                        0, -1)
                        + firstConnection.sync().zrange(
                        "dwp:platform:home-shadow-admission:v1:{home-shadow}:recipients",
                        0, -1);
                assertThat(stored)
                        .doesNotContain("decision-revision-private")
                        .doesNotContain("|71|82|");

                firstConnection.sync().flushall();
                RedisHomeShadowReceiptAdmissionGuard bounded = guard(firstConnection, 5, 1, 5);
                assertThat(bounded.admit(71, 82, "decision-a", match))
                        .isEqualTo(HomeShadowReceiptAdmissionGuard.Admission.ADMITTED);
                assertThat(bounded.admit(71, 83, "decision-b", match))
                        .isEqualTo(HomeShadowReceiptAdmissionGuard.Admission.CAPACITY_REJECTED);

                secondConnection.close();
                assertThat(second.admit(71, 84, "decision-c", match))
                        .isEqualTo(HomeShadowReceiptAdmissionGuard.Admission.UNAVAILABLE);
            } finally {
                if (firstConnection.isOpen()) firstConnection.close();
                if (secondConnection.isOpen()) secondConnection.close();
                client.shutdown();
            }
        }
    }

    private RedisHomeShadowReceiptAdmissionGuard guard(
            StatefulRedisConnection<String, String> connection,
            int maxReceipts,
            int maxRecipients,
            int maxRate) {
        return new RedisHomeShadowReceiptAdmissionGuard(
                connection.sync(), SECRET, maxReceipts, maxRecipients, maxRate,
                Duration.ofMinutes(10), Duration.ofMinutes(1));
    }

    private HomeShadowReceiptController.ShadowReceiptRequest request(
            HomeReadModelShadowComparator.ShadowOutcome outcome,
            Set<HomeReadModelShadowComparator.ShadowReason> reasons,
            int mismatchCount) {
        return new HomeShadowReceiptController.ShadowReceiptRequest(
                1, outcome, reasons, mismatchCount, "CLASSIC", "DESKTOP_STANDARD",
                "SHADOW_COMPARE", "CONTROL", "decision-revision-private");
    }
}
