package com.dwp.services.messaging.operations;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class MessagingProductionReadinessGuardTest {

    @Test
    void localEnvironmentDoesNotRequireProductionProviders() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "local");

        assertThatNoException().isThrownBy(() -> guard(environment).run(null));
    }

    @Test
    void productionRejectsLocalOrIncompleteProviderConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "production")
                .withProperty("dwp.messaging.attachments.storage", "local")
                .withProperty("dwp.messaging.attachments.scanner", "local")
                .withProperty("dwp.messaging.attachments.s3.bucket", "")
                .withProperty("dwp.messaging.attachments.clamav-host", "localhost")
                .withProperty("dwp.messaging.presence.enabled", "false")
                .withProperty("dwp.messaging.realtime.redis-enabled", "false")
                .withProperty("spring.data.redis.host", "127.0.0.1")
                .withProperty("spring.data.redis.password", "")
                .withProperty("spring.data.redis.ssl.enabled", "false")
                .withProperty("dwp.messaging.retention.mode", "ENFORCING")
                .withProperty("dwp.messaging.retention.hold-authority-ready", "false")
                .withProperty("dwp.messaging.retention.downstream-lifecycle-ready", "false");

        assertThatIllegalStateException()
                .isThrownBy(() -> guard(environment).run(null))
                .withMessageContaining("dwp.messaging.attachments.storage")
                .withMessageContaining("dwp.messaging.attachments.scanner")
                .withMessageContaining("dwp.messaging.attachments.s3.bucket")
                .withMessageContaining("dwp.messaging.attachments.s3.region")
                .withMessageContaining("dwp.messaging.attachments.clamav-host")
                .withMessageContaining("dwp.messaging.presence.enabled")
                .withMessageContaining("dwp.messaging.realtime.redis-enabled")
                .withMessageContaining("spring.data.redis.host")
                .withMessageContaining("spring.data.redis.password")
                .withMessageContaining("spring.data.redis.ssl.enabled")
                .withMessageContaining("dwp.messaging.retention.hold-authority-ready")
                .withMessageContaining("dwp.messaging.retention.downstream-lifecycle-ready");
    }

    @Test
    void activeProductionProfileAppliesTheGuardWithoutAnEnvironmentFlag() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "local");
        environment.setActiveProfiles("production");

        assertThatIllegalStateException()
                .isThrownBy(() -> guard(environment).run(null))
                .withMessageContaining("dwp.messaging.attachments.storage")
                .withMessageContaining("spring.data.redis.host");
    }

    @Test
    void conflictingEnvironmentMarkersStillApplyTheProductionGuard() {
        for (String[] markers : new String[][] {
                {"local", "production"}, {"prod", "local"}
        }) {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("dwp.environment", markers[0])
                    .withProperty("DWP_ENVIRONMENT", markers[1]);

            assertThatIllegalStateException()
                    .isThrownBy(() -> guard(environment).run(null))
                    .withMessageContaining("dwp.messaging.attachments.storage");
        }
    }

    @Test
    void productionRejectsIpLiteralProviderHosts() {
        MockEnvironment environment = completeProduction("production")
                .withProperty("dwp.messaging.attachments.clamav-host", "10.20.30.40")
                .withProperty("spring.data.redis.host", "2001:db8::10")
                .withProperty("dwp.messaging.retention.mode", "DRY_RUN");

        assertThatIllegalStateException()
                .isThrownBy(() -> guard(environment).run(null))
                .withMessageContaining("dwp.messaging.attachments.clamav-host")
                .withMessageContaining("spring.data.redis.host")
                .withMessageContaining("production DNS host");
    }

    @Test
    void productionRejectsWeakWhitespaceAndPlaceholderRedisPasswords() {
        for (String password : new String[] {
                "a",
                " password-with-leading-space-and-adequate-length-2026",
                "${REDIS_PASSWORD}",
                "password",
                "changeme",
                "changeit",
                "redis-password",
                "your-redis-password",
                "provided-by-secret-manager",
                "local-redis-secret-that-is-long-enough-2026",
                "test-redis-secret-that-is-long-enough-2026",
                "example-redis-secret-that-is-long-enough-2026",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }) {
            MockEnvironment environment = completeProduction("production")
                    .withProperty("spring.data.redis.password", password)
                    .withProperty("dwp.messaging.retention.mode", "DRY_RUN");

            assertThatIllegalStateException()
                    .isThrownBy(() -> guard(environment).run(null))
                    .withMessageContaining("spring.data.redis.password")
                    .withMessageContaining("strong non-placeholder production secret");
        }
    }

    @Test
    void productionRejectsInvalidS3RegionAndPathStyleAccess() {
        MockEnvironment environment = completeProduction("production")
                .withProperty("dwp.messaging.attachments.s3.region", "local")
                .withProperty("dwp.messaging.attachments.s3.path-style-access", "true")
                .withProperty("dwp.messaging.retention.mode", "DRY_RUN");

        assertThatIllegalStateException()
                .isThrownBy(() -> guard(environment).run(null))
                .withMessageContaining("dwp.messaging.attachments.s3.region")
                .withMessageContaining("dwp.messaging.attachments.s3.path-style-access");
    }

    @Test
    void productionRejectsInsecureOrPrivateS3Endpoints() {
        for (String endpoint : new String[] {
                "http://s3.messaging.example.com",
                "https://localhost",
                "https://127.0.0.1",
                "https://10.20.30.40",
                "https://172.16.10.20",
                "https://192.168.10.20",
                "https://169.254.10.20",
                "https://[::1]",
                "https://[fd00::10]",
                "https://[fe80::10]",
                "https://minio.storage.internal",
                "https://s3.messaging.example.com"
        }) {
            MockEnvironment environment = completeProduction("production")
                    .withProperty("dwp.messaging.attachments.s3.endpoint", endpoint)
                    .withProperty("dwp.messaging.retention.mode", "DRY_RUN");

            assertThatIllegalStateException()
                    .isThrownBy(() -> guard(environment).run(null))
                    .withMessageContaining("dwp.messaging.attachments.s3.endpoint");
        }
    }

    @Test
    void productionRejectsNonPublicDnsAndNumericS3EndpointBypasses() {
        for (String endpoint : new String[] {
                "https://minio",
                "https://2130706433",
                "https://0x7f000001",
                "https://127.1",
                "https://0177.0.0.1",
                "https://minio.c",
                "https://minio.default.svc",
                "https://minio.storage.test",
                "https://minio.storage.example",
                "https://minio.home.arpa",
                "https://minio.hidden.onion",
                "https://minio.messaging.mail",
                "https://s3.vendor.a123",
                "https://s3.vendor.ab-1"
        }) {
            MockEnvironment environment = completeProduction("production")
                    .withProperty("dwp.messaging.attachments.s3.endpoint", endpoint)
                    .withProperty("dwp.messaging.retention.mode", "DRY_RUN");

            assertThatIllegalStateException()
                    .isThrownBy(() -> guard(environment).run(null))
                    .withMessageContaining("dwp.messaging.attachments.s3.endpoint");
        }
    }

    @Test
    void productionAllowsAwsDefaultOrPublicHttpsS3Endpoints() {
        MockEnvironment awsDefault = completeProduction("production")
                .withProperty("dwp.messaging.retention.mode", "DRY_RUN");
        MockEnvironment explicit = completeProduction("production")
                .withProperty(
                        "dwp.messaging.attachments.s3.endpoint",
                        "https://s3.ap-northeast-2.amazonaws.com")
                .withProperty("dwp.messaging.retention.mode", "DRY_RUN");
        MockEnvironment internationalized = completeProduction("production")
                .withProperty(
                        "dwp.messaging.attachments.s3.endpoint", "https://s3.xn--p1ai")
                .withProperty("dwp.messaging.retention.mode", "DRY_RUN");

        assertThatNoException().isThrownBy(() -> guard(awsDefault).run(null));
        assertThatNoException().isThrownBy(() -> guard(explicit).run(null));
        assertThatNoException().isThrownBy(() -> guard(internationalized).run(null));
    }

    @Test
    void prodAliasAcceptsCompleteProviderConfiguration() {
        MockEnvironment environment = completeProduction("prod")
                .withProperty("dwp.messaging.retention.mode", "ENFORCING")
                .withProperty("dwp.messaging.retention.hold-authority-ready", "true")
                .withProperty("dwp.messaging.retention.downstream-lifecycle-ready", "true");

        assertThatNoException().isThrownBy(() -> guard(environment).run(null));
    }

    @Test
    void dryRunDoesNotRequireExternalRetentionCertifications() {
        MockEnvironment environment = completeProduction("production")
                .withProperty("dwp.messaging.retention.mode", "DRY_RUN")
                .withProperty("dwp.messaging.retention.hold-authority-ready", "false")
                .withProperty("dwp.messaging.retention.downstream-lifecycle-ready", "false");

        assertThatNoException().isThrownBy(() -> guard(environment).run(null));
    }

    private MockEnvironment completeProduction(String environmentName) {
        return new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", environmentName)
                .withProperty("dwp.messaging.attachments.storage", "s3")
                .withProperty("dwp.messaging.attachments.scanner", "clamav")
                .withProperty("dwp.messaging.attachments.s3.bucket", "dwp-messaging-prod")
                .withProperty("dwp.messaging.attachments.s3.region", "ap-northeast-2")
                .withProperty("dwp.messaging.attachments.s3.endpoint", "")
                .withProperty("dwp.messaging.attachments.s3.path-style-access", "false")
                .withProperty(
                        "dwp.messaging.attachments.clamav-host", "clamav.messaging.example.com")
                .withProperty("dwp.messaging.presence.enabled", "true")
                .withProperty("dwp.messaging.realtime.redis-enabled", "true")
                .withProperty("spring.data.redis.host", "redis.messaging.example.com")
                .withProperty(
                        "spring.data.redis.password", "R9x!RedisProd_2026-Secure#K7mQ2vLp")
                .withProperty("spring.data.redis.ssl.enabled", "true");
    }

    private MessagingProductionReadinessGuard guard(MockEnvironment environment) {
        return new MessagingProductionReadinessGuard(environment);
    }
}
