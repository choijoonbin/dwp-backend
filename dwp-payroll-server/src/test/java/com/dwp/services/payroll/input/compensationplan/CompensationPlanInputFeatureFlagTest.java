package com.dwp.services.payroll.input.compensationplan;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationPlanInputFeatureFlagTest {

    @Test
    void consumerRepositoryAndBrokerListenerAreDefaultOffTogether() {
        for (Class<?> type : List.of(
                CompensationPlanInputConsumer.class,
                JdbcCompensationPlanInputRepository.class,
                CompensationPlanInputBrokerConsumer.class,
                CompensationPlanInputKafkaReliabilityConfiguration.class,
                CompensationPlanSnapshotOwnerHttpClient.class)) {
            ConditionalOnProperty condition =
                    type.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition).isNotNull();
            assertThat(condition.name())
                    .containsExactly("dwp.hris.payroll-compensation-plan-input.enabled");
            assertThat(condition.havingValue()).isEqualTo("true");
            assertThat(condition.matchIfMissing()).isFalse();
        }
    }
}
