package com.dwp.services.notification.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRuntimeDatabaseGuardTest {

    @Test
    void acceptsASeparatedLeastPrivilegeRuntimeIdentity() {
        NotificationRuntimeDatabaseGuard.RuntimeIdentity identity = identity();

        assertThatCode(() -> NotificationRuntimeDatabaseGuard.validate(
                "dwp_notification_runtime", identity))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSuperuserBypassAndObjectOwnerIdentities() {
        assertThatThrownBy(() -> NotificationRuntimeDatabaseGuard.validate(
                "dwp_notification_runtime",
                new NotificationRuntimeDatabaseGuard.RuntimeIdentity(
                        "dwp_notification_runtime",
                        true, false, false, false, true,
                        true, true, true, 1,
                        true, 0, 0, true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAConnectionWithoutBothGovernedScopeRoles() {
        assertThatThrownBy(() -> NotificationRuntimeDatabaseGuard.validate(
                "dwp_notification_runtime",
                new NotificationRuntimeDatabaseGuard.RuntimeIdentity(
                        "dwp_notification_runtime",
                        false, false, false, false, false,
                        true, false, true, 0,
                        true, 0, 0, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("governed scope roles");
    }

    @Test
    void rejectsBroadAuditOutboxAccessOrMissingForcedRls() {
        assertThatThrownBy(() -> NotificationRuntimeDatabaseGuard.validate(
                "dwp_notification_runtime",
                new NotificationRuntimeDatabaseGuard.RuntimeIdentity(
                        "dwp_notification_runtime",
                        false, false, false, false, false,
                        true, true, true, 0,
                        false, 1, 1, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit outbox database isolation");
    }

    private NotificationRuntimeDatabaseGuard.RuntimeIdentity identity() {
        return new NotificationRuntimeDatabaseGuard.RuntimeIdentity(
                "dwp_notification_runtime",
                false, false, false, false, false,
                true, true, true, 0,
                true, 0, 0, true);
    }
}
