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
                        true, true, true, true, true, 1,
                        true, 0, 0, true,
                        true, 0, true, true, true, true, true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAConnectionWithoutBothGovernedScopeRoles() {
        assertThatThrownBy(() -> NotificationRuntimeDatabaseGuard.validate(
                "dwp_notification_runtime",
                new NotificationRuntimeDatabaseGuard.RuntimeIdentity(
                        "dwp_notification_runtime",
                        false, false, false, false, false,
                        true, false, true, true, true, 0,
                        true, 0, 0, true,
                        true, 0, true, true, true, true, true)))
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
                        true, true, true, true, true, 0,
                        false, 1, 1, true,
                        true, 0, true, true, true, true, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit outbox database isolation");
    }

    @Test
    void rejectsAttentionAuditRetentionMembershipOrBroadEvidenceWrites() {
        assertThatThrownBy(() -> NotificationRuntimeDatabaseGuard.validate(
                "dwp_notification_runtime",
                new NotificationRuntimeDatabaseGuard.RuntimeIdentity(
                        "dwp_notification_runtime",
                        false, false, false, false, false,
                        true, true, true, true, false, 0,
                        true, 0, 0, true,
                        true, 0, true, true, false, true, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    private NotificationRuntimeDatabaseGuard.RuntimeIdentity identity() {
        return new NotificationRuntimeDatabaseGuard.RuntimeIdentity(
                "dwp_notification_runtime",
                false, false, false, false, false,
                true, true, true, true, true, 0,
                true, 0, 0, true,
                true, 0, true, true, true, true, true);
    }
}
