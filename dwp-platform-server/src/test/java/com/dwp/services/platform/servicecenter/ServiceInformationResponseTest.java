package com.dwp.services.platform.servicecenter;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.security.PlatformRoutePredicateEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ServiceInformationResponseTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ServiceCenterRepository repository = mock(ServiceCenterRepository.class);
    private final PlatformAuditService audit = mock(PlatformAuditService.class);
    private final ServiceCenterService service = new ServiceCenterService(
            repository, audit, mock(PlatformRoutePredicateEvaluator.class));
    private final UUID id = UUID.randomUUID();
    private final UUID command = UUID.randomUUID();
    private final Map<String, Object> values = Map.of("systemName", "Finance report");

    private ServiceCenterDtos.InformationResponseRequest input() {
        return new ServiceCenterDtos.InformationResponseRequest(values, "Required for monthly reporting", 3L, command);
    }
    private ServiceCenterRepository.RequestRecord row(Long owner, RequestStatus status, long version) throws Exception {
        var now = OffsetDateTime.now();
        return new ServiceCenterRepository.RequestRecord(id, "SR-0042", owner, 1L,
                "access.request", "접근 신청", "Access request", "Need access", values,
                mapper.readTree("""
                        {"fields":[{"key":"systemName","type":"TEXT","required":true}]}
                        """), 1, status, RequestPriority.NORMAL, DataClassification.INTERNAL,
                "IT", "22", now, now.plusDays(1), now, version);
    }
    @BeforeEach
    void setup() throws Exception {
        when(repository.lockRequest(7L, id)).thenReturn(Optional.of(row(11L, RequestStatus.AWAITING_REQUESTER, 3)));
        when(repository.informationResponseReceipt(7L, 11L, command)).thenReturn(Optional.empty());
    }
    @Test
    void storesResponseAndAuditThenReturnsActualSourceState() throws Exception {
        when(repository.respondToInformationRequest(7L, 11L, id, values, 3L)).thenReturn(1);
        when(repository.findRequest(7L, id)).thenReturn(Optional.of(row(11L, RequestStatus.IN_PROGRESS, 4)));
        var result = service.respondToInformationRequest(7L, 11L, "corr", id, input());
        assertThat(result.request().status()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(result.request().version()).isEqualTo(4);
        verify(repository).addTimeline(7L, id, "REQUESTER_RESPONDED", RequestStatus.IN_PROGRESS,
                "USER", 11L, input().message());
        verify(repository).saveInformationResponse(7L, 11L, id, input());
        verify(audit).success(eq(7L), eq(11L), eq("service.request.information.responded"),
                eq("SERVICE_REQUEST"), eq(id.toString()), eq("corr"), any(), any());
    }
    @Test
    void rejectsForeignOwnerBeforeReceiptReplay() throws Exception {
        when(repository.lockRequest(7L, id)).thenReturn(Optional.of(row(99L, RequestStatus.AWAITING_REQUESTER, 3)));
        assertFailure(input(), ErrorCode.FORBIDDEN);
        verify(repository, never()).informationResponseReceipt(any(), any(), any());
        verifyNoInteractions(audit);
    }
    @Test
    void rejectsMissingOrCrossTenantRequest() {
        when(repository.lockRequest(7L, id)).thenReturn(Optional.empty());
        assertFailure(input(), ErrorCode.NOT_FOUND);
    }
    @Test
    void rejectsWrongStateAndStaleVersion() throws Exception {
        when(repository.lockRequest(7L, id)).thenReturn(Optional.of(row(11L, RequestStatus.SUBMITTED, 3)));
        assertFailure(input(), ErrorCode.RESOURCE_CONFLICT);
        when(repository.lockRequest(7L, id)).thenReturn(Optional.of(row(11L, RequestStatus.AWAITING_REQUESTER, 4)));
        assertFailure(input(), ErrorCode.RESOURCE_CONFLICT);
        verify(repository, never()).respondToInformationRequest(any(), any(), any(), any(), anyLong());
    }
    @Test
    void validatesRequiredFieldsUnknownFieldsAndMessage() {
        assertFailure(new ServiceCenterDtos.InformationResponseRequest(Map.of(), input().message(), 3L, command), ErrorCode.INVALID_INPUT_VALUE);
        assertFailure(new ServiceCenterDtos.InformationResponseRequest(Map.of("systemName", "DWP", "admin", true), input().message(), 3L, command), ErrorCode.INVALID_INPUT_VALUE);
        assertFailure(new ServiceCenterDtos.InformationResponseRequest(values, "          x", 3L, command), ErrorCode.INVALID_INPUT_VALUE);
        verify(repository, never()).respondToInformationRequest(any(), any(), any(), any(), anyLong());
    }
    @Test
    void confirmsExactReplayWithoutDuplicateTimelineOrAudit() throws Exception {
        var receipt = new ServiceCenterRepository.ResponseReceipt(id, mapper.valueToTree(values), input().message(), 3L);
        when(repository.lockRequest(7L, id)).thenReturn(Optional.of(row(11L, RequestStatus.IN_PROGRESS, 4)));
        when(repository.informationResponseReceipt(7L, 11L, command)).thenReturn(Optional.of(receipt));
        when(repository.matchesInformationResponse(receipt, id, input())).thenReturn(true);
        assertThat(service.respondToInformationRequest(7L, 11L, "retry", id, input()).request().version()).isEqualTo(4);
        verify(repository, never()).respondToInformationRequest(any(), any(), any(), any(), anyLong());
        verify(repository, never()).addTimeline(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }
    @Test
    void rejectsCommandIdentityReuseWithDifferentContent() {
        var receipt = new ServiceCenterRepository.ResponseReceipt(id, mapper.valueToTree(values), "Old message content", 3L);
        when(repository.informationResponseReceipt(7L, 11L, command)).thenReturn(Optional.of(receipt));
        assertFailure(input(), ErrorCode.RESOURCE_CONFLICT);
    }
    @Test
    void receiptMatchingUsesRequestVersionAndCompletePayload() {
        var real = new ServiceCenterRepository(mock(org.springframework.jdbc.core.JdbcTemplate.class), mapper);
        var receipt = new ServiceCenterRepository.ResponseReceipt(id, mapper.valueToTree(values), input().message(), 3L);
        assertThat(real.matchesInformationResponse(receipt, id, input())).isTrue();
        assertThat(real.matchesInformationResponse(receipt, UUID.randomUUID(), input())).isFalse();
        assertThat(real.matchesInformationResponse(receipt, id, new ServiceCenterDtos.InformationResponseRequest(values, input().message(), 4L, command))).isFalse();
    }
    private void assertFailure(ServiceCenterDtos.InformationResponseRequest request, ErrorCode code) {
        assertThatThrownBy(() -> service.respondToInformationRequest(7L, 11L, "corr", id, request))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
