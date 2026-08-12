package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventCorrelationServiceTest {

    @Mock
    private EventCorrelationRepository repository;

    private EventCorrelationService service;

    @BeforeEach
    void setUp() {
        service = new EventCorrelationService(repository);
    }

    @Test
    void normalizesFiltersAndBoundsPagination() {
        when(repository.correlations(any(), anyInt(), anyInt()))
                .thenReturn(new EventEnvelopeDtos.CorrelationPage(List.of(), 0, 100, 0, 0));

        service.correlations(
                9L, AuditWindow.D7, " identity_access ", "confidential", " denied ", -2, 500);

        ArgumentCaptor<EventCorrelationCriteria> criteria =
                ArgumentCaptor.forClass(EventCorrelationCriteria.class);
        verify(repository).correlations(criteria.capture(), org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(100));
        assertThat(criteria.getValue().tenantId()).isEqualTo(9L);
        assertThat(criteria.getValue().domain()).isEqualTo("IDENTITY_ACCESS");
        assertThat(criteria.getValue().classification()).isEqualTo("CONFIDENTIAL");
        assertThat(criteria.getValue().query()).isEqualTo("denied");
        assertThat(criteria.getValue().to()).isAfter(criteria.getValue().from());
    }

    @Test
    void rejectsUnknownDomainBeforeQueryingTheLedger() {
        assertThatThrownBy(() -> service.correlations(
                9L, AuditWindow.D7, "PAYROLL", "ALL", null, 0, 25))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void returnsChronologicalEnvelopeDetail() {
        EventEnvelopeDtos.Correlation summary = correlation("corr-42");
        when(repository.correlation(9L, "corr-42")).thenReturn(Optional.of(summary));
        when(repository.envelopes(9L, "corr-42")).thenReturn(List.of());

        EventEnvelopeDtos.CorrelationDetail result = service.detail(9L, " corr-42 ");

        assertThat(result.summary()).isEqualTo(summary);
        verify(repository).envelopes(9L, "corr-42");
    }

    @Test
    void masksMissingTenantCorrelationAsNotFound() {
        when(repository.correlation(9L, "corr-42")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(9L, "corr-42"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private EventEnvelopeDtos.Correlation correlation(String id) {
        Instant now = Instant.now();
        return new EventEnvelopeDtos.Correlation(
                id, now.minusSeconds(30), now, 3, 2, 2,
                List.of("IDENTITY_ACCESS", "PEOPLE_WORKFORCE"),
                List.of("CONFIDENTIAL"),
                List.of("dwp-auth-server", "dwp-people-server"),
                List.of("SUCCESS"), "identity.updated", "USER", "7", "박현우",
                "MEDIUM", 52, false);
    }
}
