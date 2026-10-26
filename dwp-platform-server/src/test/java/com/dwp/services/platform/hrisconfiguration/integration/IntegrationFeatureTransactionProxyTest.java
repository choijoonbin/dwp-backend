package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.OperationsExceptionPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IntegrationFeatureTransactionProxyTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(ProxyConfiguration.class)
                    .withPropertyValues(
                            "dwp.hris.integration-control-plane.enabled=true",
                            "dwp.hris.integration-control-plane.cursor-secret="
                                    + "0123456789abcdef0123456789abcdef");

    @Test
    void featureEnabledContextAdvisesAnnotationTransactionalServices() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertTransactionProxy(context.getBean(IntegrationService.class));
            assertTransactionProxy(
                    context.getBean(IntegrationOwnerQueryService.class));

            IntegrationRuntimeService runtime =
                    context.getBean(IntegrationRuntimeService.class);
            assertThat(runtime).isNotNull();
            assertThat(java.lang.reflect.Modifier.isFinal(
                    IntegrationRuntimeService.class.getModifiers())).isFalse();
            assertThat(Arrays.stream(IntegrationRuntimeService.class.getMethods())
                    .filter(method -> method.getDeclaringClass()
                            == IntegrationRuntimeService.class)
                    .map(java.lang.reflect.Method::getName))
                    .contains("dispatch", "reconcile", "retry", "cancel");
        });
    }

    private static void assertTransactionProxy(Object bean) {
        assertThat(AopUtils.isCglibProxy(bean)).isTrue();
        assertThat(bean).isInstanceOf(Advised.class);
        assertThat(Arrays.stream(((Advised) bean).getAdvisors())
                .map(advisor -> advisor.getAdvice())
                .map(Advice.class::cast)
                .anyMatch(TransactionInterceptor.class::isInstance)).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({IntegrationService.class, IntegrationRuntimeService.class,
            IntegrationOwnerQueryService.class, IntegrationCursorCodec.class})
    static class ProxyConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        IntegrationRepository integrationRepository() {
            return mock(IntegrationRepository.class);
        }

        @Bean
        IntegrationExecutionRepository integrationExecutionRepository() {
            return mock(IntegrationExecutionRepository.class);
        }

        @Bean
        OperationsExceptionPort operationsExceptionPort() {
            return mock(OperationsExceptionPort.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }
}
