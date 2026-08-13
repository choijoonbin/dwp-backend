package com.dwp.services.platform.search;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchAuditServiceTest {

    @Mock
    private AuditOutboxRecorder recorder;

    @Test
    void recordsOnlyPrivacyMinimizedQueryEvidence() {
        when(recorder.record(org.mockito.ArgumentMatchers.any())).thenReturn(UUID.randomUUID());
        SearchAuditService service = new SearchAuditService(recorder);

        SearchAuditDtos.AuditReceipt receipt = service.record(
                1L, 7L, "ADMIN,TENANT_ADMIN", "correlation-1",
                new SearchAuditDtos.AuditRequest(
                        "QUERY", "sensitive person name", List.of("PEOPLE", "TENANT_AUDIT"),
                        4, null, null));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(recorder).record(event.capture());
        assertThat(event.getValue().action()).isEqualTo("workspace.search.executed");
        assertThat(event.getValue().metadata()).containsEntry("queryLength", 21);
        assertThat(event.getValue().metadata().toString()).doesNotContain("sensitive person name");
        assertThat(receipt.queryDigest()).hasSize(64);
    }

    @Test
    void rejectsUnknownSourcesAndIncompleteSelections() {
        SearchAuditService service = new SearchAuditService(recorder);

        assertThatThrownBy(() -> service.record(
                1L, 7L, "ADMIN", null,
                new SearchAuditDtos.AuditRequest(
                        "QUERY", "query", List.of("EXTERNAL_WEB"), 0, null, null)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.record(
                1L, 7L, "ADMIN", null,
                new SearchAuditDtos.AuditRequest(
                        "SELECTION", "query", List.of("APPS"), 1, "APP", null)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void preservesCaseSensitiveSelectionAndCorrelationIdentifiers() {
        when(recorder.record(org.mockito.ArgumentMatchers.any())).thenReturn(UUID.randomUUID());
        SearchAuditService service = new SearchAuditService(recorder);

        service.record(
                1L, 7L, "ADMIN", "Trace-AbC-17",
                new SearchAuditDtos.AuditRequest(
                        "selection", "Catalog Asset", List.of("tenant_catalog"), 1,
                        "catalog", "Service:CustomerProfile"));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(recorder).record(event.capture());
        assertThat(event.getValue().targetType()).isEqualTo("CATALOG");
        assertThat(event.getValue().targetId()).isEqualTo("Service:CustomerProfile");
        assertThat(event.getValue().correlationId()).isEqualTo("Trace-AbC-17");
    }
}
