package com.dwp.services.notification.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NotificationDatabaseScopeTest {

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void userScopeChangesCurrentRoleBeforeSettingTenantAndUserContext() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationDatabaseScope scope = new NotificationDatabaseScope(jdbc);
        NotificationRequestContext.Actor actor = new NotificationRequestContext.Actor(
                7, 900018L, Set.of(), Set.of(), false, "dwp-gateway");
        TransactionSynchronizationManager.setActualTransactionActive(true);

        scope.applyUser(actor);

        verify(jdbc).execute("SET LOCAL ROLE dwp_notification_api");
        verify(jdbc, times(3)).queryForObject(
                eq("SELECT set_config(?, ?, true)"),
                eq(String.class),
                any(Object[].class));
    }

    @Test
    void workerScopeUsesDedicatedNonBypassRole() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationDatabaseScope scope = new NotificationDatabaseScope(jdbc);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        scope.applyWorker(7);

        verify(jdbc).execute("SET LOCAL ROLE dwp_notification_worker");
    }
}
