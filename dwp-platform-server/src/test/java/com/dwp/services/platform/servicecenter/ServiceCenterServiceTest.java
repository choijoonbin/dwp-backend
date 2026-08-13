package com.dwp.services.platform.servicecenter;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.CatalogLifecycle.ACTIVE;
import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.DataClassification.INTERNAL;
import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.RequestPriority.NORMAL;
import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.RequestStatus.DRAFT;
import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.RequestStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceCenterServiceTest {

    @Mock
    private ServiceCenterRepository repository;
    @Mock
    private PlatformAuditService audit;

    private ServiceCenterService service;
    private JsonNode schema;

    @BeforeEach
    void setUp() throws Exception {
        service = new ServiceCenterService(repository, audit);
        schema = new ObjectMapper().readTree("""
                {"fields":[
                  {"key":"systemName","type":"TEXT","labelKo":"시스템","labelEn":"System","required":true},
                  {"key":"issueType","type":"SELECT","labelKo":"유형","labelEn":"Type","required":true,"options":["SIGN_IN","MFA"]}
                ]}
                """);
    }

    @Test
    void draftAllowsIncompleteRequiredValues() {
        UUID idempotencyKey = UUID.randomUUID();
        ServiceCenterRepository.DefinitionRecord definition = definition();
        ServiceCenterRepository.RequestRecord created = request(DRAFT, Map.of());
        when(repository.findByIdempotency(7L, 11L, idempotencyKey)).thenReturn(Optional.empty());
        when(repository.definition(7L, definition.serviceKey())).thenReturn(Optional.of(definition));
        when(repository.insertRequest(
                7L, 11L, definition, "Need help", Map.of(), idempotencyKey, false))
                .thenReturn(created);
        when(repository.timeline(7L, created.requestId())).thenReturn(List.of());

        ServiceCenterDtos.RequestDetail result = service.createRequest(
                7L, 11L, "corr", new ServiceCenterDtos.CreateRequest(
                        definition.serviceKey(), "Need help", Map.of(), idempotencyKey, false));

        assertThat(result.request().status()).isEqualTo(DRAFT);
        verify(repository).addTimeline(
                7L, created.requestId(), "DRAFT_CREATED", DRAFT, "USER", 11L, null);
    }

    @Test
    void submissionRejectsMissingRequiredValues() {
        UUID idempotencyKey = UUID.randomUUID();
        when(repository.findByIdempotency(7L, 11L, idempotencyKey)).thenReturn(Optional.empty());
        when(repository.definition(7L, "technology.account-help"))
                .thenReturn(Optional.of(definition()));

        assertThatThrownBy(() -> service.createRequest(
                7L, 11L, "corr", new ServiceCenterDtos.CreateRequest(
                        "technology.account-help", "Need help", Map.of(), idempotencyKey, true)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(repository, never()).insertRequest(any(), any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    void submissionRejectsUnregisteredSelectOption() {
        UUID idempotencyKey = UUID.randomUUID();
        when(repository.findByIdempotency(7L, 11L, idempotencyKey)).thenReturn(Optional.empty());
        when(repository.definition(7L, "technology.account-help"))
                .thenReturn(Optional.of(definition()));

        assertThatThrownBy(() -> service.createRequest(
                7L, 11L, "corr", new ServiceCenterDtos.CreateRequest(
                        "technology.account-help", "Need help",
                        Map.of("systemName", "DWP", "issueType", "ROOT_ACCESS"),
                        idempotencyKey, true)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void operatorCannotSkipFromSubmittedToResolved() {
        UUID requestId = UUID.randomUUID();
        when(repository.findOperationalRequest(7L, requestId))
                .thenReturn(Optional.of(request(SUBMITTED, Map.of(
                        "systemName", "DWP", "issueType", "SIGN_IN"))));

        assertThatThrownBy(() -> service.transition(
                7L, 22L, "corr", requestId,
                new ServiceCenterDtos.TransitionRequest(
                        ServiceCenterTypes.RequestStatus.RESOLVED, "skip", null, 0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(repository, never()).changeStatus(any(), any(), any(), any(), any(), any(), any(), any(Long.class));
    }

    @Test
    void administrationCatalogPreservesBothLocalizedDefinitions() {
        when(repository.definitions(7L, true)).thenReturn(List.of(definition()));

        ServiceCenterDtos.AdminCatalogItem item = service.adminCatalog(7L).getFirst();

        assertThat(item.nameKo()).isEqualTo("계정 지원");
        assertThat(item.nameEn()).isEqualTo("Account help");
    }

    @Test
    void operationsQueueUsesTheNonDraftRepositoryBoundary() {
        ServiceCenterRepository.RequestRecord submitted = request(SUBMITTED, Map.of(
                "systemName", "DWP", "issueType", "SIGN_IN"));
        when(repository.listOperationalRequests(7L, null)).thenReturn(List.of(submitted));

        List<ServiceCenterDtos.RequestSummary> result = service.operationsQueue(7L, null);

        assertThat(result).singleElement()
                .extracting(ServiceCenterDtos.RequestSummary::status)
                .isEqualTo(SUBMITTED);
        verify(repository, never()).listRequests(7L, null, null);
    }

    @Test
    void editingAndSubmittingADraftUsesOneTransactionalServiceOperation() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> values = Map.of(
                "systemName", "DWP", "issueType", "SIGN_IN");
        ServiceCenterRepository.RequestRecord draft = request(DRAFT, Map.of());
        ServiceCenterRepository.RequestRecord submitted = request(SUBMITTED, values);
        when(repository.findRequest(7L, requestId))
                .thenReturn(Optional.of(draft))
                .thenReturn(Optional.of(submitted));
        when(repository.updateDraft(7L, 11L, requestId, "Need sign-in help", values, 0L))
                .thenReturn(1);
        when(repository.definition(7L, draft.serviceKey())).thenReturn(Optional.of(definition()));
        when(repository.changeStatus(
                eq(7L), eq(11L), eq(requestId), eq(SUBMITTED), isNull(),
                any(OffsetDateTime.class), any(OffsetDateTime.class), eq(1L)))
                .thenReturn(1);
        when(repository.timeline(7L, submitted.requestId())).thenReturn(List.of());

        ServiceCenterDtos.RequestDetail result = service.updateDraft(
                7L, 11L, "corr", requestId,
                new ServiceCenterDtos.UpdateDraftRequest(
                        "Need sign-in help", values, 0L, true));

        assertThat(result.request().status()).isEqualTo(SUBMITTED);
        verify(repository).addTimeline(
                7L, requestId, "DRAFT_UPDATED", DRAFT, "USER", 11L, null);
        verify(repository).addTimeline(
                7L, requestId, "REQUEST_SUBMITTED", SUBMITTED, "USER", 11L, null);
    }

    @Test
    void operationsCannotOpenARequesterDraftById() {
        UUID requestId = UUID.randomUUID();
        when(repository.findOperationalRequest(7L, requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.operationsDetail(7L, requestId))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private ServiceCenterRepository.DefinitionRecord definition() {
        return new ServiceCenterRepository.DefinitionRecord(
                31L, "technology.account-help", "TECHNOLOGY",
                "계정 지원", "Account help", "계정 문제", "Account issues",
                "Identity Operations", ACTIVE, schema, 1, 4, 2,
                INTERNAL, true, List.of("account"), 0L);
    }

    private ServiceCenterRepository.RequestRecord request(
            ServiceCenterTypes.RequestStatus status, Map<String, Object> values) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ServiceCenterRepository.RequestRecord(
                UUID.randomUUID(), "SR-00001001", 11L, 31L,
                "technology.account-help", "계정 지원", "Account help", "Need help",
                values, schema, 1, status, NORMAL, INTERNAL, "Identity Operations",
                null, status == DRAFT ? null : now, status == DRAFT ? null : now.plusHours(4),
                now, 0L);
    }
}
