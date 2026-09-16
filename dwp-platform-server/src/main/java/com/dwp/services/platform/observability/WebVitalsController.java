package com.dwp.services.platform.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/v1/observability/web-vitals")
public class WebVitalsController {

    private static final Set<String> METRICS = Set.of("CLS", "INP", "LCP");
    private static final Set<String> RATINGS = Set.of("good", "needs-improvement", "poor");
    private static final Set<String> HOME_MODES = Set.of("CLASSIC", "FLOW_V1");
    private static final Set<String> HOME_RUNTIME_STATES = Set.of(
            "DISABLED", "SHADOW_COMPARE", "READ_ONLY_ACTIVE", "COMMAND_CANARY");
    private static final Set<String> HOME_RINGS = Set.of(
            "CONTROL", "INTERNAL", "PILOT", "EARLY_ADOPTER", "GA");
    private static final Set<String> DEVICE_CLASSES = Set.of(
            "DESKTOP_WIDE", "DESKTOP_STANDARD", "MOBILE_STANDARD", "MOBILE_COMPACT");

    private final MeterRegistry registry;

    public WebVitalsController(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    @ApiResponse(responseCode = "202", description = "Metric accepted")
    @ApiResponse(responseCode = "422", description = "Unsupported metric or rating")
    public ResponseEntity<Void> ingest(
            @RequestHeader(value = "X-DWP-Home-Runtime-State", required = false)
            String trustedHomeRuntime,
            @RequestHeader(value = "X-DWP-Home-Rollout-Ring", required = false)
            String trustedRolloutRing,
            @Valid @RequestBody WebVitalRequest request) {
        String metric = request.name().toUpperCase(Locale.ROOT);
        String rating = request.rating().toLowerCase(Locale.ROOT);
        if (!METRICS.contains(metric) || !RATINGS.contains(rating)
                || !validHomeDimensions(request, trustedHomeRuntime, trustedRolloutRing)) {
            return ResponseEntity.unprocessableEntity().build();
        }
        boolean home = isHome(request.routeGroup());
        String meterName = home
                ? switch (metric) {
                    case "LCP" -> "dwp.home.web.vital.lcp.ms";
                    case "INP" -> "dwp.home.web.vital.inp.ms";
                    default -> "dwp.home.web.vital.cls.ratio";
                }
                : "CLS".equals(metric)
                    ? "dwp.frontend.web_vital.cls"
                    : "dwp.frontend.web_vital.duration";
        DistributionSummary.Builder builder = DistributionSummary.builder(meterName)
                .description("Browser Core Web Vital samples received through the DWP Gateway")
                .publishPercentileHistogram();
        if (home) {
            String device = request.deviceClass().startsWith("MOBILE_")
                    ? "MOBILE" : "DESKTOP";
            builder.tag("release_ring", trustedRolloutRing)
                    .tag("mode", request.homeMode())
                    .tag("runtime", trustedHomeRuntime)
                    .tag("device_class", device);
            registry.counter("dwp.home.web.vital.sample",
                    "release_ring", trustedRolloutRing,
                    "mode", request.homeMode(),
                    "runtime", trustedHomeRuntime,
                    "device_class", device,
                    "vital_name", metric).increment();
        } else {
            builder.baseUnit("CLS".equals(metric) ? "1" : "milliseconds")
                    .tag("rating", rating)
                    .tag("route.group", request.routeGroup());
            if (!"CLS".equals(metric)) builder.tag("metric", metric);
        }
        builder.register(registry).record(request.value());
        return ResponseEntity.accepted().location(URI.create("/v1/observability/web-vitals")).build();
    }

    ResponseEntity<Void> ingest(WebVitalRequest request) {
        return ingest(null, null, request);
    }

    public record WebVitalRequest(
            @Schema(allowableValues = {"CLS", "INP", "LCP"})
            @NotBlank @Size(max = 8) String name,
            @NotNull @DecimalMin("0.0") @DecimalMax("600000.0") Double value,
            @NotNull @DecimalMin("-600000.0") @DecimalMax("600000.0") Double delta,
            @NotBlank @Size(max = 160) String id,
            @Schema(allowableValues = {"good", "needs-improvement", "poor"})
            @NotBlank @Size(max = 32) String rating,
            @NotBlank @Size(max = 40) String navigationType,
            @NotBlank @Size(max = 80)
            @Pattern(regexp = "[a-z0-9][a-z0-9._/-]*") String routeGroup,
            @Schema(allowableValues = {"CLASSIC", "FLOW_V1"}) String homeMode,
            @Schema(allowableValues = {
                    "DISABLED", "SHADOW_COMPARE", "READ_ONLY_ACTIVE", "COMMAND_CANARY"})
            String homeRuntime,
            @Schema(allowableValues = {"CONTROL", "INTERNAL", "PILOT", "EARLY_ADOPTER", "GA"})
            String rolloutRing,
            @Schema(allowableValues = {
                    "DESKTOP_WIDE", "DESKTOP_STANDARD", "MOBILE_STANDARD", "MOBILE_COMPACT"})
            String deviceClass) {

        public WebVitalRequest(
                String name,
                Double value,
                Double delta,
                String id,
                String rating,
                String navigationType,
                String routeGroup) {
            this(name, value, delta, id, rating, navigationType, routeGroup,
                    null, null, null, null);
        }
    }

    private boolean validHomeDimensions(
            WebVitalRequest request,
            String trustedHomeRuntime,
            String trustedRolloutRing) {
        if (!isHome(request.routeGroup())) {
            return request.homeMode() == null
                    && request.homeRuntime() == null
                    && request.rolloutRing() == null
                    && request.deviceClass() == null
                    && trustedHomeRuntime == null
                    && trustedRolloutRing == null;
        }
        return request.homeMode() != null
                && request.homeRuntime() != null
                && request.rolloutRing() != null
                && request.deviceClass() != null
                && trustedHomeRuntime != null
                && trustedRolloutRing != null
                && HOME_MODES.contains(request.homeMode())
                && HOME_RUNTIME_STATES.contains(trustedHomeRuntime)
                && HOME_RINGS.contains(trustedRolloutRing)
                && trustedHomeRuntime.equals(request.homeRuntime())
                && trustedRolloutRing.equals(request.rolloutRing())
                && DEVICE_CLASSES.contains(request.deviceClass());
    }

    private boolean isHome(String routeGroup) {
        return "home".equals(routeGroup)
                || routeGroup != null && (routeGroup.startsWith("home.")
                || routeGroup.endsWith(".home"));
    }
}
