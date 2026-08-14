package com.dwp.services.platform.apihistory;

import com.dwp.core.exception.BaseException;
import com.dwp.observability.api.ApiHistoryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ApiHistoryServiceTest {

    private final ApiHistoryJdbcRepository repository = mock(ApiHistoryJdbcRepository.class);
    private final ApiHistoryService service = new ApiHistoryService(
            repository,
            mock(ApiHistoryCursorCodec.class),
            "dwp-gateway,dwp-platform-server",
            90);

    @Test
    void ingestsAValidatedPrivacyMinimizedBatch() {
        ApiHistoryEvent event = event("dwp-gateway", "/api/platform/v1/people/{id}");

        int accepted = service.ingest("dwp-gateway", List.of(event));

        assertThat(accepted).isEqualTo(1);
        verify(repository).ingest(List.of(event));
    }

    @Test
    void defaultServiceRegistryAcceptsTheApprovalProductService() {
        ApiHistoryService defaultRegistry = new ApiHistoryService(
                repository,
                mock(ApiHistoryCursorCodec.class),
                ApiHistoryService.DEFAULT_ALLOWED_SERVICES,
                90);
        ApiHistoryEvent event = event("dwp-approval-server", "/v1/approvals/tasks/{id}");

        int accepted = defaultRegistry.ingest("dwp-approval-server", List.of(event));

        assertThat(accepted).isEqualTo(1);
        verify(repository).ingest(List.of(event));
    }

    @Test
    void rejectsUnknownServicesAndQueryStrings() {
        assertThatThrownBy(() -> service.ingest(
                "unknown", List.of(event("unknown", "/health"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.ingest("dwp-gateway", List.of(
                event("dwp-gateway", "/v1/people?email=private@example.com"))))
                .isInstanceOf(BaseException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsAServiceClaimThatDoesNotMatchTheBatch() {
        assertThatThrownBy(() -> service.ingest(
                "dwp-platform-server", List.of(event("dwp-gateway", "/health"))))
                .isInstanceOf(BaseException.class);

        verifyNoInteractions(repository);
    }

    private ApiHistoryEvent event(String serviceName, String path) {
        Instant occurredAt = Instant.now().minusMillis(20);
        return new ApiHistoryEvent(
                UUID.randomUUID(),
                occurredAt,
                Instant.now(),
                7L,
                "USER",
                "42",
                "SESSION",
                serviceName,
                "1.0.0",
                "instance-1",
                "test",
                "GATEWAY",
                "platform-server",
                "GET",
                path,
                path,
                "http",
                "HTTP/1.1",
                200,
                "SUCCESS",
                20,
                null,
                128L,
                "correlation-1",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7",
                null,
                null,
                "CHROMIUM",
                null,
                null,
                "dwp-api-history-v1");
    }
}
