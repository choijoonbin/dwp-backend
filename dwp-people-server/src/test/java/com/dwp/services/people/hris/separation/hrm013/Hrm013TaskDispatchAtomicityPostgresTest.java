package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013TaskDispatchAtomicityPostgresTest extends Hrm013PostgresTestSupport {
    @Test
    void dispatchCallbackFenceAndAuditRollbackAsOneUnit() {
        Graph graph = graph("atomic", Hrm013TaskDispatchV1.TIME);
        Hrm013TaskDispatchOutboxStore store = dispatchStore();
        var fence = dispatchFence(graph, Hrm013TaskDispatchV1.TIME);
        var callback = callbackFence(graph, fence, Hrm013TaskDispatchV1.TIME);

        transactions.executeWithoutResult(status -> {
            store.insert(fence, callback);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM sys_people_outbox_events
                     WHERE tenant_id=? AND event_id=?
                    """, Integer.class, TENANT, fence.dispatchEventId())).isOne();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM ppl_separation_callback_authorities
                     WHERE tenant_id=? AND public_id=?
                    """, Integer.class, TENANT, callback.publicId())).isOne();
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE tenant_id=? AND event_id=?
                """, Integer.class, TENANT, fence.dispatchEventId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_separation_callback_authorities
                 WHERE tenant_id=? AND public_id=?
                """, Integer.class, TENANT, callback.publicId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND target_id=?
                """, Integer.class, TENANT, fence.dispatchEventId().toString())).isZero();
    }
}
