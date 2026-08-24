package com.dwp.services.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ProductSurfaceTelemetryControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();
    private final ProductSurfaceTelemetryService service =
            mock(ProductSurfaceTelemetryService.class);
    private final ProductSurfaceTelemetryController controller =
            new ProductSurfaceTelemetryController(service, objectMapper, validator);

    @Test
    void acceptsTheExactPublicContract() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "eventName": "surface.route.denied",
                  "productKey": "approvals",
                  "surfaceKey": "approvals.admin",
                  "routeId": "approvals.admin.operations",
                  "reasonCode": "ROUTE_DENIED"
                }
                """);

        var response = controller.ingest(7L, "internal", payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(service).ingest(eq(7L), eq("internal"),
                org.mockito.ArgumentMatchers.any(ProductSurfaceTelemetryDtos.EventRequest.class));
    }

    @Test
    void rejectsUnknownFieldsBeforeMapping() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "eventName": "surface.exposed",
                  "productKey": "services",
                  "surfaceKey": "services.work",
                  "deviceClass": "DESKTOP",
                  "attemptId": "d2e63316-8564-4d8c-bd02-eaede882f982",
                  "rawUrl": "/services?personId=81"
                }
                """);

        assertThatThrownBy(() -> controller.ingest(7L, "internal", payload))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(controller.invalidTelemetry(new IllegalArgumentException()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void rejectsRegistryUnknownDimensionsAs422EvenWhenCollectionIsOff() throws Exception {
        ProductSurfaceTelemetryRepository repository =
                mock(ProductSurfaceTelemetryRepository.class);
        ProductSurfaceTelemetryService strictService = new ProductSurfaceTelemetryService(
                repository,
                new ProductSurfaceTelemetryDimensionRegistry(objectMapper),
                Clock.fixed(Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC),
                false);
        ProductSurfaceTelemetryController strictController =
                new ProductSurfaceTelemetryController(strictService, objectMapper, validator);
        var payload = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "eventName": "surface.exposed",
                  "productKey": "communications",
                  "surfaceKey": "communications.work.person-1042",
                  "deviceClass": "DESKTOP",
                  "attemptId": "d2e63316-8564-4d8c-bd02-eaede882f982"
                }
                """);

        assertThatThrownBy(() -> strictController.ingest(7L, "internal", payload))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(strictController.invalidTelemetry(
                new IllegalArgumentException()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(repository, never()).insert(org.mockito.ArgumentMatchers.any());
    }
}
