package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge.EmployeeChangeApprovalRequestOutbox;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EmployeeChangeServiceProxyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(EmployeeChangeRepository.class,
                    () -> mock(EmployeeChangeRepository.class))
            .withBean(EmployeeChangeCanonicalizer.class,
                    () -> mock(EmployeeChangeCanonicalizer.class))
            .withBean(EmployeeChangePatchProtector.class,
                    () -> mock(EmployeeChangePatchProtector.class))
            .withBean(EmployeeChangeAuthority.class,
                    () -> mock(EmployeeChangeAuthority.class))
            .withBean(EmployeeChangeWorkbenchProjector.class,
                    () -> mock(EmployeeChangeWorkbenchProjector.class))
            .withBean(HcmPopulationScopeService.class,
                    () -> mock(HcmPopulationScopeService.class))
            .withBean(HcmPopulationRepository.class,
                    () -> mock(HcmPopulationRepository.class))
            .withBean(HcmHighRiskCommandGuard.class,
                    () -> mock(HcmHighRiskCommandGuard.class))
            .withBean(EmployeeChangeApprovalRequestOutbox.class,
                    () -> mock(EmployeeChangeApprovalRequestOutbox.class))
            .withBean(ObjectMapper.class,
                    () -> new ObjectMapper().findAndRegisterModules())
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(TransactionConfiguration.class);

    @Test
    void featureIsOffByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EmployeeChangeService.class);
        });
    }

    @Test
    void enabledServiceLoadsAsTransactionalSpringProxy() {
        runner.withPropertyValues("dwp.hris.employee-change.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EmployeeChangeService service = context.getBean(EmployeeChangeService.class);
                    assertThat(AopUtils.isAopProxy(service)).isTrue();
                    assertThat(service).isInstanceOf(Advised.class);
                    assertThat(((Advised) service).getAdvisors())
                            .anySatisfy(advisor -> assertThat(advisor.getAdvice())
                                    .isInstanceOf(TransactionInterceptor.class));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(EmployeeChangeService.class)
    static class TransactionConfiguration {
    }
}
