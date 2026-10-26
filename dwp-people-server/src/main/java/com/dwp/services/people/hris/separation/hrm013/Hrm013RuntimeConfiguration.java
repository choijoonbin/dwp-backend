package com.dwp.services.people.hris.separation.hrm013;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates no HRM-013 runtime, client, relay or credential reader while default-off. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Hrm013RuntimeProperties.class)
public class Hrm013RuntimeConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dwp.hris.separation", name = "enabled", havingValue = "true")
    static class EnabledRuntime {
        @Bean("hrm013Clock")
        @ConditionalOnMissingBean(name = "hrm013Clock")
        Clock hrm013Clock() {
            return Clock.systemUTC();
        }

        @Bean Hrm013PersistencePorts.ProofReplayStore hrm013ProofReplayStore(
                NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions,
                Clock hrm013Clock) {
            return new JdbcHrm013ProofReplayStore(jdbc, transactions, hrm013Clock);
        }

        @Bean Hrm013PersistencePorts.DigestProofReplayStore hrm013DigestProofReplayStore(
                NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions,
                Clock hrm013Clock) {
            TransactionTemplate isolated = new TransactionTemplate(transactions);
            isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return reservation -> {
                var now = hrm013Clock.instant();
                if (!reservation.expiresAt().isAfter(now)
                        || reservation.expiresAt().isAfter(now.plusSeconds(35))) return false;
                Boolean reserved = isolated.execute(status -> {
                    Hrm013TenantScope.setLocal(jdbc, reservation.tenantId());
                    int inserted = jdbc.update("""
                            INSERT INTO ppl_hrm013_callback_proof_replays(
                                tenant_id,binding_id,caller_workload_id,token_kind,
                                token_digest,expires_at,reserved_at)
                            VALUES(:tenant,:binding,:caller,'JTI',:proof,:expires,:reserved),
                                  (:tenant,:binding,:caller,'REQUEST_NONCE',:nonce,:expires,:reserved)
                            ON CONFLICT (tenant_id,token_digest) DO NOTHING
                            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                            .addValue("tenant", reservation.tenantId())
                            .addValue("binding", reservation.bindingId())
                            .addValue("caller", reservation.callerWorkloadId())
                            .addValue("proof", reservation.proofIdSha256())
                            .addValue("nonce", reservation.requestNonceSha256())
                            .addValue("expires", Timestamp.from(reservation.expiresAt()))
                            .addValue("reserved", Timestamp.from(now)));
                    if (inserted != 2) status.setRollbackOnly();
                    return inserted == 2;
                });
                return Boolean.TRUE.equals(reserved);
            };
        }

        @Bean Hrm013PersistencePorts.CallbackAuthorityStore hrm013CallbackAuthorityStore(
                NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
            return new JdbcHrm013CallbackAuthorityStore(jdbc, transactions);
        }

        @Bean Hrm013PersistencePorts.CallbackReceiptStore hrm013CallbackReceiptStore(
                NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
            return new JdbcHrm013CallbackReceiptStore(jdbc, transactions);
        }

        @Bean Hrm013PersistencePorts.WaiverRequestOutboxStore hrm013WaiverOutboxStore(
                NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
            return new JdbcHrm013WaiverRequestOutboxStore(jdbc, transactions);
        }

        @Bean JdbcHrm013OwnerStore hrm013OwnerStore(
                NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions,
                ObjectMapper mapper, Clock hrm013Clock) {
            return new JdbcHrm013OwnerStore(jdbc, transactions, mapper, hrm013Clock);
        }

        @Bean Hrm013TaskDispatchOutboxStore hrm013TaskDispatchStore(
                NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions,
                ObjectMapper mapper, Clock hrm013Clock,
                Hrm013PersistencePorts.CallbackAuthorityStore authorities) {
            return new Hrm013TaskDispatchOutboxStore(
                    jdbc, transactions, mapper, hrm013Clock, authorities);
        }

        @Bean AuthHrm013ServiceAuthorityAdapter hrm013ServiceAuthority(
                ObjectMapper mapper, Hrm013RuntimeProperties properties,
                AuthHrm013ServiceAuthorityAdapter.ControlPlaneTransport transport,
                AuthHrm013ServiceAuthorityAdapter.ResolverProofFactory proofs) {
            properties.requireCoherent();
            return new AuthHrm013ServiceAuthorityAdapter(
                    mapper, properties.getAuthorityEndpoint(), transport, proofs);
        }

        @Bean Hrm013GrantRotationReadiness hrm013GrantRotationReadiness(
                NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions,
                AuthHrm013ServiceAuthorityAdapter authority, Clock hrm013Clock) {
            return new Hrm013GrantRotationReadiness(
                    jdbc, transactions,
                    (oldLease, correlationId) -> authority.resolveResponse(
                            new Hrm013ServiceAuthorityPort.Query(
                                    oldLease.tenantId(), oldLease.callerWorkloadId(),
                                    oldLease.bindingId(), oldLease.purpose(),
                                    oldLease.signedTenantGrantRevision(),
                                    oldLease.signedAuthorizationRevision(),
                                    com.dwp.platform.contracts.hris.separation.v1
                                            .HrisServiceGrantAuthorityV1
                                            .ResolutionMode.READINESS_OBSERVE,
                                    null),
                            com.dwp.platform.contracts.hris.separation.v1
                                    .HrisServiceGrantAuthorityV1
                                    .ResolutionMode.READINESS_OBSERVE,
                            null, correlationId), hrm013Clock);
        }

        @Bean Hrm013WorkloadCredentialProvider hrm013CredentialProvider(
                Hrm013WorkloadCredentialProvider.LeaseSource source,
                ObjectMapper mapper, Clock hrm013Clock) {
            return new Hrm013WorkloadCredentialProvider(source, mapper, hrm013Clock);
        }

        @Bean Hrm013WorkloadProofVerifier hrm013WorkloadProofVerifier(
                ObjectMapper mapper, Clock hrm013Clock,
                Hrm013ServiceAuthorityPort authority,
                Hrm013PersistencePorts.DigestProofReplayStore replays) {
            return new Hrm013WorkloadProofVerifier(
                    mapper, hrm013Clock, authority, replays);
        }

        @Bean Hrm013AdmissionControl hrm013AdmissionControl() {
            return new Hrm013AdmissionControl();
        }

        @Bean Hrm013CallbackService hrm013CallbackService(
                Hrm013PersistencePorts.CallbackReceiptStore receipts,
                Hrm013PersistencePorts.CallbackAuthorityStore authorities,
                Hrm013PersistencePorts.WaiverRequestOutboxStore waivers,
                JdbcHrm013OwnerStore owner,
                Hrm013ServiceAuthorityPort serviceAuthority,
                PlatformTransactionManager transactions, Clock hrm013Clock) {
            return new Hrm013CallbackService(
                    receipts, authorities, waivers, owner, serviceAuthority,
                    transactions, hrm013Clock);
        }

        @Bean Hrm013OwnerService hrm013OwnerService(
                JdbcHrm013OwnerStore owner,
                Hrm013PersistencePorts.TaskDispatchStore dispatches,
                Hrm013PersistencePorts.WaiverRequestOutboxStore waivers,
                Hrm013PersistencePorts.CallbackAuthorityStore authorities,
                Hrm013ServiceAuthorityPort authority,
                Hrm013WorkloadCredentialProvider credentials,
                PlatformTransactionManager transactions, Clock hrm013Clock) {
            return new Hrm013OwnerService(owner, dispatches, waivers, authorities,
                    authority, credentials, transactions, hrm013Clock);
        }

        @Bean FilterRegistrationBean<Hrm013CallbackIngressFilter> hrm013IngressFilter(
                ObjectMapper mapper, Clock hrm013Clock,
                Hrm013WorkloadProofVerifier verifier, Hrm013AdmissionControl admission,
                Hrm013CallbackIngressFilter.ClientCertificateValidator certificates) {
            var registration = new FilterRegistrationBean<>(
                    new Hrm013CallbackIngressFilter(
                            mapper, hrm013Clock, verifier, admission, certificates));
            registration.setName("hrm013CallbackIngressFilter");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            registration.addUrlPatterns("/internal/hris-people/v1/separation-cases/*");
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dwp.hris.separation",
            name = {"enabled", "waiver-relay-enabled"}, havingValue = "true")
    static class WaiverRelayRuntime {
        @Bean Hrm013ApprovalClient hrm013ApprovalClient(
                Hrm013RuntimeProperties properties,
                Hrm013ApprovalClient.Transport transport,
                Hrm013ApprovalClient.ResponseProofVerifier responseProofs) {
            return new Hrm013ApprovalClient(
                    properties.getApprovalEndpoint(), transport, responseProofs);
        }

        @Bean Hrm013WaiverRequestRelay hrm013WaiverRelay(
                Hrm013PersistencePorts.WaiverRequestOutboxStore store,
                Hrm013ApprovalClient client,
                Hrm013WaiverRequestRelay.ProofFactory proofs,
                Hrm013WaiverRequestRelay.CurrentAuthority authority,
                Hrm013WaiverRequestRelay.RetryJitter jitter,
                Clock hrm013Clock, Hrm013RuntimeProperties properties) {
            return new Hrm013WaiverRequestRelay(
                    store, client, proofs, authority, jitter, hrm013Clock,
                    properties.getRelayLease());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dwp.hris.separation",
            name = {"enabled", "task-dispatch-relay-enabled"}, havingValue = "true")
    static class TaskRelayRuntime {
        @Bean Hrm013TaskDispatchClient hrm013TaskDispatchClient(
                Hrm013TaskDispatchClient.EndpointRegistry endpoints,
                Hrm013TaskDispatchClient.Transport transport,
                Hrm013TaskDispatchClient.AdmissionProofVerifier receipts) {
            return new Hrm013TaskDispatchClient(endpoints, transport, receipts);
        }

        @Bean Hrm013TaskDispatchRelay hrm013TaskDispatchRelay(
                Hrm013PersistencePorts.TaskDispatchStore store,
                Hrm013TaskDispatchClient client,
                Hrm013TaskDispatchRelay.ProofFactory proofs,
                Hrm013TaskDispatchRelay.CurrentAuthorities authorities,
                Hrm013WaiverRequestRelay.RetryJitter jitter,
                Clock hrm013Clock) {
            return new Hrm013TaskDispatchRelay(
                    store, client, proofs, authorities, jitter, hrm013Clock);
        }
    }
}
