package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.AgendaItemInput;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.provider.MeetingPreparationMaterialHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingPreparationMaterialProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingPreparationMaterialAccessPostgresTest extends MeetingWorkspacePostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private UUID meetingId;
    private VideoMeetingPreparationDtos.MaterialResponse material;
    private CapturingProvider provider;
    private MeetingPreparationMaterialAccessService access;

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @BeforeEach
    void setupAccess() {
        materialRetention.purgeExpired();
        meetingId = own(() -> meetingService.schedule(
                request(), "material-access-create", "material-access-test")
                .meeting().meetingId());
        material = own(() -> preparation.registerMaterial(
                meetingId,
                new VideoMeetingPreparationDtos.RegisterMaterialRequest(
                        "Release evidence.pdf", "application/pdf", "DWP_FILES",
                        "governed/release-evidence", "v7", "CONFIDENTIAL", 2_048L,
                        "c".repeat(64), 0L),
                "material-access-register", "material-access-test"))
                .materials().getFirst();
        provider = new CapturingProvider();
        var properties = properties();
        var transactions = transactional(new MeetingPreparationMaterialAccessTransactions(
                meetings, new MeetingPreparationMaterialAccessRepository(jdbc), audit,
                properties));
        access = new MeetingPreparationMaterialAccessService(provider, transactions);
    }

    @AfterEach
    void clearSubject() {
        MeetingRequestContext.clear();
    }

    @Test
    void invitedViewerGetsOnlyAShortTicketAfterCurrentOwnerAclRevalidation() {
        var attendee = as(1, 4, all(), () -> preparation.read(meetingId));
        assertThat(attendee.materials()).singleElement().satisfies(visible -> {
            assertThat(visible.opaqueReference()).isNull();
            assertThat(visible.displayName()).isEqualTo("Release evidence.pdf");
        });

        var ticket = direct(1, 4, all(), () -> access.issueAccessTicket(
                meetingId, material.materialId(),
                new VideoMeetingPreparationDtos.MaterialAccessRequest(material.version()),
                "corr-material-access"));

        assertThat(ticket.meetingId()).isEqualTo(meetingId);
        assertThat(ticket.accessUrl()).startsWith(
                "https://files.example.test/meeting-materials/open?ticket=");
        assertThat(provider.request.opaqueReference()).isEqualTo("governed/release-evidence");
        assertThat(provider.request.requesterUserId()).isEqualTo(4);
        assertThat(provider.request.referenceBindingSha256()).matches("^[0-9a-f]{64}$");
        String audits = jdbc.queryForObject("""
                SELECT string_agg(payload::text, '') FROM sys_audit_outbox
                 WHERE payload ->> 'action' IN (
                    'meeting.preparation-material.access-requested',
                    'meeting.preparation-material.access-issued')
                """, String.class);
        assertThat(audits).doesNotContain(
                "governed/release-evidence", "c".repeat(64), ticket.accessUrl());
    }

    @Test
    void crossTenantUninvitedDeniedAndStaleViewersNeverReachTheProvider() {
        assertThatThrownBy(() -> direct(2, 4, all(), () -> access.issueAccessTicket(
                meetingId, material.materialId(),
                new VideoMeetingPreparationDtos.MaterialAccessRequest(material.version()), null)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> direct(1, 9, all(), () -> access.issueAccessTicket(
                meetingId, material.materialId(),
                new VideoMeetingPreparationDtos.MaterialAccessRequest(material.version()), null)))
                .isInstanceOf(BaseException.class);
        jdbc.update("""
                UPDATE vm_meeting_participants SET attendance_state = 'DENIED'
                 WHERE tenant_id = 1 AND meeting_id = ? AND user_id = 4
                """, meetingId);
        assertThatThrownBy(() -> direct(1, 4, all(), () -> access.issueAccessTicket(
                meetingId, material.materialId(),
                new VideoMeetingPreparationDtos.MaterialAccessRequest(material.version()), null)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> direct(1, 3, all(), () -> access.issueAccessTicket(
                meetingId, material.materialId(),
                new VideoMeetingPreparationDtos.MaterialAccessRequest(material.version() + 1), null)))
                .isInstanceOf(BaseException.class);
        assertThat(provider.request).isNull();
    }

    @Test
    void changedSourceAfterBrokerCallIsFencedBeforeTicketReturn() {
        provider.afterIssue = () -> jdbc.update("""
                UPDATE vm_meeting_preparation_materials
                   SET source_version = 'v8', version = version + 1
                 WHERE material_id = ?
                """, material.materialId());

        assertThatThrownBy(() -> direct(1, 4, all(), () -> access.issueAccessTicket(
                meetingId, material.materialId(),
                new VideoMeetingPreparationDtos.MaterialAccessRequest(material.version()),
                "corr-material-race")))
                .isInstanceOf(BaseException.class);
        assertThat(provider.request).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.preparation-material.access-issued'
                """, Long.class)).isZero();
    }

    @Test
    void requestAuditFailurePreventsOwnerBrokerInvocation() {
        jdbc.execute("""
                CREATE FUNCTION fail_material_access_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' =
                            'meeting.preparation-material.access-requested' THEN
                        RAISE EXCEPTION 'simulated material audit outage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_material_access_audit_trigger
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_material_access_audit()
                """);

        assertThatThrownBy(() -> direct(1, 4, all(), () -> access.issueAccessTicket(
                meetingId, material.materialId(),
                new VideoMeetingPreparationDtos.MaterialAccessRequest(material.version()),
                "corr-material-audit")))
                .isInstanceOf(RuntimeException.class);
        assertThat(provider.request).isNull();
    }

    private VideoMeetingDtos.ScheduleMeetingRequest request() {
        return new VideoMeetingDtos.ScheduleMeetingRequest(
                "Release review", "Choose the rollout", "Decision and owners",
                OffsetDateTime.now().plusDays(1), 45, "Asia/Seoul", AccessScope.INVITED,
                true, false, false, false, false, List.of(4L), List.of(),
                List.of(new AgendaItemInput(null, "Decision", "Select option", 4L, 20)),
                null, null);
    }

    private MeetingPreparationMaterialHttpProperties properties() {
        var properties = new MeetingPreparationMaterialHttpProperties();
        properties.setAccessTicketAllowedHosts(Set.of("files.example.test"));
        properties.setAccessTicketPathPrefix("/meeting-materials/");
        properties.setAccessTicketTtl(Duration.ofMinutes(2));
        return properties;
    }

    private <T> T direct(long tenant, long user, Set<String> permissions,
            java.util.function.Supplier<T> command) {
        MeetingRequestContext.set(new MeetingRequestContext.Subject(
                user, tenant, null, "Test actor", Set.of("WORKSPACE_MEMBER"),
                permissions, Set.of()));
        try {
            return command.get();
        } finally {
            MeetingRequestContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private static final class CapturingProvider
            implements MeetingPreparationMaterialProvider {
        private AccessRequest request;
        private Runnable afterIssue = () -> { };

        @Override
        public AccessTicket issueAccessTicket(AccessRequest accessRequest) {
            request = accessRequest;
            afterIssue.run();
            return new AccessTicket(
                    accessRequest.materialId(), accessRequest.requesterUserId(),
                    accessRequest.materialVersion(), accessRequest.referenceBindingSha256(),
                    URI.create("https://files.example.test/meeting-materials/open"
                            + "?ticket=short-lived-ticket-001"),
                    OffsetDateTime.now().plusMinutes(1));
        }
    }
}
