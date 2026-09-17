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
    private PersonalPrivacyConsentRepository consentRepository;
    @Mock
    private PersonalPrivacyRequestRepository requestRepository;
    @Mock
    private PlatformAuditService auditService;

    private ObjectMapper objectMapper;
    private PersonalSettingsOwnerService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new PersonalSettingsOwnerService(
                favoriteRepository, activityRepository, consentRepository, requestRepository,
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

        PersonalSettingsDtos.Workspace result = service.workspace(7L, 11L);

        assertThat(result.favorites()).extracting(PersonalSettingsDtos.Favorite::settingKey)
                .containsExactly("security");
        assertThat(result.recentActivity()).extracting(PersonalSettingsDtos.Activity::activityType)
                .containsExactly("VIEW", "CHANGE");
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

        PersonalSettingsDtos.PrivacyRequest result = service.createPrivacyRequest(
                7L, 11L, "corr-1",
                new PersonalSettingsDtos.CreatePrivacyRequest(
                        "DATA_EXPORT", "ALL_PERSONAL_DATA", null, false));

        assertThat(result.requestState()).isEqualTo("RECEIVED");
        assertThat(result.fulfillmentAvailable()).isFalse();
        assertThat(result.fulfillmentBoundary())
                .isEqualTo("PRIVACY_OWNER_EXECUTION_NOT_CONNECTED");
    }

    private PersonalSettingActivity activity(String key, String type, LocalDateTime at) {
        return PersonalSettingActivity.builder()
                .id(UUID.randomUUID()).tenantId(7L).userId(11L).settingKey(key)
                .activityType(type).changedFields(objectMapper.createArrayNode()).occurredAt(at).build();
    }
}
