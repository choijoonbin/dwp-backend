package com.dwp.core.autoconfig;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ProductionReadinessAutoConfigurationTest {

    private static final String PRODUCTION_PRIVATE_KEY = rsaPrivateKeyPem(2048);
    private static final String PRODUCTION_PUBLIC_KEY = rsaPublicKeyPem(2048);
    private static final String FIXTURE_PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAs0T79NDWWfUnO4qfn3rq
            BD7AbkQ8gLIfNNbPIbGhtcYBi+BzEwzmD+znR8zq9URVZgWNgvRLgJC1S6vVSn1I
            APkzPCtyia79hePPwswXo8Zc5P/pQ8y9M88+vfBEM0SBqCRCqXjRNO6o4vH7kQFJ
            rfcsYlzQtX6BWtIuiWVmmINN3FQ4Az7tnO79YwmyYwX6QHUt/p0x0NcpQgJ6qH8I
            0CWp6FAIUsTIjvSzGX2lRdtACSrKesmJYeMpgz3lDEfSB/pA1gHTLsFc6v6Vt+/Z
            5WLMQNF1jFBXR/HmmCWiwoQ1hngNnGmX68mUy1Qg2j+e7HSujuyjIPNQ91BQKhif
            0QIDAQAB
            -----END PUBLIC KEY-----""";

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
    void rejectsLocalPilotActivationInProductionReadiness() {
        MockEnvironment environment = completeAuthProduction()
                .withProperty(
                        "dwp.product-authorization.local-pilot-activation.enabled", "true");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("local-pilot-activation.enabled must be false");
    }

    @Test
    void rejectsLocalSeedFlywayLocationInProductionReadiness() {
        MockEnvironment environment = completeAuthProduction()
                .withProperty(
                        "spring.flyway.locations",
                        "classpath:db/migration,classpath:db/local-seed");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("Flyway locations must not contain db/local-seed");
    }

    @Test
    void acceptsACompleteAuthProductionConfiguration() throws Exception {
        MockEnvironment environment = productionBase("dwp-auth-server")
                .withProperty("jwt.secret", secret("jwt"))
                .withProperty("dwp.auth.product-surface-token", secret("product-surface"))
                .withProperty("dwp.auth.approval-recovery-token", secret("approval-recovery"))
                .withProperty("dwp.security.session.cookie-secure", "true")
                .withProperty("dwp.identity-sync.token", secret("identity"))
                .withProperty("dwp.provider.provisioning-token", secret("provisioning"))
                .withProperty("dwp.scim.cursor-secret", secret("cursor"))
                .withProperty("dwp.auth.oidc.allow-unlisted-hosts", "false")
                .withProperty("dwp.auth.step-up.private-key-pem", PRODUCTION_PRIVATE_KEY)
                .withProperty("dwp.auth.step-up.issuer",
                        "https://auth.corp.example.com/product-surface-step-up")
                .withProperty("dwp.auth.step-up.key-id", "prod-stepup-2026-08")
                .withProperty("dwp.auth.step-up.required-acr", "urn:dwp:acr:mfa")
                .withProperty("dwp.auth.step-up.allowed-audiences",
                        "dwp-approval-server,dwp-people-server")
                .withProperty("dwp.auth.step-up.maximum-authentication-age-seconds", "600")
                .withProperty("dwp.auth.step-up.challenge-ttl-seconds", "900")
                .withProperty("dwp.auth.step-up.assurance-clock-skew-seconds", "30")
                .withProperty("dwp.auth.oidc.allowed-hosts", "idp.corp.example.com")
                .withProperty("dwp.auth.oidc.allowed-callback-hosts",
                        "workspace.corp.example.com")
                .withProperty("dwp.scim.base-url", "https://identity.corp.example.com/scim/v2")
                .withProperty("sso.callback-url",
                        "https://workspace.corp.example.com/auth/oidc/callback");

        new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment)
                .run(null);
    }

    @Test
    void rejectsMalformedOrWeakAuthStepUpSignerConfiguration() {
        MockEnvironment environment = completeAuthProduction()
                .withProperty("dwp.auth.step-up.private-key-pem", rsaPrivateKeyPem(1024))
                .withProperty("dwp.auth.step-up.key-id", "fixture-step-up-key");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("at least 2048 bits")
                .withMessageContaining("non-fixture key identifier");
    }

    @Test
    void rejectsMissingWeakOrPlaceholderApprovalRecoveryTokensInProduction() {
        for (String token : new String[] {
                "",
                "too-short",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "change-me-approval-recovery-token-at-least-24-characters",
                "fixture-approval-recovery-token-at-least-24-characters",
                "dummy-approval-recovery-token-12345678901234567890",
                "valid-looking-token-with-a-space-1234567 ",
                "valid-looking-token-with-a-control-1234567\u0001",
                "q9RX2nZ7vM4aK8pL5sD1fH6jC3wB0yTU".repeat(17)
        }) {
            MockEnvironment environment = completeAuthProduction()
                    .withProperty("dwp.auth.approval-recovery-token", token);

            var runner = new ProductionReadinessAutoConfiguration()
                    .dwpProductionReadinessGuard(environment);

            assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                    .withMessageContaining("dwp.auth.approval-recovery-token");
        }

        MockEnvironment prodAlias = completeAuthProduction()
                .withProperty("DWP_ENVIRONMENT", "prod")
                .withProperty("dwp.auth.approval-recovery-token", "");
        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(prodAlias);
        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("dwp.auth.approval-recovery-token");
    }

    @Test
    void acceptsApprovalRecoveryTokensAtBothProductionLengthBoundaries()
            throws Exception {
        String strong32 = "q9RX2nZ7vM4aK8pL5sD1fH6jC3wB0yTU";
        for (String token : new String[] {strong32, strong32.repeat(16)}) {
            MockEnvironment environment = completeAuthProduction()
                    .withProperty("dwp.auth.approval-recovery-token", token);

            new ProductionReadinessAutoConfiguration()
                    .dwpProductionReadinessGuard(environment)
                    .run(null);
        }
    }

    @Test
    void rejectsIncompleteAssuranceAndCallbackProviderConfiguration() {
        MockEnvironment environment = completeAuthProduction()
                .withProperty("dwp.auth.step-up.required-acr", "")
                .withProperty("dwp.auth.step-up.allowed-audiences", "*")
                .withProperty("dwp.auth.step-up.challenge-ttl-seconds", "0")
                .withProperty("dwp.auth.oidc.allowed-hosts", "idp.example.test")
                .withProperty("dwp.auth.oidc.allowed-callback-hosts", "other.example.com");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("required-acr")
                .withMessageContaining("allowed-audiences")
                .withMessageContaining("challenge-ttl-seconds")
                .withMessageContaining("allowed-hosts")
                .withMessageContaining("allowed exact OIDC callback");
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

    @Test
    void acceptsACompleteApprovalStepUpVerifierConfiguration() throws Exception {
        MockEnvironment environment = completeApprovalProduction();

        new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment)
                .run(null);
    }

    @Test
    void rejectsIncompleteOrFixtureApprovalStepUpConfiguration() {
        MockEnvironment environment = completeApprovalProduction()
                .withProperty("dwp.approval.product-authorization-v2-enabled", "false")
                .withProperty("dwp.approval.step-up.public-key-pem", FIXTURE_PUBLIC_KEY)
                .withProperty("dwp.approval.step-up.issuer", "http://localhost:8001")
                .withProperty("dwp.approval.step-up.audience", "*")
                .withProperty("dwp.approval.step-up.key-id", "test-key")
                .withProperty("dwp.approval.step-up.required-acr", "")
                .withProperty("dwp.approval.step-up.maximum-authentication-age-seconds", "0")
                .withProperty("dwp.approval.step-up.maximum-challenge-ttl-seconds", "901");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("product-authorization-v2-enabled")
                .withMessageContaining("contract-test fixture key")
                .withMessageContaining("non-fixture HTTPS issuer URI")
                .withMessageContaining("dwp.approval.step-up.audience must be dwp-approval-server")
                .withMessageContaining("non-fixture key identifier")
                .withMessageContaining("exact non-fixture ACR")
                .withMessageContaining("maximum-authentication-age-seconds")
                .withMessageContaining("maximum-challenge-ttl-seconds");
    }

    @Test
    void acceptsAProviderWithTheExactProductionRolloutPipeline() throws Exception {
        MockEnvironment environment = productionBase("dwp-provider-server")
                .withProperty("dwp.provider.service-token", secret("provider"))
                .withProperty("dwp.provider.provisioning-token", secret("provisioning"))
                .withProperty("dwp.provider.support-validation-token", secret("support"))
                .withProperty("dwp.provider.support-cookie-secure", "true")
                .withProperty("dwp.provider.product-surface-rollout.relay-enabled", "true")
                .withProperty("dwp.provider.product-surface-rollout.publisher-enabled", "true")
                .withProperty("dwp.provider.product-surface-rollout.topic",
                        "dwp.feature-rollout.decision.changed.v1")
                .withProperty("spring.kafka.bootstrap-servers", "kafka:9092");

        new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment)
                .run(null);
    }

    @Test
    void rejectsAProviderWithoutTheImmediateRolloutPipeline() {
        MockEnvironment environment = productionBase("dwp-provider-server")
                .withProperty("dwp.provider.service-token", secret("provider"))
                .withProperty("dwp.provider.provisioning-token", secret("provisioning"))
                .withProperty("dwp.provider.support-validation-token", secret("support"))
                .withProperty("dwp.provider.support-cookie-secure", "true")
                .withProperty("dwp.provider.product-surface-rollout.relay-enabled", "false")
                .withProperty("dwp.provider.product-surface-rollout.publisher-enabled", "false")
                .withProperty("dwp.provider.product-surface-rollout.topic",
                        "wrong.topic")
                .withProperty("spring.kafka.bootstrap-servers", "localhost:9092");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining("relay-enabled")
                .withMessageContaining("publisher-enabled")
                .withMessageContaining("product-surface-rollout.topic")
                .withMessageContaining("explicit production broker");
    }

    @Test
    void acceptsAPlatformWithTheApprovalAuthorizationLatchEnabled() throws Exception {
        new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(completePlatformProduction())
                .run(null);
    }

    @Test
    void rejectsAPlatformWithoutTheApprovalAuthorizationLatch() {
        MockEnvironment environment = completePlatformProduction()
                .withProperty("dwp.platform.product-authorization-approvals-v2-enabled", "false");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining(
                        "dwp.platform.product-authorization-approvals-v2-enabled must be true");
    }

    @Test
    void acceptsAPlatformThatCollectsTelemetryWithRetentionMaintenance() throws Exception {
        MockEnvironment environment = completePlatformProduction()
                .withProperty(
                        "dwp.platform.product-surface-telemetry.collection-enabled", "true")
                .withProperty(
                        "dwp.platform.product-surface-telemetry.maintenance-enabled", "true");

        new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment)
                .run(null);
    }

    @Test
    void rejectsAPlatformThatCollectsTelemetryWithoutRetentionMaintenance() {
        MockEnvironment environment = completePlatformProduction()
                .withProperty(
                        "dwp.platform.product-surface-telemetry.collection-enabled", "true")
                .withProperty(
                        "dwp.platform.product-surface-telemetry.maintenance-enabled", "false");

        var runner = new ProductionReadinessAutoConfiguration()
                .dwpProductionReadinessGuard(environment);

        assertThatIllegalStateException().isThrownBy(() -> runner.run(null))
                .withMessageContaining(
                        "dwp.platform.product-surface-telemetry.maintenance-enabled must be true")
                .withMessageContaining(
                        "dwp.platform.product-surface-telemetry.collection-enabled is true");
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

    private MockEnvironment completeAuthProduction() {
        return productionBase("dwp-auth-server")
                .withProperty("jwt.secret", secret("jwt"))
                .withProperty("dwp.auth.product-surface-token", secret("product-surface"))
                .withProperty("dwp.auth.approval-recovery-token", secret("approval-recovery"))
                .withProperty("dwp.security.session.cookie-secure", "true")
                .withProperty("dwp.identity-sync.token", secret("identity"))
                .withProperty("dwp.provider.provisioning-token", secret("provisioning"))
                .withProperty("dwp.scim.cursor-secret", secret("cursor"))
                .withProperty("dwp.auth.oidc.allow-unlisted-hosts", "false")
                .withProperty("dwp.auth.step-up.private-key-pem", PRODUCTION_PRIVATE_KEY)
                .withProperty("dwp.auth.step-up.issuer",
                        "https://auth.corp.example.com/product-surface-step-up")
                .withProperty("dwp.auth.step-up.key-id", "prod-stepup-2026-08")
                .withProperty("dwp.auth.step-up.required-acr", "urn:dwp:acr:mfa")
                .withProperty("dwp.auth.step-up.allowed-audiences",
                        "dwp-approval-server,dwp-people-server")
                .withProperty("dwp.auth.step-up.maximum-authentication-age-seconds", "600")
                .withProperty("dwp.auth.step-up.challenge-ttl-seconds", "900")
                .withProperty("dwp.auth.step-up.assurance-clock-skew-seconds", "30")
                .withProperty("dwp.auth.oidc.allowed-hosts", "idp.corp.example.com")
                .withProperty("dwp.auth.oidc.allowed-callback-hosts",
                        "workspace.corp.example.com")
                .withProperty("dwp.scim.base-url", "https://identity.corp.example.com/scim/v2")
                .withProperty("sso.callback-url",
                        "https://workspace.corp.example.com/auth/oidc/callback");
    }

    private MockEnvironment completeApprovalProduction() {
        return productionBase("dwp-approval-server")
                .withProperty("dwp.approval.service-token", secret("approval"))
                .withProperty("dwp.approval.runtime-service-token", secret("approval-runtime"))
                .withProperty("dwp.approval.product-authorization-v2-enabled", "true")
                .withProperty("dwp.approval.step-up.public-key-pem", PRODUCTION_PUBLIC_KEY)
                .withProperty("dwp.approval.step-up.issuer",
                        "https://auth.corp.example.com/product-surface-step-up")
                .withProperty("dwp.approval.step-up.audience", "dwp-approval-server")
                .withProperty("dwp.approval.step-up.key-id", "prod-stepup-2026-08")
                .withProperty("dwp.approval.step-up.required-acr", "urn:dwp:acr:mfa")
                .withProperty("dwp.approval.step-up.maximum-authentication-age-seconds", "600")
                .withProperty("dwp.approval.step-up.maximum-challenge-ttl-seconds", "900");
    }

    private MockEnvironment completePlatformProduction() {
        return productionBase("dwp-platform-server")
                .withProperty("dwp.platform.service-token", secret("platform"))
                .withProperty("dwp.platform.runtime-service-token", secret("platform-runtime"))
                .withProperty("dwp.identity-sync.token", secret("identity"))
                .withProperty("dwp.platform.api-history.cursor-secret", secret("history-cursor"))
                .withProperty("dwp.platform.audit.integrity-secret", secret("audit-integrity"))
                .withProperty("dwp.platform.productivity.data-key", secret("productivity-data"))
                .withProperty("dwp.platform.product-authorization-approvals-v2-enabled", "true")
                .withProperty(
                        "dwp.platform.product-surface-telemetry.collection-enabled", "false")
                .withProperty(
                        "dwp.platform.product-surface-telemetry.maintenance-enabled", "true");
    }

    private String secret(String purpose) {
        return "production-" + purpose + "-secret-at-least-24-characters";
    }

    private static String rsaPrivateKeyPem(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                    + "\n-----END PRIVATE KEY-----";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String rsaPublicKeyPem(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            byte[] encoded = generator.generateKeyPair().getPublic().getEncoded();
            return "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                    + "\n-----END PUBLIC KEY-----";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
