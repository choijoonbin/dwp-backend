package com.dwp.services.notification.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.DecisionRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.DraftRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Revision;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Workspace;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.dwp.services.notification.api.NotificationVersionCodec.nonNegative;
import static com.dwp.services.notification.api.NotificationVersionCodec.positive;

@Service
public class NotificationAttentionGovernanceService {

    private static final Pattern TOPIC = Pattern.compile("#[a-z0-9][a-z0-9._-]{1,79}");

    private final NotificationDatabaseScope databaseScope;
    private final NotificationAttentionGovernanceRepository repository;
    private final NotificationIdempotencyRepository idempotencyRepository;
    private final AuditOutboxRecorder audit;
    private final NotificationAttentionGovernanceLock governanceLock;
    private final Clock clock;

    @Autowired
    public NotificationAttentionGovernanceService(
            NotificationDatabaseScope databaseScope,
            NotificationAttentionGovernanceRepository repository,
            NotificationIdempotencyRepository idempotencyRepository,
            AuditOutboxRecorder audit,
            NotificationAttentionGovernanceLock governanceLock) {
        this(
                databaseScope, repository, idempotencyRepository, audit,
                governanceLock, Clock.systemUTC());
    }

    NotificationAttentionGovernanceService(
            NotificationDatabaseScope databaseScope,
            NotificationAttentionGovernanceRepository repository,
            NotificationIdempotencyRepository idempotencyRepository,
            AuditOutboxRecorder audit,
            NotificationAttentionGovernanceLock governanceLock,
            Clock clock) {
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.idempotencyRepository = idempotencyRepository;
        this.audit = audit;
        this.governanceLock = governanceLock;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Workspace workspace(NotificationRequestContext.Actor actor) {
        databaseScope.applyWorker(actor.tenantId());
        return new Workspace(
                repository.active(actor.tenantId()).orElse(null),
                repository.drafts(actor.tenantId()),
                Long.toString(repository.latestRevisionNumber(actor.tenantId())),
                Instant.now(clock));
    }

    @Transactional
    public Revision createDraft(
            NotificationRequestContext.Actor actor,
            DraftRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        Settings settings = normalize(request.settings());
        String reason = canonicalReason(request.changeReason());
        requireCanonicalIdempotencyKey(idempotencyKey);
        long expected = nonNegative(request.expectedVersion(), "expectedVersion");
        governanceLock.lockTenant(actor.tenantId());
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "NOTIFICATION_ATTENTION_GOVERNANCE_DRAFT",
                new DraftRequest(settings, reason, Long.toString(expected)));
        Revision replay = idempotencyRepository.replay(receipt, Revision.class);
        if (replay != null) return replay;
        long current = repository.latestRevisionNumber(actor.tenantId());
        if (current != expected) throw stale();
        UUID supersedes = repository.active(actor.tenantId())
                .map(Revision::governanceId)
                .orElse(null);
        try {
            Revision result = repository.createDraft(
                    actor.tenantId(), actor.userId(), settings, reason, current + 1, supersedes);
            record(actor, "notification.attention.governance.draft.created", result, reason);
            idempotencyRepository.complete(actor, receipt, result);
            return result;
        } catch (DataIntegrityViolationException exception) {
            throw stale();
        }
    }

    @Transactional
    public Revision publish(
            NotificationRequestContext.Actor actor,
            UUID governanceId,
            DecisionRequest request,
            String idempotencyKey) {
        return decide(actor, governanceId, request, idempotencyKey, "PUBLISHED");
    }

    @Transactional
    public Revision reject(
            NotificationRequestContext.Actor actor,
            UUID governanceId,
            DecisionRequest request,
            String idempotencyKey) {
        return decide(actor, governanceId, request, idempotencyKey, "REJECTED");
    }

    @Transactional
    public Revision withdraw(
            NotificationRequestContext.Actor actor,
            UUID governanceId,
            DecisionRequest request,
            String idempotencyKey) {
        return decide(actor, governanceId, request, idempotencyKey, "WITHDRAWN");
    }

    private Revision decide(
            NotificationRequestContext.Actor actor,
            UUID governanceId,
            DecisionRequest request,
            String idempotencyKey,
            String decision) {
        databaseScope.applyWorker(actor.tenantId());
        long expected = positive(request.expectedVersion(), "expectedVersion");
        String reason = canonicalReason(request.reason());
        requireCanonicalIdempotencyKey(idempotencyKey);
        governanceLock.lockTenant(actor.tenantId());
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "NOTIFICATION_ATTENTION_GOVERNANCE_" + decision,
                Map.of(
                        "governanceId", governanceId,
                        "expectedVersion", expected,
                        "reason", reason));
        Revision replay = idempotencyRepository.replay(receipt, Revision.class);
        if (replay != null) return replay;
        Revision draft = repository.findForUpdate(actor.tenantId(), governanceId)
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        if (!"DRAFT".equals(draft.state())
                || !Long.toString(expected).equals(draft.version())) {
            throw stale();
        }
        if (("PUBLISHED".equals(decision) || "REJECTED".equals(decision))
                && draft.createdBy() != null
                && draft.createdBy().equals(actor.userId())) {
            throw new NotificationException(
                    NotificationErrorCode.FORBIDDEN,
                    "The attention governance author cannot review the same revision.");
        }
        boolean changed;
        if ("PUBLISHED".equals(decision)) {
            requirePublishable(draft.settings());
            changed = repository.publish(
                    actor.tenantId(), governanceId, actor.userId(), expected, reason);
        } else {
            changed = repository.decideDraft(
                    actor.tenantId(), governanceId, actor.userId(), expected,
                    decision, reason, "REJECTED".equals(decision));
        }
        if (!changed) throw stale();
        Revision result = repository.find(actor.tenantId(), governanceId).orElseThrow();
        record(
                actor,
                "notification.attention.governance." + decision.toLowerCase(Locale.ROOT),
                result,
                reason);
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    private Settings normalize(Settings value) {
        if (value == null) throw new IllegalArgumentException("Governance settings are required.");
        if (value.maxActiveUserRules() < 1 || value.maxActiveUserRules() > 500
                || value.maxVipRules() < 0
                || value.maxVipRules() > value.maxActiveUserRules()
                || value.maxFollowRules() < 0
                || value.maxFollowRules() > value.maxActiveUserRules()
                || value.minimumAnalyticsCohort() < 10
                || value.minimumAnalyticsCohort() > 10000) {
            throw new IllegalArgumentException("Attention governance limits are invalid.");
        }
        List<String> topics = value.approvedTopicAllowlist().stream()
                .map(topic -> canonicalTopic(topic))
                .toList();
        if (topics.size() > 100 || new HashSet<>(topics).size() != topics.size()) {
            throw new IllegalArgumentException("Approved attention topics must be unique.");
        }
        return new Settings(
                value.maxActiveUserRules(),
                value.maxVipRules(),
                value.maxFollowRules(),
                topics,
                value.mandatoryPolicyPrecedence(),
                value.minimumAnalyticsCohort(),
                value.independentReviewerRequired());
    }

    private String canonicalTopic(String value) {
        if (value == null || !value.equals(value.trim()) || !TOPIC.matcher(value).matches()) {
            throw new IllegalArgumentException("Approved topics must use canonical lowercase tokens.");
        }
        return value;
    }

    private String canonicalReason(String value) {
        if (value == null || !value.equals(value.trim())
                || value.length() < 10 || value.length() > 500) {
            throw new IllegalArgumentException("A canonical governance reason is required.");
        }
        return value;
    }

    private void requireCanonicalIdempotencyKey(String value) {
        if (value == null || !value.equals(value.trim())
                || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("A canonical Idempotency-Key is required.");
        }
    }

    private void requirePublishable(Settings settings) {
        if (!settings.mandatoryPolicyPrecedence()) {
            throw new NotificationException(
                    NotificationErrorCode.FORBIDDEN,
                    "Mandatory policy precedence must remain enabled.");
        }
        if (!settings.independentReviewerRequired()) {
            throw new NotificationException(
                    NotificationErrorCode.FORBIDDEN,
                    "Independent reviewer approval must remain enabled.");
        }
    }

    private NotificationException stale() {
        return new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
    }

    private void record(
            NotificationRequestContext.Actor actor,
            String action,
            Revision revision,
            String reason) {
        Settings settings = revision.settings();
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("HIGH")
                .riskScore(70)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-notification-server")
                .sourceModule("notification-attention-governance")
                .targetType("NOTIFICATION_ATTENTION_GOVERNANCE")
                .targetId(revision.governanceId().toString())
                .targetDisplayName("revision:" + revision.revisionNumber())
                .reason(reason)
                .policyId(revision.governanceId().toString())
                .policyDecision("PUBLISHED".equals(revision.state()) ? "ALLOW" : "NOT_APPLICABLE")
                .afterState(Map.of(
                        "state", revision.state(),
                        "revisionNumber", revision.revisionNumber(),
                        "version", revision.version(),
                        "maxActiveUserRules", settings.maxActiveUserRules(),
                        "maxVipRules", settings.maxVipRules(),
                        "maxFollowRules", settings.maxFollowRules(),
                        "approvedTopicCount", settings.approvedTopicAllowlist().size(),
                        "mandatoryPolicyPrecedence", settings.mandatoryPolicyPrecedence(),
                        "minimumAnalyticsCohort", settings.minimumAnalyticsCohort(),
                        "independentReviewerRequired", settings.independentReviewerRequired()))
                .retentionClass("EXTENDED")
                .build());
    }
}
