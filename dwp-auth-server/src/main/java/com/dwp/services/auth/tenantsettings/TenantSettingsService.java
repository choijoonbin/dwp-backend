package com.dwp.services.auth.tenantsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.IdentityAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class TenantSettingsService {

    private static final String OWNER_TYPE = "AUTH_POLICY";
    private static final String OWNER_REF = "tenant-authentication";
    private static final String SSO_TEST_EXECUTION_BOUNDARY =
            "UNCONNECTED_EXTERNAL_IDP_EXECUTOR";
    private static final int MAX_SSO_TEST_HISTORY_LIMIT = 100;
    private static final List<String> PROJECTION_EXCLUSIONS = List.of(
            "EXTERNAL_IDP_GROUPS_NOT_SYNCHRONIZED_TO_AUTH",
            "EXTERNAL_SAAS_LICENSES_AND_SEATS");

    private final TenantSettingsRepository repository;
    private final IdentityAuditService audit;
    private final ObjectMapper objectMapper;
    private final TenantSettingsAuthorization authorization;
    private final InternalEntitlementAdapterRegistry entitlementAdapters;
    private final Clock clock;

    @Autowired
    public TenantSettingsService(
            TenantSettingsRepository repository,
            IdentityAuditService audit,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            InternalEntitlementAdapterRegistry entitlementAdapters) {
        this(repository, audit, objectMapper, new TenantSettingsAuthorization(jdbc),
                entitlementAdapters, Clock.systemUTC());
    }

    TenantSettingsService(
            TenantSettingsRepository repository,
            IdentityAuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this(repository, audit, objectMapper, TenantSettingsAuthorization.testAllowAll(),
                new InternalEntitlementAdapterRegistry(List.of(
                        new CoreIdentityEntitlementAdapter(repository))), clock);
    }

    TenantSettingsService(
            TenantSettingsRepository repository,
            IdentityAuditService audit,
            ObjectMapper objectMapper,
            TenantSettingsAuthorization authorization,
            InternalEntitlementAdapterRegistry entitlementAdapters,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
        this.entitlementAdapters = entitlementAdapters;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TenantSettingsDtos.ChangeSet> authPolicyChanges(Long tenantId, Long actorId) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE, "VIEW");
        return repository.listChangeSets(tenantId).stream()
                .map(change -> withActions(tenantId, actorId, change))
                .toList();
    }

    @Transactional
    public TenantSettingsDtos.ChangeSet createAuthPolicyChange(
            Long tenantId,
            Long actorId,
            String correlationId,
            TenantSettingsDtos.CreateAuthPolicyChangeRequest request) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE, "MANAGE");
        TenantSettingsRepository.PolicyState before = repository.currentPolicy(tenantId);
        TenantSettingsRepository.PolicyState proposed = normalize(request.policy(), before);
        validate(tenantId, proposed);
        JsonNode beforeState = objectMapper.valueToTree(before);
        JsonNode proposedState = objectMapper.valueToTree(proposed);
        String beforeHash = digest(beforeState);
        String proposedHash = digest(proposedState);
        if (beforeHash.equals(proposedHash)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The proposed authentication policy does not change the current policy.");
        }
        Instant now = clock.instant();
        TenantSettingsDtos.Impact impact = new TenantSettingsDtos.Impact(
                "ESTIMATED",
                repository.activeIdentityCount(tenantId),
                "INTERNAL_AUTH_DIRECTORY_ACTIVE_IDENTITIES",
                now,
                List.of(
                        "EXTERNAL_IDP_POPULATION_NOT_PROBED",
                        "EXTERNAL_APPLICATION_SESSIONS_NOT_COUNTED"));
        TenantSettingsDtos.ChangeSet change;
        try {
            change = repository.insertChangeSet(
                    tenantId, beforeState, proposedState, beforeHash, proposedHash,
                    impact, request.justification(), actorId);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An authentication policy draft is already open for this tenant.",
                    exception);
        }
        audit.success(
                tenantId, actorId, "tenant-settings.auth-policy.drafted",
                "TENANT_SETTING_CHANGE_SET", change.changeSetId().toString(), correlationId,
                objectMap(beforeState), objectMap(proposedState));
        return withActions(tenantId, actorId, change);
    }

    @Transactional
    public TenantSettingsDtos.ChangeSet submit(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeSetId,
            TenantSettingsDtos.VersionedCommand command) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE, "MANAGE");
        TenantSettingsDtos.ChangeSet previous = repository.requireChangeSet(tenantId, changeSetId);
        requireOwner(previous);
        TenantSettingsDtos.ChangeSet changed = repository.submit(
                tenantId, changeSetId, command.version(), actorId, clock.instant());
        audit.success(
                tenantId, actorId, "tenant-settings.auth-policy.submitted",
                "TENANT_SETTING_CHANGE_SET", changeSetId.toString(), correlationId,
                Map.of("lifecycleState", previous.lifecycleState(), "version", previous.version()),
                Map.of("lifecycleState", changed.lifecycleState(), "version", changed.version()));
        return withActions(tenantId, actorId, changed);
    }

    @Transactional
    public TenantSettingsDtos.ChangeSet decide(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeSetId,
            TenantSettingsDtos.DecisionCommand command) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE, "APPROVE");
        TenantSettingsDtos.ChangeSet previous = repository.requireChangeSet(tenantId, changeSetId);
        requireOwner(previous);
        if (Objects.equals(previous.requestedBy(), actorId)) {
            audit.denied(
                    tenantId, actorId, "tenant-settings.auth-policy.decided",
                    "TENANT_SETTING_CHANGE_SET", changeSetId.toString(), correlationId,
                    "REQUESTER_CANNOT_REVIEW_OWN_CHANGE",
                    Map.of("decision", command.decision(), "version", command.version()));
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The requester cannot review their own authentication policy change.");
        }
        String nextState = "APPROVE".equals(command.decision()) ? "APPROVED" : "REJECTED";
        TenantSettingsDtos.ChangeSet changed = repository.decide(
                tenantId, changeSetId, command.version(), nextState,
                command.reason(), actorId, clock.instant());
        audit.success(
                tenantId, actorId, "tenant-settings.auth-policy.decided",
                "TENANT_SETTING_CHANGE_SET", changeSetId.toString(), correlationId,
                Map.of("lifecycleState", previous.lifecycleState(), "version", previous.version()),
                Map.of(
                        "lifecycleState", changed.lifecycleState(),
                        "version", changed.version(),
                        "decisionReason", command.reason().trim()));
        return withActions(tenantId, actorId, changed);
    }

    @Transactional
    public TenantSettingsDtos.ChangeSet publish(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeSetId,
            TenantSettingsDtos.VersionedCommand command) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE, "PUBLISH");
        TenantSettingsDtos.ChangeSet change = repository.requireChangeSet(tenantId, changeSetId);
        requireOwner(change);
        if (!"APPROVED".equals(change.lifecycleState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only an independently approved authentication policy can be published.");
        }
        if (Objects.equals(change.requestedBy(), actorId)
                || Objects.equals(change.decidedBy(), actorId)) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The publisher must be independent from the requester and reviewer.");
        }
        TenantSettingsRepository.PolicyState current = repository.currentPolicy(tenantId);
        if (!digest(objectMapper.valueToTree(current)).equals(change.beforeHash())) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The active authentication policy changed after this draft was created.");
        }
        TenantSettingsRepository.PolicyState proposed = state(change.proposedState());
        validate(tenantId, proposed);
        UUID receiptId = UUID.randomUUID();
        TenantSettingsDtos.ChangeSet published = repository.publish(
                tenantId, changeSetId, command.version(), proposed,
                actorId, clock.instant(), receiptId);
        audit.success(
                tenantId, actorId, "tenant-settings.auth-policy.published",
                "TENANT_SETTING_CHANGE_SET", changeSetId.toString(), correlationId,
                objectMap(change.beforeState()),
                Map.of(
                        "policy", objectMap(change.proposedState()),
                        "publishReceiptId", receiptId.toString(),
                        "proposedHash", change.proposedHash()));
        return withActions(tenantId, actorId, published);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantSettingsDtos.AccessProjection accessProjection(
            Long tenantId, Long actorId, String query, int requestedPage, int requestedSize) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.DIRECTORY_RESOURCE, "VIEW");
        int page = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        String normalizedQuery = query == null || query.isBlank()
                ? null : query.trim().toLowerCase(Locale.ROOT);
        Instant observedAt = clock.instant();
        List<TenantSettingsRepository.UserRow> users = repository.users(
                tenantId, normalizedQuery, page, size);
        long total = repository.userCount(tenantId, normalizedQuery);
        InternalEntitlementAdapterRegistry.Projection projection = entitlementAdapters.project(
                tenantId,
                users.stream().map(TenantSettingsRepository.UserRow::userId).toList(),
                observedAt);
        Map<Long, List<TenantSettingsDtos.AccessGrant>> grants = projection.grants();
        List<TenantSettingsDtos.PrincipalAccess> principals = users.stream().map(user -> {
            List<TenantSettingsDtos.AccessGrant> userGrants = grants.getOrDefault(
                    user.userId(), List.of());
            int pending = (int) userGrants.stream()
                    .filter(grant -> "PENDING_APPROVAL".equals(grant.lifecycleState()))
                    .count();
            return new TenantSettingsDtos.PrincipalAccess(
                    user.userId(), user.displayName(), user.email(), user.status(),
                    user.mfaEnabled(), userGrants, pending, user.updatedAt());
        }).toList();
        Instant freshest = projection.owners().stream()
                .map(TenantSettingsDtos.OwnerCoverage::sourceUpdatedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        List<String> includedOwners = projection.owners().stream()
                .map(TenantSettingsDtos.OwnerCoverage::ownerKey)
                .distinct()
                .toList();
        Map<String, String> ownerRevisions = new TreeMap<>();
        projection.owners().forEach(owner -> ownerRevisions.put(
                owner.ownerKey(),
                owner.sourceUpdatedAt() == null ? "none" : owner.sourceUpdatedAt().toString()));
        String snapshotId = digest(objectMapper.valueToTree(Map.of(
                "tenantId", tenantId,
                "query", normalizedQuery == null ? "" : normalizedQuery,
                "totalElements", total,
                "ownerRevisions", ownerRevisions)));
        return new TenantSettingsDtos.AccessProjection(
                snapshotId, observedAt,
                new TenantSettingsDtos.ProjectionCoverage(
                        "COMPLETE_INTERNAL_OWNERS",
                        includedOwners,
                        PROJECTION_EXCLUSIONS,
                        freshest,
                        projection.owners()),
                principals, page, size, total, (int) Math.ceil(total / (double) size));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantSettingsDtos.TenantGovernanceSnapshot governanceSnapshot(
            Long tenantId, Long actorId, Long userId) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE, "VIEW");
        Instant observedAt = clock.instant();
        TenantSettingsRepository.TenantDirectoryRow tenant = repository.tenantDirectory(tenantId);
        TenantSettingsRepository.PolicyState policy = repository.currentPolicy(tenantId);
        TenantSettingsRepository.RecoveryCoverageRow recovery = repository.recoveryCoverage(
                tenantId, observedAt);
        TenantSettingsDtos.SsoTestLoginReceipt latestReceipt = repository
                .latestSsoTestLoginReceipt(tenantId)
                .orElse(null);

        List<String> loginBlocks = new ArrayList<>();
        String prerequisiteState;
        String externalProbeState;
        if (!policy.ssoLoginEnabled()) {
            prerequisiteState = "NOT_REQUIRED";
            externalProbeState = "NOT_REQUIRED";
        } else if (policy.ssoProviderKey() == null
                || !repository.enabledIdentityProviderExists(tenantId, policy.ssoProviderKey())) {
            prerequisiteState = "BLOCKED";
            externalProbeState = "UNAVAILABLE";
            loginBlocks.add("ENABLED_IDENTITY_PROVIDER_NOT_OBSERVED");
        } else {
            prerequisiteState = "READY_FOR_EXTERNAL_PROBE";
            externalProbeState = "UNAVAILABLE";
            loginBlocks.add("EXTERNAL_IDP_LOGIN_EXECUTOR_NOT_CONNECTED");
        }
        if (policy.ssoLoginEnabled()
                && latestReceipt != null
                && Objects.equals(policy.ssoProviderKey(), latestReceipt.providerKey())) {
            externalProbeState = latestReceipt.externalProbeState();
            loginBlocks = new ArrayList<>(latestReceipt.blockingReasons());
        }

        String recoveryState = recovery.verified() > 0
                ? recovery.overdue() > 0 || recovery.notVerified() > 0
                        ? "ATTENTION_REQUIRED" : "READY"
                : "BLOCKED";
        return new TenantSettingsDtos.TenantGovernanceSnapshot(
                observedAt,
                new TenantSettingsDtos.TenantDirectoryProjection(
                        "OBSERVED", tenant.tenantId(), tenant.code(), tenant.name(),
                        tenant.defaultLocale(), tenant.updatedAt()),
                new TenantSettingsDtos.OwnerObservation(
                        "PROVIDER_TENANT_DOMAIN", "UNAVAILABLE", observedAt,
                        List.of(
                                "PROVIDER_TENANT_MAPPING_NOT_EXPOSED_TO_AUTH",
                                "DOMAIN_DNS_VERIFICATION_NOT_OWNED_BY_AUTH")),
                new TenantSettingsDtos.LoginVerification(
                        prerequisiteState, policy.ssoProviderKey(), externalProbeState,
                        latestReceipt == null ? null : latestReceipt.completedAt(),
                        latestReceipt, loginBlocks),
                new TenantSettingsDtos.RecoveryCoverage(
                        recoveryState, recovery.total(), recovery.verified(), recovery.overdue(),
                        recovery.notVerified(), recovery.freshestVerificationAt(),
                        List.of("EXTERNAL_IDP_LOGIN_SUCCESS_NOT_INFERRED_FROM_INTERNAL_DRILL")),
                List.of(
                        new TenantSettingsDtos.OwnerObservation(
                                "AUTH_POLICY", "OBSERVED", observedAt, List.of()),
                        new TenantSettingsDtos.OwnerObservation(
                                "AUDIT_RETENTION_POLICY", "OWNER_ADAPTER_UNAVAILABLE", observedAt,
                                List.of("AUDIT_CONTROL_SERVICE_NOT_ADAPTED")),
                        new TenantSettingsDtos.OwnerObservation(
                                "EXTERNAL_SHARING_POLICY", "OWNER_ADAPTER_UNAVAILABLE", observedAt,
                                List.of("CONTENT_OWNER_NOT_CONNECTED")),
                        new TenantSettingsDtos.OwnerObservation(
                                "LEGAL_HOLD_POLICY", "OWNER_ADAPTER_UNAVAILABLE", observedAt,
                                List.of("LEGAL_HOLD_OWNER_NOT_CONNECTED"))),
                effectiveSettings(tenantId, userId, observedAt));
    }

    @Transactional
    public TenantSettingsDtos.SsoTestLoginReceipt requestSsoTestLogin(
            Long tenantId,
            Long actorId,
            String correlationId,
            TenantSettingsDtos.SsoTestLoginCommand command) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE, "MANAGE");
        TenantSettingsRepository.PolicyState policy = repository.currentPolicy(tenantId);
        Instant requestedAt = clock.instant();
        UUID jobId = UUID.randomUUID();
        List<String> blockingReasons = new ArrayList<>();
        String lifecycleState;
        String internalPrerequisiteState;
        String externalProbeState;
        if (!policy.ssoLoginEnabled()) {
            lifecycleState = "BLOCKED";
            internalPrerequisiteState = "NOT_REQUIRED";
            externalProbeState = "NOT_REQUIRED";
            blockingReasons.add("SSO_LOGIN_NOT_ENABLED");
        } else if (policy.ssoProviderKey() == null
                || !repository.enabledIdentityProviderExists(tenantId, policy.ssoProviderKey())) {
            lifecycleState = "BLOCKED";
            internalPrerequisiteState = "BLOCKED";
            externalProbeState = "UNAVAILABLE";
            blockingReasons.add("ENABLED_IDENTITY_PROVIDER_NOT_OBSERVED");
        } else {
            lifecycleState = "UNAVAILABLE";
            internalPrerequisiteState = "READY_FOR_EXTERNAL_PROBE";
            externalProbeState = "UNAVAILABLE";
            blockingReasons.add("EXTERNAL_IDP_LOGIN_EXECUTOR_NOT_CONNECTED");
        }
        Instant completedAt = clock.instant();
        Map<String, Object> receiptContent = new TreeMap<>();
        receiptContent.put("testLoginJobId", jobId.toString());
        receiptContent.put("tenantId", tenantId);
        receiptContent.put("providerKey", Objects.toString(policy.ssoProviderKey(), ""));
        receiptContent.put("requestedBy", actorId);
        receiptContent.put("idempotencyKey", command.idempotencyKey().toString());
        receiptContent.put("lifecycleState", lifecycleState);
        receiptContent.put("internalPrerequisiteState", internalPrerequisiteState);
        receiptContent.put("externalProbeState", externalProbeState);
        receiptContent.put("blockingReasons", List.copyOf(blockingReasons));
        receiptContent.put("executionBoundary", SSO_TEST_EXECUTION_BOUNDARY);
        receiptContent.put("requestedAt", requestedAt.toString());
        receiptContent.put("completedAt", completedAt.toString());
        String receiptSha256 = digest(objectMapper.valueToTree(receiptContent));
        TenantSettingsRepository.SsoReceiptWrite write = repository.recordSsoTestLoginReceipt(
                tenantId, jobId, policy.ssoProviderKey(), actorId, command.idempotencyKey(),
                command.justification(), lifecycleState, internalPrerequisiteState,
                externalProbeState, blockingReasons, SSO_TEST_EXECUTION_BOUNDARY,
                requestedAt, completedAt, receiptSha256, correlationId);
        TenantSettingsDtos.SsoTestLoginReceipt receipt = write.receipt();
        if (write.created()) {
            audit.success(
                    tenantId, actorId, "tenant-settings.sso-test-login.completed",
                    "SSO_TEST_LOGIN_JOB", receipt.testLoginJobId().toString(), correlationId,
                    Map.of("providerKey", Objects.toString(policy.ssoProviderKey(), "")),
                    Map.of(
                            "lifecycleState", receipt.lifecycleState(),
                            "externalProbeState", receipt.externalProbeState(),
                            "receiptSha256", receipt.receiptSha256()));
        }
        return receipt;
    }

    @Transactional(readOnly = true)
    public TenantSettingsDtos.SsoTestLoginReceiptPage ssoTestLoginReceipts(
            Long tenantId, Long actorId, int limit) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE, "VIEW");
        if (limit < 1 || limit > MAX_SSO_TEST_HISTORY_LIMIT) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The SSO test-login history limit must be between 1 and 100.");
        }
        List<TenantSettingsDtos.SsoTestLoginReceipt> fetched =
                repository.ssoTestLoginReceipts(tenantId, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<TenantSettingsDtos.SsoTestLoginReceipt> items = hasMore
                ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        return new TenantSettingsDtos.SsoTestLoginReceiptPage(items, limit, hasMore);
    }

    @Transactional(readOnly = true)
    public TenantSettingsDtos.SsoTestLoginReceipt ssoTestLoginReceipt(
            Long tenantId, Long actorId, UUID jobId) {
        authorization.require(
                tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE, "VIEW");
        return repository.requireSsoTestLoginReceipt(tenantId, jobId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TenantSettingsDtos.EffectiveSetting> effectiveSettings(
            Long tenantId, Long userId) {
        return effectiveSettings(tenantId, userId, clock.instant());
    }

    @Transactional(readOnly = true)
    public TenantSettingsDtos.UserPreferenceState preferredLocale(
            Long tenantId, Long userId) {
        return preferenceState(repository.userPreference(tenantId, userId));
    }

    @Transactional
    public TenantSettingsDtos.UserPreferenceState restorePreferredLocale(
            Long tenantId,
            Long actorId,
            String correlationId,
            TenantSettingsDtos.RestorePreferenceCommand command) {
        TenantSettingsRepository.UserPreferenceRow before = repository.userPreference(
                tenantId, actorId);
        TenantSettingsRepository.UserPreferenceRow restored = repository.restorePreferredLocale(
                tenantId, actorId, command.version(), actorId, clock.instant());
        audit.success(
                tenantId, actorId, "tenant-settings.preference.locale-restored",
                "USER_PREFERENCE", actorId.toString(), correlationId,
                Map.of(
                        "preferredLocale", Objects.toString(before.preferredLocale(), "INHERITED"),
                        "version", before.version()),
                Map.of(
                        "preferredLocale", "INHERITED",
                        "effectiveLocale", restored.tenantDefaultLocale(),
                        "version", restored.version()));
        return preferenceState(restored);
    }

    private List<TenantSettingsDtos.EffectiveSetting> effectiveSettings(
            Long tenantId, Long userId, Instant evaluatedAt) {
        TenantSettingsRepository.PolicyState policy = repository.currentPolicy(tenantId);
        TenantSettingsRepository.UserPreferenceRow preference = repository.userPreference(
                tenantId, userId);
        List<TenantSettingsDtos.EffectiveSetting> settings = new ArrayList<>();
        settings.add(tenantLockedSetting(
                "authentication.defaultLoginType", policy.defaultLoginType(), evaluatedAt));
        settings.add(tenantLockedSetting(
                "authentication.requireMfa", policy.requireMfa(), evaluatedAt));
        settings.add(tenantLockedSetting(
                "authentication.tokenTtlSec", policy.tokenTtlSec(), evaluatedAt));

        String effectiveLocale = preference.preferredLocale() == null
                ? preference.tenantDefaultLocale() : preference.preferredLocale();
        List<TenantSettingsDtos.SettingSource> localeSources = new ArrayList<>();
        localeSources.add(new TenantSettingsDtos.SettingSource(
                "TENANT", "AUTH_TENANT_DIRECTORY",
                objectMapper.valueToTree(preference.tenantDefaultLocale()),
                preference.preferredLocale() == null ? "WINNER" : "OVERRIDDEN",
                preference.preferredLocale() == null
                        ? "No user override is present." : "A user override has higher precedence."));
        localeSources.add(new TenantSettingsDtos.SettingSource(
                "USER", "AUTH_USER_PROFILE",
                preference.preferredLocale() == null
                        ? null : objectMapper.valueToTree(preference.preferredLocale()),
                preference.preferredLocale() == null ? "INHERITED" : "WINNER",
                preference.preferredLocale() == null
                        ? "The tenant default is inherited." : "The user selected this locale."));
        settings.add(new TenantSettingsDtos.EffectiveSetting(
                "identity.preferredLocale", objectMapper.valueToTree(effectiveLocale),
                "OVERRIDABLE_DEFAULT",
                preference.preferredLocale() == null ? "TENANT" : "USER",
                false, true,
                preference.preferredLocale() == null ? "INHERITED" : "EXPLICIT",
                localeSources, evaluatedAt, "OBSERVED"));
        return List.copyOf(settings);
    }

    private TenantSettingsDtos.EffectiveSetting tenantLockedSetting(
            String key, Object value, Instant evaluatedAt) {
        return new TenantSettingsDtos.EffectiveSetting(
                key, objectMapper.valueToTree(value), "OWNER_RESOLVED", "TENANT",
                true, false, "DISALLOWED",
                List.of(new TenantSettingsDtos.SettingSource(
                        "TENANT", "AUTH_POLICY", objectMapper.valueToTree(value), "WINNER",
                        "The tenant authentication policy owns this value.")),
                evaluatedAt, "OBSERVED");
    }

    private TenantSettingsDtos.UserPreferenceState preferenceState(
            TenantSettingsRepository.UserPreferenceRow value) {
        return new TenantSettingsDtos.UserPreferenceState(
                value.userId(), value.preferredLocale(), value.tenantDefaultLocale(),
                value.version(), value.updatedAt());
    }

    private TenantSettingsDtos.ChangeSet withActions(
            Long tenantId, Long actorId, TenantSettingsDtos.ChangeSet change) {
        List<String> actions = new ArrayList<>();
        if ("DRAFT".equals(change.lifecycleState())
                && Objects.equals(change.requestedBy(), actorId)
                && authorization.can(
                        tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE,
                        "MANAGE")) {
            actions.add("SUBMIT");
        }
        if ("IN_REVIEW".equals(change.lifecycleState())
                && !Objects.equals(change.requestedBy(), actorId)
                && authorization.can(
                        tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE,
                        "APPROVE")) {
            actions.add("APPROVE");
            actions.add("REJECT");
        }
        if ("APPROVED".equals(change.lifecycleState())
                && !Objects.equals(change.requestedBy(), actorId)
                && !Objects.equals(change.decidedBy(), actorId)
                && authorization.can(
                        tenantId, actorId, TenantSettingsAuthorization.POLICY_RESOURCE,
                        "PUBLISH")) {
            actions.add("PUBLISH");
        }
        return new TenantSettingsDtos.ChangeSet(
                change.changeSetId(), change.ownerType(), change.ownerRef(),
                change.lifecycleState(), change.beforeState(), change.proposedState(),
                change.beforeHash(), change.proposedHash(), change.impact(),
                change.justification(), change.requestedBy(), change.submittedAt(),
                change.decidedBy(), change.decidedAt(), change.decisionReason(),
                change.publishedBy(), change.publishedAt(), change.publishReceiptId(),
                change.version(), change.createdAt(), change.updatedAt(), actions);
    }

    private TenantSettingsRepository.PolicyState normalize(
            TenantSettingsDtos.AuthPolicyDraft policy,
            TenantSettingsRepository.PolicyState current) {
        LinkedHashSet<String> loginTypes = new LinkedHashSet<>();
        policy.allowedLoginTypes().stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .forEach(loginTypes::add);
        String providerKey = policy.ssoProviderKey() == null || policy.ssoProviderKey().isBlank()
                ? null : policy.ssoProviderKey().trim();
        return new TenantSettingsRepository.PolicyState(
                policy.defaultLoginType().trim().toUpperCase(Locale.ROOT),
                List.copyOf(loginTypes),
                policy.localLoginEnabled(), policy.ssoLoginEnabled(), providerKey,
                policy.requireMfa(),
                policy.tokenTtlSec() == null ? current.tokenTtlSec() : policy.tokenTtlSec());
    }

    private void validate(Long tenantId, TenantSettingsRepository.PolicyState policy) {
        if (policy.allowedLoginTypes().isEmpty()
                || !policy.allowedLoginTypes().contains(policy.defaultLoginType())) {
            invalid("The default login type must be included in allowed login types.");
        }
        boolean allowsLocal = policy.allowedLoginTypes().contains("LOCAL");
        boolean allowsSso = policy.allowedLoginTypes().contains("SSO");
        if (allowsLocal != policy.localLoginEnabled()) {
            invalid("LOCAL allowance and local-login state must match.");
        }
        if (allowsSso != policy.ssoLoginEnabled()) {
            invalid("SSO allowance and SSO-login state must match.");
        }
        if (!policy.localLoginEnabled() && !policy.ssoLoginEnabled()) {
            invalid("At least one login method must remain enabled.");
        }
        if (policy.ssoLoginEnabled()) {
            if (policy.ssoProviderKey() == null) {
                invalid("An enabled SSO policy requires an identity provider key.");
            }
            if (!repository.enabledIdentityProviderExists(tenantId, policy.ssoProviderKey())) {
                invalid("The selected identity provider is not enabled for this tenant.");
            }
        } else if (policy.ssoProviderKey() != null) {
            invalid("A disabled SSO policy cannot retain an active provider key.");
        }
        if (policy.tokenTtlSec() != null
                && (policy.tokenTtlSec() < 300 || policy.tokenTtlSec() > 86_400)) {
            invalid("Token lifetime must be between 300 and 86400 seconds.");
        }
    }

    private TenantSettingsRepository.PolicyState state(JsonNode value) {
        try {
            return objectMapper.treeToValue(value, TenantSettingsRepository.PolicyState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid governed authentication policy state.", exception);
        }
    }

    private void requireOwner(TenantSettingsDtos.ChangeSet change) {
        if (!OWNER_TYPE.equals(change.ownerType()) || !OWNER_REF.equals(change.ownerRef())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Unsupported tenant setting owner.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(JsonNode value) {
        return objectMapper.convertValue(value, Map.class);
    }

    private String digest(JsonNode value) {
        try {
            JsonNode canonical = canonical(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Tenant setting state hashing failed.", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            var result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private void invalid(String message) {
        throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
