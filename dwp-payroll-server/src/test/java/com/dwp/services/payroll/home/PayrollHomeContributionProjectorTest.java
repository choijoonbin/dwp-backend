package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.MASK;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.OMIT;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.VIEW;
import static com.dwp.services.payroll.home.PayrollHomePorts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayrollHomeContributionProjectorTest {

    private static final Instant AS_OF = Instant.parse("2026-10-16T03:00:00Z");
    private final PayrollHomePayloadCodec codec = new PayrollHomePayloadCodec(
            "payroll-home-test-secret-32-bytes-minimum");
    private final PayrollHomeContributionProjector projector =
            new PayrollHomeContributionProjector(
                    codec, Duration.ofMinutes(2), Duration.ofMinutes(10));

    @Test
    void projectsOnlyFinalizedSelfMoneyAndMarksUnavailableOwnerFieldsAsTypedPartialFailure() {
        var value = projector.project(self(decisions(VIEW, VIEW, VIEW, VIEW)), selfSource(9));

        assertThat(value.module()).isEqualTo(HrisHomeContributionMaterializationV1.Module.PAY);
        assertThat(value.state()).isEqualTo(HrisHomeContributionMaterializationV1.State.DEGRADED);
        assertThat(value.data()).isInstanceOfSatisfying(
                HrisHomeContributionMaterializationV1.PayrollHomeData.class, data -> {
                    assertThat(data.payPeriodLabel())
                            .isInstanceOf(HrisHomeContributionMaterializationV1.OmittedField.class);
                    assertThat(data.grossPay())
                            .isEqualTo(new HrisHomeContributionMaterializationV1.ViewedMoney(
                                    VIEW, "1200.5", "KRW"));
                    assertThat(data.netPay())
                            .isEqualTo(new HrisHomeContributionMaterializationV1.ViewedMoney(
                                    VIEW, "1000", "KRW"));
                    assertThat(data.payslipAvailable())
                            .isInstanceOf(HrisHomeContributionMaterializationV1.OmittedField.class);
                });
        assertThat(value.partialFailures()).singleElement()
                .extracting(HrisHomeContributionMaterializationV1.PartialFailure::code)
                .isEqualTo(HrisHomeContributionMaterializationV1.FailureCode.OWNER_UNAVAILABLE);
        assertThat(value.deepLink()).isEqualTo("/hr/pay");
    }

    @Test
    void enforcesProtectedMoneyViewMaskAndOmitWithoutValueLeakage() throws Exception {
        var value = projector.project(self(decisions(OMIT, MASK, OMIT, OMIT)), selfSource(9));
        var data = (HrisHomeContributionMaterializationV1.PayrollHomeData) value.data();

        assertThat(data.grossPay())
                .isEqualTo(new HrisHomeContributionMaterializationV1.MaskedField(
                        MASK, HrisHomeContributionMaterializationV1.MASKED_DISPLAY));
        assertThat(data.netPay())
                .isEqualTo(new HrisHomeContributionMaterializationV1.OmittedField(OMIT));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        assertThat(json).contains("\"maskedDisplay\":\"••••\"")
                .doesNotContain("workerId", "resultLines", "bank", "tax");
    }

    @Test
    void aggregateAudiencesNeverReceiveMoneyEvenWhenFieldPolicyRequestsView() {
        var value = projector.project(legalEntity(decisions(VIEW, VIEW, VIEW, VIEW)),
                new PayrollHomeSourceSnapshot(12, AS_OF, Optional.empty(),
                        Optional.of(new MoneyValue("500000", "KRW")),
                        Optional.of(new MoneyValue("400000", "KRW")), Optional.empty()));
        var data = (HrisHomeContributionMaterializationV1.PayrollHomeData) value.data();

        assertThat(data.grossPay())
                .isEqualTo(new HrisHomeContributionMaterializationV1.OmittedField(OMIT));
        assertThat(data.netPay())
                .isEqualTo(new HrisHomeContributionMaterializationV1.OmittedField(OMIT));
        assertThat(value.state())
                .isEqualTo(HrisHomeContributionMaterializationV1.State.UNAVAILABLE);
    }

    @Test
    void canonicalDigestAndTenantBoundReferenceAreDeterministicAndDetectDrift() {
        var authority = self(decisions(OMIT, VIEW, VIEW, OMIT));
        var first = projector.project(authority, selfSource(9));
        var second = projector.project(authority, selfSource(9));

        assertThat(second.payloadDigest()).isEqualTo(first.payloadDigest());
        assertThat(second.payloadRef()).isEqualTo(first.payloadRef());
        assertThat(codec.matches(first.payloadRef(), authority, 9, first.payloadDigest())).isTrue();
        assertThat(HrisHomeMaterializationDigestV1.matches(
                first, first.payloadDigest())).isTrue();
        assertThat(codec.matches(first.payloadRef(), authority, 10, first.payloadDigest())).isFalse();
        assertThat(codec.matches("href_0000000000000000", authority, 9,
                first.payloadDigest())).isFalse();
    }

    @Test
    void rejectsUnsealedCapabilityAndIncompleteFieldPolicy() {
        assertThatThrownBy(() -> new ResolvedPayrollHomeAuthority(
                7, uuid(1), "hcm.personal.pay-statement.view",
                HrisHomeContributionMaterializationV1.BrowserAudience.SELF,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF,
                4, "a".repeat(64), 6, 8, "payroll-summary", Optional.of(uuid(2)),
                Set.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static ResolvedPayrollHomeAuthority self(
            Map<String, HrisHomeContributionMaterializationV1.Decision> decisions) {
        return new ResolvedPayrollHomeAuthority(
                7, uuid(1), "hcm.operations.payroll-support.view",
                HrisHomeContributionMaterializationV1.BrowserAudience.SELF,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF,
                4, "a".repeat(64), 6, 8, "payroll-summary",
                Optional.of(uuid(2)), Set.of(), decisions);
    }

    static ResolvedPayrollHomeAuthority legalEntity(
            Map<String, HrisHomeContributionMaterializationV1.Decision> decisions) {
        return new ResolvedPayrollHomeAuthority(
                7, uuid(1), "hcm.operations.payroll-support.view",
                HrisHomeContributionMaterializationV1.BrowserAudience.EXECUTIVE,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.LEGAL_ENTITY,
                4, "a".repeat(64), 6, 8, "payroll-summary", Optional.empty(),
                Set.of(new PayrollPopulation(uuid(3), uuid(4))), decisions);
    }

    static Map<String, HrisHomeContributionMaterializationV1.Decision> decisions(
            HrisHomeContributionMaterializationV1.Decision period,
            HrisHomeContributionMaterializationV1.Decision gross,
            HrisHomeContributionMaterializationV1.Decision net,
            HrisHomeContributionMaterializationV1.Decision payslip) {
        return Map.of("payPeriodLabel", period, "grossPay", gross,
                "netPay", net, "payslipAvailable", payslip);
    }

    static PayrollHomeSourceSnapshot selfSource(long version) {
        return new PayrollHomeSourceSnapshot(version, AS_OF, Optional.empty(),
                Optional.of(new MoneyValue("1200.5", "KRW")),
                Optional.of(new MoneyValue("1000", "KRW")), Optional.empty());
    }

    static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }
}
