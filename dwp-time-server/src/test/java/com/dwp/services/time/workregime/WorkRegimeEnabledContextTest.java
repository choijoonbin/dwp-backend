package com.dwp.services.time.workregime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class WorkRegimeEnabledContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    PersistenceExceptionTranslationAutoConfiguration.class))
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(
                    JdbcWorkRegimeRepository.class,
                    JdbcWorkRegimeReceiptStore.class,
                    JdbcWorkRegimeTargetPopulationResolver.class,
                    GatewayVerifiedWorkRegimeOwnerAuthoritySource.class,
                    WorkRegimeApplicationService.class,
                    WorkRegimeOwnerAccessFilter.class,
                    WorkRegimeOwnerController.class)
            .withPropertyValues(
                    "dwp.time.work-regime-api.enabled=true",
                    "dwp.time.product-authorization-enabled=true",
                    "dwp.time.service-token=test-service-token",
                    "spring.aop.proxy-target-class=true");

    @Test
    void enabledOwnerSliceCreatesEveryRuntimeBean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JdbcWorkRegimeRepository.class);
            assertThat(context).hasSingleBean(JdbcWorkRegimeReceiptStore.class);
            assertThat(context).hasSingleBean(JdbcWorkRegimeTargetPopulationResolver.class);
            assertThat(context)
                    .hasSingleBean(GatewayVerifiedWorkRegimeOwnerAuthoritySource.class);
            assertThat(context).hasSingleBean(WorkRegimeApplicationService.class);
            assertThat(context).hasSingleBean(WorkRegimeOwnerAccessFilter.class);
            assertThat(context).hasSingleBean(WorkRegimeOwnerController.class);
        });
    }
}
