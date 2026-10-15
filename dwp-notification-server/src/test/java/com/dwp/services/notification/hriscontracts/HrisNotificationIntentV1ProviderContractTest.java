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

    @Test
    void admitsTheExactHrm002EmployeeChangeContractPins() {
        HrisNotificationIntentV1 intent = hrm002Intent(11L);

        HrisNotificationContractAdapter.Admission admission =
                HrisNotificationContractAdapter.admit(intent);

        assertThat(admission.effectiveRecipientUserIds()).containsExactly(11L);
        assertThat(intent.producerAppKey()).isEqualTo("hcm");
        assertThat(intent.typeKey()).isEqualTo("HCM.EMPLOYEE_CHANGE_APPLIED");
        assertThat(intent.purposeCode()).isEqualTo("EMPLOYEE_SELF_SERVICE");
        assertThat(intent.reasonCode()).isEqualTo("EMPLOYEE_CHANGE_APPLIED");
        assertThat(intent.templateKey()).isEqualTo("hcm.employee-change.applied");
        assertThat(intent.templateVersion()).isOne();
        assertThat(intent.templateModelDigest()).isEqualTo(
                "24b7eff4c70309f32c86911c7e52acf05ee0b38d368630272422e963109dd784");
        assertThat(intent.classification()).isEqualTo("INTERNAL");
        assertThat(intent.actionRequired()).isFalse();
    }

    static HrisNotificationIntentV1 intent(List<Long> recipients, List<Long> exclusions) {
        return intent(recipients, exclusions, "a".repeat(64), "intent-key-1");
    }

    static HrisNotificationIntentV1 hrm002Intent(long recipient) {
        UUID requestId = UUID.fromString("00000000-0000-4000-8000-000000000081");
        UUID personId = UUID.fromString("00000000-0000-4000-8000-000000000077");
        return new HrisNotificationIntentV1(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                1L,
                "hcm",
                "HCM.EMPLOYEE_CHANGE_APPLIED",
                "EMPLOYEE_SELF_SERVICE",
                List.of(recipient),
                List.of(19L),
                "hris:employee-change:" + requestId,
                "ko-KR",
                "EMPLOYEE_CHANGE_APPLIED",
                null,
                "person:" + personId,
                "employee-change:" + requestId,
                "hcm.employee-change.applied",
                1L,
                "24b7eff4c70309f32c86911c7e52acf05ee0b38d368630272422e963109dd784",
                "INTERNAL",
                false,
                null,
                Instant.parse("2026-09-11T00:00:00Z"),
                "hrm002:00000000-0000-0000-0000-000000000002",
                UUID.fromString("00000000-0000-0000-0000-000000000003"));
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
