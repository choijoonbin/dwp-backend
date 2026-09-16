package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.overview.HomeOverviewController;
import com.dwp.services.platform.home.overview.HomeOverviewDtos;
import com.dwp.services.platform.home.overview.HomeOverviewService;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HomeRuntimeRollbackDrillTest {

    @Test
    void v2KillSwitchPreservesLegacyV1ReadPathAndPurgesRuntimeCache() {
        HomeRuntimeProperties disabled = properties(false, false);
        HomeReadModelService v2 = mock(HomeReadModelService.class);
        HomeReadModelController v2Controller = new HomeReadModelController(
                v2, mock(HomeWidgetCommandService.class), disabled);
        HomeRuntimeContext context = TestFixtures.context();

        assertThatThrownBy(() -> v2Controller.read(
                context.tenantId(), context.userId(), context.personPublicId(),
                context.permissionsHeader(), context.rolesHeader(), context.groupsHeader(),
                context.authorityDecisionRevision(), context.authorityRevalidateAt().toString(),
                context.locale(), null, "CLASSIC", "DESKTOP_STANDARD", context.timeZone()))
                .isInstanceOf(BaseException.class);

        HomeOverviewService legacy = mock(HomeOverviewService.class);
        HomeOverviewDtos.HomeOverviewResponse legacyResponse =
                mock(HomeOverviewDtos.HomeOverviewResponse.class);
        when(legacy.overview(
                context.tenantId(), context.userId(), context.personPublicId(),
                context.permissionsHeader(), context.rolesHeader(), context.locale(),
                context.timeZone(), context.groupsHeader())).thenReturn(legacyResponse);
        assertThat(new HomeOverviewController(legacy).overview(
                context.tenantId(), context.userId(), context.personPublicId(),
                context.permissionsHeader(), context.rolesHeader(), context.groupsHeader(),
                context.locale(), context.timeZone()).getData()).isSameAs(legacyResponse);

        RecipientBoundWidgetCache cache = new RecipientBoundWidgetCache(disabled);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RecipientBoundWidgetCache.Key key = new RecipientBoundWidgetCache.Key(
                context.tenantId(), context.userId(), context.fingerprint(),
                context.authorityDecisionRevision(), context.locale(), context.timeZone(),
                "CLASSIC", "DESKTOP_STANDARD", "view-1", "catalog-1", "policy-1",
                "safety-1", "platform", Set.of("core.work.focus"), "request-1");
        HomeWidgetProviderContract.WidgetResult result = new HomeWidgetProviderContract.WidgetResult(
                TestFixtures.request("core.work.focus").instanceId(), "core.work.focus",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "binding-1", HomeWidgetProviderContract.State.AVAILABLE,
                new HomeWidgetProviderContract.SourceState(
                        "PLATFORM_HOME", now, now.plusSeconds(30), now,
                        null, false, "result-1"),
                Map.of("count", 1), List.of(), List.of());
        HomeWidgetProviderContract.BatchResponse response =
                new HomeWidgetProviderContract.BatchResponse(
                        HomeWidgetProviderContract.SCHEMA_VERSION,
                        context.tenantId(), context.userId(),
                        context.authorityDecisionRevision(), List.of(result));
        cache.put(key, response, now.plusSeconds(30), context.authorityRevalidateAt());
        assertThat(cache.size()).isEqualTo(1);
        cache.clear();
        assertThat(cache.size()).isZero();
    }

    @Test
    void blankOwnerCredentialIsAProviderKillSwitchThatFailsBeforeTransport() {
        HttpWidgetProviderClient client = new HttpWidgetProviderClient(
                "meeting", "http://127.0.0.1:1", "", false,
                Duration.ofMillis(100), RestClient.builder(),
                CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());

        assertThatThrownBy(() -> client.readBatch(
                TestFixtures.context(), List.of(TestFixtures.request("meetings.next-prep")),
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1)))
                .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(WidgetProviderException.Kind.UNAVAILABLE);
                    assertThat(failure.reasonCode()).isEqualTo("PROVIDER_NOT_CONFIGURED");
                });
    }

    private HomeRuntimeProperties properties(boolean enabled, boolean shadowEnabled) {
        return new HomeRuntimeProperties(
                enabled, shadowEnabled, false,
                Duration.ofMillis(900), Duration.ofMillis(400), Duration.ofSeconds(30),
                Duration.ofMinutes(5), 100, 262_144);
    }
}
