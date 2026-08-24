package com.dwp.services.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSurfaceTelemetryPrivacyContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsIdentityObjectScopeAndRawLocationFields() throws Exception {
        for (String forbidden : List.of(
                "actorId", "personId", "objectId", "scopeKey", "rawUrl", "query")) {
            var payload = objectMapper.readTree("""
                    {
                      "schemaVersion": 1,
                      "eventName": "surface.exposed",
                      "productKey": "hcm",
                      "surfaceKey": "hcm.work",
                      "deviceClass": "MOBILE",
                      "attemptId": "d2e63316-8564-4d8c-bd02-eaede882f982",
                      "%s": "must-not-enter-analytics"
                    }
                    """.formatted(forbidden));

            assertThatThrownBy(() -> ProductSurfaceTelemetryDtos.parseStrict(
                    payload,
                    objectMapper,
                    Validation.buildDefaultValidatorFactory().getValidator()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rawAndAggregateStoresContainNoIdentityOrObjectColumns() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V177__create_product_surface_ux_telemetry.sql"))
                .toLowerCase();

        assertThat(migration)
                .doesNotContain("actor_id", "person_id", "object_id", "scope_key", "raw_url")
                .contains("raw retention is fixed at 30 days")
                .contains("retention is fixed at 180 days")
                .contains("dwp_product_surface_analytics_reader");
    }

    @Test
    void operationsDefaultsKeepCollectionOffAndRetentionMaintenanceOn() throws Exception {
        String configuration = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(configuration)
                .contains("collection-enabled: "
                        + "${DWP_PRODUCT_SURFACE_TELEMETRY_COLLECTION_ENABLED:false}")
                .contains("maintenance-enabled: "
                        + "${DWP_PRODUCT_SURFACE_TELEMETRY_MAINTENANCE_ENABLED:true}")
                .contains("maintenance-batch-size: "
                        + "${DWP_PRODUCT_SURFACE_TELEMETRY_MAINTENANCE_BATCH_SIZE:1000}")
                .contains("maintenance-cron: "
                        + "${DWP_PRODUCT_SURFACE_TELEMETRY_MAINTENANCE_CRON:0 41 2 * * *}");
    }
}
