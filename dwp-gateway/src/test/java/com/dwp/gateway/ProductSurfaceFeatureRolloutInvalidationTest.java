package com.dwp.gateway;

import com.dwp.gateway.productsurface.FeatureRolloutDecisionCache;
import com.dwp.gateway.productsurface.FeatureRolloutEvaluationClient;
import com.dwp.gateway.productsurface.FeatureRolloutInvalidationConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceFeatureRolloutInvalidationTest extends ProductSurfaceFeatureRolloutTestSupport {

    @Test
    void kafkaInvalidationListenerIsDisabledByDefaultAndRequiresTheExactOptIn() {
        try (AnnotationConfigApplicationContext disabled = invalidationContext(false)) {
            assertThat(disabled.getBeansOfType(FeatureRolloutInvalidationConsumer.class))
                    .isEmpty();
        }
        try (AnnotationConfigApplicationContext enabled = invalidationContext(true)) {
            assertThat(enabled.getBeansOfType(FeatureRolloutInvalidationConsumer.class))
                    .hasSize(1);
        }
    }

    @Test
    void kafkaConsumerIgnoresUnknownPayloadsAndAppliesTheExactEventSchema() {
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        String flag = FeatureRolloutEvaluationClient.uiFlag("hcm");
        cache.put(7L, decision(flag, true, 3, "eligible-10"));
        FeatureRolloutInvalidationConsumer consumer =
                new FeatureRolloutInvalidationConsumer(cache, new ObjectMapper());

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000001",
                 "tenantScope":"ALL","tenantId":null,
                 "flagKey":"ux.product-surfaces.hcm.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:01Z",
                 "unexpected":"must-not-be-accepted"}
                """);
        assertThat(cache.current(7L, flag)).isPresent();

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000003",
                 "tenantScope":"ALL","tenantId":null,
                 "flagKey":"ux.product-surfaces.hcm.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"UNKNOWN_FUTURE_STATE","occurredAt":"2026-08-24T00:00:01Z"}
                """);
        assertThat(cache.current(7L, flag)).isPresent();

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000004",
                 "eventId":"00000000-0000-0000-0000-000000000005",
                 "tenantScope":"ALL","tenantId":null,
                 "flagKey":"ux.product-surfaces.hcm.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:01Z"}
                """);
        assertThat(cache.current(7L, flag)).isPresent();

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000002",
                 "tenantScope":"ALL","tenantId":null,
                 "flagKey":"ux.product-surfaces.hcm.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:01Z"}
                """);
        assertThat(cache.current(7L, flag)).isEmpty();
    }

    @Test
    void exactInvalidationUsesTheVerifiedAuthTenantLongAndIgnoresOlderRevisions() {
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        String flag = FeatureRolloutEvaluationClient.uiFlag("services");
        cache.put(7L, decision(flag, true, 3, "eligible-10"));
        cache.put(8L, decision(flag, true, 3, "eligible-10"));
        FeatureRolloutInvalidationConsumer consumer =
                new FeatureRolloutInvalidationConsumer(cache, new ObjectMapper());

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000011",
                 "tenantScope":"EXACT","tenantId":7,
                 "flagKey":"ux.product-surfaces.services.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"ROLLED_BACK","occurredAt":"2026-08-24T00:00:01Z"}
                """);

        assertThat(cache.current(7L, flag)).isEmpty();
        assertThat(cache.current(8L, flag)).isPresent();

        cache.put(7L, new FeatureRolloutDecisionCache.FlagDecision(
                flag, true, "ROLLOUT_MATCH", revision(4), "eligible-10",
                T0.plusSeconds(2), true));
        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000012",
                 "tenantScope":"EXACT","tenantId":7,
                 "flagKey":"ux.product-surfaces.services.v1",
                 "opaqueRevision":"rev-00000000000000000003",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:03Z"}
                """);
        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000013",
                 "tenantScope":"EXACT",
                 "tenantId":"00000000-0000-0000-0000-000000000007",
                 "flagKey":"ux.product-surfaces.services.v1",
                 "opaqueRevision":"rev-00000000000000000005",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:04Z"}
                """);

        assertThat(cache.current(7L, flag)).isPresent();
    }

}
