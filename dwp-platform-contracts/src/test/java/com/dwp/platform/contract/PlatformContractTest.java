package com.dwp.platform.contract;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformContractTest {

    private final ExecutionContext context = new ExecutionContext(
            "tenant-1",
            "user-1",
            Set.of("EMPLOYEE"),
            "correlation-1");

    @Test
    void snapshotsMutableCollectionsAtTheBoundary() {
        Set<String> roles = new HashSet<>(Set.of("EMPLOYEE"));
        ExecutionContext copiedContext = new ExecutionContext(
                "tenant-1",
                "user-1",
                roles,
                "correlation-1");
        roles.add("ADMIN");

        List<ConnectorPort.Item> items = new ArrayList<>();
        ConnectorPort.ReadPage page = new ConnectorPort.ReadPage(
                items,
                null,
                "opaque-sync-cursor",
                false);
        items.add(new ConnectorPort.Item(
                "source-1",
                "calendar-event",
                "Review",
                Instant.parse("2026-08-08T01:00:00Z"),
                URI.create("https://source.example/events/1"),
                "acl-1",
                DataClassification.INTERNAL));

        assertThat(copiedContext.roles()).containsExactly("EMPLOYEE");
        assertThat(page.items()).isEmpty();
    }

    @Test
    void rejectsUnboundedConnectorAndSearchReads() {
        assertThatThrownBy(() -> new ConnectorPort.ReadRequest(context, null, 201, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSearchPort.SearchRequest(
                context,
                "policy",
                Set.of(),
                51))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresApprovalForElevatedPlansAndNeverMutatesDuringPreview() {
        assertThatThrownBy(() -> new AgentRuntimePort.PlanPreview(
                "run-1",
                RiskTier.L2,
                false,
                false,
                "Preview",
                List.of(),
                List.of(),
                "audit-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require approval");

        assertThatThrownBy(() -> new AgentRuntimePort.PlanPreview(
                "run-1",
                RiskTier.L1,
                false,
                true,
                "Preview",
                List.of(),
                List.of(),
                "audit-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never allow mutation");
    }
}
