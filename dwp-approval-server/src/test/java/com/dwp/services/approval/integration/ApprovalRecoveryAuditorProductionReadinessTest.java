package com.dwp.services.approval.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ApprovalRecoveryAuditorProductionReadinessTest {

    private static final String STRONG_TOKEN =
            "q9RX2nZ7vM4aK8pL5sD1fH6jC3wB0yTU";

    @Test
    void acceptsEnabledProductionOnlyWithExactHardenedConfiguration() {
        var readiness = readiness(
                "production", true, "https://auth.corp.company.com", STRONG_TOKEN);

        assertThatCode(() -> readiness.run(null)).doesNotThrowAnyException();
        assertThat(AuthApprovalRecoveryAuditorResolver.SERVICE_IDENTITY)
                .isEqualTo("dwp-approval-server");
    }

    @Test
    void doesNotActivateOrDemandCredentialsOutsideEnabledProduction() {
        assertThatCode(() -> readiness(
                "local", true, "http://localhost:8001", "").run(null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new ApprovalRecoveryAuditorProductionReadiness(
                "production", false, false, "http://localhost:8001", "",
                10, 86400, 604800).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void governedProductionFailsFastWhenTheRecoveryWorkerIsDisabled() {
        assertThatIllegalStateException()
                .isThrownBy(() -> readiness(
                        "production", false,
                        "https://auth.corp.company.com", STRONG_TOKEN).run(null))
                .withMessageContaining(
                        "assignment must be enabled for governed recovery");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://auth.corp.company.com",
            "https://localhost:8001",
            "https://auth.example.test",
            "https://127.0.0.1",
            "https://user@auth.corp.company.com",
            "https://auth.corp.company.com/base-path",
            "https://auth.corp.company.com?mode=fixture"
    })
    void rejectsNonHttpsFixtureOrNonOriginAuthUrls(String authUrl) {
        assertThatIllegalStateException()
                .isThrownBy(() -> readiness(
                        "prod", true, authUrl, STRONG_TOKEN).run(null))
                .withMessageContaining("non-fixture HTTPS origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short-token",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "change-me-recovery-token-123456789012345",
            "placeholder-recovery-token-1234567890123",
            "test-recovery-token-12345678901234567890",
            "valid-looking-token-with-a-space-1234567 "
    })
    void rejectsWeakOrPlaceholderDedicatedTokens(String token) {
        assertThatIllegalStateException()
                .isThrownBy(() -> readiness(
                        "production", true,
                        "https://auth.corp.company.com", token).run(null))
                .withMessageContaining("strong non-placeholder dedicated secret");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsAnUnboundedProductionAttemptBudget(int maximumAttempts) {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ApprovalRecoveryAuditorProductionReadiness(
                        "production", true, true,
                        "https://auth.corp.company.com", STRONG_TOKEN,
                        maximumAttempts, 86400, 604800).run(null))
                .withMessageContaining("maximum attempts must be between 1 and 100");
    }

    @ParameterizedTest
    @ValueSource(longs = {3599, 604801})
    void rejectsUnsafeInitialProbeCooldowns(long cooldownSeconds) {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ApprovalRecoveryAuditorProductionReadiness(
                        "production", true, true,
                        "https://auth.corp.company.com", STRONG_TOKEN,
                        10, cooldownSeconds, 604800).run(null))
                .withMessageContaining("probe cooldown must be between");
    }

    @Test
    void rejectsAMaximumProbeCooldownBelowTheInitialCooldown() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ApprovalRecoveryAuditorProductionReadiness(
                        "production", true, true,
                        "https://auth.corp.company.com", STRONG_TOKEN,
                        10, 86400, 3600).run(null))
                .withMessageContaining("maximum probe cooldown");
    }

    private ApprovalRecoveryAuditorProductionReadiness readiness(
            String environment,
            boolean enabled,
            String authUrl,
            String token) {
        return new ApprovalRecoveryAuditorProductionReadiness(
                environment, true, enabled, authUrl, token,
                10, 86400, 604800);
    }
}
