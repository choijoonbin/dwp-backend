package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ProviderResultValidatorTest {

    private final ProviderResultValidator validator = new ProviderResultValidator(
            new ObjectMapper().findAndRegisterModules(), properties());
    private final HomeRuntimeContext context = TestFixtures.context();
    private final WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");

    @Test
    void rejectsMismatchedRecipientEvenWhenPayloadLooksValid() {
        HomeWidgetProviderContract.BatchResponse response = response(valid(
                Map.of("title", "safe"), List.of(), List.of()));
        response = new HomeWidgetProviderContract.BatchResponse(
                1, context.tenantId(), context.userId() + 1,
                context.authorityDecisionRevision(), response.results());

        HomeWidgetProviderContract.BatchResponse mismatched = response;
        assertThatThrownBy(() -> validator.validate(mismatched, context, List.of(request)))
                .isInstanceOf(WidgetProviderException.class);
    }

    @Test
    void rejectsNullResultCollectionAndAuthorityRevision() {
        HomeWidgetProviderContract.BatchResponse nullResults =
                new HomeWidgetProviderContract.BatchResponse(
                        1, context.tenantId(), context.userId(),
                        context.authorityDecisionRevision(), null);
        HomeWidgetProviderContract.BatchResponse nullRevision =
                new HomeWidgetProviderContract.BatchResponse(
                        1, context.tenantId(), context.userId(), null, List.of());

        assertThatThrownBy(() -> validator.validate(nullResults, context, List.of(request)))
                .isInstanceOf(WidgetProviderException.class);
        assertThatThrownBy(() -> validator.validate(nullRevision, context, List.of()))
                .isInstanceOf(WidgetProviderException.class);
    }

    @Test
    void rejectsExternalUrlsInsideDeclarativePayload() {
        HomeWidgetProviderContract.BatchResponse response = response(valid(
                Map.of("image", "https://evil.example/pixel"), List.of(), List.of()));

        assertThatThrownBy(() -> validator.validate(response, context, List.of(request)))
                .isInstanceOfSatisfying(WidgetProviderException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.kind())
                                .isEqualTo(WidgetProviderException.Kind.MALFORMED));
    }

    @Test
    void rejectsCustomSchemeUrlsInsideDeclarativePayload() {
        HomeWidgetProviderContract.BatchResponse response = response(valid(
                Map.of("deepLink", "skdwp://evil.example/path"), List.of(), List.of()));

        assertThatThrownBy(() -> validator.validate(response, context, List.of(request)))
                .isInstanceOfSatisfying(WidgetProviderException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.kind())
                                .isEqualTo(WidgetProviderException.Kind.MALFORMED));
    }

    @Test
    void acceptsOrdinaryLocalizedTextContainingAColon() {
        HomeWidgetProviderContract.BatchResponse response = response(valid(
                Map.of("summary", "Re: quarterly planning"), List.of(), List.of()));

        assertThatCode(() -> validator.validate(response, context, List.of(request)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsEqualFreshnessBoundsAndFutureGeneration() {
        OffsetDateTime generated = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2);
        HomeWidgetProviderContract.WidgetResult result = new HomeWidgetProviderContract.WidgetResult(
                request.instanceId(), request.definition().definitionKey(),
                request.definition().manifestHash(), request.definition().rendererBindingRevision(),
                HomeWidgetProviderContract.State.AVAILABLE,
                new HomeWidgetProviderContract.SourceState(
                        "PLATFORM_HOME", generated, generated, generated, null, false, "v1"),
                Map.of("count", 1), List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(response(result), context, List.of(request)))
                .isInstanceOf(WidgetProviderException.class);
    }

    @Test
    void commandActionCannotSmuggleSourceRoute() {
        HomeWidgetProviderContract.Action action = new HomeWidgetProviderContract.Action(
                "accept-item", "home.action.accept",
                HomeWidgetProviderContract.ActionKind.COMMAND,
                "/approval/requests/1", "approval.accept", "v1", true);

        assertThatThrownBy(() -> validator.validate(
                response(valid(Map.of("count", 1), List.of(action), List.of())),
                context, List.of(request))).isInstanceOf(WidgetProviderException.class);
    }

    @Test
    void forbiddenStateCannotBeRetryable() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeWidgetProviderContract.WidgetResult result = new HomeWidgetProviderContract.WidgetResult(
                request.instanceId(), request.definition().definitionKey(),
                request.definition().manifestHash(), request.definition().rendererBindingRevision(),
                HomeWidgetProviderContract.State.FORBIDDEN,
                new HomeWidgetProviderContract.SourceState(
                        "PLATFORM_HOME", now, now.plusSeconds(30), null,
                        "AUTHORIZATION_SOURCE_FORBIDDEN", true, null),
                Map.of(), List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(response(result), context, List.of(request)))
                .isInstanceOf(WidgetProviderException.class);
    }

    private HomeWidgetProviderContract.WidgetResult valid(
            Map<String, Object> payload,
            List<HomeWidgetProviderContract.Action> actions,
            List<String> redactions) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeWidgetProviderContract.WidgetResult(
                request.instanceId(), request.definition().definitionKey(),
                request.definition().manifestHash(), request.definition().rendererBindingRevision(),
                HomeWidgetProviderContract.State.AVAILABLE,
                new HomeWidgetProviderContract.SourceState(
                        "PLATFORM_HOME", now, now.plusSeconds(30), now,
                        null, false, "v1"), payload, actions, redactions);
    }

    private HomeWidgetProviderContract.BatchResponse response(
            HomeWidgetProviderContract.WidgetResult result) {
        return new HomeWidgetProviderContract.BatchResponse(
                1, context.tenantId(), context.userId(),
                context.authorityDecisionRevision(), List.of(result));
    }

    private HomeRuntimeProperties properties() {
        return new HomeRuntimeProperties(
                true, false, true, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }
}
