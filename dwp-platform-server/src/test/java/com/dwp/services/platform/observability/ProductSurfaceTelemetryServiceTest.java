package com.dwp.services.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductSurfaceTelemetryServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProductSurfaceTelemetryRepository repository;

    private final ProductSurfaceTelemetryDimensionRegistry dimensions =
            new ProductSurfaceTelemetryDimensionRegistry(new ObjectMapper());

    @Test
    void keepsCollectionOffUntilPrivacyFlagIsEnabled() {
        ProductSurfaceTelemetryService service = service(false);

        ProductSurfaceTelemetryDtos.AcceptedEvent result = service.ingest(
                41L, "internal", exposed());

        assertThat(result.collected()).isFalse();
        assertThat(result.acceptedAt().toInstant()).isEqualTo(FIXED_CLOCK.instant());
        verify(repository, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistsOnlyServerTenantAndServerEvaluatedCohort() {
        ProductSurfaceTelemetryService service = service(true);

        service.ingest(41L, "design-partner", exposed());

        ArgumentCaptor<ProductSurfaceTelemetryRepository.EventRow> row =
                ArgumentCaptor.forClass(ProductSurfaceTelemetryRepository.EventRow.class);
        verify(repository).insert(row.capture());
        assertThat(row.getValue().tenantId()).isEqualTo(41L);
        assertThat(row.getValue().cohort()).isEqualTo("design-partner");
        assertThat(row.getValue().event().eventName()).isEqualTo("surface.exposed");
    }

    @Test
    void rejectsUnknownCohortAndUnknownEvent() {
        ProductSurfaceTelemetryService service = service(true);

        assertThatThrownBy(() -> service.ingest(41L, "client-selected", exposed()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(
                41L, "internal", request("surface.actor.viewed", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsFieldsThatAreNotDeclaredForTheSpecificEvent() {
        ProductSurfaceTelemetryService service = service(true);

        assertThatThrownBy(() -> service.ingest(
                41L, "internal", request("surface.exposed", "communications.work.home")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid for event");
    }

    @Test
    void acceptsExactCanaryAndW1aApprovalDimensions() {
        ProductSurfaceTelemetryService service = service(true);

        service.ingest(41L, "internal", routeDenied(
                "communications", "communications.work", "communications.work.home"));
        service.ingest(41L, "internal", routeDenied(
                "services", "services.management", "services.management.operations"));
        service.ingest(41L, "internal", routeDenied(
                "approvals", "approvals.admin", "approvals.admin.operations"));

        verify(repository, times(3)).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsCrossProductAndPiiLikeValuesAcrossEverySurfaceDimension() {
        ProductSurfaceTelemetryService service = service(true);

        assertRejected(service, exposed("communications", "approvals.admin"));
        assertRejected(service, switchStarted(
                "communications", "approvals.work", "communications.work"));
        assertRejected(service, switchStarted(
                "communications", "communications.work", "services.work"));
        assertRejected(service, switchFailed(
                "communications", "communications.work.person-1042"));
        verify(repository, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsV3HcmAndUnknownOrCrossSurfaceRoutes() {
        ProductSurfaceTelemetryService service = service(true);

        assertRejected(service, exposed("hcm", "hcm.personal"));
        assertRejected(service, exposed("person-1042", "person-1042.work"));
        assertRejected(service, routeDenied(
                "communications", "communications.work", "communications.work.person-1042"));
        assertRejected(service, routeDenied(
                "services", "services.work", "services.management.catalog"));
        assertRejected(service, routeDenied(
                "approvals", "approvals.admin", "communications.work.home"));
        verify(repository, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void collectionDisabledStillRejectsUnknownDimensionsBeforeReturningAccepted() {
        ProductSurfaceTelemetryService service = service(false);

        assertRejected(service, exposed("communications", "communications.work.person-1042"));
        verify(repository, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    private ProductSurfaceTelemetryService service(boolean collectionEnabled) {
        return new ProductSurfaceTelemetryService(
                repository, dimensions, FIXED_CLOCK, collectionEnabled);
    }

    private static void assertRejected(
            ProductSurfaceTelemetryService service,
            ProductSurfaceTelemetryDtos.EventRequest request) {
        assertThatThrownBy(() -> service.ingest(41L, "internal", request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProductSurfaceTelemetryDtos.EventRequest exposed() {
        return request("surface.exposed", null);
    }

    private static ProductSurfaceTelemetryDtos.EventRequest exposed(
            String product,
            String surface) {
        return event(
                "surface.exposed", product, surface, null, null, null, null,
                ProductSurfaceTelemetryDtos.DeviceClass.DESKTOP, null, null);
    }

    private static ProductSurfaceTelemetryDtos.EventRequest switchStarted(
            String product,
            String fromSurface,
            String toSurface) {
        return event(
                "surface.switch.started", product, null, fromSurface, toSurface, null, null,
                null, null, null);
    }

    private static ProductSurfaceTelemetryDtos.EventRequest switchFailed(
            String product,
            String targetSurface) {
        return event(
                "surface.switch.failed", product, null, null, null, targetSurface, null,
                null, null, ProductSurfaceTelemetryDtos.ReasonCode.VALIDATION_ERROR);
    }

    static ProductSurfaceTelemetryDtos.EventRequest routeDenied(
            String product,
            String surface,
            String route) {
        return event(
                "surface.route.denied", product, surface, null, null, null, route,
                null, null, ProductSurfaceTelemetryDtos.ReasonCode.ROUTE_DENIED);
    }

    private static ProductSurfaceTelemetryDtos.EventRequest event(
            String eventName,
            String product,
            String surface,
            String fromSurface,
            String toSurface,
            String targetSurface,
            String route,
            ProductSurfaceTelemetryDtos.DeviceClass deviceClass,
            ProductSurfaceTelemetryDtos.ElapsedBucket elapsedBucket,
            ProductSurfaceTelemetryDtos.ReasonCode reasonCode) {
        return new ProductSurfaceTelemetryDtos.EventRequest(
                1, eventName, product, surface, fromSurface, toSurface, targetSurface, route,
                null, deviceClass, elapsedBucket, reasonCode, null, null, null,
                route == null
                        ? UUID.fromString("d2e63316-8564-4d8c-bd02-eaede882f982")
                        : null);
    }

    private static ProductSurfaceTelemetryDtos.EventRequest request(
            String eventName,
            String routeId) {
        return new ProductSurfaceTelemetryDtos.EventRequest(
                1,
                eventName,
                "communications",
                "communications.work",
                null,
                null,
                null,
                routeId,
                null,
                ProductSurfaceTelemetryDtos.DeviceClass.DESKTOP,
                null,
                null,
                null,
                null,
                null,
                UUID.fromString("d2e63316-8564-4d8c-bd02-eaede882f982"));
    }
}
