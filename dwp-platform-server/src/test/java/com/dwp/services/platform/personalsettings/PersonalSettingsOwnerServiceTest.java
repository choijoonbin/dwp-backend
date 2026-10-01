package com.dwp.services.platform.personalsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalSettingsOwnerServiceTest {

    @Mock
    private PersonalSettingFavoriteRepository favoriteRepository;
    @Mock
    private PersonalSettingActivityRepository activityRepository;
    @Mock
    private PersonalSettingsWorkspaceStateRepository workspaceStateRepository;
    @Mock
    private PersonalPrivacyConsentRepository consentRepository;
    @Mock
    private PersonalPrivacyRequestRepository requestRepository;
    @Mock
    private PersonalPrivacyRequestEventRepository requestEventRepository;
    @Mock
    private PersonalPrivacyRequestReceiptRepository requestReceiptRepository;
    @Mock
    private PlatformAuditService auditService;

    private ObjectMapper objectMapper;
    private PersonalSettingsOwnerService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new PersonalSettingsOwnerService(
                favoriteRepository, activityRepository, workspaceStateRepository,
                consentRepository, requestRepository, requestEventRepository,
                requestReceiptRepository,
                auditService, objectMapper);
    }

    @Test
    void workspaceReadsOnlyTheAuthenticatedTenantAndUserAndDeduplicatesRecentKinds() {
        PersonalSettingFavorite favorite = PersonalSettingFavorite.builder()
                .tenantId(7L).userId(11L).settingKey("security").favorite(true).version(2L).build();
        PersonalSettingActivity newest = activity("security", "VIEW", LocalDateTime.now());
        PersonalSettingActivity duplicate = activity("security", "VIEW", LocalDateTime.now().minusMinutes(1));
        PersonalSettingActivity changed = activity("security", "CHANGE", LocalDateTime.now().minusMinutes(2));
        when(favoriteRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(7L, 11L))
                .thenReturn(List.of(favorite));
        when(activityRepository.findTop50ByTenantIdAndUserIdOrderByOccurredAtDesc(7L, 11L))
                .thenReturn(List.of(newest, duplicate, changed));
        PersonalSettingsWorkspaceState state = PersonalSettingsWorkspaceState.builder()
                .tenantId(7L).userId(11L)
                .lastChangeAt(LocalDateTime.now().minusHours(1))
                .lastConfirmedAt(LocalDateTime.now().minusHours(2))
                .version(3L).build();
        when(workspaceStateRepository.findById(new PersonalSettingsWorkspaceStateId(7L, 11L)))
                .thenReturn(Optional.of(state));

        PersonalSettingsDtos.Workspace result = service.workspace(7L, 11L);

        assertThat(result.favorites()).extracting(PersonalSettingsDtos.Favorite::settingKey)
                .containsExactly("security");
        assertThat(result.recentActivity()).extracting(PersonalSettingsDtos.Activity::activityType)
                .containsExactly("VIEW", "CHANGE");
        assertThat(result.observation().sourceState()).isEqualTo("AVAILABLE");
        assertThat(result.observation().freshnessState())
                .isEqualTo("CHANGED_SINCE_CONFIRMATION");
        assertThat(result.observation().offlineBehavior()).isEqualTo("MEMORY_ONLY_READ_ONLY");
        verify(favoriteRepository).findByTenantIdAndUserIdOrderByUpdatedAtDesc(7L, 11L);
        verify(activityRepository).findTop50ByTenantIdAndUserIdOrderByOccurredAtDesc(7L, 11L);
    }

    @Test
    void favoriteUsesOptimisticVersionAndRejectsUnknownKeys() {
        PersonalSettingFavorite favorite = PersonalSettingFavorite.builder()
                .tenantId(7L).userId(11L).settingKey("profile").favorite(false).version(3L).build();
        when(favoriteRepository.findByTenantIdAndUserIdAndSettingKey(7L, 11L, "profile"))
                .thenReturn(Optional.of(favorite));

        assertThatThrownBy(() -> service.updateFavorite(
                7L, 11L, "profile", null,
                new PersonalSettingsDtos.UpdateFavoriteRequest(true, 2L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> service.updateFavorite(
                7L, 11L, "billing", null,
                new PersonalSettingsDtos.UpdateFavoriteRequest(true, 0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(favoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    void preferencePatchProducesServerOwnedChangeEventsForEachKnownNamespace() {
        when(activityRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectNode patch = objectMapper.createObjectNode();
        patch.putObject("appearance").put("mode", "dark");
        patch.putObject("regional").put("timeZone", "Asia/Seoul");

        service.recordPreferenceChange(7L, 11L, patch);

        ArgumentCaptor<PersonalSettingActivity> captor = ArgumentCaptor.forClass(PersonalSettingActivity.class);
        verify(activityRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(PersonalSettingActivity::getSettingKey)
                .containsExactly("appearance", "language");
        assertThat(captor.getAllValues()).allSatisfy(activity -> {
            assertThat(activity.getTenantId()).isEqualTo(7L);
            assertThat(activity.getUserId()).isEqualTo(11L);
            assertThat(activity.getActivityType()).isEqualTo("CHANGE");
        });
    }

    @Test
    void passiveViewDoesNotInvalidateAnExplicitWorkspaceConfirmation() {
        when(activityRepository
                .findTopByTenantIdAndUserIdAndSettingKeyAndActivityTypeOrderByOccurredAtDesc(
                        7L, 11L, "security", "VIEW"))
                .thenReturn(Optional.empty());
        when(activityRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PersonalSettingsDtos.Activity result = service.recordView(7L, 11L, "security");

        assertThat(result.activityType()).isEqualTo("VIEW");
        verify(workspaceStateRepository, never()).save(any());
    }

    @Test
    void aNewConsentDecisionIsAProfileChangeAndInvalidatesWorkspaceFreshness() {
        when(consentRepository.findTopByTenantIdAndUserIdAndPurposeKeyOrderByOccurredAtDesc(
                7L, 11L, "PRODUCT_ANALYTICS")).thenReturn(Optional.empty());
        when(consentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(activityRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workspaceStateRepository.findById(new PersonalSettingsWorkspaceStateId(7L, 11L)))
                .thenReturn(Optional.empty());

        service.updateProductAnalyticsConsent(
                7L, 11L, "corr-consent",
                new PersonalSettingsDtos.UpdateConsentRequest(true, "notice-2"));

        ArgumentCaptor<PersonalSettingActivity> activity =
                ArgumentCaptor.forClass(PersonalSettingActivity.class);
        verify(activityRepository).save(activity.capture());
        assertThat(activity.getValue().getSettingKey()).isEqualTo("profile");
        assertThat(activity.getValue().getActivityType()).isEqualTo("CHANGE");
        assertThat(activity.getValue().getChangedFields().toString())
                .contains("productAnalyticsConsent");
        verify(workspaceStateRepository).save(any(PersonalSettingsWorkspaceState.class));
    }

    @Test
    void consentLedgerIsImmutableAndRepeatedSameNoticeIsIdempotent() {
        PersonalPrivacyConsent existing = PersonalPrivacyConsent.builder()
                .id(UUID.randomUUID()).tenantId(7L).userId(11L)
                .purposeKey("PRODUCT_ANALYTICS").consentState("GRANTED")
                .noticeVersion("notice-1").source("ACCOUNT_SETTINGS")
                .occurredAt(LocalDateTime.now()).build();
        when(consentRepository.findTopByTenantIdAndUserIdAndPurposeKeyOrderByOccurredAtDesc(
                7L, 11L, "PRODUCT_ANALYTICS")).thenReturn(Optional.of(existing));

        PersonalSettingsDtos.Consent result = service.updateProductAnalyticsConsent(
                7L, 11L, null, new PersonalSettingsDtos.UpdateConsentRequest(true, "notice-1"));

        assertThat(result.consentId()).isEqualTo(existing.getId());
        verify(consentRepository, never()).save(any());
    }

    @Test
    void consentLedgerStatesItsProductLocalCoverageAndBoundedHistory() {
        LocalDateTime now = LocalDateTime.now();
        List<PersonalPrivacyConsent> bounded = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> PersonalPrivacyConsent.builder()
                        .id(UUID.randomUUID()).tenantId(7L).userId(11L)
                        .purposeKey("PRODUCT_ANALYTICS")
                        .consentState(index % 2 == 0 ? "GRANTED" : "WITHDRAWN")
                        .noticeVersion("notice-" + index).source("ACCOUNT_SETTINGS")
                        .occurredAt(now.minusMinutes(index)).build())
                .toList();
        when(consentRepository.findTop51ByTenantIdAndUserIdOrderByOccurredAtDesc(7L, 11L))
                .thenReturn(bounded);
        when(consentRepository.findTopByTenantIdAndUserIdAndPurposeKeyOrderByOccurredAtDesc(
                7L, 11L, "PRODUCT_ANALYTICS")).thenReturn(Optional.of(bounded.getFirst()));

        PersonalSettingsDtos.ConsentLedger result = service.consentLedger(7L, 11L);

        assertThat(result.currentProductAnalytics().consentId()).isEqualTo(bounded.getFirst().getId());
        assertThat(result.history()).hasSize(50);
        assertThat(result.historyHasMore()).isTrue();
        assertThat(result.historyLimit()).isEqualTo(50);
        assertThat(result.coveredPurposes()).containsExactly("PRODUCT_ANALYTICS");
        assertThat(result.coverageState()).isEqualTo("PRODUCT_LOCAL");
        assertThat(result.coverageBoundary())
                .isEqualTo("CROSS_PRODUCT_CONSENT_SOURCES_NOT_CONNECTED");
    }

    @Test
    void privacyRequestAndLifecyclePagesExposeTheLimitPlusOneBoundary() {
        LocalDateTime now = LocalDateTime.now();
        List<PersonalPrivacyRequest> requests = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> {
                    PersonalPrivacyRequest request = PersonalPrivacyRequest.builder()
                            .id(new UUID(0L, index + 1L)).tenantId(7L).userId(11L)
                            .requestType("DATA_EXPORT").requestState("RECEIVED")
                            .requestedScope("ALL_PERSONAL_DATA").version(0L).build();
                    request.setCreatedAt(now.minusMinutes(index));
                    request.setUpdatedAt(now.minusMinutes(index));
                    return request;
                })
                .toList();
        UUID requestId = requests.getFirst().getId();
        List<PersonalPrivacyRequestEvent> events = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> PersonalPrivacyRequestEvent.builder()
                        .id(new UUID(1L, index + 1L)).requestId(requestId)
                        .tenantId(7L).userId(11L)
                        .eventType("REQUEST_RECEIVED").requestState("RECEIVED")
                        .detailKey("PRIVACY_REQUEST_INTAKE_RECORDED")
                        .occurredAt(now.minusSeconds(index)).build())
                .toList();
        when(requestRepository.findOwnerPageWithOpenRequestsFirst(
                7L, 11L, PageRequest.of(0, 51))).thenReturn(requests);
        when(requestReceiptRepository
                .findByTenantIdAndUserIdAndRequestIdInOrderByIssuedAtDescReceiptIdDesc(
                        7L, 11L, requests.subList(0, 50).stream()
                                .map(PersonalPrivacyRequest::getId).toList()))
                .thenReturn(List.of());
        when(requestEventRepository.findByRequestIdAndTenantIdAndUserIdOrderByOccurredAtDescIdDesc(
                requestId, 7L, 11L, PageRequest.of(0, 51)))
                .thenReturn(events);

        PersonalSettingsDtos.PrivacyRequestPage result = service.privacyRequests(7L, 11L, 50);

        assertThat(result.items()).hasSize(50);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.limit()).isEqualTo(50);
        assertThat(result.items().getFirst().lifecycle()).hasSize(50);
        assertThat(result.items().getFirst().lifecycleHasMore()).isTrue();
        assertThat(result.items().getFirst().lifecycleLimit()).isEqualTo(50);
    }

    @Test
    void privacyRequestPageIsCompleteAtTheExactLimit() {
        LocalDateTime now = LocalDateTime.now();
        List<PersonalPrivacyRequest> requests = java.util.stream.IntStream.range(0, 50)
                .mapToObj(index -> {
                    PersonalPrivacyRequest request = PersonalPrivacyRequest.builder()
                            .id(new UUID(2L, index + 1L)).tenantId(7L).userId(11L)
                            .requestType("DATA_EXPORT").requestState("CANCELLED")
                            .requestedScope("ALL_PERSONAL_DATA").version(1L).build();
                    request.setCreatedAt(now.minusMinutes(index));
                    request.setUpdatedAt(now.minusMinutes(index));
                    return request;
                })
                .toList();
        when(requestRepository.findOwnerPageWithOpenRequestsFirst(
                7L, 11L, PageRequest.of(0, 51))).thenReturn(requests);
        when(requestReceiptRepository
                .findByTenantIdAndUserIdAndRequestIdInOrderByIssuedAtDescReceiptIdDesc(
                        7L, 11L, requests.stream().map(PersonalPrivacyRequest::getId).toList()))
                .thenReturn(List.of());

        PersonalSettingsDtos.PrivacyRequestPage result = service.privacyRequests(7L, 11L, 50);

        assertThat(result.items()).hasSize(50);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.limit()).isEqualTo(50);
    }

    @Test
    void privacyRequestPageKeepsBothOpenRequestTypesVisibleAtTheEffectiveMinimum() {
        LocalDateTime now = LocalDateTime.now();
        PersonalPrivacyRequest export = PersonalPrivacyRequest.builder()
                .id(new UUID(3L, 1L)).tenantId(7L).userId(11L)
                .requestType("DATA_EXPORT").requestState("RECEIVED")
                .requestedScope("ALL_PERSONAL_DATA").version(0L).build();
        export.setCreatedAt(now.minusDays(30));
        export.setUpdatedAt(now.minusDays(30));
        PersonalPrivacyRequest deletion = PersonalPrivacyRequest.builder()
                .id(new UUID(3L, 2L)).tenantId(7L).userId(11L)
                .requestType("ACCOUNT_DELETION").requestState("RECEIVED")
                .requestedScope("ACCOUNT_AND_PERSONAL_DATA").version(0L).build();
        deletion.setCreatedAt(now.minusDays(31));
        deletion.setUpdatedAt(now.minusDays(31));
        PersonalPrivacyRequest recentCancelled = PersonalPrivacyRequest.builder()
                .id(new UUID(3L, 3L)).tenantId(7L).userId(11L)
                .requestType("DATA_EXPORT").requestState("CANCELLED")
                .requestedScope("ALL_PERSONAL_DATA").version(1L).build();
        recentCancelled.setCreatedAt(now);
        recentCancelled.setUpdatedAt(now);
        when(requestRepository.findOwnerPageWithOpenRequestsFirst(
                7L, 11L, PageRequest.of(0, 3)))
                .thenReturn(List.of(export, deletion, recentCancelled));
        when(requestReceiptRepository
                .findByTenantIdAndUserIdAndRequestIdInOrderByIssuedAtDescReceiptIdDesc(
                        7L, 11L, List.of(export.getId(), deletion.getId())))
                .thenReturn(List.of());

        PersonalSettingsDtos.PrivacyRequestPage result = service.privacyRequests(7L, 11L, 1);

        assertThat(result.limit()).isEqualTo(2);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.items()).extracting(PersonalSettingsDtos.PrivacyRequest::requestType)
                .containsExactly("DATA_EXPORT", "ACCOUNT_DELETION");
        assertThat(result.items()).extracting(PersonalSettingsDtos.PrivacyRequest::requestState)
                .containsOnly("RECEIVED");
    }

    @Test
    void workspaceReconfirmationUsesOptimisticVersionAndRecordsTheConfirmation() {
        PersonalSettingsWorkspaceState state = PersonalSettingsWorkspaceState.builder()
                .tenantId(7L).userId(11L)
                .lastChangeAt(LocalDateTime.now().minusHours(1))
                .version(4L).build();
        when(workspaceStateRepository.findById(new PersonalSettingsWorkspaceStateId(7L, 11L)))
                .thenReturn(Optional.of(state));
        when(workspaceStateRepository.saveAndFlush(state)).thenAnswer(invocation -> {
            state.setVersion(5L);
            return state;
        });
        when(favoriteRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(7L, 11L))
                .thenReturn(List.of());
        when(activityRepository.findTop50ByTenantIdAndUserIdOrderByOccurredAtDesc(7L, 11L))
                .thenReturn(List.of());

        PersonalSettingsDtos.Workspace result = service.reconfirmWorkspace(7L, 11L, "corr-2", 4L);

        assertThat(state.getLastConfirmedAt()).isNotNull();
        assertThat(result.observation().freshnessState()).isEqualTo("CURRENT");
        assertThat(result.observation().version()).isEqualTo(5L);
        verify(auditService).success(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq("personal-settings.workspace.reconfirmed"),
                org.mockito.ArgumentMatchers.eq("PERSONAL_SETTINGS_WORKSPACE"),
                org.mockito.ArgumentMatchers.eq("11"), org.mockito.ArgumentMatchers.eq("corr-2"),
                any(), any());
    }

    @Test
    void deletionRequestRequiresExplicitAcknowledgementAndNeverClaimsFulfillment() {
        PersonalSettingsDtos.CreatePrivacyRequest missingAcknowledgement =
                new PersonalSettingsDtos.CreatePrivacyRequest(
                        "ACCOUNT_DELETION", "ALL_PERSONAL_DATA", null, false);
        assertThatThrownBy(() -> service.createPrivacyRequest(
                7L, 11L, null, missingAcknowledgement))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        when(requestRepository
                .findFirstByTenantIdAndUserIdAndRequestTypeAndRequestStateOrderByCreatedAtDesc(
                        7L, 11L, "DATA_EXPORT", "RECEIVED"))
                .thenReturn(Optional.empty());
        when(requestRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            PersonalPrivacyRequest value = invocation.getArgument(0);
            value.setVersion(0L);
            value.setCreatedAt(LocalDateTime.now());
            value.setUpdatedAt(LocalDateTime.now());
            return value;
        });
        when(requestReceiptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(requestEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PersonalSettingsDtos.PrivacyRequest result = service.createPrivacyRequest(
                7L, 11L, "corr-1",
                new PersonalSettingsDtos.CreatePrivacyRequest(
                        "DATA_EXPORT", "ALL_PERSONAL_DATA", null, false));

        assertThat(result.requestState()).isEqualTo("RECEIVED");
        assertThat(result.fulfillmentAvailable()).isFalse();
        assertThat(result.fulfillmentBoundary())
                .isEqualTo("PRIVACY_OWNER_EXECUTION_NOT_CONNECTED");
        assertThat(result.receipt().receiptType()).isEqualTo("INTAKE");
        assertThat(result.receipt().evidenceState()).isEqualTo("INTAKE_ONLY");
        assertThat(result.receipt().requestFingerprint()).hasSize(64);
        assertThat(result.lifecycle()).extracting(PersonalSettingsDtos.PrivacyRequestEvent::eventType)
                .containsExactly("REQUEST_RECEIVED", "FULFILLMENT_BOUNDARY_RECORDED");
    }

    private PersonalSettingActivity activity(String key, String type, LocalDateTime at) {
        return PersonalSettingActivity.builder()
                .id(UUID.randomUUID()).tenantId(7L).userId(11L).settingKey(key)
                .activityType(type).changedFields(objectMapper.createArrayNode()).occurredAt(at).build();
    }
}
