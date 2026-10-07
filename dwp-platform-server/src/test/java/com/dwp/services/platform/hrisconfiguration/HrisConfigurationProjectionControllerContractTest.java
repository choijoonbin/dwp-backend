package com.dwp.services.platform.hrisconfiguration;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class HrisConfigurationProjectionControllerContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(
                    HrisConfigurationProjectionService.class,
                    () -> mock(HrisConfigurationProjectionService.class))
            .withUserConfiguration(HrisConfigurationProjectionController.class);

    @Test
    void exposesDisplayProjectionOnlyAndLeavesCommandsToOwnerApis() {
        var methods = Arrays.asList(
                HrisConfigurationProjectionController.class.getDeclaredMethods());

        assertThat(methods).anyMatch(method -> method.isAnnotationPresent(GetMapping.class));
        assertThat(methods).noneMatch(method ->
                method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class));
    }

    @Test
    void waveOneEndpointBeanIsAbsentWhenFlagIsMissingOrFalse() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(HrisConfigurationProjectionController.class);
        });

        contextRunner
                .withPropertyValues("dwp.hris.system.wave1.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(HrisConfigurationProjectionController.class);
                });
    }

    @Test
    void explicitTrueRegistersWaveOneEndpointBean() {
        contextRunner
                .withPropertyValues("dwp.hris.system.wave1.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HrisConfigurationProjectionController.class);
                });
    }

    @Test
    void acceptsOnlyTheAbsentLegacyViewOrExactSystemView() {
        HrisConfigurationProjectionController.requireSupportedView(null);
        HrisConfigurationProjectionController.requireSupportedView("system");

        assertThatThrownBy(
                () -> HrisConfigurationProjectionController.requireSupportedView("personal"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Unsupported HRIS configuration projection view");
    }
}
