package com.dwp.services.people.hris.workbench.hrm007;

import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class Hrm007DefaultOffTest {

    private final ApplicationContextRunner serviceContext = new ApplicationContextRunner()
            .withBean(Hrm007RolloutGate.class, () -> mock(Hrm007RolloutGate.class))
            .withBean(Hrm007Projector.class, () -> mock(Hrm007Projector.class))
            .withBean(Hrm007Authority.class, () -> mock(Hrm007Authority.class))
            .withBean(Hrm007ProjectionSource.class,
                    () -> mock(Hrm007ProjectionSource.class))
            .withBean(Hrm007CursorCodec.class, () -> mock(Hrm007CursorCodec.class))
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(TransactionConfiguration.class);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void everyRuntimeBeanIsDefaultOff() throws Exception {
        assertThat(Hrm007RolloutGate.FEATURE)
                .isEqualTo("HRIS_HRM_APPOINTMENT_WORKBENCH");
        List<Class<?>> beans = List.of(
                Hrm007RolloutGate.class,
                Hrm007Authority.class,
                Hrm007CursorCodec.class,
                Hrm007Projector.class,
                JdbcHrm007ProjectionSource.class,
                Hrm007IaEntryQueryService.class,
                Hrm007IaEntryQueryController.class,
                Hrm007ProblemAdvice.class);

        for (Class<?> bean : beans) {
            ConditionalOnProperty condition = bean.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition).as(bean.getSimpleName()).isNotNull();
            assertThat(condition.prefix()).isEqualTo(Hrm007RolloutGate.PREFIX);
            assertThat(condition.name()).containsExactly("enabled");
            assertThat(condition.havingValue()).isEqualTo("true");
            assertThat(condition.matchIfMissing()).isFalse();
        }
        Transactional transaction = Hrm007IaEntryQueryService.class
                .getDeclaredMethod(
                        "query", Hrm007QueryDefinition.class,
                        Hrm007Dtos.QueryRequest.class)
                .getAnnotation(Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
    }

    @Test
    void emptyEnrollmentStopsBeforeProjectionAuthorityCursorAndSource() {
        PeopleRequestContext.set(
                7L, 41L,
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                Set.of(), Set.of());
        Hrm007Projector projector = mock(Hrm007Projector.class);
        Hrm007Authority authority = mock(Hrm007Authority.class);
        Hrm007ProjectionSource source = mock(Hrm007ProjectionSource.class);
        Hrm007CursorCodec cursors = mock(Hrm007CursorCodec.class);
        Hrm007IaEntryQueryService service = new Hrm007IaEntryQueryService(
                new Hrm007RolloutGate(""), projector, authority, source, cursors,
                Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.query(
                Hrm007QueryDefinition.PEOPLE_CHANGES, null))
                .isInstanceOf(Hrm007Exception.class);

        verifyNoInteractions(projector, authority, source, cursors);
    }

    @Test
    void serviceIsAbsentByDefaultAndTransactionalWhenEnabled() {
        serviceContext.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(Hrm007IaEntryQueryService.class);
        });
        serviceContext.withPropertyValues("dwp.hris.hrm007.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Hrm007IaEntryQueryService service =
                            context.getBean(Hrm007IaEntryQueryService.class);
                    assertThat(AopUtils.isAopProxy(service)).isTrue();
                    assertThat(service).isInstanceOf(Advised.class);
                    assertThat(((Advised) service).getAdvisors())
                            .anySatisfy(advisor -> assertThat(advisor.getAdvice())
                                    .isInstanceOf(TransactionInterceptor.class));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(Hrm007IaEntryQueryService.class)
    static class TransactionConfiguration {
    }
}
