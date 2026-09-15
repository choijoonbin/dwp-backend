package com.dwp.services.auth.workflowplanning;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Operational readiness for the private planning authority, without issuing synthetic authority. */
public final class PlanningReadiness implements HealthIndicator {
    private final PlanningAuthorityService service;
    PlanningReadiness(PlanningAuthorityService service) {this.service=service;}
    @Override public Health health() {
        if(!service.enabled()) return Health.up().withDetail("state","DISABLED").build();
        try {service.requireReady();return Health.up().withDetail("state","READY").build();}
        catch(RuntimeException unavailable) {return Health.down().withDetail("state","UNAVAILABLE").build();}
    }
}
