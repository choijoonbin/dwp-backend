package com.dwp.services.platform.activity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ActivityMetrics metrics = new ActivityMetrics(registry, true);

    @Test
    void staysFailClosedUntilTheGovernedMetricIsEnabled() {
        SimpleMeterRegistry disabledRegistry = new SimpleMeterRegistry();
        ActivityMetrics disabled = new ActivityMetrics(disabledRegistry, false);
        WorkspaceDtos.ActivityFeed feed = mock(WorkspaceDtos.ActivityFeed.class);
        when(feed.events()).thenReturn(List.of());

        assertThat(disabled.list(() -> feed)).isSameAs(feed);
        assertThat(disabledRegistry.getMeters()).isEmpty();
    }

    @Test
    void recordsOnlyBoundedOperationAndResultTags() {
        WorkspaceDtos.ActivityFeed feed = mock(WorkspaceDtos.ActivityFeed.class);
        when(feed.events()).thenReturn(List.of());

        metrics.list(() -> feed);
        metrics.sources(() -> statuses("READY", "BLOCKED"));
        metrics.sources(() -> statuses("STALE"));

        assertCount("LIST", "EMPTY", 1);
        assertCount("SOURCE_STATUS", "PARTIAL", 1);
        assertCount("SOURCE_STATUS", "STALE", 1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsExactlyInAnyOrder("operation", "result"));
    }

    @Test
    void recordsEvidenceStateAndFailureWithoutIdentifierTags() {
        UUID eventId = UUID.randomUUID();
        metrics.evidence(() -> new ActivityEvidenceDtos.Evidence(
                eventId, UUID.randomUUID(), "LINKED", "AVAILABLE", null, null,
                "PENDING", "DAILY_CHECKPOINT_REPORTED", null, OffsetDateTime.now()));

        assertThatThrownBy(() -> metrics.evidence(() -> {
            throw new BaseException(ErrorCode.NOT_FOUND);
        })).isInstanceOf(BaseException.class);

        assertCount("EVIDENCE", "PENDING", 1);
        assertCount("EVIDENCE", "NOT_FOUND", 1);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTag("event_id")).isNull());
    }

    private ActivityEvidenceDtos.SourceStatuses statuses(String... values) {
        OffsetDateTime now = OffsetDateTime.now();
        List<ActivityEvidenceDtos.SourceStatus> sources = java.util.Arrays.stream(values)
                .map(status -> new ActivityEvidenceDtos.SourceStatus(
                        "bounded-source", "Source", "MAIL", status,
                        now.minusMinutes(1), now.minusMinutes(1), now,
                        "PERSONAL_SYNC_LEDGER"))
                .toList();
        return new ActivityEvidenceDtos.SourceStatuses(now, sources);
    }

    private void assertCount(String operation, String result, long expected) {
        Timer timer = registry.find("dwp.activity.read.duration")
                .tags("operation", operation, "result", result).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(expected);
    }
}
