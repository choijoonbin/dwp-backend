package com.dwp.services.platform.auditcontrol;

import com.dwp.audit.AuditEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRiskEngineTest {

    private final AuditRiskEngine engine = new AuditRiskEngine();

    @Test
    void elevatesDeniedPrivilegeChanges() {
        AuditEvent event = AuditEvent.builder()
                .tenantId(1L)
                .category("AUTHORIZATION")
                .action("identity.role.permission.updated")
                .outcome("DENIED")
                .sourceService("dwp-auth-server")
                .targetType("ROLE")
                .targetId("admin")
                .build();

        AuditEvent enriched = engine.enrich(event);

        assertThat(enriched.riskScore()).isGreaterThanOrEqualTo(70);
        assertThat(enriched.severity()).isEqualTo("HIGH");
    }

    @Test
    void keepsRoutineSystemEventsLowRisk() {
        AuditEvent event = AuditEvent.builder()
                .tenantId(1L)
                .category("SYSTEM_EVENT")
                .action("system.health.checked")
                .outcome("SUCCESS")
                .sourceService("dwp-platform-server")
                .targetType("SERVICE")
                .targetId("platform")
                .riskScore(5)
                .build();

        AuditEvent enriched = engine.enrich(event);

        assertThat(enriched.riskScore()).isEqualTo(5);
        assertThat(enriched.severity()).isEqualTo("INFO");
    }
}
