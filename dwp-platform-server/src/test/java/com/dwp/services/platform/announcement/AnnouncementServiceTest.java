package com.dwp.services.platform.announcement;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock
    private AnnouncementRepository repository;
    @Mock
    private PlatformAuditService auditService;
    @Mock
    private JdbcTemplate jdbc;

    private AnnouncementService service;

    @BeforeEach
    void setUp() {
        service = new AnnouncementService(repository, jdbc, auditService);
    }

    @Test
    void createsANormalizedRoleTargetedDraftAndAuditsIt() {
        when(repository.saveAndFlush(any(Announcement.class))).thenAnswer(invocation -> {
            Announcement announcement = invocation.getArgument(0);
            announcement.setAnnouncementId(91L);
            announcement.setVersion(0L);
            return announcement;
        });

        AnnouncementDtos.AnnouncementResponse result = service.create(
                7L,
                11L,
                "corr-announcement",
                new AnnouncementDtos.CreateAnnouncementRequest(new AnnouncementDtos.AnnouncementDefinition(
                        "  Planned maintenance  ",
                        "  Access may be interrupted.  ",
                        AnnouncementSeverity.WARNING,
                        AnnouncementAudienceType.ROLE,
                        " tenant_admin ",
                        OffsetDateTime.parse("2026-08-10T01:00:00Z"),
                        OffsetDateTime.parse("2026-08-10T03:00:00Z"),
                        true,
                        "View status",
                        "/activity")));

        assertThat(result.lifecycleState()).isEqualTo(AnnouncementLifecycle.DRAFT);
        assertThat(result.title()).isEqualTo("Planned maintenance");
        assertThat(result.audienceValue()).isEqualTo("TENANT_ADMIN");
        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("announcement.created"),
                eq("ANNOUNCEMENT"),
                eq("91"),
                eq("corr-announcement"),
                eq(null),
                anyMap());
    }

    @Test
    void rejectsUnsafeActionSchemesBeforeWriting() {
        AnnouncementDtos.AnnouncementDefinition definition = new AnnouncementDtos.AnnouncementDefinition(
                "Unsafe",
                "Unsafe action",
                AnnouncementSeverity.INFO,
                AnnouncementAudienceType.ALL,
                null,
                null,
                null,
                false,
                "Open",
                "javascript:alert(1)");

        assertThatThrownBy(() -> service.create(
                        7L,
                        11L,
                        null,
                        new AnnouncementDtos.CreateAnnouncementRequest(definition)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsAmbiguousInternalActionPathsBeforeWriting() {
        AnnouncementDtos.AnnouncementDefinition definition = new AnnouncementDtos.AnnouncementDefinition(
                "Unsafe path",
                "Unsafe internal action",
                AnnouncementSeverity.INFO,
                AnnouncementAudienceType.ALL,
                null,
                null,
                null,
                false,
                "Open",
                "/\\external.example");

        assertThatThrownBy(() -> service.create(
                        7L,
                        11L,
                        null,
                        new AnnouncementDtos.CreateAnnouncementRequest(definition)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void normalizesVerifiedRolesForRuntimeTargeting() {
        when(repository.findActive(eq(7L), any(), eq(List.of("ADMIN", "EMPLOYEE")), any()))
                .thenReturn(List.of());

        assertThat(service.listActive(7L, " admin, EMPLOYEE,invalid role ")).isEmpty();

        verify(repository).findActive(eq(7L), any(), eq(List.of("ADMIN", "EMPLOYEE")), any());
    }
}
