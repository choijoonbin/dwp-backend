package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackAuthorityRecord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013TaskDispatchCallbackGrantFencePostgresTest
        extends Hrm013PostgresTestSupport {
    @Test
    void closedCallbackFenceBlocksSendButPreservesHistoricalAcceptanceIntegrity() {
        Graph graph = graph("closed-fence", Hrm013TaskDispatchV1.TIME);
        Hrm013TaskDispatchOutboxStore store = dispatchStore();
        var fence = dispatchFence(graph, Hrm013TaskDispatchV1.TIME);
        CallbackAuthorityRecord admitted = callbackFence(
                graph, fence, Hrm013TaskDispatchV1.TIME);
        transactions.executeWithoutResult(status -> store.insert(fence, admitted));

        CallbackAuthorityRecord revoked = new CallbackAuthorityRecord(
                UUID.randomUUID(), admitted.key(), admitted.separationCaseId(),
                admitted.separationTaskId(), admitted.callbackType(), admitted.audience(),
                admitted.taskType(), admitted.consumerEventId(),
                admitted.tenantGrantRevision(), admitted.authorizationRevision(),
                admitted.expectedNextOwnerRevision(), admitted.correlationId(),
                admitted.originatingPayloadSha256(), 2, "REVOKE", admitted.publicId(),
                SHA_B, NOW.plusSeconds(1), NOW.plusSeconds(1));
        var authorities = new JdbcHrm013CallbackAuthorityStore(named, manager);
        transactions.executeWithoutResult(status -> authorities.append(revoked));

        assertThatThrownBy(() -> store.requireIntegrity(fence.dispatchEventId(), TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not open");
        assertThat(store.requireStoredIntegrity(fence.dispatchEventId(), TENANT))
                .extracting(value -> value.dispatchEventId(),
                        value -> value.requestBodySha256(),
                        value -> value.callbackTenantGrantRevision(),
                        value -> value.callbackAuthorizationRevision())
                .containsExactly(fence.dispatchEventId(), fence.requestBodySha256(),
                        fence.callbackTenantGrantRevision(),
                        fence.callbackAuthorizationRevision());
        assertThat(jdbc.queryForMap("""
                SELECT last_error,dead_lettered_at FROM sys_people_outbox_events
                 WHERE tenant_id=? AND event_id=?
                """, TENANT, fence.dispatchEventId()))
                .containsEntry("last_error", "INTEGRITY_CONFLICT");
    }
}
