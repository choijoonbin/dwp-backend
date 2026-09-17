package com.dwp.services.approval.auditrecords;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApprovalAuditApplicationContextTest {
    @Test
    void createsTheTransactionalFacadeAndProductionController() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            context.registerBean(
                    ApprovalAuditService.class,
                    () -> mock(ApprovalAuditService.class));
            context.registerBean(
                    ApprovalAuditHttpAuthority.class,
                    () -> mock(ApprovalAuditHttpAuthority.class));
            context.registerBean(
                    ApprovalAuditCommandGuard.class,
                    () -> mock(ApprovalAuditCommandGuard.class));
            context.registerBean(ApprovalAuditCommandFacade.class);
            context.registerBean(ApprovalAuditController.class);
            context.refresh();

            assertThat(AopUtils.isAopProxy(
                    context.getBean(ApprovalAuditCommandFacade.class))).isTrue();
            assertThat(context.getBean(ApprovalAuditController.class)).isNotNull();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }
}
