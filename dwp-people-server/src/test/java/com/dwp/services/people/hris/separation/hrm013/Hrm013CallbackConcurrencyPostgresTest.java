package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013CallbackConcurrencyPostgresTest extends Hrm013PostgresTestSupport {
    @Test
    void concurrentExactCallbacksConvergeOnOneDurableReceipt() throws Exception {
        Graph graph = graph("callback-concurrency", Hrm013TaskDispatchV1.TIME);
        byte[] response = "{\"receipt\":\"one\"}".getBytes(StandardCharsets.UTF_8);
        CallbackReceiptRecord record = new CallbackReceiptRecord(
                new CallbackIdentity(TENANT, "dwp-payroll-server", UUID.randomUUID()),
                graph.caseId(), 0, "RECONCILIATION_RESULT", "CONSISTENT", null,
                1, 7, 11, SHA_A, SHA_B, SHA_C, sha256(new byte[] {1}),
                "dwp-people-server", "HRIS_SEPARATION_PAYROLL_RECONCILIATION",
                graph.correlationId(), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, UUID.randomUUID(), response,
                sha256(response), "ACCEPTED", NOW, NOW);
        var store = new JdbcHrm013CallbackReceiptStore(named, manager);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return transactions.execute(status -> store.append(record));
            });
            var second = executor.submit(() -> {
                start.await();
                return transactions.execute(status -> store.append(record));
            });
            start.countDown();
            assertThat(first.get()).isEqualTo(record);
            assertThat(second.get()).isEqualTo(record);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_separation_callbacks
                 WHERE tenant_id=? AND caller_workload_id=? AND callback_event_id=?
                """, Integer.class, TENANT, record.identity().callerWorkloadId(),
                record.identity().callbackEventId())).isOne();
    }
}
