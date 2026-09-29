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
                    PayrollFoundationSecurityFilter.class)
            .withBean(PayrollFoundationService.class,
                    () -> mock(PayrollFoundationService.class))
            .withBean(PayrollFoundationAccessPolicyProvider.class,
                    () -> tenantId -> PayrollFoundationAccess.compatibilityPolicy())
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
                });
    }
}
