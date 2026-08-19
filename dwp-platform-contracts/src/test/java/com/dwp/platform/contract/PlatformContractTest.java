package com.dwp.platform.contract;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformContractTest {

    private static final String PLAN_HASH = "a".repeat(64);

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
                PLAN_HASH,
                RiskTier.L2,
                false,
                false,
                "Preview",
                List.of(),
                List.of(),
                "audit-1",
                "correlation-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require approval");

        assertThatThrownBy(() -> new AgentRuntimePort.PlanPreview(
                "run-1",
                PLAN_HASH,
                RiskTier.L1,
                false,
                true,
                "Preview",
                List.of(),
                List.of(),
                "audit-1",
                "correlation-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never allow mutation");

        assertThatThrownBy(() -> new AgentRuntimePort.PlanPreview(
                "run-1",
                "mutable-plan-id",
                RiskTier.L1,
                false,
                false,
                "Preview",
                List.of(),
                List.of(),
                "audit-1",
                "correlation-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void mailConnectorAcceptsOnlyOpaqueSecretReferencesAndBoundedSynchronization() {
        assertThatThrownBy(() -> new MailConnectorPort.ConnectionContext(
                context,
                UUID.randomUUID(),
                URI.create("https://raw-secret.example/token"),
                "sk.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret-store scheme");

        MailConnectorPort.ConnectionContext connection =
                new MailConnectorPort.ConnectionContext(
                        context,
                        UUID.randomUUID(),
                        URI.create("vault://tenant/mail/microsoft"),
                        "sk.com");

        assertThatThrownBy(() -> new MailConnectorPort.SyncRequest(
                connection, "account-1", null, 501))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        assertThatThrownBy(() -> new MailConnectorPort.SendRequest(
                connection, "account-1", null, List.of("person@sk.com"),
                "Subject", "Message", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }
}
