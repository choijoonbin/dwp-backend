package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.overview.HomeOverviewService;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.dwp.services.platform.workplace.WorkplaceDtos;
import com.dwp.services.platform.workplace.WorkplaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus.RESERVED;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType.FOCUS_POD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HomeNativeProviderContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void workplaceProjectionReadsRecipientBookingsAndProducesAValidatedResult() {
        HomeRuntimeContext context = context();
        WorkplaceService workplace = mock(WorkplaceService.class);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        WorkplaceDtos.Booking booking = new WorkplaceDtos.Booking(
                UUID.randomUUID(), UUID.randomUUID(), "Focus Pod 3", FOCUS_POD,
                "HQ", "7F", "Planning", now.plusMinutes(20), now.plusMinutes(80),
                RESERVED, false, null, null, true, true, false,
                now.plusMinutes(10), now.plusMinutes(30), 4L);
        when(workplace.myBookings(
                org.mockito.ArgumentMatchers.eq(context.tenantId()),
                org.mockito.ArgumentMatchers.eq(context.userId()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(context.locale()),
                org.mockito.ArgumentMatchers.eq(context.groupsHeader())))
                .thenReturn(List.of(booking));
        WorkplaceNativeWidgetProvider provider = new WorkplaceNativeWidgetProvider(
                workplace, objectMapper, new HomeCanonicalJson(objectMapper));
        WidgetProviderPort.Request request = request(
                "workplace.booking", "workplace-booking", "APP.WORKPLACE");

        HomeWidgetProviderContract.BatchResponse response = provider.readBatch(
                context, List.of(request), now.plusSeconds(1));
        new ProviderResultValidator(objectMapper, properties())
                .validate(response, context, List.of(request));

        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.state()).isEqualTo(HomeWidgetProviderContract.State.AVAILABLE);
            assertThat(result.source().sourceKey()).isEqualTo("WORKPLACE_HOME");
            assertThat(result.payload()).containsKey("items");
            assertThat(objectMapper.valueToTree(result.payload()).toString())
                    .doesNotContain("resourceId")
                    .doesNotContain("purpose")
                    .doesNotContain("visibleToColleagues")
                    .doesNotContain("checkedInAt")
                    .doesNotContain("releasedAt")
                    .doesNotContain("version");
            assertThat(result.actions()).singleElement().satisfies(action ->
                    assertThat(action)
                            .returns("open-source", HomeWidgetProviderContract.Action::actionId)
                            .returns("home.action.openSource",
                                    HomeWidgetProviderContract.Action::labelKey)
                            .returns(HomeWidgetProviderContract.ActionKind.SOURCE_ROUTE,
                                    HomeWidgetProviderContract.Action::kind)
                            .returns("/workplace/home",
                                    HomeWidgetProviderContract.Action::sourceRoute)
                            .returns(null, HomeWidgetProviderContract.Action::commandKey)
                            .returns(null,
                                    HomeWidgetProviderContract.Action::expectedResultVersion)
                            .returns(false,
                                    HomeWidgetProviderContract.Action::requiresConfirmation));
        });
        verify(workplace).myBookings(
                org.mockito.ArgumentMatchers.eq(context.tenantId()),
                org.mockito.ArgumentMatchers.eq(context.userId()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(context.locale()),
                org.mockito.ArgumentMatchers.eq(context.groupsHeader()));
    }

    @Test
    void dwaionIsExplicitlyInactiveAndCannotLeakDataDuringWaveFour() {
        HomeRuntimeContext context = context();
        WidgetProviderPort.Request request = request(
                "dwaion.artifact", "dwaion-artifact", "APP.DWAION_ARTIFACTS");

        HomeWidgetProviderContract.BatchResponse response = new InactiveDwaionWidgetProvider()
                .readBatch(context, List.of(request),
                        OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        new ProviderResultValidator(objectMapper, properties())
                .validate(response, context, List.of(request));

        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.state()).isEqualTo(HomeWidgetProviderContract.State.UNAVAILABLE);
            assertThat(result.source().reasonCode())
                    .isEqualTo("OWNER_PROVIDER_NOT_CONFIGURED");
            assertThat(result.source().retryable()).isFalse();
            assertThat(result.payload()).isEmpty();
            assertThat(result.actions()).isEmpty();
        });
    }

    @Test
    void expiredAuthorityStopsThePlatformNativeCommandBeforeOwnerMutation() {
        HomeRuntimeContext context = TestFixtures.withAuthorityRevalidateAt(
                TestFixtures.context(), OffsetDateTime.now(ZoneOffset.UTC).minusNanos(1));
        HomeOverviewService overview = mock(HomeOverviewService.class);
        HomeOwnerActionReceiptService ownerReceipts = mock(HomeOwnerActionReceiptService.class);
        PlatformNativeWidgetProvider provider = new PlatformNativeWidgetProvider(
                overview, objectMapper, ownerReceipts);
        HomeOwnerActionContracts.Contract contract =
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION;
        HomeWidgetProviderContract.CommandRequest request =
                new HomeWidgetProviderContract.CommandRequest(
                        HomeWidgetProviderContract.SCHEMA_VERSION,
                        UUID.randomUUID(), UUID.randomUUID(),
                        contract.definitionKey(), contract.definitionManifestHash(),
                        "binding-revision-1234567890", contract.actionId(), contract.commandKey(),
                        "result-1", Map.of("recommendationKey", "work-due-soon"));

        assertThatThrownBy(() -> provider.executeCommand(
                context, request, OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1)))
                .isInstanceOf(BaseException.class);

        verify(ownerReceipts, never()).execute(any(), any(), any(), any());
        verify(overview, never()).recordFeedback(anyLong(), anyLong(), any(), any(), any());
    }

    private HomeRuntimeContext context() {
        return HomeRuntimeContext.create(
                71L, 82L, UUID.randomUUID(),
                "APP.WORKPLACE:VIEW,APP.DWAION_ARTIFACTS:VIEW", "MEMBER", "team-a",
                "decision-17", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString(),
                "ko-KR", "Asia/Seoul");
    }

    private WidgetProviderPort.Request request(
            String definitionKey,
            String legacyKey,
            String sourceApp) {
        WidgetCatalogService.RuntimeDefinition definition =
                new WidgetCatalogService.RuntimeDefinition(
                        UUID.randomUUID(), definitionKey, legacyKey, UUID.randomUUID(), "1.0.0",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "binding-1", "home." + legacyKey,
                        sourceApp.equals("APP.DWAION_ARTIFACTS")
                                ? "ai.agent-runtime" : "core.workplace",
                        sourceApp, List.of(sourceApp + ":VIEW"),
                        "CONFIDENTIAL", "NONE", 30,
                        WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE, List.of());
        return new WidgetProviderPort.Request(UUID.randomUUID(), definition, Map.of(), 10);
    }

    private HomeRuntimeProperties properties() {
        return new HomeRuntimeProperties(
                true, false, false, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }
}
