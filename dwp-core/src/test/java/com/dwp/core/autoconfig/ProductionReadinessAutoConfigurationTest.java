package com.dwp.core.autoconfig;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ProductionReadinessAutoConfigurationTest {

    @Test
    void rejectsLocalDefaultsInProduction() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "production")
                .withProperty("spring.application.name", "dwp-auth-server")
                .withProperty("jwt.secret",
                        "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("Production readiness checks failed")
                .withMessageContaining("jwt.secret uses a local default");
    }

    @Test
    void ignoresLocalDevelopment() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "local")
                .withProperty("spring.application.name", "dwp-auth-server");

        new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment)
                .run(null);
    }

    @Test
    void acceptsACompleteAuthProductionConfiguration() throws Exception {
        MockEnvironment environment = productionBase("dwp-auth-server")
                .withProperty("jwt.secret", secret("jwt"))
                .withProperty("dwp.security.session.cookie-secure", "true")
                .withProperty("dwp.identity-sync.token", secret("identity"))
                .withProperty("dwp.provider.provisioning-token", secret("provisioning"))
                .withProperty("dwp.scim.cursor-secret", secret("cursor"))
                .withProperty("dwp.auth.oidc.allow-unlisted-hosts", "false")
                .withProperty("dwp.scim.base-url", "https://identity.example.test/scim/v2")
                .withProperty("sso.callback-url", "https://workspace.example.test/auth/oidc/callback");

        new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment)
                .run(null);
    }

    @Test
    void rejectsEnabledEventsWithoutTheApprovedProductionTransport() {
        MockEnvironment environment = productionBase("dwp-approval-server")
                .withProperty("dwp.approval.service-token", secret("approval"))
                .withProperty("dwp.events.transport-enabled", "true")
                .withProperty("dwp.events.transport", "noop")
                .withProperty("spring.kafka.bootstrap-servers", "localhost:9092");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("dwp.events.transport must be kafka")
                .withMessageContaining("explicit production broker");
    }

    private MockEnvironment productionBase(String service) {
        return new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "production")
                .withProperty("spring.application.name", service)
                .withProperty("otel.sdk.disabled", "false")
                .withProperty("otel.exporter.otlp.endpoint", "http://otel-collector:4318")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("dwp.audit.collector-url", "http://platform:8002/internal/audit/events")
                .withProperty("dwp.audit.ingest-token", secret("audit"))
                .withProperty("dwp.observability.api-history.enabled", "true")
                .withProperty("dwp.observability.api-history.collector-url",
                        "http://platform:8002/internal/observability/api-history")
                .withProperty("dwp.observability.api-history.ingest-token", secret("history"))
                .withProperty("dwp.observability.api-history.privacy-hash-secret", secret("privacy"));
    }

    private String secret(String purpose) {
        return "production-" + purpose + "-secret-at-least-24-characters";
    }
}
