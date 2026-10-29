package com.dwp.services.platform.mail;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MailMetricsTest {

    @Test
    void recordsBoundedWorkerOutcomesAndBatchVolumes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MailMetrics metrics = new MailMetrics(registry);

        metrics.delivery(MailTypes.ProviderType.DWP_SANDBOX, "delivered");
        metrics.delivery(MailTypes.ProviderType.MICROSOFT_GRAPH, "provider_accepted");
        metrics.sync(new MailExternalSyncMaterializer.SyncResult(3, 2, 1, false),
                Duration.ofMillis(12));
        metrics.syncJob("continued", Duration.ofMillis(5));
        metrics.webhook("ambiguous");
        metrics.followUp(new MailFollowUpDueNotificationTransactions.BatchResult(5, 4, 1));
        metrics.purge("SUCCEEDED");
        metrics.incomingRule("changed");

        assertThat(registry.get("dwp.mail.delivery.outcomes")
                .tag("provider", "dwp_sandbox")
                .tag("outcome", "delivered").counter().count()).isEqualTo(1);
        assertThat(registry.get("dwp.mail.delivery.outcomes")
                .tag("provider", "microsoft_graph")
                .tag("outcome", "provider_accepted").counter().count()).isEqualTo(1);
        assertThat(registry.get("dwp.mail.sync.jobs")
                .tag("outcome", "continued").counter().count()).isEqualTo(1);
        assertThat(registry.get("dwp.mail.webhooks")
                .tag("outcome", "ambiguous").counter().count()).isEqualTo(1);
        assertThat(registry.get("dwp.mail.sync.messages")
                .tag("outcome", "inserted").counter().count()).isEqualTo(3);
        assertThat(registry.get("dwp.mail.followup.notifications")
                .tag("outcome", "emitted").counter().count()).isEqualTo(4);
        assertThat(registry.get("dwp.mail.purge.jobs")
                .tag("outcome", "succeeded").counter().count()).isEqualTo(1);
        assertThat(registry.get("dwp.mail.incoming.rules")
                .tag("outcome", "changed").counter().count()).isEqualTo(1);
    }
}
