package com.dwp.services.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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
}
