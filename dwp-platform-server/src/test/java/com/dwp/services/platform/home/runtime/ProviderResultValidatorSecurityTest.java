package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
