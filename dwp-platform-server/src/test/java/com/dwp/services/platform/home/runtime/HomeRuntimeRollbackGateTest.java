package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class HomeRuntimeRollbackGateTest {

    @Test
    void verifiesRuntimeCommandProviderAndCacheRollbackControls() throws Exception {
        long started = System.nanoTime();
        String configuration = new ClassPathResource("application.yml").getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(configuration)
                .contains("enabled: ${DWP_HOME_RUNTIME_ENABLED:false}")
                .contains("shadow-enabled: ${DWP_HOME_RUNTIME_SHADOW_ENABLED:true}")
                .contains("commands-enabled: ${DWP_HOME_RUNTIME_COMMANDS_ENABLED:false}")
                .contains("token: ${DWP_HOME_PROVIDER_MEETING_TOKEN:}")
                .contains("signing-secret: ${DWP_DWAION_HOME_IDENTITY_SIGNING_SECRET:}")
                .contains("key-id: ${DWP_DWAION_HOME_IDENTITY_KEY_ID:platform-dwaion-home-v1}");

        verifyReadKillSwitch();
        verifyCommandKillSwitch();
        verifyProviderKillSwitch();
        verifyCachePurge();

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("gate", "W4-RUNTIME-ROLLBACK");
        evidence.put("status", "PASS");
        evidence.put("v2KillSwitchVerified", true);
        evidence.put("commandKillSwitchVerified", true);
        evidence.put("providerKillSwitchVerified", true);
        evidence.put("cachePurgeVerified", true);
        evidence.put("schemaDowngradeRequired", false);
        evidence.put("recoveryTimeMillis", elapsedMillis);
        evidence.put("recoveryTimeSeconds", (int) Math.ceil(elapsedMillis / 1_000.0));
        Path output = Path.of("build/reports/wave4/home-runtime-rollback.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), evidence);
    }

    private void verifyReadKillSwitch() {
        HomeReadModelService reads = mock(HomeReadModelService.class);
        HomeReadModelController controller = new HomeReadModelController(
                reads, mock(HomeWidgetCommandService.class),
                properties(false, false, false));

        assertThatThrownBy(() -> controller.read(
                71L, 82L, null, "APP.WORK:VIEW", "MEMBER", "", "decision-1",
                future(), "ko-KR", null, "CLASSIC", "DESKTOP_STANDARD", "Asia/Seoul"))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(reads);
    }

    private void verifyCommandKillSwitch() {
        HomeWidgetCommandService commands = mock(HomeWidgetCommandService.class);
        HomeReadModelController controller = new HomeReadModelController(
                mock(HomeReadModelService.class), commands,
                properties(true, false, false));

        assertThatThrownBy(() -> controller.execute(
                71L, 82L, null, "APP.WORK:VIEW", "MEMBER", "", "decision-1",
                future(), "ko-KR", UUID.randomUUID(), "CLASSIC", "DESKTOP_STANDARD",
                "Asia/Seoul", new HomeReadModelDtos.CommandRequest(
                        UUID.randomUUID(), "open-item", "result-1", Map.of())))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(commands);
    }

    private void verifyProviderKillSwitch() {
        HttpWidgetProviderClient provider = new HttpWidgetProviderClient(
                "meeting", "http://127.0.0.1:1", "", Duration.ofMillis(50),
                RestClient.builder(), CircuitBreakerRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults());

        assertThatThrownBy(() -> provider.readBatch(
                TestFixtures.context(), List.of(TestFixtures.request("meetings.rollback")),
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1)))
                .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                    assertThat(failure.kind())
                            .isEqualTo(WidgetProviderException.Kind.UNAVAILABLE);
                    assertThat(failure.reasonCode()).isEqualTo("PROVIDER_NOT_CONFIGURED");
                });
    }

    private void verifyCachePurge() {
        RecipientBoundWidgetCache cache = new RecipientBoundWidgetCache(
                properties(true, false, false));
        RecipientBoundWidgetCache.Key key = new RecipientBoundWidgetCache.Key(
                71L, 82L, "fingerprint", "decision-1", "ko-KR", "Asia/Seoul",
                "CLASSIC", "DESKTOP_STANDARD", "view-1", "catalog-1", "policy-1",
                "safety-1", "platform", Set.of("core.work.rollback"), "request-1");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeWidgetProviderContract.BatchResponse response =
                new HomeWidgetProviderContract.BatchResponse(
                        HomeWidgetProviderContract.SCHEMA_VERSION,
                        71L, 82L, "decision-1", List.of());
        cache.put(key, response, now.plusSeconds(30), now.plusMinutes(1));
        assertThat(cache.size()).isOne();

        cache.clear();

        assertThat(cache.size()).isZero();
        assertThat(cache.fresh(key)).isEmpty();
        assertThat(cache.stale(key)).isEmpty();
    }

    private HomeRuntimeProperties properties(
            boolean enabled,
            boolean shadowEnabled,
            boolean commandsEnabled) {
        return new HomeRuntimeProperties(
                enabled, shadowEnabled, commandsEnabled,
                Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }

    private String future() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString();
    }
}
