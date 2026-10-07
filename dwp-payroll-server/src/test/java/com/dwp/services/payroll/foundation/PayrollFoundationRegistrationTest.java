package com.dwp.services.payroll.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PayrollFoundationRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    PayrollFoundationController.class,
                    PayrollFoundationSecurityFilter.class,
                    GatewayVerifiedPayrollFoundationAccessPolicyProvider.class,
                    UnavailablePayrollFoundationAccessPolicyProvider.class)
            .withBean(PayrollFoundationService.class,
                    () -> mock(PayrollFoundationService.class))
            .withBean(PayrollLegalEntityScopeResolver.class,
                    () -> subject -> new PayrollLegalEntityScopeResolver.Resolution(
                            "registration-test-r1",
                            java.util.Set.of(java.util.UUID.fromString(
                                    "10000000-0000-0000-0000-000000000001"))))
            .withBean(ObjectMapper.class,
                    () -> JsonMapper.builder().findAndAddModules().build())
            .withPropertyValues("dwp.payroll.service-token=verified-test-token");

    @Test
    void waveOneHttpSurfaceIsDefaultOff() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PayrollFoundationController.class);
            assertThat(context).doesNotHaveBean(PayrollFoundationSecurityFilter.class);
        });
    }

    @Test
    void explicitWaveOneFlagRegistersControllerAndVerifiedBoundary() {
        contextRunner
                .withPropertyValues("dwp.hris.payroll-foundation.wave1.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PayrollFoundationController.class);
                    assertThat(context).hasSingleBean(PayrollFoundationSecurityFilter.class);
                    assertThat(context).hasSingleBean(
                            PayrollFoundationAccessPolicyProvider.class);
                    assertThat(context.getBean(PayrollFoundationAccessPolicyProvider.class))
                            .isInstanceOf(
                                    GatewayVerifiedPayrollFoundationAccessPolicyProvider.class);
                });
    }
}
