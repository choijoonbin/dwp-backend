package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.platform.contract.AuditEventEnvelope;
import com.dwp.platform.contract.DataClassification;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractCanonicalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Hrm011WorkloadAuditContractTest {
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final UUID CORRELATION =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Test
    void exactEnvelopeMapsToMinimizedExtendedServiceDataAccessAudit() {
        AuditOutboxRecorder outbox = mock(AuditOutboxRecorder.class);
        Hrm011WorkloadAuditOutboxAdapter adapter =
                new Hrm011WorkloadAuditOutboxAdapter(outbox);
        AuditEventEnvelope envelope = envelope(data());

        adapter.record(envelope);

        ArgumentCaptor<AuditEvent> captured = ArgumentCaptor.forClass(AuditEvent.class);
        verify(outbox).record(captured.capture());
        AuditEvent event = captured.getValue();
        assertThat(event.eventId()).isEqualTo(UUID.fromString(envelope.id()));
        assertThat(event.tenantId()).isEqualTo(71L);
        assertThat(event.category()).isEqualTo("DATA_ACCESS");
        assertThat(event.actorType()).isEqualTo("SERVICE");
        assertThat(event.actorId()).isEqualTo(Hrm011WorkloadIngress.PAYROLL_CALLER);
        assertThat(event.action()).isEqualTo(Hrm011WorkloadIngress.SVC005_OPERATION);
        assertThat(event.targetId()).isEqualTo("TAX_IDENTITY_V1");
        assertThat(event.correlationId()).isEqualTo(CORRELATION.toString());
        assertThat(event.retentionClass()).isEqualTo("EXTENDED");
        assertThat(event.metadata().keySet())
                .isEqualTo(Hrm011WorkloadAuditPort.DATA_FIELDS);
        assertThat(event.metadata()).isEqualTo(envelope.data());
        assertThat(event.metadata().toString()).doesNotContain(
                "signed-jwt", "spiffe://", "tok_", "taxIdentifierToken",
                "bankAccountToken", "eligibilityFacts");
    }

    @Test
    void mismatchedTenantOrNonExactDataShapeIsRejectedBeforeOutbox() {
        AuditOutboxRecorder outbox = mock(AuditOutboxRecorder.class);
        Hrm011WorkloadAuditOutboxAdapter adapter =
                new Hrm011WorkloadAuditOutboxAdapter(outbox);
        AuditEventEnvelope valid = envelope(data());
        AuditEventEnvelope mismatchedTenant = new AuditEventEnvelope(
                valid.id(), valid.source(), valid.type(), valid.specVersion(), valid.time(),
                valid.subject(), "72", valid.correlationId(), valid.causationId(),
                valid.schemaVersion(), valid.classification(), valid.data());
        Map<String, Object> extraField = new LinkedHashMap<>(valid.data());
        extraField.put("snapshot", "forbidden");
        AuditEventEnvelope nonExactShape = envelope(extraField);

        assertThatThrownBy(() -> adapter.record(mismatchedTenant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.record(nonExactShape))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void outboxFailurePropagatesFailClosed() {
        AuditOutboxRecorder outbox = mock(AuditOutboxRecorder.class);
        when(outbox.record(any(AuditEvent.class)))
                .thenThrow(new IllegalStateException("audit outbox unavailable"));
        Hrm011WorkloadAuditOutboxAdapter adapter =
                new Hrm011WorkloadAuditOutboxAdapter(outbox);

        assertThatThrownBy(() -> adapter.record(envelope(data())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit outbox unavailable");
    }

    @Test
    void workloadIngressIsCglibProxiedAndAuditRunsInsideTransactionBoundary() {
        List<String> phases = new ArrayList<>();
        RecordingTransactionManager transactionManager =
                new RecordingTransactionManager(phases);
        RecordingAuditPort audit = new RecordingAuditPort(phases);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Hrm011WorkloadIngress.WorkloadAuthorityPort authority = (evidence, purpose) ->
                new Hrm011WorkloadIngress.TrustedWorkload(
                        71L, Hrm011WorkloadIngress.PAYROLL_CALLER,
                        Hrm011WorkloadIngress.AUDIENCE, purpose,
                        true, true, true, true, 7);

        new ApplicationContextRunner()
                .withBean(PlatformTransactionManager.class, () -> transactionManager)
                .withBean(Hrm011WorkloadIngress.WorkloadAuthorityPort.class,
                        () -> authority)
                .withBean(Hrm011WorkloadIngress.PayrollWorkerFactSource.class,
                        () -> mock(Hrm011WorkloadIngress.PayrollWorkerFactSource.class))
                .withBean(ObjectMapper.class, () -> mapper)
                .withBean(EmploymentContractCanonicalizer.class,
                        () -> new EmploymentContractCanonicalizer(mapper))
                .withBean(Hrm011WorkloadAuditPort.class, () -> audit)
                .withPropertyValues(
                        "dwp.hris.contract-compensation-workbench.enabled=true",
                        "dwp.hris.contract-compensation-workbench.workload-cursor-key="
                                + "01234567890123456789012345678901")
                .withUserConfiguration(TransactionConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Hrm011WorkloadIngress ingress =
                            context.getBean(Hrm011WorkloadIngress.class);
                    assertThat(AopUtils.isCglibProxy(ingress)).isTrue();
                    assertThat(ingress).isInstanceOf(Advised.class);
                    assertThat(((Advised) ingress).getAdvisors())
                            .anySatisfy(advisor -> assertThat(advisor.getAdvice())
                                    .isInstanceOf(TransactionInterceptor.class));

                    ingress.compensationSnapshots(
                            new Hrm011WorkloadIngress.WorkloadEvidence(
                                    "signed-jwt", "spiffe://dwp/payroll/dwp-payroll-server",
                                    "TLSv1.3", CORRELATION),
                            new Hrm011WorkloadIngress.SnapshotRequest(
                                    Hrm011WorkloadIngress.SVC003_PURPOSE, NOW,
                                    Hrm011WorkloadIngress.COMPENSATION, 100, null));

                    assertThat(phases).containsExactly("begin", "audit", "commit");
                    assertThat(audit.envelope).isNotNull();
                });
    }

    private Map<String, Object> data() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callerWorkloadId", Hrm011WorkloadIngress.PAYROLL_CALLER);
        data.put("tenantId", 71L);
        data.put("operationId", Hrm011WorkloadIngress.SVC005_OPERATION);
        data.put("purposeCode", Hrm011WorkloadIngress.SVC005_PURPOSE);
        data.put("projectionId", "TAX_IDENTITY_V1");
        data.put("policyRevision", 7L);
        data.put("correlationId", CORRELATION.toString());
        data.put("outcome", "SUCCESS");
        data.put("rowCount", 2);
        data.put("payloadDigest", "a".repeat(64));
        return data;
    }

    private AuditEventEnvelope envelope(Map<String, Object> data) {
        return new AuditEventEnvelope(
                Hrm011WorkloadAuditPort.deterministicEventId(data).toString(),
                URI.create(Hrm011WorkloadAuditPort.SOURCE),
                Hrm011WorkloadAuditPort.TYPE,
                Hrm011WorkloadAuditPort.SPEC_VERSION,
                NOW,
                Hrm011WorkloadIngress.SVC005_OPERATION,
                "71",
                CORRELATION.toString(),
                null,
                Hrm011WorkloadAuditPort.SCHEMA_VERSION,
                DataClassification.RESTRICTED,
                data);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(Hrm011WorkloadIngress.class)
    static class TransactionConfiguration {
    }

    private static final class RecordingTransactionManager
            implements PlatformTransactionManager {
        private final List<String> phases;

        private RecordingTransactionManager(List<String> phases) {
            this.phases = phases;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            phases.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            phases.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            phases.add("rollback");
        }
    }

    private static final class RecordingAuditPort implements Hrm011WorkloadAuditPort {
        private final List<String> phases;
        private AuditEventEnvelope envelope;

        private RecordingAuditPort(List<String> phases) {
            this.phases = phases;
        }

        @Override
        public void record(AuditEventEnvelope envelope) {
            phases.add("audit");
            this.envelope = envelope;
        }
    }
}
