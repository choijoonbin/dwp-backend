package com.dwp.services.provider.rollout;

import java.util.List;

@FunctionalInterface
public interface FeatureRolloutDecisionEventPublisher {

    FeatureRolloutDecisionEventPublisher NOOP = events -> {
    };

    void publish(List<FeatureRolloutDecisionOutboxRepository.DecisionEvent> events);
}
