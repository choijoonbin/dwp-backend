package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderResultValidatorSecurityTest {

    private final ProviderResultValidator validator = new ProviderResultValidator(
            new ObjectMapper().findAndRegisterModules(), properties());
    private final HomeRuntimeContext context = TestFixtures.context();
    private final WidgetProviderPort.Request request = TestFixtures.request("core.work.security");

    @Test
    void successfulStateCannotArriveExpired() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeWidgetProviderContract.WidgetResult expired = result(
                now.minusSeconds(30), now.minusSeconds(1), now.minusSeconds(30), List.of());

        assertMalformed(expired);
    }

    @Test
    void lastSuccessCannotBeLaterThanTheGeneratedSnapshot() {
        OffsetDateTime generated = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2);
        HomeWidgetProviderContract.WidgetResult inconsistent = result(
                generated, generated.plusSeconds(30), generated.plusSeconds(1), List.of());

        assertMalformed(inconsistent);
    }

    @Test
    void duplicateCommandActionIdsAreRejectedWithinAWidget() {
        HomeWidgetProviderContract.Action first = command("approve-item", "work.approve");
        HomeWidgetProviderContract.Action duplicate = command("approve-item", "work.reject");
        OffsetDateTime generated = OffsetDateTime.now(ZoneOffset.UTC);
        HomeWidgetProviderContract.WidgetResult result = result(
                generated, generated.plusSeconds(30), generated, List.of(first, duplicate));

        assertMalformed(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "//admin", "///admin", "/work/./admin", "/work/../admin",
            "/work/.%2e/admin", "/work/%2E%2E/admin", "/work/%2fadmin",
            "/work/%5cadmin", "/work/%252e%252e/admin", "/work/%2",
            "/work?item=1?next=2", "/work#admin", "/work/%00admin",
            "/work/%C3", "/work/%C3%A9"
    })
    void ambiguousInternalRoutesAreRejected(String route) {
        assertThatThrownBy(() -> ProviderResultValidator.internalRoute(route))
                .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(WidgetProviderException.Kind.MALFORMED);
                    assertThat(failure.reasonCode()).isEqualTo("INVALID_SOURCE_ROUTE");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/work", "/work/queue?item=PERSONAL_TASK%3A123%3A",
            "/approvals/home?filter=due-today&owner=me"
    })
    void canonicalInternalRoutesRemainAvailable(String route) {
        assertThatCode(() -> ProviderResultValidator.internalRoute(route))
                .doesNotThrowAnyException();
    }

    @Test
    void encodedTraversalSourceActionIsRejectedBeforeProjection() {
        HomeWidgetProviderContract.Action unsafe = new HomeWidgetProviderContract.Action(
                "open-source", "home.action.openSource",
                HomeWidgetProviderContract.ActionKind.SOURCE_ROUTE,
                "/work/%2e%2e/admin", null, null, false);
        OffsetDateTime generated = OffsetDateTime.now(ZoneOffset.UTC);

        HomeWidgetProviderContract.WidgetResult unsafeResult = result(
                generated, generated.plusSeconds(30), generated, List.of(unsafe));
        HomeWidgetProviderContract.BatchResponse response =
                new HomeWidgetProviderContract.BatchResponse(
                        HomeWidgetProviderContract.SCHEMA_VERSION,
                        context.tenantId(), context.userId(),
                        context.authorityDecisionRevision(), List.of(unsafeResult));

        assertThatThrownBy(() -> validator.validate(response, context, List.of(request)))
                .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(WidgetProviderException.Kind.MALFORMED);
                    assertThat(failure.reasonCode()).isEqualTo("INVALID_SOURCE_ROUTE");
                });
    }

    private HomeWidgetProviderContract.Action command(String actionId, String commandKey) {
        return new HomeWidgetProviderContract.Action(
                actionId,
                "home.action." + actionId,
                HomeWidgetProviderContract.ActionKind.COMMAND,
                null,
                commandKey,
                "result-1",
                true);
    }

    private HomeWidgetProviderContract.WidgetResult result(
            OffsetDateTime generatedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime lastSuccessAt,
            List<HomeWidgetProviderContract.Action> actions) {
        return new HomeWidgetProviderContract.WidgetResult(
                request.instanceId(), request.definition().definitionKey(),
                request.definition().manifestHash(),
                request.definition().rendererBindingRevision(),
                HomeWidgetProviderContract.State.AVAILABLE,
                new HomeWidgetProviderContract.SourceState(
                        "PLATFORM_HOME", generatedAt, expiresAt, lastSuccessAt,
                        null, false, "result-1"),
                Map.of("count", 1), actions, List.of());
    }

    private void assertMalformed(HomeWidgetProviderContract.WidgetResult result) {
        HomeWidgetProviderContract.BatchResponse response =
                new HomeWidgetProviderContract.BatchResponse(
                        HomeWidgetProviderContract.SCHEMA_VERSION,
                        context.tenantId(), context.userId(),
                        context.authorityDecisionRevision(), List.of(result));
        assertThatThrownBy(() -> validator.validate(response, context, List.of(request)))
                .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(WidgetProviderException.Kind.MALFORMED);
                    assertThat(failure.reasonCode()).isEqualTo("PROVIDER_CONTRACT_INVALID");
                });
    }

    private HomeRuntimeProperties properties() {
        return new HomeRuntimeProperties(
                true, false, true, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }
}
