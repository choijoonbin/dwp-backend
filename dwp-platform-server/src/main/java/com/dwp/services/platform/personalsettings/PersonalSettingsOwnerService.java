package com.dwp.services.platform.personalsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PersonalSettingsOwnerService {

    static final Set<String> SETTING_KEYS = Set.of(
            "profile", "security", "appearance", "accessibility", "language",
            "home", "notifications", "managed");
    private static final String VIEW = "VIEW";
    private static final String CHANGE = "CHANGE";
    private static final String RECEIVED = "RECEIVED";
    private static final String CANCELLED = "CANCELLED";
    private static final String PRODUCT_ANALYTICS = "PRODUCT_ANALYTICS";
    private static final String FULFILLMENT_BOUNDARY = "PRIVACY_OWNER_EXECUTION_NOT_CONNECTED";

    private final PersonalSettingFavoriteRepository favoriteRepository;
    private final PersonalSettingActivityRepository activityRepository;
    private final PersonalPrivacyConsentRepository consentRepository;
    private final PersonalPrivacyRequestRepository privacyRequestRepository;
    private final PlatformAuditService auditService;
    private final ObjectMapper objectMapper;

    public PersonalSettingsOwnerService(
            PersonalSettingFavoriteRepository favoriteRepository,
            PersonalSettingActivityRepository activityRepository,
            PersonalPrivacyConsentRepository consentRepository,
            PersonalPrivacyRequestRepository privacyRequestRepository,
            PlatformAuditService auditService,
            ObjectMapper objectMapper) {
        this.favoriteRepository = favoriteRepository;
        this.activityRepository = activityRepository;
        this.consentRepository = consentRepository;
        this.privacyRequestRepository = privacyRequestRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PersonalSettingsDtos.Workspace workspace(Long tenantId, Long userId) {
        List<PersonalSettingsDtos.Favorite> favorites = favoriteRepository
                .findByTenantIdAndUserIdOrderByUpdatedAtDesc(tenantId, userId).stream()
                .filter(PersonalSettingFavorite::isFavorite)
                .map(this::favorite)
                .toList();
        LinkedHashMap<String, PersonalSettingsDtos.Activity> recent = new LinkedHashMap<>();
        activityRepository.findTop50ByTenantIdAndUserIdOrderByOccurredAtDesc(tenantId, userId)
                .forEach(item -> recent.putIfAbsent(
                        item.getActivityType() + ":" + item.getSettingKey(), activity(item)));
        return new PersonalSettingsDtos.Workspace(favorites, recent.values().stream().limit(12).toList());
    }

    @Transactional
    public PersonalSettingsDtos.Favorite updateFavorite(
            Long tenantId,
            Long userId,
            String rawSettingKey,
            String correlationId,
            PersonalSettingsDtos.UpdateFavoriteRequest request) {
        String settingKey = settingKey(rawSettingKey);
        PersonalSettingFavorite value = favoriteRepository
                .findByTenantIdAndUserIdAndSettingKey(tenantId, userId, settingKey)
                .orElseGet(() -> {
                    if (request.version() != 0L) throw conflict();
                    return PersonalSettingFavorite.builder()
                            .tenantId(tenantId)
                            .userId(userId)
                            .settingKey(settingKey)
                            .favorite(false)
                            .build();
                });
        long currentVersion = value.getVersion() == null ? 0L : value.getVersion();
        if (currentVersion != request.version()) throw conflict();
        boolean before = value.isFavorite();
        value.setFavorite(request.favorite());
        try {
            PersonalSettingFavorite saved = favoriteRepository.saveAndFlush(value);
            auditService.success(
                    tenantId, userId, "personal-setting.favorite.updated", "PERSONAL_SETTING",
                    settingKey, correlationId, Map.of("favorite", before),
                    Map.of("favorite", saved.isFavorite()));
            return favorite(saved);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException exception) {
            throw conflict();
        }
    }

    @Transactional
    public PersonalSettingsDtos.Activity recordView(Long tenantId, Long userId, String rawSettingKey) {
        String settingKey = settingKey(rawSettingKey);
        LocalDateTime now = LocalDateTime.now();
        return activityRepository
                .findTopByTenantIdAndUserIdAndSettingKeyAndActivityTypeOrderByOccurredAtDesc(
                        tenantId, userId, settingKey, VIEW)
                .filter(existing -> existing.getOccurredAt().isAfter(now.minusMinutes(5)))
                .map(this::activity)
                .orElseGet(() -> activity(activityRepository.save(PersonalSettingActivity.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .userId(userId)
                        .settingKey(settingKey)
                        .activityType(VIEW)
                        .changedFields(objectMapper.createArrayNode())
                        .occurredAt(now)
                        .build())));
    }

    @Transactional
    public void recordPreferenceChange(Long tenantId, Long userId, JsonNode patch) {
        if (patch == null || !patch.isObject()) return;
        patch.properties().forEach(namespace -> {
            String settingKey = switch (namespace.getKey()) {
                case "appearance" -> "appearance";
                case "accessibility" -> "accessibility";
                case "regional" -> "language";
                default -> null;
            };
            if (settingKey == null) return;
            List<String> fields = new ArrayList<>();
            if (namespace.getValue().isObject()) {
                namespace.getValue().fieldNames().forEachRemaining(fields::add);
            }
            activityRepository.save(PersonalSettingActivity.builder()
                    .id(UUID.randomUUID())
                    .tenantId(tenantId)
                    .userId(userId)
                    .settingKey(settingKey)
                    .activityType(CHANGE)
                    .changedFields(objectMapper.valueToTree(fields))
                    .occurredAt(LocalDateTime.now())
                    .build());
        });
    }

    @Transactional
    public void recordPreferenceReset(Long tenantId, Long userId) {
        for (String settingKey : List.of("appearance", "accessibility", "language")) {
            activityRepository.save(PersonalSettingActivity.builder()
                    .id(UUID.randomUUID())
                    .tenantId(tenantId)
                    .userId(userId)
                    .settingKey(settingKey)
                    .activityType(CHANGE)
                    .changedFields(objectMapper.valueToTree(List.of("reset")))
                    .occurredAt(LocalDateTime.now())
                    .build());
        }
    }

    @Transactional(readOnly = true)
    public PersonalSettingsDtos.ConsentLedger consentLedger(Long tenantId, Long userId) {
        List<PersonalSettingsDtos.Consent> history = consentRepository
                .findTop50ByTenantIdAndUserIdOrderByOccurredAtDesc(tenantId, userId).stream()
                .map(this::consent)
                .toList();
        PersonalSettingsDtos.Consent current = history.stream()
                .filter(item -> PRODUCT_ANALYTICS.equals(item.purposeKey()))
                .findFirst()
                .orElse(null);
        return new PersonalSettingsDtos.ConsentLedger(current, history);
    }

    @Transactional
    public PersonalSettingsDtos.Consent updateProductAnalyticsConsent(
            Long tenantId,
            Long userId,
            String correlationId,
            PersonalSettingsDtos.UpdateConsentRequest request) {
        String state = request.granted() ? "GRANTED" : "WITHDRAWN";
        PersonalPrivacyConsent latest = consentRepository
                .findTopByTenantIdAndUserIdAndPurposeKeyOrderByOccurredAtDesc(
                        tenantId, userId, PRODUCT_ANALYTICS)
                .orElse(null);
        if (latest != null
                && state.equals(latest.getConsentState())
                && request.noticeVersion().equals(latest.getNoticeVersion())) {
            return consent(latest);
        }
        PersonalPrivacyConsent saved = consentRepository.save(PersonalPrivacyConsent.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .userId(userId)
                .purposeKey(PRODUCT_ANALYTICS)
                .consentState(state)
                .noticeVersion(request.noticeVersion().trim())
                .source("ACCOUNT_SETTINGS")
                .occurredAt(LocalDateTime.now())
                .build());
        auditService.success(
                tenantId, userId, "personal-privacy.consent.recorded", "PRIVACY_CONSENT",
                PRODUCT_ANALYTICS, correlationId,
                latest == null ? null : Map.of("state", latest.getConsentState()),
                Map.of("state", saved.getConsentState(), "noticeVersion", saved.getNoticeVersion()));
        return consent(saved);
    }

    @Transactional(readOnly = true)
    public List<PersonalSettingsDtos.PrivacyRequest> privacyRequests(Long tenantId, Long userId) {
        return privacyRequestRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(tenantId, userId)
                .stream().map(this::privacyRequest).toList();
    }

    @Transactional
    public PersonalSettingsDtos.PrivacyRequest createPrivacyRequest(
            Long tenantId,
            Long userId,
            String correlationId,
            PersonalSettingsDtos.CreatePrivacyRequest request) {
        String type = request.requestType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DATA_EXPORT", "ACCOUNT_DELETION").contains(type)) {
            throw invalid("Unsupported privacy request type.");
        }
        if ("ACCOUNT_DELETION".equals(type) && !request.accountDeletionAcknowledged()) {
            throw invalid("Account deletion requests require an explicit acknowledgement.");
        }
        if (!Set.of("PROFILE_AND_SETTINGS", "ALL_PERSONAL_DATA")
                .contains(request.requestedScope().trim())) {
            throw invalid("Unsupported privacy request scope.");
        }
        PersonalPrivacyRequest existing = privacyRequestRepository
                .findFirstByTenantIdAndUserIdAndRequestTypeAndRequestStateOrderByCreatedAtDesc(
                        tenantId, userId, type, RECEIVED)
                .orElse(null);
        if (existing != null) return privacyRequest(existing);
        PersonalPrivacyRequest value = PersonalPrivacyRequest.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .userId(userId)
                .requestType(type)
                .requestState(RECEIVED)
                .requestedScope(request.requestedScope().trim())
                .reason(trimToNull(request.reason()))
                .build();
        try {
            PersonalPrivacyRequest saved = privacyRequestRepository.saveAndFlush(value);
            auditService.success(
                    tenantId, userId, "personal-privacy.request.received", "PRIVACY_REQUEST",
                    saved.getId().toString(), correlationId, null,
                    Map.of("requestType", type, "requestState", RECEIVED,
                            "requestedScope", saved.getRequestedScope()));
            return privacyRequest(saved);
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    @Transactional
    public PersonalSettingsDtos.PrivacyRequest cancelPrivacyRequest(
            Long tenantId,
            Long userId,
            UUID requestId,
            String correlationId,
            long version) {
        PersonalPrivacyRequest value = privacyRequestRepository
                .findByIdAndTenantIdAndUserId(requestId, tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!RECEIVED.equals(value.getRequestState()) || value.getVersion() != version) {
            throw conflict();
        }
        value.setRequestState(CANCELLED);
        try {
            PersonalPrivacyRequest saved = privacyRequestRepository.saveAndFlush(value);
            auditService.success(
                    tenantId, userId, "personal-privacy.request.cancelled", "PRIVACY_REQUEST",
                    saved.getId().toString(), correlationId,
                    Map.of("requestState", RECEIVED), Map.of("requestState", CANCELLED));
            return privacyRequest(saved);
        } catch (OptimisticLockingFailureException exception) {
            throw conflict();
        }
    }

    private PersonalSettingsDtos.Favorite favorite(PersonalSettingFavorite value) {
        return new PersonalSettingsDtos.Favorite(
                value.getSettingKey(), value.isFavorite(), value.getVersion() == null ? 0 : value.getVersion(),
                value.getUpdatedAt());
    }

    private PersonalSettingsDtos.Activity activity(PersonalSettingActivity value) {
        List<String> fields = new ArrayList<>();
        if (value.getChangedFields() != null && value.getChangedFields().isArray()) {
            value.getChangedFields().forEach(field -> fields.add(field.asText()));
        }
        return new PersonalSettingsDtos.Activity(
                value.getId(), value.getSettingKey(), value.getActivityType(), fields,
                value.getOccurredAt());
    }

    private PersonalSettingsDtos.Consent consent(PersonalPrivacyConsent value) {
        return new PersonalSettingsDtos.Consent(
                value.getId(), value.getPurposeKey(), value.getConsentState(), value.getNoticeVersion(),
                value.getSource(), value.getOccurredAt());
    }

    private PersonalSettingsDtos.PrivacyRequest privacyRequest(PersonalPrivacyRequest value) {
        return new PersonalSettingsDtos.PrivacyRequest(
                value.getId(), value.getRequestType(), value.getRequestState(), value.getRequestedScope(),
                value.getReason(), false, FULFILLMENT_BOUNDARY,
                value.getVersion() == null ? 0 : value.getVersion(), value.getCreatedAt(), value.getUpdatedAt());
    }

    private String settingKey(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SETTING_KEYS.contains(value)) throw invalid("Unsupported personal setting key.");
        return value;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }
}
