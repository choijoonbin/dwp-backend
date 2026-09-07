package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.PreferencesInput;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

class MeetingPreferencesPostgresTest extends MeetingWorkspacePostgresFixture {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");
    @Override org.testcontainers.containers.PostgreSQLContainer<?> postgres() { return POSTGRES; }
    @Test
    void returnsSafeDefaultsWithoutWritingAndViewOnlyUserMayChangeOwnPreferences() {
        var defaults = own(() -> preferences.get());
        assertThat(defaults.version()).isZero();
        assertThat(defaults.microphoneOff()).isTrue();
        assertThat(defaults.cameraOff()).isTrue();
        assertThat(defaults.prejoinEnabled()).isTrue();
        assertThat(count("vm_meeting_user_preferences")).isZero();
        var saved = as(1, 3, Set.of("APP.MEETINGS:VIEW"), () -> preferences.update(input(0), "prefs-update-001", "corr"));
        assertThat(saved.version()).isOne();
        assertThat(saved.displayName()).isEqualTo("Private display name");
        assertThat(as(1, 4, all(), () -> preferences.get()).displayName()).isEmpty();
        assertThat(as(2, 3, all(), () -> preferences.get()).displayName()).isEmpty();
        assertThat(own(() -> preferences.update(input(0), "prefs-update-001", "corr"))).isEqualTo(saved);
        assertThat(count("vm_meeting_workspace_commands")).isOne();
    }

    @Test
    void rejectsStaleVersionAndDifferentPayloadUnderSameKey() {
        own(() -> preferences.update(input(0), "prefs-update-001", "corr"));
        assertThatThrownBy(() -> own(() -> preferences.update(input(0), "prefs-update-002", "corr")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> own(() -> preferences.update(input(1), "prefs-update-001", "corr")))
                .isInstanceOf(BaseException.class);
        assertThat(own(() -> preferences.get()).version()).isOne();
    }

    @Test
    void auditFailureRollsBackFirstPreferenceAndReceiptWithoutSensitiveAuditData() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).workspaceChanged(
                any(), eq("meeting.preferences.updated"), anyString(), anyString(), any(), anyMap());
        assertThatThrownBy(() -> own(() -> preferences.update(input(0), "prefs-update-001", "corr")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meeting_user_preferences")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isZero();
    }

    @Test
    void preferencesNeverAlterConsentPoliciesAndRawDeviceFieldsDoNotExist() {
        long notices = count("vm_meeting_content_notices");
        var policy = jdbc.queryForMap("SELECT * FROM vm_tenant_policies WHERE tenant_id = 1");
        own(() -> preferences.update(input(0), "prefs-update-001", "corr"));
        assertThat(jdbc.queryForMap("SELECT * FROM vm_tenant_policies WHERE tenant_id = 1")).isEqualTo(policy);
        assertThat(count("vm_meeting_content_notices")).isEqualTo(notices);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'vm_meeting_user_preferences'", String.class))
                .doesNotContain("device_id", "microphone_device_id", "camera_device_id", "consent", "token");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM sys_audit_outbox", String.class))
                .doesNotContain("Private display name");
    }

    @Test
    void providerSupportIdentityCannotUseSelfPreferencesAsConfusedDeputy() {
        MeetingRequestContext.set(new MeetingRequestContext.Subject(3, 1, null, "Support",
                Set.of("PROVIDER_SUPPORT"), Set.of("APP.MEETINGS:VIEW"), Set.of()));
        try {
            assertThatThrownBy(() -> transaction.execute(status -> preferences.get()))
                    .isInstanceOf(BaseException.class);
        } finally { MeetingRequestContext.clear(); }
    }

    private PreferencesInput input(long version) {
        return new PreferencesInput("Private display name", false, false, false,
                true, 15, true, version);
    }
}
