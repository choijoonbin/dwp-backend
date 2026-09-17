package com.dwp.services.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlatformAuditServiceTest {

    @Mock
    private PlatformAuditEventRepository repository;

    private PlatformAuditService service;

    @BeforeEach
    void setUp() {
        service = new PlatformAuditService(repository, new ObjectMapper());
    }

    @Test
    void returnsExactlyTheEvidenceIdSavedInTheAuditRepository() {
        UUID reference = service.successWithId(7L, 11L, "workspace.work-status.updated",
                "WORK_ITEM", "WK-1", "corr-one", null, java.util.Map.of("status", "WAITING"));
        ArgumentCaptor<PlatformAuditEvent> capture = ArgumentCaptor.forClass(PlatformAuditEvent.class);
        verify(repository).save(capture.capture());
        assertThat(capture.getValue().getAuditEventId()).isEqualTo(reference);
        assertThat(capture.getValue().getTenantId()).isEqualTo(7L);
        assertThat(capture.getValue().getCorrelationId()).isEqualTo("corr-one");
    }

    @Test
    void recordsMetadataOnlyDeniedEventWithoutBusinessPayload() {
        service.event(7L, 11L, "home.widget.command.denied", "HOME_WIDGET_ACTION",
                "instance:action", "command-id", "DENIED");

        ArgumentCaptor<PlatformAuditEvent> capture = ArgumentCaptor.forClass(PlatformAuditEvent.class);
        verify(repository).save(capture.capture());
        assertThat(capture.getValue().getOutcome()).isEqualTo("DENIED");
        assertThat(capture.getValue().getBeforeSnapshot()).isNull();
        assertThat(capture.getValue().getAfterSnapshot()).isNull();
    }

    @Test
    void returnsOnlyReferenceSetAggregateActivityInDescendingOrder() {
        PlatformAuditEvent event = PlatformAuditEvent.builder()
                .auditEventId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
                .tenantId(7L)
                .actorType("USER")
                .actorId(11L)
                .action("reference-item.updated")
                .targetType("REFERENCE_ITEM")
                .targetId("WORK_PRIORITY/HIGH")
                .outcome("SUCCESS")
                .occurredAt(Instant.parse("2026-08-11T10:00:00Z"))
                .build();
        when(repository.findReferenceSetActivity(
                eq(7L),
                eq("WORK_PRIORITY"),
                argThat((Pageable pageable) -> pageable.getPageNumber() == 0
                        && pageable.getPageSize() == 50
                        && pageable.getSort().getOrderFor("occurredAt").isDescending())))
                .thenReturn(new PageImpl<>(List.of(event)));

        PlatformAuditService.AuditPage result = service.listReferenceSetActivity(
                7L, "WORK_PRIORITY", 0, 50);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).singleElement().satisfies(activity -> {
            assertThat(activity.action()).isEqualTo("reference-item.updated");
            assertThat(activity.targetId()).isEqualTo("WORK_PRIORITY/HIGH");
        });
    }

    @Test
    void homeStudioAuditUsesOnlyTheExactBoundedTargetFamilyBeforePaging() {
        PlatformAuditEvent event = PlatformAuditEvent.builder()
                .auditEventId(UUID.fromString("20000000-0000-0000-0000-000000000001"))
                .tenantId(7L)
                .actorType("USER")
                .actorId(11L)
                .action("home-experience.rolled-back")
                .targetType("HOME_EXPERIENCE")
                .targetId("7")
                .outcome("SUCCESS")
                .occurredAt(Instant.parse("2026-09-16T10:00:00Z"))
                .build();
        when(repository.findByTenantIdAndTargetTypeIn(
                eq(7L),
                eq(PlatformAuditService.HOME_STUDIO_TARGET_TYPES),
                argThat((Pageable pageable) -> pageable.getPageNumber() == 2
                        && pageable.getPageSize() == 100
                        && pageable.getSort().getOrderFor("occurredAt").isDescending()
                        && pageable.getSort().getOrderFor("auditEventId").isDescending())))
                .thenReturn(new PageImpl<>(List.of(event)));

        PlatformAuditService.AuditPage result = service.listHomeStudio(7L, 2, 500);

        assertThat(result.content()).singleElement().satisfies(activity -> {
            assertThat(activity.action()).isEqualTo("home-experience.rolled-back");
            assertThat(activity.targetType()).isEqualTo("HOME_EXPERIENCE");
        });
    }
}
