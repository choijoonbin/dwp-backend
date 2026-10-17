package com.dwp.services.payroll.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PayrollFoundationSettingsRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    PayrollFoundationSettingsController.class,
                    PayrollFoundationSettingsSecurityFilter.class,
                    PayrollFoundationSettingsV48PepRegistry.class)
            .withBean(PayrollFoundationService.class,
                    () -> mock(PayrollFoundationService.class))
            .withBean(PayrollFoundationAccessPolicyProvider.class,
                    () -> tenantId -> PayrollFoundationAccess.compatibilityPolicy())
            .withBean(PayrollLegalEntityScopeResolver.class,
                    () -> subject -> new PayrollLegalEntityScopeResolver.Resolution(
                            "settings-registration-r1",
                            Set.of(UUID.fromString(
                                    "10000000-0000-0000-0000-000000000001"))))
            .withBean(ObjectMapper.class,
                    () -> JsonMapper.builder().findAndAddModules().build())
            .withPropertyValues("dwp.payroll.service-token=verified-test-token");

    @Test
    void settingsExposureIsDefaultOffEvenWhenFoundationBusinessSliceIsEnabled() {
        contextRunner
                .withPropertyValues("dwp.hris.payroll-foundation.wave1.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PayrollFoundationSettingsController.class);
                    assertThat(context).doesNotHaveBean(PayrollFoundationSettingsSecurityFilter.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollFoundationSettingsV48PepRegistry.class);
                });
    }

    @Test
    void bothExplicitFlagsRegisterTheSettingsAdapterAndPinnedPep() {
        contextRunner
                .withPropertyValues(
                        "dwp.hris.payroll-foundation.wave1.enabled=true",
                        "dwp.hris.payroll-foundation.settings.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PayrollFoundationSettingsController.class);
                    assertThat(context).hasSingleBean(
                            PayrollFoundationSettingsSecurityFilter.class);
                    assertThat(context).hasSingleBean(
                            PayrollFoundationSettingsV48PepRegistry.class);
                });
    }
}
