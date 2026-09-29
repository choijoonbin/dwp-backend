package com.dwp.services.platform.home.runtime;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.home.personalization.HomeCommandReceiptService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryMutationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

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

    private void verifyReadKillSwitch() throws Exception {
        HomeReadModelService reads = mock(HomeReadModelService.class);
        MockMvc mvc = mvc(new HomeReadModelController(
                reads, mock(HomeWidgetCommandService.class),
                properties(false, false, false)));

        mvc.perform(get("/v2/home")
                        .queryParam("mode", "CLASSIC")
                        .queryParam("deviceClass", "DESKTOP_STANDARD")
                        .queryParam("timeZone", "Asia/Seoul")
                        .header("X-DWP-Tenant-ID", "71")
                        .header("X-DWP-User-ID", "82")
                        .header("X-DWP-Permissions", "APP.WORK:VIEW")
                        .header("X-DWP-Roles", "MEMBER")
                        .header("X-DWP-Current-Decision-Revision", "decision-1")
                        .header("X-DWP-Current-Revalidate-At", future())
                        .header("X-DWP-Home-Runtime-State", "SHADOW_COMPARE")
                        .header("X-DWP-Home-Rollout-Ring", "CONTROL")
                        .header("X-DWP-Home-Rollout-Revision", "rollout-1")
                        .header("Accept-Language", "ko-KR"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_AVAILABLE"));
        verifyNoInteractions(reads);
    }

    private void verifyCommandKillSwitch() throws Exception {
        HomeRuntimeProperties disabled = properties(true, false, false);
        HomeReadModelService reads = mock(HomeReadModelService.class);
        HomeWidgetCommandService commands = new HomeWidgetCommandService(
                List.of(),
                reads,
                mock(HomeCommandReceiptService.class),
                new HomeCanonicalJson(new ObjectMapper().findAndRegisterModules()),
                mock(ProviderResultValidator.class),
                disabled,
                mock(PlatformAuditService.class),
                mock(WidgetRegistryMutationGuard.class),
                mock(HomeRuntimeTelemetry.class));
        MockMvc mvc = mvc(new HomeReadModelController(reads, commands, disabled));
        HomeReadModelDtos.CommandRequest request = new HomeReadModelDtos.CommandRequest(
                UUID.randomUUID(), "open-item", "result-1", Map.of());

        mvc.perform(post("/v2/home/widget-actions:execute")
                        .queryParam("mode", "CLASSIC")
                        .queryParam("deviceClass", "DESKTOP_STANDARD")
                        .queryParam("timeZone", "Asia/Seoul")
                        .header("X-DWP-Tenant-ID", "71")
                        .header("X-DWP-User-ID", "82")
                        .header("X-DWP-Permissions", "APP.WORK:VIEW")
                        .header("X-DWP-Roles", "MEMBER")
                        .header("X-DWP-Current-Decision-Revision", "decision-1")
                        .header("X-DWP-Current-Revalidate-At", future())
                        .header("X-DWP-Home-Runtime-State", "SHADOW_COMPARE")
                        .header("X-DWP-Home-Rollout-Ring", "CONTROL")
                        .header("X-DWP-Home-Rollout-Revision", "rollout-1")
                        .header("Accept-Language", "ko-KR")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_AVAILABLE"));
        verifyNoInteractions(reads);
    }

    private void verifyProviderKillSwitch() {
        HttpWidgetProviderClient provider = new HttpWidgetProviderClient(
                "meeting", "http://127.0.0.1:1", "", false, Duration.ofMillis(50),
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
                "safety-1", "rollout-1", "platform", Set.of("core.work.rollback"),
                "request-1");
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

    private MockMvc mvc(HomeReadModelController controller) {
        return standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    private String future() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString();
    }
}
