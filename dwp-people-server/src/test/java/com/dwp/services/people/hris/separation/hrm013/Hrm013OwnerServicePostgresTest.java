package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013OwnerServicePostgresTest extends Hrm013PostgresTestSupport {
    @Test
    void incompleteTaskSetIsRejectedBeforeAuthorityOrPersistenceAndTransactionIsBounded() {
        var dispatches = mock(Hrm013PersistencePorts.TaskDispatchStore.class);
        var waivers = mock(Hrm013PersistencePorts.WaiverRequestOutboxStore.class);
        var authorities = mock(Hrm013PersistencePorts.CallbackAuthorityStore.class);
        var authority = mock(Hrm013ServiceAuthorityPort.class);
        var credentials = mock(Hrm013WorkloadCredentialProvider.class);
        var owner = new JdbcHrm013OwnerStore(named, manager, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var service = new Hrm013OwnerService(owner, dispatches, waivers, authorities,
                authority, credentials, manager, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.createCaseAndActivateTasks(nullSafeCase(), List.of()))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(dispatches, waivers, authorities, authority, credentials);

        TransactionTemplate required = (TransactionTemplate)
                ReflectionTestUtils.getField(service, "required");
        assertThat(required).isNotNull();
        assertThat(required.getTimeout()).isEqualTo(30);
    }

    private Hrm013OwnerService.CaseCreation nullSafeCase() {
        Graph graph = graph("owner-bound", Hrm013TaskDispatchV1.TIME);
        Long worker = jdbc.queryForObject("""
                SELECT worker_id FROM ppl_separation_cases
                 WHERE tenant_id=? AND separation_case_id=?
                """, Long.class, TENANT, graph.caseId());
        Long relationship = jdbc.queryForObject("""
                SELECT work_relationship_id FROM ppl_separation_cases
                 WHERE tenant_id=? AND separation_case_id=?
                """, Long.class, TENANT, graph.caseId());
        return new Hrm013OwnerService.CaseCreation(
                TENANT, java.util.UUID.randomUUID(), worker, relationship,
                "RESIGNATION", "VOLUNTARY", java.time.LocalDate.parse("2026-11-01"),
                java.time.LocalDate.parse("2026-10-31"), SHA_A,
                java.util.UUID.randomUUID(), 1, SHA_B, java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), 1, NOW);
    }
}
