package com.dwp.services.platform.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/observability/product-surface-events")
public class ProductSurfaceTelemetryController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String COHORT = "X-DWP-Rollout-Cohort";

    private final ProductSurfaceTelemetryService service;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public ProductSurfaceTelemetryController(
            ProductSurfaceTelemetryService service,
            ObjectMapper objectMapper,
            Validator validator) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping
    @Operation(operationId = "ingestProductSurfaceTelemetry")
    @ApiResponse(responseCode = "202", description = "Privacy-filtered UX event accepted")
    @ApiResponse(responseCode = "422", description = "Unknown event, field, or dimension")
    public ResponseEntity<Void> ingest(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(COHORT) String serverEvaluatedCohort,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = ProductSurfaceTelemetryDtos.EventRequest.class)))
            @RequestBody JsonNode payload) {
        ProductSurfaceTelemetryDtos.EventRequest request =
                ProductSurfaceTelemetryDtos.parseStrict(payload, objectMapper, validator);
        service.ingest(tenantId, serverEvaluatedCohort, request);
        return ResponseEntity.accepted().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidTelemetry(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("code", "INVALID_PRODUCT_SURFACE_TELEMETRY"));
    }
}
