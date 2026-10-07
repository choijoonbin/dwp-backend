package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;

final class WorkplaceServiceInventoryRepository
        extends WorkplaceServiceOperationsRepositorySupport {
    WorkplaceServiceInventoryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    List<ProviderRow> providers(long tenantId) {
        return jdbc.query(providerSelect() + " WHERE profile.tenant_id = ?"
                + " ORDER BY profile.display_name_en, profile.provider_code",
                (rs, row) -> providerRow(rs, row, ProviderRow::new), tenantId);
    }

    Optional<ProviderRow> provider(long tenantId, UUID providerId) {
        return jdbc.query(providerSelect() + " WHERE profile.tenant_id = ?"
                + " AND profile.provider_profile_id = ?",
                (rs, row) -> providerRow(rs, row, ProviderRow::new),
                tenantId, providerId).stream().findFirst();
    }

    Optional<ProviderRow> providerByCode(long tenantId, String providerCode) {
        return jdbc.query(providerSelect() + " WHERE profile.tenant_id = ?"
                + " AND profile.provider_code = ?",
                (rs, row) -> providerRow(rs, row, ProviderRow::new),
                tenantId, providerCode).stream().findFirst();
    }

    private static String providerSelect() {
        return """
                SELECT profile.*, truth.configured, truth.observed_configuration_version,
                       truth.reported_state, truth.evidence_reference, truth.observed_at,
                       truth.received_at, truth.error_code
                  FROM wp_service_provider_profiles profile
                  JOIN wp_service_provider_truth truth
                    ON truth.tenant_id = profile.tenant_id
                   AND truth.provider_code = profile.provider_code
                """;
    }

    void createProvider(long tenantId, UUID providerId, ProviderCreateRequest request,
                        OffsetDateTime now) {
        String code = request.providerCode().trim();
        jdbc.update("""
                INSERT INTO wp_service_provider_truth (
                    tenant_id, provider_code, configured, configuration_version,
                    version, updated_at)
                VALUES (?, ?, FALSE, 1, 1, ?)
                """, tenantId, code, now);
        jdbc.update("""
                INSERT INTO wp_service_provider_profiles (
                    provider_profile_id, tenant_id, provider_code, display_name_ko,
                    display_name_en, adapter_type, lifecycle_state, site_scope,
                    capabilities, support_metadata, credential_binding_reference,
                    configuration_version, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, 1, 1, ?, ?)
                """, providerId, tenantId, code, request.displayNameKo().trim(),
                request.displayNameEn().trim(), request.adapterType().trim(),
                json(request.siteScope()), json(request.capabilities()), json(request.support()),
                normalize(request.credentialBindingReference()), now, now);
    }

    boolean updateProvider(long tenantId, UUID providerId,
                           ProviderUpdateRequest request, OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE wp_service_provider_profiles
                   SET display_name_ko = ?, display_name_en = ?, adapter_type = ?,
                       site_scope = ?::jsonb, capabilities = ?::jsonb,
                       support_metadata = ?::jsonb,
                       credential_binding_reference = CASE WHEN ? THEN NULL
                           WHEN ? IS NOT NULL THEN ? ELSE credential_binding_reference END,
                       configuration_version = configuration_version + 1,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND provider_profile_id = ? AND version = ?
                """, request.displayNameKo().trim(), request.displayNameEn().trim(),
                request.adapterType().trim(), json(request.siteScope()),
                json(request.capabilities()), json(request.support()),
                request.clearCredentialBinding(), normalize(request.credentialBindingReference()),
                normalize(request.credentialBindingReference()), now, tenantId, providerId,
                request.expectedVersion());
        if (changed == 1) {
            jdbc.update("""
                    UPDATE wp_service_provider_truth truth
                       SET configured = FALSE, configuration_version = profile.configuration_version,
                           observed_configuration_version = NULL, reported_state = NULL,
                           evidence_reference = NULL, observed_at = NULL, received_at = NULL,
                           error_code = NULL, version = truth.version + 1, updated_at = ?
                      FROM wp_service_provider_profiles profile
                     WHERE truth.tenant_id = profile.tenant_id
                       AND truth.provider_code = profile.provider_code
                       AND profile.tenant_id = ? AND profile.provider_profile_id = ?
                    """, now, tenantId, providerId);
        }
        return changed == 1;
    }

    boolean changeProviderState(long tenantId, UUID providerId, long expectedVersion,
                                ProviderLifecycleState state, OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE wp_service_provider_profiles
                   SET lifecycle_state = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND provider_profile_id = ? AND version = ?
                """, state.name(), now, tenantId, providerId, expectedVersion);
        if (changed == 1) {
            jdbc.update("""
                    UPDATE wp_service_provider_truth truth
                       SET configured = (? = 'ACTIVE'
                           AND profile.credential_binding_reference IS NOT NULL),
                           configuration_version = profile.configuration_version,
                           observed_configuration_version = CASE WHEN ? = 'ACTIVE'
                               THEN observed_configuration_version ELSE NULL END,
                           reported_state = CASE WHEN ? = 'ACTIVE' THEN reported_state ELSE NULL END,
                           evidence_reference = CASE WHEN ? = 'ACTIVE' THEN evidence_reference ELSE NULL END,
                           observed_at = CASE WHEN ? = 'ACTIVE' THEN observed_at ELSE NULL END,
                           received_at = CASE WHEN ? = 'ACTIVE' THEN received_at ELSE NULL END,
                           error_code = CASE WHEN ? = 'ACTIVE' THEN error_code ELSE NULL END,
                           version = truth.version + 1, updated_at = ?
                      FROM wp_service_provider_profiles profile
                     WHERE truth.tenant_id = profile.tenant_id
                       AND truth.provider_code = profile.provider_code
                       AND profile.tenant_id = ? AND profile.provider_profile_id = ?
                    """, state.name(), state.name(), state.name(), state.name(), state.name(),
                    state.name(), state.name(), now, tenantId, providerId);
        }
        return changed == 1;
    }

    boolean completeProviderVerification(
            long tenantId, UUID providerId, long expectedVersion, long actorUserId,
            WorkplaceServiceProviderVerifier.VerificationResult result,
            OffsetDateTime receivedAt) {
        ProviderRow provider = provider(tenantId, providerId).orElse(null);
        if (provider == null || provider.version() != expectedVersion) return false;
        jdbc.update("""
                INSERT INTO wp_service_provider_verifications (
                    provider_verification_id, tenant_id, provider_profile_id,
                    configuration_version, reported_state, evidence_reference,
                    capability_evidence, source_observed_at, received_at,
                    error_code, verified_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, providerId, provider.configurationVersion(),
                result.reportedState(), result.evidenceReference(),
                json(result.capabilityEvidence()), result.sourceObservedAt(), receivedAt,
                normalize(result.errorCode()), actorUserId, receivedAt);
        return jdbc.update("""
                UPDATE wp_service_provider_truth
                   SET configured = TRUE, configuration_version = ?,
                       observed_configuration_version = ?, reported_state = ?,
                       evidence_reference = ?, observed_at = ?, received_at = ?, error_code = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND provider_code = ? AND configuration_version = ?
                """, provider.configurationVersion(), provider.configurationVersion(),
                result.reportedState(), result.evidenceReference(), result.sourceObservedAt(),
                receivedAt, normalize(result.errorCode()), receivedAt, tenantId,
                provider.providerCode(), provider.configurationVersion()) == 1;
    }

    CatalogOperationsPolicy catalogPolicy(long tenantId, UUID itemId) {
        return jdbc.query("""
                SELECT catalog_item_id, capacity_mode, capacity_freshness_seconds,
                       inspection_mode, inspection_checklist_schema
                  FROM wp_service_catalog_items
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, (rs, row) -> new CatalogOperationsPolicy(
                        rs.getObject("catalog_item_id", UUID.class),
                        CapacityMode.valueOf(rs.getString("capacity_mode")),
                        rs.getInt("capacity_freshness_seconds"),
                        InspectionMode.valueOf(rs.getString("inspection_mode")),
                        node(rs.getString("inspection_checklist_schema"))),
                tenantId, itemId).stream().findFirst().orElse(null);
    }

    List<CapacityBucketRow> capacityBuckets(
            long tenantId, UUID itemId, String siteReference,
            OffsetDateTime from, OffsetDateTime to, OffsetDateTime now) {
        return jdbc.query("""
                SELECT bucket.*,
                       COALESCE((SELECT SUM(hold.quantity)
                         FROM wp_service_capacity_holds hold
                        WHERE hold.tenant_id = bucket.tenant_id
                          AND hold.capacity_bucket_id = bucket.capacity_bucket_id
                          AND hold.hold_state = 'HELD' AND hold.expires_at > ?), 0) held_quantity
                  FROM wp_service_capacity_buckets bucket
                 WHERE bucket.tenant_id = ? AND bucket.catalog_item_id = ?
                   AND bucket.site_reference = ?
                   AND bucket.bucket_starts_at < ? AND bucket.bucket_ends_at > ?
                 ORDER BY bucket.bucket_starts_at, bucket.capacity_bucket_id
                """, (rs, row) -> capacityBucketRow(rs, row, CapacityBucketRow::new),
                now, tenantId, itemId, siteReference, to, from);
    }

    void upsertCapacityBucket(long tenantId, UUID itemId, String siteReference,
                              CapacityBucketInput input, OffsetDateTime receivedAt) {
        jdbc.update("""
                INSERT INTO wp_service_capacity_buckets (
                    capacity_bucket_id, tenant_id, catalog_item_id, site_reference,
                    bucket_starts_at, bucket_ends_at, capacity_limit, committed_quantity,
                    source_version, source_observed_at, received_at, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (tenant_id, catalog_item_id, site_reference,
                    bucket_starts_at, bucket_ends_at)
                DO UPDATE SET capacity_limit = EXCLUDED.capacity_limit,
                    source_version = EXCLUDED.source_version,
                    source_observed_at = EXCLUDED.source_observed_at,
                    received_at = EXCLUDED.received_at,
                    version = wp_service_capacity_buckets.version + 1,
                    updated_at = EXCLUDED.updated_at
                WHERE wp_service_capacity_buckets.committed_quantity <= EXCLUDED.capacity_limit
                """, UUID.randomUUID(), tenantId, itemId, siteReference,
                input.startsAt(), input.endsAt(), input.capacityLimit(),
                input.sourceVersion().trim(), input.sourceObservedAt(), receivedAt,
                receivedAt, receivedAt);
    }

    CapacityHoldResult createCapacityHolds(
            long tenantId, UUID previewId, UUID itemId, String siteReference,
            OffsetDateTime from, OffsetDateTime to, int quantity,
            int freshnessSeconds, OffsetDateTime expiresAt, OffsetDateTime now) {
        List<CapacityBucketRow> buckets = jdbc.query("""
                SELECT bucket.*, COALESCE((SELECT SUM(hold.quantity)
                         FROM wp_service_capacity_holds hold
                        WHERE hold.tenant_id = bucket.tenant_id
                          AND hold.capacity_bucket_id = bucket.capacity_bucket_id
                          AND hold.hold_state = 'HELD' AND hold.expires_at > ?), 0) held_quantity
                  FROM wp_service_capacity_buckets bucket
                 WHERE bucket.tenant_id = ? AND bucket.catalog_item_id = ?
                   AND bucket.site_reference = ?
                   AND bucket.bucket_starts_at < ? AND bucket.bucket_ends_at > ?
                 ORDER BY bucket.bucket_starts_at, bucket.capacity_bucket_id
                 FOR UPDATE OF bucket
                """, (rs, row) -> capacityBucketRow(rs, row, CapacityBucketRow::new),
                now, tenantId, itemId, siteReference, to, from);
        if (buckets.isEmpty()) return CapacityHoldResult.missing();
        OffsetDateTime cursor = from;
        OffsetDateTime freshUntil = null;
        for (CapacityBucketRow bucket : buckets) {
            if (bucket.startsAt().isAfter(cursor)) return CapacityHoldResult.missing();
            if (bucket.receivedAt().plusSeconds(freshnessSeconds).isBefore(now)) {
                return CapacityHoldResult.stale(bucket.receivedAt().plusSeconds(freshnessSeconds));
            }
            if (bucket.capacityLimit() - bucket.committedQuantity() - bucket.heldQuantity()
                    < quantity) return CapacityHoldResult.exhausted();
            if (bucket.endsAt().isAfter(cursor)) cursor = bucket.endsAt();
            OffsetDateTime candidate = bucket.receivedAt().plusSeconds(freshnessSeconds);
            if (freshUntil == null || candidate.isBefore(freshUntil)) freshUntil = candidate;
        }
        if (cursor.isBefore(to)) return CapacityHoldResult.missing();
        List<UUID> holdIds = buckets.stream().map(ignored -> UUID.randomUUID()).toList();
        for (int index = 0; index < buckets.size(); index++) {
            CapacityBucketRow bucket = buckets.get(index);
            jdbc.update("""
                    INSERT INTO wp_service_capacity_holds (
                        capacity_hold_id, tenant_id, preview_id, catalog_item_id,
                        capacity_bucket_id, quantity, hold_state, bucket_version,
                        expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'HELD', ?, ?, ?, ?)
                    """, holdIds.get(index), tenantId, previewId, itemId,
                    bucket.bucketId(), quantity, bucket.version(), expiresAt, now, now);
        }
        return CapacityHoldResult.held(holdIds, expiresAt, freshUntil);
    }

    boolean commitCapacityHolds(long tenantId, UUID previewId, UUID orderId,
                                OffsetDateTime now) {
        List<CapacityHoldRow> holds = jdbc.query("""
                SELECT hold.* FROM wp_service_capacity_holds hold
                 WHERE hold.tenant_id = ? AND hold.preview_id = ?
                 ORDER BY hold.capacity_bucket_id
                 FOR UPDATE
                """, (rs, row) -> capacityHoldRow(rs, row, CapacityHoldRow::new),
                tenantId, previewId);
        if (holds.isEmpty()) return true;
        if (holds.stream().anyMatch(hold -> !"HELD".equals(hold.state())
                || !hold.expiresAt().isAfter(now))) return false;
        for (CapacityHoldRow hold : holds) {
            int updated = jdbc.update("""
                    UPDATE wp_service_capacity_buckets
                       SET committed_quantity = committed_quantity + ?,
                           version = version + 1, updated_at = ?
                     WHERE tenant_id = ? AND capacity_bucket_id = ?
                       AND version = ?
                       AND committed_quantity + ? <= capacity_limit
                    """, hold.quantity(), now, tenantId, hold.bucketId(),
                    hold.bucketVersion(), hold.quantity());
            if (updated != 1) return false;
            jdbc.update("""
                    UPDATE wp_service_capacity_holds
                       SET hold_state = 'COMMITTED', service_order_id = ?, committed_at = ?,
                           updated_at = ?
                     WHERE tenant_id = ? AND capacity_hold_id = ? AND hold_state = 'HELD'
                    """, orderId, now, now, tenantId, hold.holdId());
        }
        return true;
    }

    record ProviderRow(
            UUID providerId, String providerCode, String displayNameKo, String displayNameEn,
            String adapterType, ProviderLifecycleState lifecycleState, List<UUID> siteScope,
            List<String> capabilities, com.fasterxml.jackson.databind.JsonNode support,
            String credentialBindingReference, long configurationVersion, boolean configured,
            Long observedConfigurationVersion, String reportedState, String evidenceReference,
            OffsetDateTime observedAt, OffsetDateTime receivedAt, String errorCode,
            long version, OffsetDateTime updatedAt) { }

    record CatalogOperationsPolicy(
            UUID catalogItemId, CapacityMode capacityMode, int capacityFreshnessSeconds,
            InspectionMode inspectionMode,
            com.fasterxml.jackson.databind.JsonNode inspectionChecklistSchema) { }

    record CapacityBucketRow(
            UUID bucketId, UUID catalogItemId, String siteReference,
            OffsetDateTime startsAt, OffsetDateTime endsAt, int capacityLimit,
            int committedQuantity, int heldQuantity, String sourceVersion,
            OffsetDateTime sourceObservedAt, OffsetDateTime receivedAt, long version) { }

    record CapacityHoldRow(
            UUID holdId, UUID bucketId, int quantity, String state,
            long bucketVersion, OffsetDateTime expiresAt) { }

    record CapacityHoldResult(
            List<UUID> holdIds, OffsetDateTime expiresAt, OffsetDateTime freshUntil,
            String limitation) {
        static CapacityHoldResult held(
                List<UUID> ids, OffsetDateTime expiresAt, OffsetDateTime freshUntil) {
            return new CapacityHoldResult(List.copyOf(ids), expiresAt, freshUntil, null);
        }
        static CapacityHoldResult missing() {
            return new CapacityHoldResult(List.of(), null, null, "CAPACITY_BUCKET_MISSING");
        }
        static CapacityHoldResult stale(OffsetDateTime freshUntil) {
            return new CapacityHoldResult(List.of(), null, freshUntil, "CAPACITY_STALE");
        }
        static CapacityHoldResult exhausted() {
            return new CapacityHoldResult(List.of(), null, null, "CAPACITY_EXHAUSTED");
        }
    }
}
