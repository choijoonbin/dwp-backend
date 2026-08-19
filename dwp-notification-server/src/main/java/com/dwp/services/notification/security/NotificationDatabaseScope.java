package com.dwp.services.notification.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class NotificationDatabaseScope {

    private final JdbcTemplate jdbc;

    public NotificationDatabaseScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void applyUser(NotificationRequestContext.Actor actor) {
        requireTransaction();
        if (actor.userId() == null) throw new IllegalArgumentException("User scope is required.");
        jdbc.execute("SET LOCAL ROLE dwp_notification_api");
        set("dwp.tenant_id", Long.toString(actor.tenantId()));
        set("dwp.user_id", Long.toString(actor.userId()));
        set("dwp.notification_scope", "API");
    }

    public void applyWorker(long tenantId) {
        requireTransaction();
        jdbc.execute("SET LOCAL ROLE dwp_notification_worker");
        set("dwp.tenant_id", Long.toString(tenantId));
        set("dwp.user_id", "0");
        set("dwp.notification_scope", "WORKER");
    }

    private void set(String key, String value) {
        jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class, key, value);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notification DB scope requires an active transaction.");
        }
    }
}
