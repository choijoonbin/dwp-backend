package com.dwp.services.auth.tenantsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.IdentityAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.UUID;

@Service
public class TenantSettingsService {

    private static final String OWNER_TYPE = "AUTH_POLICY";
    private static final String OWNER_REF = "tenant-authentication";
    private static final List<String> INTERNAL_PROJECTION_OWNERS = List.of(
            "AUTH_USER_DIRECTORY",
            "DIRECT_ROLE_ASSIGNMENTS",
            "GROUP_ROLE_ASSIGNMENTS",
            "PRIVILEGED_ACCESS_GRANTS",
            "APP_ADMIN_PRESET_ASSIGNMENTS");
    private static final List<String> PROJECTION_EXCLUSIONS = List.of(
            "EXTERNAL_IDP_GROUPS_NOT_SYNCHRONIZED_TO_AUTH",
            "EXTERNAL_SAAS_LICENSES_AND_SEATS",
            "PRODUCT_LOCAL_ENTITLEMENTS_WITHOUT_AUTH_ADAPTER");

    private final TenantSettingsRepository repository;
    private final IdentityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public TenantSettingsService(
            TenantSettingsRepository repository,
            IdentityAuditService audit,
            ObjectMapper objectMapper) {
        this(repository, audit, objectMapper, Clock.systemUTC());
    }

    TenantSettingsService(
            TenantSettingsRepository repository,
            IdentityAuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TenantSettingsDtos.ChangeSet> authPolicyChanges(Long tenantId) {
        return repository.listChangeSets(tenantId);
    }

    @Transactional
    public TenantSettingsDtos.ChangeSet createAuthPolicyChange(
            Long tenantId,
            Long actorId,
            String correlationId,
            TenantSettingsDtos.CreateAuthPolicyChangeRequest request) {
        TenantSettingsRepository.PolicyState before = repository.currentPolicy(tenantId);
        TenantSettingsRepository.PolicyState proposed = normalize(request.policy());
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
        return change;
    }

    @Transactional
    public TenantSettingsDtos.ChangeSet submit(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeSetId,
            TenantSettingsDtos.VersionedCommand command) {
        TenantSettingsDtos.ChangeSet previous = repository.requireChangeSet(tenantId, changeSetId);
        requireOwner(previous);
        TenantSettingsDtos.ChangeSet changed = repository.submit(
                tenantId, changeSetId, command.version(), actorId, clock.instant());
        audit.success(
                tenantId, actorId, "tenant-settings.auth-policy.submitted",
                "TENANT_SETTING_CHANGE_SET", changeSetId.toString(), correlationId,
                Map.of("lifecycleState", previous.lifecycleState(), "version", previous.version()),
                Map.of("lifecycleState", changed.lifecycleState(), "version", changed.version()));
        return changed;
    }

    @Transactional
    public TenantSettingsDtos.ChangeSet decide(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeSetId,
            TenantSettingsDtos.DecisionCommand command) {
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
        return changed;
    }

    @Transactional
    public TenantSettingsDtos.ChangeSet publish(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeSetId,
            TenantSettingsDtos.VersionedCommand command) {
        TenantSettingsDtos.ChangeSet change = repository.requireChangeSet(tenantId, changeSetId);
        requireOwner(change);
        if (!"APPROVED".equals(change.lifecycleState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only an independently approved authentication policy can be published.");
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
        return published;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantSettingsDtos.AccessProjection accessProjection(
            Long tenantId, String query, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        String normalizedQuery = query == null || query.isBlank()
                ? null : query.trim().toLowerCase(Locale.ROOT);
        Instant observedAt = clock.instant();
        List<TenantSettingsRepository.UserRow> users = repository.users(
                tenantId, normalizedQuery, page, size);
        long total = repository.userCount(tenantId, normalizedQuery);
        Map<Long, List<TenantSettingsDtos.AccessGrant>> grants = repository.grants(
                tenantId, users.stream().map(TenantSettingsRepository.UserRow::userId).toList());
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
        Instant freshest = repository.freshestProjectionSource(tenantId);
        String snapshotId = digest(objectMapper.valueToTree(Map.of(
                "tenantId", tenantId,
                "observedAt", observedAt.toString(),
                "page", page,
                "size", size,
                "userIds", users.stream().map(TenantSettingsRepository.UserRow::userId).toList(),
                "freshestSourceUpdatedAt", freshest == null ? "none" : freshest.toString())));
        return new TenantSettingsDtos.AccessProjection(
                snapshotId, observedAt,
                new TenantSettingsDtos.ProjectionCoverage(
                        "COMPLETE_INTERNAL_OWNERS",
                        INTERNAL_PROJECTION_OWNERS,
                        PROJECTION_EXCLUSIONS,
                        freshest),
                principals, page, size, total, (int) Math.ceil(total / (double) size));
    }

    private TenantSettingsRepository.PolicyState normalize(
            TenantSettingsDtos.AuthPolicyDraft policy) {
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
                policy.requireMfa(), policy.tokenTtlSec());
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
