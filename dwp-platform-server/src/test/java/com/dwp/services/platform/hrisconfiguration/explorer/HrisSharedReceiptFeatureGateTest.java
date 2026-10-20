package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HrisSharedReceiptFeatureGateTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(context -> context.getBeanFactory()
                            .setConversionService(new ApplicationConversionService()))
                    .withBean(HrisExplorerService.class,
                            () -> mock(HrisExplorerService.class))
                    .withBean(HrisExplorerOwnerEvidenceAdapter.class,
                            () -> mock(HrisExplorerOwnerEvidenceAdapter.class))
                    .withBean(HrisPlatformReceiptAuthorityAdapter.class,
                            () -> mock(HrisPlatformReceiptAuthorityAdapter.class))
                    .withBean(HcmPlatformPepRegistry.class,
                            () -> mock(HcmPlatformPepRegistry.class))
                    .withBean(RestClient.Builder.class, RestClient::builder)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withUserConfiguration(
                            HrisExplorerController.class,
                            HrisCommandReceiptController.class,
                            HrisExplorerOwnerEvidenceFilter.class,
                            AuthHrisExplorerAuthorityEvidenceQuery.class);

    @Test
    void allFeaturesOffExposeNeitherExplorerNorSharedReceiptBoundary() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(HrisExplorerController.class);
            assertThat(context).doesNotHaveBean(HrisCommandReceiptController.class);
            assertThat(context).doesNotHaveBean(HrisExplorerOwnerEvidenceFilter.class);
            assertThat(context)
                    .doesNotHaveBean(AuthHrisExplorerAuthorityEvidenceQuery.class);
        });
    }

    @Test
    void controlledTransferAloneExposesOnlySharedReceiptBoundaryAndEvidence() {
        contextRunner
                .withPropertyValues(
                        "dwp.hris.explorer.enabled=false",
                        "dwp.hris.controlled-transfer.enabled=true",
                        "dwp.hris.automation-control-plane.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HrisExplorerController.class);
                    assertThat(context)
                            .hasSingleBean(HrisCommandReceiptController.class);
                    assertThat(context)
                            .hasSingleBean(HrisExplorerOwnerEvidenceFilter.class);
                    assertThat(context).hasSingleBean(
                            AuthHrisExplorerAuthorityEvidenceQuery.class);
                });
    }

    @Test
    void explorerAlonePreservesExplorerAndSharedReceiptBoundaries() {
        contextRunner
                .withPropertyValues(
                        "dwp.hris.explorer.enabled=true",
                        "dwp.hris.controlled-transfer.enabled=false",
                        "dwp.hris.automation-control-plane.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HrisExplorerController.class);
                    assertThat(context)
                            .hasSingleBean(HrisCommandReceiptController.class);
                    assertThat(context)
                            .hasSingleBean(HrisExplorerOwnerEvidenceFilter.class);
                    assertThat(context).hasSingleBean(
                            AuthHrisExplorerAuthorityEvidenceQuery.class);
                });
    }

    @Test
    void automationAloneExposesOnlySharedReceiptControllerAndAuthorityQuery() {
        contextRunner
                .withPropertyValues(
                        "dwp.hris.explorer.enabled=false",
                        "dwp.hris.controlled-transfer.enabled=false",
                        "dwp.hris.automation-control-plane.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HrisExplorerController.class);
                    assertThat(context)
                            .hasSingleBean(HrisCommandReceiptController.class);
                    assertThat(context)
                            .doesNotHaveBean(HrisExplorerOwnerEvidenceFilter.class);
                    assertThat(context).hasSingleBean(
                            AuthHrisExplorerAuthorityEvidenceQuery.class);
                });
    }
}
