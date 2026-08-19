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
                        true, true, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAConnectionWithoutBothGovernedScopeRoles() {
        assertThatThrownBy(() -> NotificationRuntimeDatabaseGuard.validate(
                "dwp_notification_runtime",
                new NotificationRuntimeDatabaseGuard.RuntimeIdentity(
                        "dwp_notification_runtime",
                        false, false, false, false, false,
                        true, false, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("governed scope roles");
    }

    private NotificationRuntimeDatabaseGuard.RuntimeIdentity identity() {
        return new NotificationRuntimeDatabaseGuard.RuntimeIdentity(
                "dwp_notification_runtime",
                false, false, false, false, false,
                true, true, 0);
    }
}
