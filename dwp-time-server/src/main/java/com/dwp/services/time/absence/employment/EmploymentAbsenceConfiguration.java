package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventInboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Default-off TIM-003 runtime assembly; no broker, route or browser surface is mounted. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.hris.time.employment-absence.enabled",
        havingValue = "true",
        matchIfMissing = false)
class EmploymentAbsenceConfiguration {

    @Bean
    EmploymentAbsenceActivationGate employmentAbsenceActivationGate(
            @Value("${dwp.hris.time.employment-absence.enabled:false}") boolean enabled,
            @Value("${dwp.hris.time.employment-absence.enrolled-tenants:}")
                    String enrolledTenants) {
        return new EmploymentAbsenceActivationGate(enabled, enrolledTenants);
    }

    @Bean
    EmploymentAbsenceProjectionStore employmentAbsenceProjectionStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        return new JdbcEmploymentAbsenceProjectionStore(jdbc, mapper);
    }

    @Bean
    EmploymentAbsenceAuditSink employmentAbsenceAuditSink(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        return new JdbcEmploymentAbsenceAuditSink(jdbc, mapper);
    }

    @Bean
    EmploymentAbsenceInboxSource employmentAbsenceInboxSource(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        return new JdbcEmploymentAbsenceInboxSource(jdbc, mapper);
    }

    @Bean
    EmploymentAbsenceDeferredInboxActivator employmentAbsenceDeferredInboxActivator(
            NamedParameterJdbcTemplate jdbc) {
        return new JdbcEmploymentAbsenceDeferredInboxActivator(jdbc);
    }

    @Bean
    EmploymentAbsenceOwnerClient employmentAbsenceOwnerClient(
            EmploymentAbsenceContract.OwnerGrantAuthority grants,
            EmploymentAbsenceContract.TenantContextAuthority contexts,
            EmploymentAbsenceContract.OwnerProofIssuer issuer,
            EmploymentAbsenceContract.OwnerTransport transport,
            EmploymentAbsenceContract.ResponseKeyAuthority keys,
            EmploymentAbsenceContract.OwnerAudit audit,
            ObjectMapper mapper) {
        return new EmploymentAbsenceOwnerClient(
                grants, contexts, issuer, transport, keys, audit,
                mapper, Clock.systemUTC());
    }

    @Bean
    EmploymentAbsenceConsumerCoordinator employmentAbsenceConsumerCoordinator(
            DomainEventInboxRepository inbox,
            EmploymentAbsenceProjectionStore projections,
            EmploymentAbsenceOwnerClient owner,
            EmploymentAbsenceAuditSink audit,
            EmploymentAbsenceDeferredInboxActivator deferredActivator,
            EmploymentAbsenceActivationGate gate,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            @Value("${dwp.hris.time.employment-absence.worker-id:tim003-worker}")
                    String workerId) {
        return new EmploymentAbsenceConsumerCoordinator(
                inbox, projections, owner, audit, deferredActivator, gate, mapper,
                transactionManager, Clock.systemUTC(), workerId);
    }

    @Bean
    EmploymentAbsenceReadGuard employmentAbsenceReadGuard(
            EmploymentAbsenceProjectionStore projections,
            EmploymentAbsenceContract.OwnerGrantAuthority grants,
            EmploymentAbsenceContract.TenantContextAuthority contexts,
            EmploymentAbsenceContract.ResponseKeyAuthority keys,
            EmploymentAbsenceRecoveryScheduler scheduler,
            EmploymentAbsenceAuditSink audit,
            EmploymentAbsenceActivationGate gate,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        return new EmploymentAbsenceReadGuard(
                projections, grants, contexts, keys, scheduler, audit,
                gate, mapper, transactionManager, Clock.systemUTC());
    }

    @Bean
    EmploymentAbsenceQuarantineRecoveryService
            employmentAbsenceQuarantineRecoveryService(
                    EmploymentAbsenceProjectionStore projections,
                    EmploymentAbsenceProjectionRecoveryAuthority authority,
                    EmploymentAbsenceAuditSink audit,
                    EmploymentAbsenceActivationGate gate,
                    PlatformTransactionManager transactionManager) {
        return new EmploymentAbsenceQuarantineRecoveryService(
                projections, authority, audit, gate,
                transactionManager, Clock.systemUTC());
    }

    @Bean
    EmploymentAbsenceInboxProjectionReconciler employmentAbsenceInboxProjectionReconciler(
            EmploymentAbsenceInboxSource inbox,
            EmploymentAbsenceConsumerCoordinator coordinator) {
        return new EmploymentAbsenceInboxProjectionReconciler(
                inbox, coordinator, Clock.systemUTC());
    }
}
