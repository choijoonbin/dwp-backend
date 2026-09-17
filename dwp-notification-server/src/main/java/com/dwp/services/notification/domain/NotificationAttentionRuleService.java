package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextReference;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControl;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlImpactPreview;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlMutationRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlPreviewRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControls;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionPolicyDecision;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRule;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleCreateRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRulePreview;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRulePreviewRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleUpdateRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionScope;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionScopeEvidence;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionTypeTarget;
import com.dwp.services.notification.domain.NotificationAttentionModels.NotificationAttentionContext;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRuntime.EffectiveGovernance;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicy;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationAttentionRuleService {

    private final NotificationDatabaseScope databaseScope;
    private final NotificationAttentionRuleRepository ruleRepository;
    private final NotificationAttentionContextRepository contextRepository;
    private final NotificationAttentionPolicyGuard policyGuard;
    private final NotificationAttentionGovernanceRuntime governanceRuntime;
    private final NotificationAttentionGovernanceLock governanceLock;
    private final Clock clock;

    @Autowired
    public NotificationAttentionRuleService(
            NotificationDatabaseScope databaseScope,
            NotificationAttentionRuleRepository ruleRepository,
            NotificationAttentionContextRepository contextRepository,
            NotificationAttentionPolicyGuard policyGuard,
            NotificationAttentionGovernanceRuntime governanceRuntime,
            NotificationAttentionGovernanceLock governanceLock) {
        this(
                databaseScope,
                ruleRepository,
                contextRepository,
                policyGuard,
                governanceRuntime,
                governanceLock,
                Clock.systemUTC());
    }

    NotificationAttentionRuleService(
            NotificationDatabaseScope databaseScope,
            NotificationAttentionRuleRepository ruleRepository,
            NotificationAttentionContextRepository contextRepository,
            NotificationAttentionPolicyGuard policyGuard,
            NotificationAttentionGovernanceRuntime governanceRuntime,
            NotificationAttentionGovernanceLock governanceLock,
            Clock clock) {
        this.databaseScope = databaseScope;
        this.ruleRepository = ruleRepository;
        this.contextRepository = contextRepository;
        this.policyGuard = policyGuard;
        this.governanceRuntime = governanceRuntime;
        this.governanceLock = governanceLock;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AttentionRule> list(NotificationRequestContext.Actor actor) {
        databaseScope.applyUser(actor);
        return ruleRepository.list(actor);
    }

    @Transactional(readOnly = true)
    public int maxActiveRules(NotificationRequestContext.Actor actor) {
        databaseScope.applyUser(actor);
        return governanceRuntime.resolve(actor.tenantId()).settings().maxActiveUserRules();
    }

    @Transactional(readOnly = true)
    public AttentionRulePreview preview(
            NotificationRequestContext.Actor actor,
            AttentionRulePreviewRequest request) {
        databaseScope.applyUser(actor);
        AttentionScope scope = prepare(
                request.scopeKind(), request.scopeKey(), request.channels(),
                request.startsAt(), request.expiresAt());
        validateDisplayLabel(request.displayLabel());
        AttentionScopeEvidence evidence = requireEvidence(actor, scope);
        AttentionPolicyDecision decision = AttentionPolicyDecision.allowed();
        if (enabled(request.enabled())) {
            decision = governanceDecision(
                    scope, governanceRuntime.resolve(actor.tenantId()));
            if (!decision.locked()) {
                decision = policyGuard.evaluate(
                        policyGuard.load(actor), evidence.targets(),
                        request.effect(), request.channels());
            }
        }
        return new AttentionRulePreview(
                !decision.locked(),
                request.effect(),
                decision.locked(),
                decision.reasonCode(),
                evidence.affectedNotifications(),
                true,
                Instant.now(clock));
    }

    @Transactional
    public AttentionRule create(
            NotificationRequestContext.Actor actor,
            AttentionRuleCreateRequest request,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        long expectedVersion = optionalVersion(request.expectedVersion());
        if (expectedVersion != 0) throw stale();
        AttentionScope scope = prepare(
                request.scopeKind(), request.scopeKey(), request.channels(),
                request.startsAt(), request.expiresAt());
        validateDisplayLabel(request.displayLabel());
        AttentionScopeEvidence evidence = requireEvidence(actor, scope);
        governanceLock.lockTenant(actor.tenantId());
        EffectiveGovernance governance = governanceRuntime.resolve(actor.tenantId());
        if (enabled(request.enabled())) {
            enforceGovernance(scope, governance);
            enforcePolicy(actor, evidence.targets(), request.effect(), request.channels());
        }
        return ruleRepository.create(
                actor, scope, request, governance.settings(), idempotencyKey);
    }

    @Transactional
    public AttentionRule update(
            NotificationRequestContext.Actor actor,
            UUID ruleId,
            AttentionRuleUpdateRequest request,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        long expectedVersion = NotificationVersionCodec.positive(
                request.expectedVersion(), "expectedVersion");
        AttentionRule current = ruleRepository.find(actor, ruleId)
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.ATTENTION_RULE_NOT_FOUND));
        AttentionScope scope = prepare(
                request.scopeKind(), request.scopeKey(), request.channels(),
                request.startsAt(), request.expiresAt());
        if (!current.scopeKind().equals(scope.kind())
                || !current.scopeKey().equals(scope.key())) {
            throw new IllegalArgumentException("An attention rule scope is immutable.");
        }
        validateDisplayLabel(request.displayLabel());
        AttentionScopeEvidence evidence = requireEvidence(actor, scope);
        governanceLock.lockTenant(actor.tenantId());
        EffectiveGovernance governance = governanceRuntime.resolve(actor.tenantId());
        if (enabled(request.enabled())) {
            enforceGovernance(scope, governance);
            enforcePolicy(actor, evidence.targets(), request.effect(), request.channels());
        }
        return ruleRepository.update(
                actor, ruleId, request, expectedVersion, governance.settings(), idempotencyKey);
    }

    @Transactional
    public void delete(
            NotificationRequestContext.Actor actor,
            UUID ruleId,
            String expectedVersion,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        ruleRepository.delete(
                actor,
                ruleId,
                NotificationVersionCodec.positive(expectedVersion, "expectedVersion"),
                idempotencyKey);
    }

    @Transactional(readOnly = true)
    public AttentionControls controls(
            NotificationRequestContext.Actor actor,
            UUID notificationId) {
        databaseScope.applyUser(actor);
        return controls(actor, contextRepository.notification(actor, notificationId));
    }

    @Transactional(readOnly = true)
    public AttentionControlImpactPreview previewControl(
            NotificationRequestContext.Actor actor,
            UUID notificationId,
            AttentionControlPreviewRequest request) {
        databaseScope.applyUser(actor);
        validateWindow(null, request.expiresAt());
        NotificationAttentionContext context = contextRepository.notification(
                actor, notificationId);
        ControlCandidate candidate = requireCandidate(
                context, request.controlKey(), request.effect());
        ControlEvaluation evaluation = evaluateControl(
                actor,
                context,
                candidate,
                request.expiresAt(),
                ruleRepository.list(actor),
                policyGuard.load(actor),
                governanceRuntime.resolve(actor.tenantId()));
        return new AttentionControlImpactPreview(
                candidate.controlKey(),
                evaluation.reference() != null && !evaluation.decision().locked(),
                evaluation.decision().locked(),
                candidate.effect(),
                evaluation.decision().policySource(),
                evaluation.reference() == null
                        ? "CONTEXT_NOT_AVAILABLE" : evaluation.decision().reasonCode(),
                evaluation.fingerprint(),
                evaluation.currentRule() == null
                        ? null : evaluation.currentRule().version(),
                request.expiresAt(),
                Instant.now(clock));
    }

    @Transactional
    public AttentionRule mutateControl(
            NotificationRequestContext.Actor actor,
            UUID notificationId,
            AttentionControlMutationRequest request,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        validateWindow(null, request.expiresAt());
        NotificationAttentionContext context = contextRepository.notification(
                actor, notificationId);
        ControlCandidate candidate = requireCandidate(
                context, request.controlKey(), request.effect());
        governanceLock.lockTenant(actor.tenantId());
        ControlEvaluation evaluation = evaluateControl(
                actor,
                context,
                candidate,
                request.expiresAt(),
                ruleRepository.list(actor),
                policyGuard.load(actor),
                governanceRuntime.resolve(actor.tenantId()));
        if (evaluation.reference() == null) {
            throw new NotificationException(NotificationErrorCode.ATTENTION_SCOPE_NOT_AVAILABLE);
        }
        if (evaluation.decision().locked()) {
            throw new NotificationException(
                    NotificationErrorCode.ATTENTION_POLICY_LOCKED,
                    "Organization policy prevents this attention change: "
                            + evaluation.decision().reasonCode());
        }
        AttentionScope scope = NotificationAttentionScope.canonical(
                evaluation.reference().scopeKind(), evaluation.reference().scopeKey());
        long expectedVersion = optionalVersion(request.expectedVersion());
        return ruleRepository.applyControl(
                actor,
                notificationId,
                scope,
                evaluation.reference().displayHint(),
                request,
                expectedVersion,
                evaluation.governance().settings(),
                NotificationAttentionControlFingerprint.matches(
                        evaluation.fingerprint(), request.previewFingerprint()),
                idempotencyKey);
    }

    private AttentionControls controls(
            NotificationRequestContext.Actor actor,
            NotificationAttentionContext context) {
        List<AttentionRule> rules = ruleRepository.list(actor);
        List<EffectivePolicy> policies = policyGuard.load(actor);
        EffectiveGovernance governance = governanceRuntime.resolve(actor.tenantId());
        List<AttentionControl> controls = new ArrayList<>();
        for (ControlCandidate candidate : candidates(context)) {
            ControlEvaluation evaluation = evaluateControl(
                    actor, context, candidate, null, rules, policies, governance);
            AttentionContextReference reference = evaluation.reference();
            AttentionRule current = evaluation.currentRule();
            AttentionPolicyDecision decision = evaluation.decision();
            controls.add(new AttentionControl(
                    candidate.controlKey(),
                    reference == null ? candidate.scopeKind() : reference.scopeKind(),
                    candidate.label(),
                    candidate.description(),
                    reference == null ? List.of() : List.of(candidate.effect()),
                    current == null ? null : current.effect(),
                    decision.locked(),
                    reference == null ? "CONTEXT_NOT_AVAILABLE" : decision.reasonCode(),
                    false,
                    current == null ? null : current.expiresAt(),
                    current == null ? null : current.ruleId(),
                    current == null ? null : current.version()));
        }
        return new AttentionControls(
                false,
                List.of(),
                null,
                context.notificationId(),
                whyReceived(context.reasonCode()),
                controls,
                Instant.now(clock));
    }

    private ControlCandidate requireCandidate(
            NotificationAttentionContext context,
            String controlKey,
            String effect) {
        ControlCandidate candidate = candidates(context).stream()
                .filter(item -> item.controlKey().equals(controlKey))
                .findFirst()
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.ATTENTION_SCOPE_NOT_AVAILABLE));
        if (!candidate.effect().equals(effect)) {
            throw new IllegalArgumentException("The attention control effect is not allowed.");
        }
        return candidate;
    }

    private ControlEvaluation evaluateControl(
            NotificationRequestContext.Actor actor,
            NotificationAttentionContext context,
            ControlCandidate candidate,
            Instant expiresAt,
            List<AttentionRule> rules,
            List<EffectivePolicy> policies,
            EffectiveGovernance governance) {
        AttentionContextReference reference = candidate.reference();
        AttentionRule current = reference == null ? null : rules.stream()
                .filter(rule -> rule.scopeKind().equals(reference.scopeKind())
                        && rule.scopeKey().equals(reference.scopeKey()))
                .findFirst()
                .orElse(null);
        AttentionPolicyDecision decision = reference == null
                ? AttentionPolicyDecision.allowed()
                : governanceDecision(
                        NotificationAttentionScope.canonical(
                                reference.scopeKind(), reference.scopeKey()),
                        governance);
        List<AttentionTypeTarget> targets = List.of(
                new AttentionTypeTarget(context.appKey(), context.typeKey()));
        if (reference != null && !decision.locked()) {
            decision = policyGuard.evaluate(
                    policies, targets, candidate.effect(), Map.of());
        }
        EffectivePolicy effectivePolicy = NotificationEffectivePolicyRepository.selectPolicy(
                policies, context.appKey(), context.typeKey());
        String fingerprint = NotificationAttentionControlFingerprint.issue(
                actor,
                context.notificationId(),
                context.appKey(),
                context.typeKey(),
                candidate.controlKey(),
                candidate.effect(),
                expiresAt,
                reference,
                current,
                governance,
                effectivePolicy,
                decision);
        return new ControlEvaluation(
                reference, current, decision, governance, fingerprint);
    }

    private List<ControlCandidate> candidates(NotificationAttentionContext context) {
        AttentionContextReference appType = reference(context, "APP_TYPE");
        AttentionContextReference actor = reference(context, "ACTOR");
        AttentionContextReference contextual = context.references().stream()
                .filter(reference -> List.of("RESOURCE", "THREAD", "TOPIC_TOKEN")
                        .contains(reference.scopeKind()))
                .min(Comparator.comparingInt(reference -> switch (reference.scopeKind()) {
                    case "RESOURCE" -> 0;
                    case "THREAD" -> 1;
                    default -> 2;
                }))
                .orElse(null);
        return List.of(
                new ControlCandidate(
                        "MUTE_TYPE", "APP_TYPE", "MUTE",
                        "Mute this type", "Suppress optional notifications of this type.", appType),
                new ControlCandidate(
                        "MUTE_CONTEXT", "RESOURCE", "MUTE",
                        "Mute this context", "Suppress optional notifications for this context.",
                        contextual),
                new ControlCandidate(
                        "FOLLOW_CONTEXT", "RESOURCE", "FOLLOW",
                        "Follow this context", "Keep notifications for this context visible.",
                        contextual),
                new ControlCandidate(
                        "PRIORITIZE_ACTOR", "ACTOR", "PRIORITIZE",
                        "Prioritize this actor", "Raise notifications from this actor.", actor));
    }

    private AttentionContextReference reference(
            NotificationAttentionContext context,
            String scopeKind) {
        return context.references().stream()
                .filter(reference -> scopeKind.equals(reference.scopeKind()))
                .findFirst()
                .orElse(null);
    }

    private AttentionScope prepare(
            String scopeKind,
            String scopeKey,
            Map<String, Boolean> channels,
            Instant startsAt,
            Instant expiresAt) {
        NotificationAttentionScope.channels(channels);
        validateWindow(startsAt, expiresAt);
        return NotificationAttentionScope.canonical(scopeKind, scopeKey);
    }

    private void validateWindow(Instant startsAt, Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now(clock))) {
            throw new IllegalArgumentException("Attention rule expiry must be in the future.");
        }
        if (startsAt != null && expiresAt != null && !expiresAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Attention rule expiry must follow its start.");
        }
    }

    private AttentionScopeEvidence requireEvidence(
            NotificationRequestContext.Actor actor,
            AttentionScope scope) {
        AttentionScopeEvidence evidence = contextRepository.evidence(actor, scope);
        if (evidence.targets().isEmpty()) {
            throw new NotificationException(NotificationErrorCode.ATTENTION_SCOPE_NOT_AVAILABLE);
        }
        return evidence;
    }

    private void enforcePolicy(
            NotificationRequestContext.Actor actor,
            List<AttentionTypeTarget> targets,
            String effect,
            Map<String, Boolean> channels) {
        AttentionPolicyDecision decision = policyGuard.evaluate(
                policyGuard.load(actor), targets, effect, channels);
        if (decision.locked()) {
            throw new NotificationException(
                    NotificationErrorCode.ATTENTION_POLICY_LOCKED,
                    "Organization policy prevents this attention change: "
                            + decision.reasonCode());
        }
    }

    private void enforceGovernance(
            AttentionScope scope,
            EffectiveGovernance governance) {
        AttentionPolicyDecision decision = governanceDecision(scope, governance);
        if (decision.locked()) {
            throw new NotificationException(
                    NotificationErrorCode.ATTENTION_POLICY_LOCKED,
                    "Tenant attention governance prevents this change: "
                            + decision.reasonCode());
        }
    }

    private AttentionPolicyDecision governanceDecision(
            AttentionScope scope,
            EffectiveGovernance governance) {
        if (!"TOPIC_TOKEN".equals(scope.kind()) || governance.topicAllowed(scope.key())) {
            return AttentionPolicyDecision.allowed();
        }
        return new AttentionPolicyDecision(
                true,
                governance.published() ? "TENANT_GOVERNANCE" : "PLATFORM_DEFAULT",
                "TOPIC_NOT_APPROVED",
                Long.toString(governance.revisionNumber()));
    }

    private long optionalVersion(String value) {
        return value == null
                ? 0L
                : NotificationVersionCodec.nonNegative(value, "expectedVersion");
    }

    private boolean enabled(Boolean value) {
        return value == null || value;
    }

    private void validateDisplayLabel(String value) {
        if (value != null && (value.isBlank()
                || !value.equals(value.trim())
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("The attention display label is invalid.");
        }
    }

    private String whyReceived(String reasonCode) {
        return reasonCode == null || reasonCode.isBlank()
                ? "This notification matched your current delivery settings."
                : "This notification was delivered for reason " + reasonCode + ".";
    }

    private NotificationException stale() {
        return new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
    }

    private record ControlCandidate(
            String controlKey,
            String scopeKind,
            String effect,
            String label,
            String description,
            AttentionContextReference reference) {
    }

    private record ControlEvaluation(
            AttentionContextReference reference,
            AttentionRule currentRule,
            AttentionPolicyDecision decision,
            EffectiveGovernance governance,
            String fingerprint) {
    }
}
