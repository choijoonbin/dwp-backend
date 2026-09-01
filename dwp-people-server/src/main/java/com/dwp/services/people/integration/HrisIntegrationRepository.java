package com.dwp.services.people.integration;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Repository
public class HrisIntegrationRepository extends HrisIntegrationManagementRepository {
    public HrisIntegrationRepository(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    public record Receipt(
            long receiptId,
            UUID syncRunId,
            String state,
            String payloadSha256,
            boolean acquired) {
    }
    public record PersonUpsert(long personId, UUID publicId, boolean inserted) {
    }
    public record MappingRuntime(
            UUID mappingProfileId,
            long sourceSystemId,
            String profileKey,
            String adapterType,
            String sourceSchemaVersion,
            String targetSchemaVersion,
            String mappingDefinition,
            long version) {
    }
}
