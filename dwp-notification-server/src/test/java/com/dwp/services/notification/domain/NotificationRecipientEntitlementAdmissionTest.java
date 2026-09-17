package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.integration.NotificationRecipientEntitlementDirectory;
import com.dwp.services.notification.integration.NotificationRecipientEntitlementDirectory.Subject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRecipientEntitlementAdmissionTest {

    private final NotificationRecipientEntitlementDirectory directory =
            mock(NotificationRecipientEntitlementDirectory.class);
    private final NotificationRecipientEntitlementAdmission admission =
            new NotificationRecipientEntitlementAdmission(
                    directory,
                    "approvals=APP.APPROVALS:VIEW,hcm=APP.HCM:VIEW,"
                            + "messaging=APP.MESSAGING:VIEW,space=APP.SPACES:VIEW,"
                            + "meetings=APP.MEETINGS:VIEW,workplace=APP.WORKPLACE:VIEW,"
                            + "mail=APP.MAIL:VIEW");

    @Test
    void admitsOnlyActiveTenantUsersWithTheExactOwnerAppViewPermission() {
        when(directory.find(7L, 11L)).thenReturn(Optional.of(subject(
                7L, 11L, "ACTIVE", "TENANT", "APP.MESSAGING:VIEW")));
        when(directory.find(7L, 12L)).thenReturn(Optional.of(subject(
                7L, 12L, "ACTIVE", "TENANT", "app.messaging:view")));
        when(directory.find(7L, 13L)).thenReturn(Optional.of(subject(
                7L, 13L, "INACTIVE", "TENANT", "APP.MESSAGING:VIEW")));
        when(directory.find(7L, 14L)).thenReturn(Optional.of(subject(
                7L, 14L, "ACTIVE", "PROVIDER", "APP.MESSAGING:VIEW")));
        when(directory.find(7L, 15L)).thenReturn(Optional.empty());

        assertThat(admission.admittedRecipients(
                7L, List.of(11L, 12L, 13L, 14L, 15L, 11L), "MESSAGING"))
                .containsExactly(11L);
    }

    @Test
    void rechecksTheAuthoritativeDirectoryWithoutAStaleLocalCache() {
        when(directory.find(7L, 11L))
                .thenReturn(Optional.of(subject(
                        7L, 11L, "ACTIVE", "TENANT", "APP.MESSAGING:VIEW")))
                .thenReturn(Optional.of(subject(
                        7L, 11L, "ACTIVE", "TENANT", "APP.WORK:VIEW")));

        assertThat(admission.admittedRecipients(7L, List.of(11L), "messaging"))
                .containsExactly(11L);
        assertThat(admission.admittedRecipients(7L, List.of(11L), "messaging"))
                .isEmpty();
        verify(directory, times(2)).find(7L, 11L);
    }

    @Test
    void admitsMeetingRecipientsOnlyWithTheExactMeetingsViewPermission() {
        when(directory.find(7L, 21L)).thenReturn(Optional.of(subject(
                7L, 21L, "ACTIVE", "TENANT", "APP.MEETINGS:VIEW")));
        when(directory.find(7L, 22L)).thenReturn(Optional.of(subject(
                7L, 22L, "ACTIVE", "TENANT", "APP.MEETING:VIEW")));

        assertThat(admission.admittedRecipients(7L, List.of(21L, 22L), "MEETINGS"))
                .containsExactly(21L);
    }

    @Test
    void admitsWorkplaceRecipientsOnlyWithTheExactWorkplaceViewPermission() {
        when(directory.find(7L, 31L)).thenReturn(Optional.of(subject(
                7L, 31L, "ACTIVE", "TENANT", "APP.WORKPLACE:VIEW")));
        when(directory.find(7L, 32L)).thenReturn(Optional.of(subject(
                7L, 32L, "ACTIVE", "TENANT", "ADMIN.WORKPLACE:VIEW")));

        assertThat(admission.admittedRecipients(7L, List.of(31L, 32L), "WORKPLACE"))
                .containsExactly(31L);
    }

    @Test
    void admitsMailRecipientsOnlyWithTheExactMailViewPermission() {
        when(directory.find(7L, 41L)).thenReturn(Optional.of(subject(
                7L, 41L, "ACTIVE", "TENANT", "APP.MAIL:VIEW")));
        when(directory.find(7L, 42L)).thenReturn(Optional.of(subject(
                7L, 42L, "ACTIVE", "TENANT", "ADMIN.MAIL:VIEW")));

        assertThat(admission.admittedRecipients(7L, List.of(41L, 42L), "MAIL"))
                .containsExactly(41L);
    }

    @Test
    void failsClosedOnUnknownOwnerBindingsAndDirectoryFailures() {
        assertThatThrownBy(() -> admission.admittedRecipients(
                7L, List.of(11L), "unknown"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.NOTIFICATION_CONTRACT_QUARANTINED));

        when(directory.find(7L, 11L)).thenThrow(new RuntimeException("auth unavailable"));
        assertThatThrownBy(() -> admission.admittedRecipients(
                7L, List.of(11L), "messaging"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.NOTIFICATION_IDENTITY_UNAVAILABLE));
    }

    @Test
    void rejectsMalformedOrNonViewBindings() {
        assertThatThrownBy(() -> new NotificationRecipientEntitlementAdmission(
                directory, "messaging=APP.MESSAGING:READ"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationRecipientEntitlementAdmission(
                directory,
                "messaging=APP.MESSAGING:VIEW,messaging=APP.COMMUNICATIONS:VIEW"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationRecipientEntitlementAdmission(
                directory,
                "approvals=APP.APPROVALS:VIEW,hcm=APP.HCM:VIEW,"
                        + "messaging=APP.WORK:VIEW,space=APP.SPACES:VIEW,"
                        + "meetings=APP.MEETINGS:VIEW,workplace=APP.WORKPLACE:VIEW,"
                        + "mail=APP.MAIL:VIEW"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact app VIEW entitlements");
    }

    private Subject subject(
            long tenantId,
            long userId,
            String status,
            String identityPlane,
            String permission) {
        return new Subject(
                tenantId, userId, status, identityPlane, List.of(permission));
    }
}
