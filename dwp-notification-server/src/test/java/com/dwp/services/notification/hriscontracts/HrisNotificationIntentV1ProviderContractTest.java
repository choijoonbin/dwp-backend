package com.dwp.services.notification.hriscontracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dwp.platform.contracts.hris.generated.HrisNotificationIntentV1;
import org.junit.jupiter.api.Test;

class HrisNotificationIntentV1ProviderContractTest {

    @Test
    void admitsOnlyThePrivacyMinimizedEffectiveRecipientSet() {
        HrisNotificationIntentV1 intent = intent(List.of(11L, 12L), List.of(12L));

        HrisNotificationContractAdapter.Admission admission =
                HrisNotificationContractAdapter.admit(intent);

        assertThat(admission.effectiveRecipientUserIds()).containsExactly(11L);
        assertThat(admission.intent()).isSameAs(intent);
        assertThat(admission.idempotencyBindingDigest()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> intent(List.of(11L), List.of(11L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minus excludedUserIds");
        assertThatThrownBy(() -> new HrisNotificationContractAdapter.Admission(
                intent, List.of(12L), admission.idempotencyBindingDigest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly match");
        assertThatThrownBy(() -> new HrisNotificationContractAdapter.Admission(
                intent, List.of(11L), "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bind the admitted intent exactly");
        assertThat(HrisNotificationContractAdapter.admit(intent(
                List.of(11L, 12L), List.of(12L))).idempotencyBindingDigest())
                .isEqualTo(admission.idempotencyBindingDigest());
        assertThat(HrisNotificationContractAdapter.admit(intent(
                List.of(11L, 12L), List.of(12L), "b".repeat(64), "intent-key-1"))
                .idempotencyBindingDigest()).isNotEqualTo(admission.idempotencyBindingDigest());
        assertThat(HrisNotificationContractAdapter.admit(intent(
                List.of(11L, 12L), List.of(12L), "a".repeat(64), "intent-key-2"))
                .idempotencyBindingDigest()).isNotEqualTo(admission.idempotencyBindingDigest());
    }

    static HrisNotificationIntentV1 intent(List<Long> recipients, List<Long> exclusions) {
        return intent(recipients, exclusions, "a".repeat(64), "intent-key-1");
    }

    private static HrisNotificationIntentV1 intent(
            List<Long> recipients,
            List<Long> exclusions,
            String templateModelDigest,
            String idempotencyKey) {
        return new HrisNotificationIntentV1(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                1L,
                "hris.payroll",
                "PAYROLL_READY",
                "PAYROLL_NOTICE",
                recipients,
                exclusions,
                null,
                "ko-KR",
                "PAYROLL_CLOSED",
                "actor:1",
                "worker:11",
                null,
                "payroll.closed",
                1L,
                templateModelDigest,
                "CONFIDENTIAL",
                true,
                Instant.parse("2026-09-12T00:00:00Z"),
                Instant.parse("2026-09-11T00:00:00Z"),
                idempotencyKey,
                UUID.fromString("00000000-0000-0000-0000-000000000003"));
    }
}
