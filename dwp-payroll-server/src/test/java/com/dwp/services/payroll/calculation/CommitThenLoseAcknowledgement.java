package com.dwp.services.payroll.calculation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class CommitThenLoseAcknowledgement
        implements PlatformTransactionManager {
    private final PlatformTransactionManager delegate;
    private final JdbcTemplate observer;
    private final UUID runId;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicBoolean lost = new AtomicBoolean();

    CommitThenLoseAcknowledgement(
            PlatformTransactionManager delegate,
            JdbcTemplate observer,
            UUID runId) {
        this.delegate = delegate;
        this.observer = observer;
        this.runId = runId;
    }

    void arm() {
        armed.set(true);
    }

    boolean didLoseAcknowledgement() {
        return lost.get();
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return delegate.getTransaction(definition);
    }

    @Override
    public void commit(TransactionStatus status) {
        delegate.commit(status);
        if (armed.get() && !lost.get() && observer.queryForObject("""
                SELECT COUNT(*) FROM pay_outbox_events
                 WHERE tenant_id = 1 AND run_id = ?
                   AND event_type = 'PayrollCalculated'
                """, Long.class, runId) == 1L
                && lost.compareAndSet(false, true)) {
            armed.set(false);
            throw new TransactionSystemException(
                    "simulated acknowledgement loss after PostgreSQL commit");
        }
    }

    @Override
    public void rollback(TransactionStatus status) {
        delegate.rollback(status);
    }
}
