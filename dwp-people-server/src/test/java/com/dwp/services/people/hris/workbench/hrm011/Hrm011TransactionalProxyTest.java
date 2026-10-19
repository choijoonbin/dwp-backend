package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.services.people.hris.contracts.hrm011.Hrm011EventOutbox;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractArtifactPort;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractAuthorization;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractCanonicalizer;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractRolloutGate;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractService;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractStore;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractTemplateQueryPort;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class Hrm011TransactionalProxyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(EmploymentContractRolloutGate.class,
                    () -> new EmploymentContractRolloutGate(java.util.Set.of()))
            .withBean(EmploymentContractAuthorization.class,
                    () -> mock(EmploymentContractAuthorization.class))
            .withBean(EmploymentContractStore.class,
                    () -> mock(EmploymentContractStore.class))
            .withBean(EmploymentContractTemplateQueryPort.class,
                    () -> mock(EmploymentContractTemplateQueryPort.class))
            .withBean(EmploymentContractArtifactPort.class,
                    () -> mock(EmploymentContractArtifactPort.class))
            .withBean(EmploymentContractCanonicalizer.class,
                    () -> mock(EmploymentContractCanonicalizer.class))
            .withBean(HcmHighRiskCommandGuard.class,
                    () -> mock(HcmHighRiskCommandGuard.class))
            .withBean(Hrm011EventOutbox.class,
                    () -> mock(Hrm011EventOutbox.class))
            .withBean(Hrm011QueryAuthority.class,
                    () -> mock(Hrm011QueryAuthority.class))
            .withBean(Hrm011QuerySource.class,
                    () -> mock(Hrm011QuerySource.class))
            .withBean(Hrm011CursorCodec.class,
                    () -> mock(Hrm011CursorCodec.class))
            .withBean(ObjectMapper.class,
                    () -> new ObjectMapper().findAndRegisterModules())
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(TransactionConfiguration.class)
            .withPropertyValues(
                    "dwp.hris.contract-compensation-workbench.enabled=true");

    @Test
    void commandAndReadServiceAreCglibTransactionalProxiesWithExactModes()
            throws Exception {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertTransactionalProxy(context.getBean(EmploymentContractService.class));
            assertTransactionalProxy(context.getBean(Hrm011IaEntryQueryService.class));
        });
        Transactional command = EmploymentContractService.class.getDeclaredMethod(
                "issue", com.dwp.services.people.hris.employeeservice.employmentcontract
                        .EmploymentContractDtos.IssueRequest.class,
                String.class, String.class, long.class, java.util.UUID.class,
                String.class, com.dwp.services.people.security.HcmStepUpHeaders.class)
                .getAnnotation(Transactional.class);
        Transactional query = Hrm011IaEntryQueryService.class.getDeclaredMethod(
                "query", Hrm011QueryModels.QueryRequest.class)
                .getAnnotation(Transactional.class);
        assertThat(command.readOnly()).isFalse();
        assertThat(query.readOnly()).isTrue();
    }

    private void assertTransactionalProxy(Object service) {
        assertThat(AopUtils.isCglibProxy(service)).isTrue();
        assertThat(service).isInstanceOf(Advised.class);
        assertThat(((Advised) service).getAdvisors())
                .anySatisfy(advisor -> assertThat(advisor.getAdvice())
                        .isInstanceOf(TransactionInterceptor.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({EmploymentContractService.class, Hrm011IaEntryQueryService.class})
    static class TransactionConfiguration {
    }
}
