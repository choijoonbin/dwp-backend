package com.dwp.services.platform.personalsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.support.CappedList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PersonalSettingsOwnerService {

    private static final int CONSENT_HISTORY_LIMIT = 50;
    private static final int PRIVACY_REQUEST_DEFAULT_LIMIT = 50;
    private static final int PRIVACY_REQUEST_MAX_LIMIT = 100;
    private static final int PRIVACY_REQUEST_EVENT_LIMIT = 50;

    static final Set<String> SETTING_KEYS = Set.of(
            "profile", "security", "appearance", "accessibility", "language",
            "home", "notifications", "managed");
    private static final String VIEW = "VIEW";
    private static final String CHANGE = "CHANGE";
    private static final String RECEIVED = "RECEIVED";
    private static final String CANCELLED = "CANCELLED";
    private static final String PRODUCT_ANALYTICS = "PRODUCT_ANALYTICS";
    private static final String FULFILLMENT_BOUNDARY = "PRIVACY_OWNER_EXECUTION_NOT_CONNECTED";
    private static final String CONSENT_COVERAGE_BOUNDARY =
            "CROSS_PRODUCT_CONSENT_SOURCES_NOT_CONNECTED";
    private static final int WORKSPACE_REVIEW_DAYS = 30;

    private final PersonalSettingFavoriteRepository favoriteRepository;
    private final PersonalSettingActivityRepository activityRepository;
    private final PersonalSettingsWorkspaceStateRepository workspaceStateRepository;
    private final PersonalPrivacyConsentRepository consentRepository;
    private final PersonalPrivacyRequestRepository privacyRequestRepository;
    private final PersonalPrivacyRequestEventRepository privacyRequestEventRepository;
    private final PersonalPrivacyRequestReceiptRepository privacyRequestReceiptRepository;
    private final PlatformAuditService auditService;
    private final ObjectMapper objectMapper;

    public PersonalSettingsOwnerService(
            PersonalSettingFavoriteRepository favoriteRepository,
            PersonalSettingActivityRepository activityRepository,
            PersonalSettingsWorkspaceStateRepository workspaceStateRepository,
            PersonalPrivacyConsentRepository consentRepository,
            PersonalPrivacyRequestRepository privacyRequestRepository,
            PersonalPrivacyRequestEventRepository privacyRequestEventRepository,
            PersonalPrivacyRequestReceiptRepository privacyRequestReceiptRepository,
            PlatformAuditService auditService,
            ObjectMapper objectMapper) {
        this.favoriteRepository = favoriteRepository;
        this.activityRepository = activityRepository;
        this.workspaceStateRepository = workspaceStateRepository;
        this.consentRepository = consentRepository;
        this.privacyRequestRepository = privacyRequestRepository;
        this.privacyRequestEventRepository = privacyRequestEventRepository;
        this.privacyRequestReceiptRepository = privacyRequestReceiptRepository;
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
        PersonalSettingsWorkspaceState state = workspaceStateRepository
                .findById(new PersonalSettingsWorkspaceStateId(tenantId, userId))
                .orElse(null);
        return new PersonalSettingsDtos.Workspace(
                favorites,
                recent.values().stream().limit(12).toList(),
                workspaceObservation(state, LocalDateTime.now()));
    }

    @Transactional
    public PersonalSettingsDtos.Workspace reconfirmWorkspace(
            Long tenantId, Long userId, String correlationId, long version) {
        PersonalSettingsWorkspaceStateId id = new PersonalSettingsWorkspaceStateId(tenantId, userId);
        PersonalSettingsWorkspaceState state = workspaceStateRepository.findById(id).orElse(null);
        long currentVersion = state == null || state.getVersion() == null ? 0L : state.getVersion();
        if (currentVersion != version) throw conflict();
        LocalDateTime now = LocalDateTime.now();
        if (state == null) {
            state = PersonalSettingsWorkspaceState.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .lastChangeAt(now)
                    .lastConfirmedAt(now)
                    .build();
        } else {
            state.setLastConfirmedAt(now);
        }
        try {
            PersonalSettingsWorkspaceState saved = workspaceStateRepository.saveAndFlush(state);
            auditService.success(
                    tenantId, userId, "personal-settings.workspace.reconfirmed",
                    "PERSONAL_SETTINGS_WORKSPACE", userId.toString(), correlationId,
                    Map.of("version", currentVersion),
                    Map.of("version", saved.getVersion() == null ? 0L : saved.getVersion(),
                            "lastConfirmedAt", now));
            return workspace(tenantId, userId);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException exception) {
            throw conflict();
        }
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
            touchWorkspaceState(tenantId, userId, LocalDateTime.now());
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
        PersonalSettingActivity existing = activityRepository
                .findTopByTenantIdAndUserIdAndSettingKeyAndActivityTypeOrderByOccurredAtDesc(
                        tenantId, userId, settingKey, VIEW)
                .filter(activity -> activity.getOccurredAt().isAfter(now.minusMinutes(5)))
                .orElse(null);
        if (existing != null) return activity(existing);
        PersonalSettingActivity saved = activityRepository.save(PersonalSettingActivity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .userId(userId)
                .settingKey(settingKey)
                .activityType(VIEW)
                .changedFields(objectMapper.createArrayNode())
                .occurredAt(now)
                .build());
        return activity(saved);
    }

    @Transactional
    public void recordPreferenceChange(Long tenantId, Long userId, JsonNode patch) {
        if (patch == null || !patch.isObject()) return;
        LocalDateTime now = LocalDateTime.now();
        boolean[] recorded = {false};
        patch.properties().forEach(namespace -> {
            String settingKey = switch (namespace.getKey()) {
                case "appearance" -> "appearance";
                case "accessibility" -> "accessibility";
                case "regional" -> "language";
                default -> null;
            };
            if (settingKey == null) return;
            recorded[0] = true;
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
                    .occurredAt(now)
                    .build());
        });
        if (recorded[0]) touchWorkspaceState(tenantId, userId, now);
    }

    @Transactional
    public void recordPreferenceReset(Long tenantId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        for (String settingKey : List.of("appearance", "accessibility", "language")) {
            activityRepository.save(PersonalSettingActivity.builder()
                    .id(UUID.randomUUID())
                    .tenantId(tenantId)
                    .userId(userId)
                    .settingKey(settingKey)
                    .activityType(CHANGE)
                    .changedFields(objectMapper.valueToTree(List.of("reset")))
                    .occurredAt(now)
                    .build());
        }
        touchWorkspaceState(tenantId, userId, now);
    }

    @Transactional(readOnly = true)
    public PersonalSettingsDtos.ConsentLedger consentLedger(Long tenantId, Long userId) {
        List<PersonalPrivacyConsent> boundedHistory = consentRepository
                .findTop51ByTenantIdAndUserIdOrderByOccurredAtDesc(tenantId, userId);
        boolean historyHasMore = boundedHistory.size() > CONSENT_HISTORY_LIMIT;
        List<PersonalSettingsDtos.Consent> history = boundedHistory.stream()
                .limit(CONSENT_HISTORY_LIMIT)
                .map(this::consent)
                .toList();
        PersonalSettingsDtos.Consent current = consentRepository
                .findTopByTenantIdAndUserIdAndPurposeKeyOrderByOccurredAtDesc(
                        tenantId, userId, PRODUCT_ANALYTICS)
                .map(this::consent)
                .orElse(null);
        return new PersonalSettingsDtos.ConsentLedger(
                current,
                history,
                historyHasMore,
                CONSENT_HISTORY_LIMIT,
                List.of(PRODUCT_ANALYTICS),
                "PRODUCT_LOCAL",
                CONSENT_COVERAGE_BOUNDARY);
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
        recordSettingChange(
                tenantId, userId, "profile", List.of("productAnalyticsConsent"),
                saved.getOccurredAt());
        auditService.success(
                tenantId, userId, "personal-privacy.consent.recorded", "PRIVACY_CONSENT",
                PRODUCT_ANALYTICS, correlationId,
                latest == null ? null : Map.of("state", latest.getConsentState()),
                Map.of("state", saved.getConsentState(), "noticeVersion", saved.getNoticeVersion()));
        return consent(saved);
    }

    @Transactional(readOnly = true)
    public PersonalSettingsDtos.PrivacyRequestPage privacyRequests(
            Long tenantId, Long userId, int requestedLimit) {
        int limit = Math.max(1, Math.min(PRIVACY_REQUEST_MAX_LIMIT, requestedLimit));
        CappedList<PersonalPrivacyRequest> page = CappedList.from(
                privacyRequestRepository.findByTenantIdAndUserIdOrderByCreatedAtDescIdDesc(
                        tenantId, userId, PageRequest.of(0, limit + 1)),
                limit);
        Map<UUID, PersonalPrivacyRequestReceipt> receipts = new HashMap<>();
        List<UUID> requestIds = page.items().stream().map(PersonalPrivacyRequest::getId).toList();
        if (!requestIds.isEmpty()) {
            privacyRequestReceiptRepository
                    .findByTenantIdAndUserIdAndRequestIdInOrderByIssuedAtDescReceiptIdDesc(
                            tenantId, userId, requestIds)
                    .forEach(receipt -> receipts.put(receipt.getRequestId(), receipt));
        }
        List<PersonalSettingsDtos.PrivacyRequest> items = page.items().stream()
                .map(value -> privacyRequest(value, receipts.get(value.getId())))
                .toList();
        return new PersonalSettingsDtos.PrivacyRequestPage(
                items, page.hasMore(), page.limit());
    }

    public PersonalSettingsDtos.PrivacyRequestPage privacyRequests(Long tenantId, Long userId) {
        return privacyRequests(tenantId, userId, PRIVACY_REQUEST_DEFAULT_LIMIT);
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
            LocalDateTime issuedAt = saved.getCreatedAt() == null
                    ? LocalDateTime.now() : saved.getCreatedAt();
            PersonalPrivacyRequestReceipt receipt = privacyRequestReceiptRepository.save(
                    PersonalPrivacyRequestReceipt.builder()
                            .requestId(saved.getId())
                            .receiptId(UUID.randomUUID())
                            .tenantId(tenantId)
                            .userId(userId)
                            .receiptType("INTAKE")
                            .evidenceState("INTAKE_ONLY")
                            .fulfillmentBoundary(FULFILLMENT_BOUNDARY)
                            .requestFingerprint(requestFingerprint(saved, issuedAt))
                            .issuedAt(issuedAt)
                            .build());
            List<PersonalPrivacyRequestEvent> events = List.of(
                    privacyRequestEventRepository.save(requestEvent(
                            saved, "REQUEST_RECEIVED", RECEIVED,
                            "PRIVACY_REQUEST_INTAKE_RECORDED", issuedAt)),
                    privacyRequestEventRepository.save(requestEvent(
                            saved, "FULFILLMENT_BOUNDARY_RECORDED", RECEIVED,
                            FULFILLMENT_BOUNDARY, issuedAt)));
            recordSettingChange(
                    tenantId, userId, "profile", List.of("privacyRequest"), issuedAt);
            auditService.success(
                    tenantId, userId, "personal-privacy.request.received", "PRIVACY_REQUEST",
                    saved.getId().toString(), correlationId, null,
                    Map.of("requestType", type, "requestState", RECEIVED,
                            "requestedScope", saved.getRequestedScope()));
            return privacyRequest(saved, receipt, events);
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
            PersonalPrivacyRequestEvent cancelled = privacyRequestEventRepository.save(requestEvent(
                    saved, "REQUEST_CANCELLED", CANCELLED,
                    "CANCELLED_BY_REQUEST_OWNER", LocalDateTime.now()));
            recordSettingChange(
                    tenantId, userId, "profile", List.of("privacyRequest"),
                    cancelled.getOccurredAt());
            auditService.success(
                    tenantId, userId, "personal-privacy.request.cancelled", "PRIVACY_REQUEST",
                    saved.getId().toString(), correlationId,
                    Map.of("requestState", RECEIVED), Map.of("requestState", CANCELLED));
            PersonalPrivacyRequestReceipt receipt = privacyRequestReceiptRepository
                    .findById(saved.getId()).orElse(null);
            return privacyRequest(saved, receipt);
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
        PersonalPrivacyRequestReceipt receipt = privacyRequestReceiptRepository
                .findById(value.getId()).orElse(null);
        return privacyRequest(value, receipt);
    }

    private PersonalSettingsDtos.PrivacyRequest privacyRequest(
            PersonalPrivacyRequest value,
            PersonalPrivacyRequestReceipt receipt) {
        CappedList<PersonalPrivacyRequestEvent> lifecycle = CappedList.from(
                privacyRequestEventRepository
                        .findByRequestIdAndTenantIdAndUserIdOrderByOccurredAtDescIdDesc(
                                value.getId(), value.getTenantId(), value.getUserId(),
                                PageRequest.of(0, PRIVACY_REQUEST_EVENT_LIMIT + 1)),
                PRIVACY_REQUEST_EVENT_LIMIT);
        return privacyRequest(value, receipt, lifecycle.items(), lifecycle.hasMore());
    }

    private PersonalSettingsDtos.PrivacyRequest privacyRequest(
            PersonalPrivacyRequest value,
            PersonalPrivacyRequestReceipt receipt,
            List<PersonalPrivacyRequestEvent> lifecycle) {
        return privacyRequest(value, receipt, lifecycle, false);
    }

    private PersonalSettingsDtos.PrivacyRequest privacyRequest(
            PersonalPrivacyRequest value,
            PersonalPrivacyRequestReceipt receipt,
            List<PersonalPrivacyRequestEvent> lifecycle,
            boolean lifecycleHasMore) {
        return new PersonalSettingsDtos.PrivacyRequest(
                value.getId(), value.getRequestType(), value.getRequestState(), value.getRequestedScope(),
                value.getReason(), false, FULFILLMENT_BOUNDARY,
                value.getVersion() == null ? 0 : value.getVersion(), value.getCreatedAt(), value.getUpdatedAt(),
                receipt == null ? null : new PersonalSettingsDtos.PrivacyRequestReceipt(
                        receipt.getReceiptId(), receipt.getReceiptType(), receipt.getEvidenceState(),
                        receipt.getFulfillmentBoundary(), receipt.getRequestFingerprint(),
                        receipt.getIssuedAt()),
                lifecycle.stream()
                        .sorted(Comparator.comparing(PersonalPrivacyRequestEvent::getOccurredAt)
                                .thenComparingInt(event -> requestEventOrder(event.getEventType()))
                                .thenComparing(PersonalPrivacyRequestEvent::getId))
                        .map(event -> new PersonalSettingsDtos.PrivacyRequestEvent(
                                event.getId(), event.getEventType(), event.getRequestState(),
                                event.getDetailKey(), event.getOccurredAt()))
                        .toList(),
                lifecycleHasMore,
                PRIVACY_REQUEST_EVENT_LIMIT);
    }

    private int requestEventOrder(String eventType) {
        return switch (eventType) {
            case "REQUEST_RECEIVED" -> 0;
            case "FULFILLMENT_BOUNDARY_RECORDED" -> 1;
            case "REQUEST_CANCELLED" -> 2;
            default -> 99;
        };
    }

    private PersonalSettingsDtos.WorkspaceObservation workspaceObservation(
            PersonalSettingsWorkspaceState state, LocalDateTime observedAt) {
        LocalDateTime lastChangeAt = state == null ? null : state.getLastChangeAt();
        LocalDateTime lastConfirmedAt = state == null ? null : state.getLastConfirmedAt();
        LocalDateTime reviewDueAt = lastConfirmedAt == null
                ? null : lastConfirmedAt.plusDays(WORKSPACE_REVIEW_DAYS);
        String freshnessState;
        if (lastConfirmedAt == null) {
            freshnessState = "UNCONFIRMED";
        } else if (lastChangeAt != null && lastChangeAt.isAfter(lastConfirmedAt)) {
            freshnessState = "CHANGED_SINCE_CONFIRMATION";
        } else if (observedAt.isAfter(reviewDueAt)) {
            freshnessState = "REVIEW_DUE";
        } else {
            freshnessState = "CURRENT";
        }
        return new PersonalSettingsDtos.WorkspaceObservation(
                "AVAILABLE",
                freshnessState,
                observedAt,
                lastChangeAt,
                lastConfirmedAt,
                reviewDueAt,
                state == null || state.getVersion() == null ? 0L : state.getVersion(),
                "MEMORY_ONLY_READ_ONLY");
    }

    private void touchWorkspaceState(Long tenantId, Long userId, LocalDateTime changedAt) {
        PersonalSettingsWorkspaceStateId id = new PersonalSettingsWorkspaceStateId(tenantId, userId);
        PersonalSettingsWorkspaceState state = workspaceStateRepository.findById(id)
                .orElseGet(() -> PersonalSettingsWorkspaceState.builder()
                        .tenantId(tenantId)
                        .userId(userId)
                        .lastChangeAt(changedAt)
                        .build());
        state.setLastChangeAt(changedAt);
        workspaceStateRepository.save(state);
    }

    private void recordSettingChange(
            Long tenantId,
            Long userId,
            String settingKey,
            List<String> changedFields,
            LocalDateTime occurredAt) {
        activityRepository.save(PersonalSettingActivity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .userId(userId)
                .settingKey(settingKey)
                .activityType(CHANGE)
                .changedFields(objectMapper.valueToTree(changedFields))
                .occurredAt(occurredAt)
                .build());
        touchWorkspaceState(tenantId, userId, occurredAt);
    }

    private PersonalPrivacyRequestEvent requestEvent(
            PersonalPrivacyRequest request,
            String eventType,
            String requestState,
            String detailKey,
            LocalDateTime occurredAt) {
        return PersonalPrivacyRequestEvent.builder()
                .id(UUID.randomUUID())
                .requestId(request.getId())
                .tenantId(request.getTenantId())
                .userId(request.getUserId())
                .eventType(eventType)
                .requestState(requestState)
                .detailKey(detailKey)
                .occurredAt(occurredAt)
                .build();
    }

    private String requestFingerprint(PersonalPrivacyRequest request, LocalDateTime issuedAt) {
        String value = String.join("|",
                request.getId().toString(),
                request.getTenantId().toString(),
                request.getUserId().toString(),
                request.getRequestType(),
                request.getRequestedScope(),
                issuedAt.toString());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
