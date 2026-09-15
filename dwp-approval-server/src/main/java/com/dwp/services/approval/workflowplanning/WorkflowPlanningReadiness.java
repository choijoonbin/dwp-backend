package com.dwp.services.approval.workflowplanning;

import java.util.function.Supplier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Config and Auth endpoint readiness; it never fabricates a user planning decision. */
public final class WorkflowPlanningReadiness implements HealthIndicator {
    private final boolean enabled;
    private final Supplier<WorkflowPlanningRuntime> runtime;
    WorkflowPlanningReadiness(boolean enabled,Supplier<WorkflowPlanningRuntime> runtime) {this.enabled=enabled;this.runtime=runtime;}
    @Override public Health health() {
        if(!enabled) return Health.up().withDetail("state","DISABLED").build();
        try {runtime.get().requireReady();return Health.up().withDetail("state","READY").build();}
        catch(RuntimeException unavailable) {return Health.down().withDetail("state","UNAVAILABLE").build();}
    }
}
