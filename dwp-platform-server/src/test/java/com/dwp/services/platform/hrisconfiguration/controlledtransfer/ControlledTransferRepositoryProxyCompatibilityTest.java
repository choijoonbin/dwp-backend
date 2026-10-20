package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class ControlledTransferRepositoryProxyCompatibilityTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    PersistenceExceptionTranslationAutoConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(
                    ControlledTransferDomainEvents.class,
                    () -> mock(ControlledTransferDomainEvents.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(JdbcControlledTransferRepository.class)
            .withPropertyValues("spring.aop.proxy-target-class=true");

    @Test
    void repositorySupportsBootsClassBasedExceptionTranslationProxy() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(AopUtils.isCglibProxy(
                    context.getBean(JdbcControlledTransferRepository.class)))
                    .isTrue();
        });
    }
}
