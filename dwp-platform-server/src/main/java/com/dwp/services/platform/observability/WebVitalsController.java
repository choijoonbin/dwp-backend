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

    private final MeterRegistry registry;

    public WebVitalsController(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    @ApiResponse(responseCode = "202", description = "Metric accepted")
    @ApiResponse(responseCode = "422", description = "Unsupported metric or rating")
    public ResponseEntity<Void> ingest(@Valid @RequestBody WebVitalRequest request) {
        String metric = request.name().toUpperCase(Locale.ROOT);
        String rating = request.rating().toLowerCase(Locale.ROOT);
        if (!METRICS.contains(metric) || !RATINGS.contains(rating)) {
            return ResponseEntity.unprocessableEntity().build();
        }
        DistributionSummary.builder("dwp.frontend.web_vital")
                .description("Browser Core Web Vital samples received through the DWP Gateway")
                .baseUnit("milliseconds")
                .tag("metric", metric)
                .tag("rating", rating)
                .tag("route.group", request.routeGroup())
                .publishPercentileHistogram()
                .register(registry)
                .record(request.value());
        return ResponseEntity.accepted().location(URI.create("/v1/observability/web-vitals")).build();
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
            @Pattern(regexp = "[a-z0-9][a-z0-9._/-]*") String routeGroup) {
    }
}
