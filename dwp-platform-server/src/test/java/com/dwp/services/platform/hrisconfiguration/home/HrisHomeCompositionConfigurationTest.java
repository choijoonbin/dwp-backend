package com.dwp.services.platform.hrisconfiguration.home;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HrisHomeCompositionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                    ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(HrisHomeCompositionConfiguration.class);

    @Test
    void compositionCoreIsAbsentWhenTheFlagIsMissingOrFalse() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(HrisHomeCompositionService.class);
            assertThat(context).doesNotHaveBean(HrisHomeCompositionCoordinator.class);
        });

        contextRunner
                .withPropertyValues("dwp.hris.home-composition.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HrisHomeCompositionService.class);
                    assertThat(context).doesNotHaveBean(HrisHomeCompositionCoordinator.class);
                });
    }

    @Test
    void explicitEnablementWithoutEveryControlledPortFailsClosed() {
        contextRunner
                .withPropertyValues("dwp.hris.home-composition.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void explicitEnablementRegistersOnlyWhenAllControlledPortsExist() {
        contextRunner
                .withPropertyValues("dwp.hris.home-composition.enabled=true")
                .withBean(HrisHomeCompositionPorts.CandidateSource.class,
                        () -> query -> List.of())
                .withBean(HrisHomeCompositionPorts.OwnerMaterializationClient.class,
                        () -> request -> null)
                .withBean(HrisHomeCompositionPorts.DeepLinkPolicy.class,
                        () -> (authority, owner, deepLink) -> null)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HrisHomeCompositionService.class);
                    assertThat(context).hasSingleBean(HrisHomeCompositionCoordinator.class);
                    assertThat(context).hasSingleBean(HrisHomeCompositionRuntimeControls.class);
                });
    }
}
