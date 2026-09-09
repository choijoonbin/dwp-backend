package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class MeetingAdminOperationsExportPostgresTest extends MeetingWorkspacePostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @BeforeEach
    void deterministicExport() {
        when(media.capability()).thenReturn(new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 600));
        meetingService = new VideoMeetingService(
                meetings, media, new MeetingJoinCodeGenerator(
                        new com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties()),
                audit, Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void exportContainsOnlyBoundedAggregateMetadataAndDurableDigestEvidence() {
        var export = own(() -> meetingService.adminOperationsExport(
                "Asia/Seoul", "meeting-operations-export-001"));
        String csv = new String(export.content(), StandardCharsets.UTF_8);

        assertThat(export.filename()).isEqualTo("dwp-meeting-operations-20260908T020000Z.csv");
        assertThat(csv.lines()).hasSize(2);
        assertThat(csv).startsWith("schemaVersion,observedAt,timeZone,dayStart,dayEnd,")
                .contains("\"meeting-admin-operations-v1\"", "\"Asia/Seoul\"",
                        "\"NOT_MEASURED\"");
        jdbc.queryForList("SELECT title FROM vm_meetings WHERE tenant_id = 1", String.class)
                .forEach(title -> assertThat(csv).doesNotContain(title));
        jdbc.queryForList("""
                SELECT email_address FROM vm_meeting_participants WHERE tenant_id = 1
                """, String.class).forEach(email -> assertThat(csv).doesNotContain(email));

        assertThat(jdbc.queryForMap("""
                SELECT payload ->> 'category' category,
                       payload ->> 'action' action,
                       payload ->> 'correlationId' correlation_id,
                       payload -> 'afterState' ->> 'schemaVersion' schema_version,
                       payload -> 'afterState' ->> 'payloadSha256' payload_sha256
                  FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.admin.operations.exported'
                """))
                .containsEntry("category", "DATA_EXPORT")
                .containsEntry("action", "meeting.admin.operations.exported")
                .containsEntry("correlation_id", "meeting-operations-export-001")
                .containsEntry("schema_version", "meeting-admin-operations-v1")
                .containsEntry("payload_sha256", VideoMeetingCommandPolicy.requestHash(csv));
    }

    @Test
    void exportRequiresTenantAdminViewAndRejectsSupportIdentity() {
        assertThatThrownBy(() -> as(1, 3, Set.of("APP.MEETINGS:VIEW"),
                () -> meetingService.adminOperationsExport("UTC", null)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(1, 3, Set.of("ADMIN.MEETINGS:VIEW"), () -> {
            var actor = com.dwp.services.meeting.security.MeetingRequestContext.get();
            com.dwp.services.meeting.security.MeetingRequestContext.set(
                    new com.dwp.services.meeting.security.MeetingRequestContext.Subject(
                            actor.userId(), actor.tenantId(), null, actor.displayName(),
                            Set.of("PROVIDER_SUPPORT"), actor.permissions(), Set.of()));
            return meetingService.adminOperationsExport("UTC", null);
        })).isInstanceOf(BaseException.class);
        assertThat(count("sys_audit_outbox")).isZero();
    }

    @Test
    void auditFailurePreventsExportAndRollsBackDefaultPolicyCreation() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .adminOperationsExport(any(), any(), anyMap());
        assertThatThrownBy(() -> as(991, 3, Set.of("ADMIN.MEETINGS:VIEW"),
                () -> meetingService.adminOperationsExport("UTC", "export-audit-failure")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_tenant_policies WHERE tenant_id = 991
                """, Integer.class)).isZero();
        assertThat(count("sys_audit_outbox")).isZero();
    }
}
