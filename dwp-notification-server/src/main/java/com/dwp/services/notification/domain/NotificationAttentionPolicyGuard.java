package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionPolicyDecision;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionTypeTarget;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicy;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicyChannel;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
final class NotificationAttentionPolicyGuard {

    private final NotificationEffectivePolicyRepository policyRepository;

    NotificationAttentionPolicyGuard(
            NotificationEffectivePolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    List<EffectivePolicy> load(NotificationRequestContext.Actor actor) {
        try {
            return policyRepository.findForTenant(actor.tenantId());
        } catch (DataAccessException exception) {
            throw new NotificationException(
                    NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Mandatory notification policy could not be resolved.");
        }
    }

    AttentionPolicyDecision evaluate(
            List<EffectivePolicy> policies,
            List<AttentionTypeTarget> targets,
            String effect,
            Map<String, Boolean> channels) {
        for (AttentionTypeTarget target : targets) {
            EffectivePolicy policy = NotificationEffectivePolicyRepository.selectPolicy(
                    policies, target.appKey(), target.typeKey());
            if (policy == null) continue;
            if ("MUTE".equals(effect) && policy.mandatory()) {
                return locked(policy, "MANDATORY_POLICY");
            }
            EffectivePolicyChannel inApp = policy.channels().get("IN_APP");
            if ("MUTE".equals(effect)
                    && inApp != null
                    && !inApp.userOverridable()) {
                return locked(policy, "USER_OVERRIDE_DISABLED");
            }
            for (Map.Entry<String, Boolean> override : channels.entrySet()) {
                EffectivePolicyChannel governed = policy.channels().get(override.getKey());
                if (governed == null) continue;
                boolean mandatoryDisable = policy.mandatory()
                        && governed.enabled()
                        && !override.getValue();
                boolean managedDifference = !governed.userOverridable()
                        && governed.enabled() != override.getValue();
                if (mandatoryDisable || managedDifference) {
                    return locked(policy, mandatoryDisable
                            ? "MANDATORY_POLICY" : "USER_OVERRIDE_DISABLED");
                }
            }
        }
        return AttentionPolicyDecision.allowed();
    }

    private AttentionPolicyDecision locked(
            EffectivePolicy policy,
            String reasonCode) {
        return new AttentionPolicyDecision(
                true,
                policy.source(),
                reasonCode,
                NotificationVersionCodec.external(policy.version()));
    }
}
