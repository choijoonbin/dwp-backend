package com.dwp.services.notification.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.domain.NotificationAdminRepository.AdminSnapshot;
import com.dwp.services.notification.domain.NotificationAdminRepository.DeliveryQueueSnapshot;
import com.dwp.services.notification.domain.NotificationModels.AdminMetric;
import com.dwp.services.notification.domain.NotificationModels.AdminOverview;
import com.dwp.services.notification.domain.NotificationModels.DeliveryOperations;
import com.dwp.services.notification.domain.NotificationModels.OperationalFinding;
import com.dwp.services.notification.domain.NotificationModels.PolicyChannelRule;
import com.dwp.services.notification.domain.NotificationModels.PolicyPublishRequest;
import com.dwp.services.notification.domain.NotificationModels.ProviderHealth;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicy;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicyChangeRequest;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicyPage;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicyPreview;
import com.dwp.services.notification.domain.NotificationModels.TypeContract;
import com.dwp.services.notification.domain.NotificationModels.TypeContractPage;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.notification.api.NotificationVersionCodec.nonNegative;
import static com.dwp.services.notification.api.NotificationVersionCodec.positive;

@Service
public class NotificationAdminService {

    private static final Set<String> CONTRACT_STATES = Set.of(
            "DRAFT", "IN_REVIEW", "ACTIVE", "DEPRECATED", "RETIRED", "QUARANTINED");

    private final NotificationDatabaseScope databaseScope;
    private final NotificationAdminRepository repository;
    private final NotificationIdempotencyRepository idempotencyRepository;
    private final AuditOutboxRecorder audit;

    public NotificationAdminService(
            NotificationDatabaseScope databaseScope,
            NotificationAdminRepository repository,
            NotificationIdempotencyRepository idempotencyRepository,
            AuditOutboxRecorder audit) {
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.idempotencyRepository = idempotencyRepository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public AdminOverview overview(NotificationRequestContext.Actor actor) {
        databaseScope.applyWorker(actor.tenantId());
        Instant generatedAt = Instant.now();
        AdminSnapshot snapshot = repository.snapshot(actor.tenantId());
        List<AdminMetric> metrics = List.of(
                metric(
                        "active-contracts",
                        "Active contracts",
                        snapshot.activeContracts(),
                        "contracts",
                        snapshot.brokenContracts() == 0 ? "HEALTHY" : "ATTENTION"),
                metric(
                        "notifications-24h",
                        "Notifications (24h)",
                        snapshot.notifications24Hours(),
                        "notifications",
                        "HEALTHY"),
                metric(
                        "queued-deliveries",
                        "Queued deliveries",
                        snapshot.queuedJobs(),
                        "jobs",
                        snapshot.queuedJobs() > 1000 ? "ATTENTION" : "HEALTHY"),
                metric(
                        "failed-deliveries",
                        "Failed deliveries",
                        snapshot.failedJobs(),
                        "jobs",
                        snapshot.failedJobs() > 0 ? "CRITICAL" : "HEALTHY"));
        return new AdminOverview(
                false,
                List.of(),
                null,
                generatedAt,
                metrics,
                repository.trend(actor.tenantId()),
                findings(snapshot, generatedAt));
    }

    @Transactional(readOnly = true)
    public TypeContractPage types(
            NotificationRequestContext.Actor actor,
            String cursor,
            int limit,
            String query,
            String state,
            String appKey) {
        databaseScope.applyWorker(actor.tenantId());
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int offset = decodeOffset(cursor);
        String normalizedState = normalizeState(state);
        List<TypeContract> rows = repository.typeContracts(
                actor.tenantId(),
                bounded(query, 200),
                normalizedState,
                bounded(appKey, 100),
                offset,
                boundedLimit + 1);
        boolean hasMore = rows.size() > boundedLimit;
        List<TypeContract> items = hasMore
                ? List.copyOf(rows.subList(0, boundedLimit))
                : List.copyOf(rows);
        return new TypeContractPage(
                false,
                List.of(),
                null,
                items,
                hasMore ? encodeOffset(offset + boundedLimit) : null,
                hasMore);
    }

    @Transactional(readOnly = true)
    public DeliveryOperations operations(NotificationRequestContext.Actor actor) {
        databaseScope.applyWorker(actor.tenantId());
        Instant generatedAt = Instant.now();
        DeliveryQueueSnapshot queue = repository.deliveryQueue(actor.tenantId());
        List<ProviderHealth> providers = List.of(
                disabledProvider("email", "Email provider", "EMAIL", generatedAt),
                disabledProvider("web-push", "Web Push provider", "WEB_PUSH", generatedAt),
                disabledProvider("mobile-push", "Mobile Push provider", "MOBILE_PUSH", generatedAt),
                disabledProvider("teams", "Microsoft Teams provider", "TEAMS", generatedAt),
                disabledProvider("slack", "Slack provider", "SLACK", generatedAt));
        List<OperationalFinding> findings = new ArrayList<>();
        findings.add(new OperationalFinding(
                "external-delivery-disabled",
                "DELIVERY",
                "INFO",
                "External delivery adapters are disabled",
                "Direct-recipient in-app delivery is the only enabled foundation capability.",
                providers.size(),
                "Notification Platform",
                generatedAt,
                null));
        if (queue.deadLetterQueue() > 0) {
            findings.add(new OperationalFinding(
                    "dead-letter-queue",
                    "DELIVERY",
                    "CRITICAL",
                    "Dead-letter queue requires review",
                    "One or more delivery jobs exhausted the retry budget.",
                    queue.deadLetterQueue(),
                    "Notification Operations",
                    generatedAt,
                    null));
        }
        return new DeliveryOperations(
                false,
                List.of(),
                null,
                generatedAt,
                repository.deliveryLanes(actor.tenantId()),
                providers,
                queue.retryQueue(),
                queue.deadLetterQueue(),
                queue.unknownOutcomes(),
                List.copyOf(findings));
    }

    @Transactional(readOnly = true)
    public TenantPolicyPage policies(NotificationRequestContext.Actor actor) {
        databaseScope.applyWorker(actor.tenantId());
        return new TenantPolicyPage(
                repository.effectivePolicies(actor.tenantId()),
                repository.policyDrafts(actor.tenantId()),
                Instant.now());
    }

    @Transactional(readOnly = true)
    public TenantPolicyPreview previewPolicy(
            NotificationRequestContext.Actor actor,
            TenantPolicyChangeRequest request) {
        databaseScope.applyWorker(actor.tenantId());
        validatePolicy(actor.tenantId(), request);
        long currentVersion = repository.latestTenantPolicyVersion(
                actor.tenantId(), request.scopeType(), request.scopeKey());
        requireExpectedVersion(request.expectedVersion(), currentVersion);
        TenantPolicy current = repository.effectivePolicy(
                actor.tenantId(), request.scopeType(), request.scopeKey()).orElse(null);
        TenantPolicy proposed = new TenantPolicy(
                new UUID(0, 0),
                request.scopeType(),
                request.scopeKey(),
                scopeLabel(request.scopeType(), request.scopeKey()),
                "TENANT_POLICY",
                "PREVIEW",
                request.mandatory(),
                request.quietHoursBypass(),
                request.digestMode(),
                request.channels(),
                request.changeReason().trim(),
                actor.userId(),
                null,
                null,
                Long.toString(currentVersion + 1),
                Instant.now());
        return new TenantPolicyPreview(
                current,
                proposed,
                repository.affectedTypeCount(
                        actor.tenantId(), request.scopeType(), request.scopeKey()),
                repository.observedRecipients30Days(
                        actor.tenantId(), request.scopeType(), request.scopeKey()),
                riskFlags(request));
    }

    @Transactional
    public TenantPolicy createPolicyDraft(
            NotificationRequestContext.Actor actor,
            TenantPolicyChangeRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        validatePolicy(actor.tenantId(), request);
        Request receipt = idempotencyRepository.begin(
                actor, idempotencyKey, "TENANT_NOTIFICATION_POLICY_DRAFT", request);
        TenantPolicy replay = idempotencyRepository.replay(receipt, TenantPolicy.class);
        if (replay != null) return replay;
        long currentVersion = repository.latestTenantPolicyVersion(
                actor.tenantId(), request.scopeType(), request.scopeKey());
        requireExpectedVersion(request.expectedVersion(), currentVersion);
        UUID supersedes = repository.effectivePolicy(
                actor.tenantId(), request.scopeType(), request.scopeKey())
                .map(TenantPolicy::policyId)
                .orElse(null);
        try {
            TenantPolicy result = repository.createPolicyDraft(
                    actor.tenantId(), actor.userId(), request, currentVersion + 1, supersedes);
            recordPolicy(
                    actor,
                    "notification.policy.draft.created",
                    result,
                    request.changeReason());
            idempotencyRepository.complete(actor, receipt, result);
            return result;
        } catch (DuplicateKeyException exception) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
    }

    @Transactional
    public TenantPolicy publishPolicy(
            NotificationRequestContext.Actor actor,
            UUID policyId,
            PolicyPublishRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "TENANT_NOTIFICATION_POLICY_PUBLISH",
                Map.of("policyId", policyId, "request", request));
        TenantPolicy replay = idempotencyRepository.replay(receipt, TenantPolicy.class);
        if (replay != null) return replay;
        TenantPolicy draft = repository.policy(actor.tenantId(), policyId)
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        if (!"DRAFT".equals(draft.state())) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        if (draft.createdBy() != null && draft.createdBy().equals(actor.userId())) {
            throw new NotificationException(
                    NotificationErrorCode.FORBIDDEN,
                    "A notification policy author cannot approve the same version.");
        }
        long expectedVersion = positive(request.expectedVersion(), "expectedVersion");
        if (!repository.publishPolicy(
                actor.tenantId(), actor.userId(), policyId,
                expectedVersion, request.approvalReason())) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        TenantPolicy result = repository.policy(actor.tenantId(), policyId).orElseThrow();
        recordPolicy(
                actor,
                "notification.policy.published",
                result,
                request.approvalReason());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    private void recordPolicy(
            NotificationRequestContext.Actor actor,
            String action,
            TenantPolicy policy,
            String reason) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("MEDIUM")
                .riskScore(40)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-notification-server")
                .sourceModule("notification-policy-governance")
                .targetType("NOTIFICATION_POLICY")
                .targetId(policy.policyId().toString())
                .targetDisplayName(policy.scopeType() + ":" + policy.scopeKey())
                .reason(reason)
                .policyId(policy.policyId().toString())
                .policyDecision("notification.policy.published".equals(action)
                        ? "ALLOW"
                        : "NOT_APPLICABLE")
                .afterState(Map.of(
                        "scopeType", policy.scopeType(),
                        "scopeKey", policy.scopeKey(),
                        "state", policy.state(),
                        "version", policy.version(),
                        "mandatory", policy.mandatory(),
                        "quietHoursBypass", policy.quietHoursBypass()))
                .retentionClass("EXTENDED")
                .build());
    }

    private void validatePolicy(long tenantId, TenantPolicyChangeRequest request) {
        if (!repository.policyScopeExists(tenantId, request.scopeType(), request.scopeKey())) {
            throw new IllegalArgumentException("The notification policy scope is not active.");
        }
        Set<String> channels = new HashSet<>();
        for (PolicyChannelRule channel : request.channels()) {
            if (!channels.add(channel.channel())) {
                throw new IllegalArgumentException("Notification policy channels must be unique.");
            }
            if (!"IN_APP".equals(channel.channel()) && channel.enabled()) {
                throw new NotificationException(
                        NotificationErrorCode.NOTIFICATION_CAPABILITY_DISABLED,
                        "External delivery must be certified before it can be enabled.");
            }
        }
        PolicyChannelRule inApp = request.channels().stream()
                .filter(channel -> "IN_APP".equals(channel.channel()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "An in-app notification policy is required."));
        if (request.mandatory()
                && (!inApp.enabled()
                || "MUTED".equals(inApp.defaultMode())
                || inApp.userOverridable())) {
            throw new IllegalArgumentException(
                    "Mandatory notifications must use a managed, enabled in-app route.");
        }
    }

    private void requireExpectedVersion(String value, long currentVersion) {
        if (nonNegative(value, "expectedVersion") != currentVersion) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
    }

    private List<String> riskFlags(TenantPolicyChangeRequest request) {
        List<String> flags = new ArrayList<>();
        if (request.mandatory()) flags.add("MANDATORY_DELIVERY");
        if (request.quietHoursBypass()) flags.add("QUIET_HOURS_BYPASS");
        if (request.channels().stream().anyMatch(channel -> !channel.userOverridable())) {
            flags.add("USER_OVERRIDE_RESTRICTED");
        }
        if (request.channels().stream().anyMatch(channel -> "MUTED".equals(channel.defaultMode()))) {
            flags.add("DEFAULT_MUTED");
        }
        return List.copyOf(flags);
    }

    private String scopeLabel(String scopeType, String scopeKey) {
        return "APP".equals(scopeType) ? NotificationQueryRepository.appName(scopeKey) : scopeKey;
    }

    private List<OperationalFinding> findings(AdminSnapshot snapshot, Instant detectedAt) {
        List<OperationalFinding> findings = new ArrayList<>();
        if (snapshot.brokenContracts() > 0) {
            findings.add(new OperationalFinding(
                    "contracts-without-active-template",
                    "CONTRACT",
                    "WARNING",
                    "Contracts require an active in-app template",
                    "Active delivery requires a published in-app template for every type.",
                    snapshot.brokenContracts(),
                    "Notification Contract Owners",
                    detectedAt,
                    null));
        }
        if (snapshot.failedJobs() > 0) {
            findings.add(new OperationalFinding(
                    "failed-delivery-jobs",
                    "DELIVERY",
                    "CRITICAL",
                    "Delivery failures require attention",
                    "Review retry and dead-letter queues before enabling external channels.",
                    snapshot.failedJobs(),
                    "Notification Operations",
                    detectedAt,
                    null));
        }
        return List.copyOf(findings);
    }

    private AdminMetric metric(
            String key,
            String label,
            double value,
            String unit,
            String state) {
        return new AdminMetric(key, label, value, unit, null, state);
    }

    private ProviderHealth disabledProvider(
            String providerKey,
            String displayName,
            String channel,
            Instant generatedAt) {
        return new ProviderHealth(
                providerKey,
                displayName,
                channel,
                "DISABLED",
                0,
                0,
                "CLOSED",
                generatedAt);
    }

    private String normalizeState(String state) {
        if (state == null || state.isBlank()) return null;
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        if (!CONTRACT_STATES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported notification contract state.");
        }
        return normalized;
    }

    private String bounded(String value, int maximumLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("Notification admin filter is too long.");
        }
        return normalized;
    }

    private String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private int decodeOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int offset = Integer.parseInt(value);
            if (offset < 0 || offset > 1_000_000) throw new IllegalArgumentException();
            return offset;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Notification admin cursor is invalid.");
        }
    }
}
