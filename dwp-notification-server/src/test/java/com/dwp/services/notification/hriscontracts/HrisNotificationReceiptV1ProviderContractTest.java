package com.dwp.services.notification.hriscontracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dwp.platform.contracts.hris.generated.HrisNotificationReceiptV1;
import org.junit.jupiter.api.Test;

class HrisNotificationReceiptV1ProviderContractTest {

    @Test
    void bindsReceiptToExactIntentTenantEventCorrelationAndRecipient() {
        HrisNotificationContractAdapter.Admission admission =
                HrisNotificationContractAdapter.admit(
                        HrisNotificationIntentV1ProviderContractTest.intent(
                                List.of(11L), List.of()));
        HrisNotificationReceiptV1 receipt = receipt(11L);

        assertThat(HrisNotificationContractAdapter.bindReceipt(admission, receipt))
                .isSameAs(receipt);
        assertThatThrownBy(() -> HrisNotificationContractAdapter.bindReceipt(
                admission, receipt(12L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
        assertThatThrownBy(() -> HrisNotificationContractAdapter.bindReceipt(
                admission, receipt(11L, 2L, Instant.parse("2026-09-11T00:01:00Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
        assertThatThrownBy(() -> HrisNotificationContractAdapter.bindReceipt(
                admission, receipt(11L, 1L, Instant.parse("2026-09-10T23:59:59Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("predates");
    }

    @Test
    void bindsAnInAppReceiptToTheExactHrm002Intent() {
        HrisNotificationContractAdapter.Admission admission =
                HrisNotificationContractAdapter.admit(
                        HrisNotificationIntentV1ProviderContractTest.hrm002Intent(11L));
        HrisNotificationReceiptV1 receipt = new HrisNotificationReceiptV1(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                1L,
                11L,
                1L,
                "DELIVERED",
                "IN_APP",
                "POLICY_ALLOWED",
                "EMPLOYEE_CHANGE_APPLIED",
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                1L,
                1L,
                "a".repeat(64),
                null,
                0,
                Instant.parse("2026-09-11T00:01:00Z"),
                null,
                false,
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000003"));

        assertThat(HrisNotificationContractAdapter.bindReceipt(admission, receipt))
                .isSameAs(receipt);
        assertThat(receipt.channel()).isEqualTo("IN_APP");
        assertThat(receipt.templateVersion()).isOne();
    }

    private static HrisNotificationReceiptV1 receipt(long recipient) {
        return receipt(recipient, 1L, Instant.parse("2026-09-11T00:01:00Z"));
    }

    private static HrisNotificationReceiptV1 receipt(
            long recipient,
            long templateVersion,
            Instant occurredAt) {
        return new HrisNotificationReceiptV1(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                1L,
                recipient,
                1L,
                "DELIVERED",
                "EMAIL",
                "POLICY_ALLOWED",
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                1L,
                templateVersion,
                "a".repeat(64),
                "b".repeat(64),
                1,
                occurredAt,
                null,
                false,
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000003"));
    }
}
