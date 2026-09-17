package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionAdmissionRepository.RuleCandidate;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRuntime.EffectiveGovernance;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicy;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicyChannel;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public final class NotificationAttentionAdmissionService {

    private static final String IN_APP = "IN_APP";

    private final NotificationAttentionAdmissionRepository rules;
    private final NotificationEffectivePolicyRepository policies;
    private final NotificationAttentionGovernanceRuntime governanceRuntime;

    public NotificationAttentionAdmissionService(
            NotificationAttentionAdmissionRepository rules,
            NotificationEffectivePolicyRepository policies,
            NotificationAttentionGovernanceRuntime governanceRuntime) {
        this.rules = rules;
        this.policies = policies;
        this.governanceRuntime = governanceRuntime;
    }

    Map<Long, NotificationAttentionDecision> evaluate(
            long tenantId,
            Set<Long> recipientUserIds,
            DirectMaterializationRequest request,
            TemplateContract contract,
            Instant now) {
        if (recipientUserIds.isEmpty()) return Map.of();
        Map<String, Integer> scopes = matchableScopes(request, contract);
        List<RuleCandidate> candidates = rules.findActive(
                tenantId, recipientUserIds, hashes(scopes), now);
        if (candidates.isEmpty()) return Map.of();
        EffectiveGovernance governance = governanceRuntime.resolve(tenantId);

        EffectivePolicy policy = candidates.stream().anyMatch(candidate ->
                "MUTE".equals(candidate.effect()))
                ? effectivePolicy(tenantId, contract)
                : null;
        Map<Long, List<RankedCandidate>> byUser = new HashMap<>();
        for (RuleCandidate candidate : candidates) {
            if (!governanceAllows(candidate, governance)) continue;
            Integer specificity = scopes.get(scopeKey(
                    candidate.scopeKind(), candidate.scopeKeyHash()));
            if (specificity == null || muteLocked(candidate, policy)) continue;
            byUser.computeIfAbsent(candidate.userId(), ignored -> new ArrayList<>())
                    .add(new RankedCandidate(candidate, specificity));
        }

        Map<Long, NotificationAttentionDecision> decisions = new LinkedHashMap<>();
        byUser.forEach((userId, matches) -> matches.stream()
                .max(Comparator
                        .comparingInt(RankedCandidate::specificity)
                        .thenComparingInt(match -> effectRank(match.candidate().effect()))
                        .thenComparingLong(match -> match.candidate().version())
                        .thenComparing(match -> match.candidate().ruleId().toString()))
                .ifPresent(match -> decisions.put(
                        userId, decision(match.candidate()))));
        return Map.copyOf(decisions);
    }

    private Map<String, Integer> matchableScopes(
            DirectMaterializationRequest request,
            TemplateContract contract) {
        Map<String, Integer> scopes = new LinkedHashMap<>();
        addScope(scopes, "APP_TYPE", NotificationAttentionScope.appTypeKey(
                contract.ownerAppKey(), contract.typeKey()), 1);
        addScope(scopes, "ACTOR", request.actorReference(), 3);
        addScope(scopes, "THREAD", request.threadKey(), 4);
        addScope(scopes, "RESOURCE", request.subjectReference(), 5);
        addScope(scopes, "RESOURCE", request.targetReference(), 5);
        request.contexts().stream()
                .filter(MaterializationContext::matchable)
                .forEach(context -> addScope(
                        scopes,
                        NotificationStructuredContexts.scopeKind(context.kind()),
                        context.key(),
                        contextSpecificity(context)));
        return scopes;
    }

    private int contextSpecificity(MaterializationContext context) {
        return switch (context.kind()) {
            case TOPIC -> 2;
            case PERSON -> 3;
            case CONVERSATION, THREAD, CHANNEL -> 4;
            case PROJECT, WORK_ITEM -> 5;
        };
    }

    private void addScope(
            Map<String, Integer> scopes,
            String kind,
            String value,
            int specificity) {
        if (value == null) return;
        try {
            var scope = NotificationAttentionScope.canonical(kind, value);
            scopes.put(scopeKey(kind, scope.hash()), specificity);
        } catch (IllegalArgumentException ignored) {
            // Legacy free-form references remain deliverable but never become matchable.
        }
    }

    private Set<String> hashes(Map<String, Integer> scopes) {
        Set<String> result = new LinkedHashSet<>();
        scopes.keySet().forEach(key -> result.add(key.substring(key.indexOf('\u0000') + 1)));
        return Set.copyOf(result);
    }

    private EffectivePolicy effectivePolicy(long tenantId, TemplateContract contract) {
        try {
            return NotificationEffectivePolicyRepository.selectPolicy(
                    policies.findForTenant(tenantId),
                    contract.ownerAppKey(),
                    contract.typeKey());
        } catch (DataAccessException exception) {
            throw new NotificationException(
                    NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Mandatory notification policy could not be resolved.");
        }
    }

    private boolean muteLocked(RuleCandidate candidate, EffectivePolicy policy) {
        if (!"MUTE".equals(candidate.effect()) || policy == null) return false;
        if (policy.mandatory()) return true;
        EffectivePolicyChannel channel = policy.channels().get(IN_APP);
        return channel != null && !channel.userOverridable();
    }

    private boolean governanceAllows(
            RuleCandidate candidate,
            EffectiveGovernance governance) {
        return !"TOPIC_TOKEN".equals(candidate.scopeKind())
                || governance.topicHashAllowed(candidate.scopeKeyHash());
    }

    private NotificationAttentionDecision decision(RuleCandidate candidate) {
        return new NotificationAttentionDecision(
                candidate.ruleId(),
                candidate.scopeKind(),
                candidate.effect(),
                candidate.version(),
                candidate.source(),
                switch (candidate.effect()) {
                    case "MUTE" -> "USER_ATTENTION_MUTE";
                    case "PRIORITIZE" -> "USER_ATTENTION_PRIORITIZE";
                    default -> "USER_ATTENTION_FOLLOW";
                });
    }

    private int effectRank(String effect) {
        return switch (effect) {
            case "MUTE" -> 3;
            case "PRIORITIZE" -> 2;
            case "FOLLOW" -> 1;
            default -> 0;
        };
    }

    private String scopeKey(String kind, String hash) {
        return kind + "\u0000" + hash;
    }

    private record RankedCandidate(RuleCandidate candidate, int specificity) {
    }
}
