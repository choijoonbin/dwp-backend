package com.dwp.services.time.workregime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class WorkRegimeRepositoryProxyCompatibilityTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    PersistenceExceptionTranslationAutoConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(
                    JdbcWorkRegimeRepository.class,
                    JdbcWorkRegimeReceiptStore.class,
                    JdbcWorkRegimeTargetPopulationResolver.class)
            .withPropertyValues(
                    "dwp.time.work-regime-api.enabled=true",
                    "spring.aop.proxy-target-class=true");

    @Test
    void enabledRepositoriesSupportBootsClassBasedExceptionTranslationProxies() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(AopUtils.isCglibProxy(
                            context.getBean(JdbcWorkRegimeRepository.class)))
                    .isTrue();
            assertThat(AopUtils.isCglibProxy(
                            context.getBean(JdbcWorkRegimeReceiptStore.class)))
                    .isTrue();
            assertThat(AopUtils.isCglibProxy(
                            context.getBean(JdbcWorkRegimeTargetPopulationResolver.class)))
                    .isTrue();
        });
    }
}
