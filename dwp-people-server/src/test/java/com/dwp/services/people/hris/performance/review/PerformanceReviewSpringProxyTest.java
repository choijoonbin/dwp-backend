package com.dwp.services.people.hris.performance.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PerformanceReviewSpringProxyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(NamedParameterJdbcTemplate.class,
                    () -> mock(NamedParameterJdbcTemplate.class))
            .withBean(ObjectMapper.class,
                    () -> new ObjectMapper().findAndRegisterModules())
            .withBean(PerformanceReviewAuthorityPort.class,
                    () -> mock(PerformanceReviewAuthorityPort.class))
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(ProxyConfiguration.class);

    @Test
    void repositoryAndTransactionalServiceLoadAsSpringProxies() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(AopUtils.isCglibProxy(
                    context.getBean(PerformanceReviewRepository.class))).isTrue();

            PerformanceReviewService service =
                    context.getBean(PerformanceReviewService.class);
            assertThat(AopUtils.isCglibProxy(service)).isTrue();
            assertThat(service).isInstanceOf(Advised.class);
            assertThat(((Advised) service).getAdvisors())
                    .anySatisfy(advisor -> assertThat(advisor.getAdvice())
                            .isInstanceOf(TransactionInterceptor.class));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({
            PersistenceExceptionTranslationPostProcessor.class,
            PerformanceReviewRepository.class,
            PerformanceReviewCanonicalizer.class,
            PerformanceReviewService.class
    })
    static class ProxyConfiguration {
    }
}
