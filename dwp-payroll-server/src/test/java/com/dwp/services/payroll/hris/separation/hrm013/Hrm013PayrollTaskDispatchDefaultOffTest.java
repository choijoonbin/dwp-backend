package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class Hrm013PayrollTaskDispatchDefaultOffTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Slice.class);
    private final ApplicationContextRunner controllerContext = new ApplicationContextRunner()
            .withBean(Hrm013PayrollTaskDispatchService.class,
                    () -> mock(Hrm013PayrollTaskDispatchService.class))
            .withUserConfiguration(ControllerSlice.class);

    @Test
    void everyRuntimeBeanIsAbsentWhenTheFeatureFlagIsMissing() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(Hrm013PayrollTaskDispatchController.class);
            assertThat(result).doesNotHaveBean(Hrm013PayrollTaskDispatchService.class);
            assertThat(result).doesNotHaveBean(Hrm013PayrollTaskDispatchStore.class);
            assertThat(result).doesNotHaveBean(Hrm013PayrollTaskDispatchIngressFilter.class);
            assertThat(result).doesNotHaveBean(AuthHrm013ServiceAuthorityClient.class);
            assertThat(result).doesNotHaveBean(Hrm013PayrollTaskReceiptRelay.class);
            assertThat(result).doesNotHaveBean(Hrm013PayrollWorkloadCredentialProvider.class);
        });
    }

    @Test
    void onlyTheSealedCanonicalFlagCanEnableTheRuntime() {
        controllerContext.withPropertyValues(
                "dwp.hris.payroll.hrm013-task-owner.enabled=true")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).doesNotHaveBean(
                            Hrm013PayrollTaskDispatchController.class);
                });
        controllerContext.withPropertyValues(
                "dwp.payroll.hrm013-task-dispatch.enabled=true")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(
                            Hrm013PayrollTaskDispatchController.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({Hrm013PayrollTaskDispatchController.class,
            Hrm013PayrollTaskDispatchService.class,
            Hrm013PayrollTaskDispatchStore.class,
            Hrm013PayrollTaskDispatchIngressFilter.class,
            AuthHrm013ServiceAuthorityClient.class,
            Hrm013PayrollTaskReceiptRelay.class,
            Hrm013PayrollWorkloadCredentialProvider.class})
    static class Slice { }

    @Configuration(proxyBeanMethods = false)
    @Import(Hrm013PayrollTaskDispatchController.class)
    static class ControllerSlice { }
}
