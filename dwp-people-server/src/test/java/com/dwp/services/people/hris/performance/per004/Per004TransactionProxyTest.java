package com.dwp.services.people.hris.performance.per004;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Per004TransactionProxyTest {
    @Test void concreteOwnerRecoveryAndWorkbenchBeansCanBeClassProxied() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Per004ArtifactProtection protection = mock(Per004ArtifactProtection.class);
        ObjectMapper mapper = new ObjectMapper();
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        Per004OwnerService owner = proxy(new Per004OwnerService(jdbc, mapper,
                new Per004PayloadCodec(mapper), protection,
                empty.getBeanProvider(Per004OwnerService.TemplateProvenanceVerifier.class),
                empty.getBeanProvider(Per004OwnerService.SampleScopeSimulator.class)));
        assertThat(AopUtils.isCglibProxy(owner)).isTrue();
        assertThat(AopUtils.isCglibProxy(proxy(new Per004PreparationRecovery(jdbc,
                empty.getBeanProvider(Per004PreparationRecovery.AuthoritativeCreateLookupPort.class),
                empty.getBeanProvider(Per004PreparationRecovery.LookupEvidenceVerifier.class))))).isTrue();
        assertThat(AopUtils.isCglibProxy(proxy(new Per004WorkbenchQueries(jdbc, protection,
                new Per004PayloadCodec(mapper), mapper,
                empty.getBeanProvider(Per004WorkbenchController.PlatformOwnerProjectionPort.class),
                empty.getBeanProvider(Per004WorkbenchController.TrustedConfigurationScopePort.class),
                empty.getBeanProvider(Per004WorkbenchController.PlatformCreateCandidatePort.class))))).isTrue();
        assertThatThrownBy(() -> owner.prepare(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
