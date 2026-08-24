package com.dwp.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class GatewayProductionReadinessConfigurationTest {

    @Test
    void rejectsDevelopmentCorsAndUnencryptedRedisInProduction() {
        MockEnvironment environment = validProduction()
                .withProperty("CORS_ALLOWED_ORIGIN_PATTERN", "http://localhost:*")
                .withProperty("spring.data.redis.ssl.enabled", "false");

        var runner = new GatewayProductionReadinessConfiguration()
                .gatewayProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("CORS_ALLOWED_ORIGIN_PATTERN")
                .withMessageContaining("spring.data.redis.ssl.enabled");
    }

    @Test
    void acceptsAnExplicitProductionConfiguration() throws Exception {
        new GatewayProductionReadinessConfiguration()
                .gatewayProductionReadinessGuard(validProduction())
                .run(null);
    }

    @Test
    void rejectsAProductionGatewayWithoutImmediateRolloutInvalidation() {
        MockEnvironment environment = validProduction()
                .withProperty(
                        "dwp.gateway.product-surface-rollout.invalidation-enabled", "false");

        var runner = new GatewayProductionReadinessConfiguration()
                .gatewayProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining(
                        "dwp.gateway.product-surface-rollout.invalidation-enabled");
    }

    private MockEnvironment validProduction() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "production")
                .withProperty("spring.data.redis.password", secret("redis"))
                .withProperty("spring.data.redis.ssl.enabled", "true")
                .withProperty("spring.kafka.bootstrap-servers", "kafka:9092")
                .withProperty(
                        "dwp.gateway.product-surface-rollout.invalidation-enabled", "true")
                .withProperty(
                        "dwp.gateway.product-surface-rollout.invalidation-topic",
                        "dwp.feature-rollout.decision.changed.v1")
                .withProperty("dwp.agent.service-token", secret("agent"))
                .withProperty("dwp.auth.product-surface-token", secret("product-surface"))
                .withProperty("dwp.platform.service-token", secret("platform"))
                .withProperty("dwp.people.service-token", secret("people"))
                .withProperty("dwp.provider.service-token", secret("provider"))
                .withProperty("dwp.provider.support-validation-token", secret("support"))
                .withProperty("dwp.approval.service-token", secret("approval"))
                .withProperty("dwp.space.service-token", secret("space"))
                .withProperty("dwp.notification.service-token", secret("notification"))
                .withProperty("dwp.observability.api-history.enabled", "true")
                .withProperty("dwp.observability.api-history.ingest-token", secret("history"))
                .withProperty("dwp.observability.api-history.privacy-hash-secret", secret("privacy"))
                .withProperty("dwp.observability.api-history.collector-url",
                        "http://platform:8002/internal/observability/api-history")
                .withProperty("otel.sdk.disabled", "false")
                .withProperty("otel.exporter.otlp.endpoint", "http://otel-collector:4318")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("CORS_ALLOWED_ORIGIN_PATTERN", "https://workspace.example.test")
                .withProperty("CORS_ALLOWED_LOOPBACK_PATTERN", "https://workspace.example.test");
        return environment;
    }

    private String secret(String purpose) {
        return "production-" + purpose + "-secret-at-least-24-characters";
    }
}
