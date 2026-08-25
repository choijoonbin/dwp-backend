package com.dwp.services.auth.repository;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProductAuthorizationContractRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProductAuthorizationContractRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void lockBundleKey(String bundleKey) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                result -> null,
                "product-authorization:" + bundleKey);
    }

    public Optional<StoredBundle> find(String bundleKey, long version) {
        return queryBundle("""
                SELECT bundle_id, bundle_key, version, bundle_status, schema_version,
                       checksum_algorithm, checksum, owner, approved_by, approved_at,
                       activated_at, created_at
                  FROM auth_product_authorization_bundle
                 WHERE bundle_key = ? AND version = ?
                """, bundleKey, version);
    }

    public Optional<StoredBundle> lock(String bundleKey, long version) {
        return queryBundle("""
                SELECT bundle_id, bundle_key, version, bundle_status, schema_version,
                       checksum_algorithm, checksum, owner, approved_by, approved_at,
                       activated_at, created_at
                  FROM auth_product_authorization_bundle
                 WHERE bundle_key = ? AND version = ?
                   FOR UPDATE
                """, bundleKey, version);
    }

    public Optional<StoredBundle> lock(UUID bundleId) {
        return queryBundle("""
                SELECT bundle_id, bundle_key, version, bundle_status, schema_version,
                       checksum_algorithm, checksum, owner, approved_by, approved_at,
                       activated_at, created_at
                  FROM auth_product_authorization_bundle
                 WHERE bundle_id = ?
                   FOR UPDATE
                """, bundleId);
    }

    public Optional<ActivePointer> findActivePointer(String bundleKey) {
        return queryPointer("""
                SELECT bundle_key, bundle_id, revision, activated_by, activated_at
                  FROM auth_product_authorization_active
                 WHERE bundle_key = ?
                """, bundleKey);
    }

    public Optional<ActivePointer> lockActivePointer(String bundleKey) {
        return queryPointer("""
                SELECT bundle_key, bundle_id, revision, activated_by, activated_at
                  FROM auth_product_authorization_active
                 WHERE bundle_key = ?
                   FOR UPDATE
                """, bundleKey);
    }

    public Optional<StoredBundle> findActive(String bundleKey) {
        return queryBundle("""
                SELECT bundle.bundle_id, bundle.bundle_key, bundle.version,
                       bundle.bundle_status, bundle.schema_version,
                       bundle.checksum_algorithm, bundle.checksum, bundle.owner,
                       bundle.approved_by, bundle.approved_at, bundle.activated_at,
                       bundle.created_at
                  FROM auth_product_authorization_active active_pointer
                  JOIN auth_product_authorization_bundle bundle
                    ON bundle.bundle_id = active_pointer.bundle_id
                   AND bundle.bundle_key = active_pointer.bundle_key
                 WHERE active_pointer.bundle_key = ?
                """, bundleKey);
    }

    public UUID insertDraft(ProductAuthorizationContractDtos.BundleContract contract) {
        UUID bundleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_product_authorization_bundle (
                    bundle_id, bundle_key, version, bundle_status, schema_version,
                    checksum_algorithm, checksum, owner)
                VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                """, bundleId, contract.bundleKey(), contract.version(), contract.schemaVersion(),
                contract.checksumAlgorithm(), contract.checksum(), contract.owner());

        for (ProductAuthorizationContractDtos.CapabilityContract value : contract.capabilities()) {
            jdbc.update("""
                    INSERT INTO auth_product_capability_contract (
                        bundle_id, contract_key, product_key, surface_key,
                        lifecycle_state, descriptor)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, bundleId, value.contractKey(), value.productKey(), value.surfaceKey(),
                    value.lifecycleState(), json(value));
        }
        for (ProductAuthorizationContractDtos.AccessPolicy value : contract.accessPolicies()) {
            jdbc.update("""
                    INSERT INTO auth_product_access_policy (
                        bundle_id, access_policy_key, navigation_context_id,
                        product_key, surface_key, lifecycle_state, descriptor)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, bundleId, value.accessPolicyKey(), value.navigationContextId(),
                    value.productKey(), value.surfaceKey(), value.lifecycleState(), json(value));
        }
        for (ProductAuthorizationContractDtos.EntitlementExpression value
                : contract.entitlementExpressions()) {
            jdbc.update("""
                    INSERT INTO auth_product_entitlement_expression (
                        bundle_id, expression_key, lifecycle_state, descriptor)
                    VALUES (?, ?, ?, CAST(? AS jsonb))
                    """, bundleId, value.expressionKey(), value.lifecycleState(), json(value));
        }
        for (ProductAuthorizationContractDtos.PredicatePolicy value : contract.predicatePolicies()) {
            jdbc.update("""
                    INSERT INTO auth_product_predicate_policy (
                        bundle_id, predicate_policy_key, owner_service_key,
                        lifecycle_state, descriptor)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                    """, bundleId, value.predicatePolicyKey(), value.ownerServiceKey(),
                    value.lifecycleState(), json(value));
        }
        for (ProductAuthorizationContractDtos.GovernedRoute value : contract.routes()) {
            jdbc.update("""
                    INSERT INTO auth_governed_route_contract (
                        bundle_id, route_contract_key, navigation_context_id,
                        subject_type, product_key, surface_key, route_kind,
                        lifecycle_state, descriptor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, bundleId, value.routeContractKey(), value.navigationContextId(),
                    value.subject().type(), value.subject().productKey(), value.subject().surfaceKey(),
                    value.routeKind(), value.lifecycleState(), json(value));
        }
        for (ProductAuthorizationContractDtos.AuthorityEndpoint value
                : contract.authorityEndpoints() == null
                ? List.<ProductAuthorizationContractDtos.AuthorityEndpoint>of()
                : contract.authorityEndpoints()) {
            jdbc.update("""
                    INSERT INTO auth_product_authority_endpoint (
                        bundle_id, endpoint_key, service_key, descriptor)
                    VALUES (?, ?, ?, CAST(? AS jsonb))
                    """, bundleId, value.endpointKey(), value.serviceKey(), json(value));
        }
        return bundleId;
    }

    public boolean approve(UUID bundleId, String approver) {
        return jdbc.update("""
                UPDATE auth_product_authorization_bundle
                   SET bundle_status = 'APPROVED', approved_by = ?,
                       approved_at = CURRENT_TIMESTAMP
                 WHERE bundle_id = ? AND bundle_status = 'DRAFT'
                """, approver, bundleId) == 1;
    }

    public boolean markActive(UUID bundleId) {
        return jdbc.update("""
                UPDATE auth_product_authorization_bundle
                   SET bundle_status = 'ACTIVE', activated_at = CURRENT_TIMESTAMP
                 WHERE bundle_id = ? AND bundle_status = 'APPROVED'
                """, bundleId) == 1;
    }

    public boolean markApproved(UUID bundleId) {
        return jdbc.update("""
                UPDATE auth_product_authorization_bundle
                   SET bundle_status = 'APPROVED'
                 WHERE bundle_id = ? AND bundle_status = 'ACTIVE'
                """, bundleId) == 1;
    }

    public void insertActivePointer(
            String bundleKey, UUID bundleId, String actorRef, long resultingRevision) {
        jdbc.update("""
                INSERT INTO auth_product_authorization_active (
                    bundle_key, bundle_id, revision, activated_by)
                VALUES (?, ?, ?, ?)
                """, bundleKey, bundleId, resultingRevision, actorRef);
    }

    public boolean replaceActivePointer(
            String bundleKey,
            UUID bundleId,
            String actorRef,
            long expectedRevision) {
        return jdbc.update("""
                UPDATE auth_product_authorization_active
                   SET bundle_id = ?, revision = revision + 1,
                       activated_by = ?, activated_at = CURRENT_TIMESTAMP
                 WHERE bundle_key = ? AND revision = ?
                """, bundleId, actorRef, bundleKey, expectedRevision) == 1;
    }

    public void insertActivationEvent(
            String bundleKey,
            UUID fromBundleId,
            UUID toBundleId,
            String operation,
            long expectedRevision,
            String actorRef) {
        jdbc.update("""
                INSERT INTO auth_product_authorization_activation_event (
                    bundle_key, from_bundle_id, to_bundle_id, operation,
                    expected_revision, resulting_revision, actor_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, bundleKey, fromBundleId, toBundleId, operation,
                expectedRevision, expectedRevision + 1, actorRef);
    }

    public Optional<StoredBundle> findImmediatePreviousApproved(
            String bundleKey, long activeVersion) {
        return queryBundle("""
                SELECT bundle_id, bundle_key, version, bundle_status, schema_version,
                       checksum_algorithm, checksum, owner, approved_by, approved_at,
                       activated_at, created_at
                  FROM auth_product_authorization_bundle
                 WHERE bundle_key = ? AND version = (
                       SELECT MAX(version)
                         FROM auth_product_authorization_bundle
                        WHERE bundle_key = ? AND version < ?
                          AND bundle_status = 'APPROVED')
                """, bundleKey, bundleKey, activeVersion);
    }

    public ProductAuthorizationContractDtos.BundleContract loadContract(StoredBundle bundle) {
        return new ProductAuthorizationContractDtos.BundleContract(
                bundle.schemaVersion(),
                bundle.bundleKey(),
                bundle.version(),
                bundle.bundleStatus(),
                bundle.owner(),
                bundle.checksumAlgorithm(),
                bundle.checksum(),
                readDescriptors(
                        "auth_product_capability_contract", "contract_key", bundle.bundleId(),
                        ProductAuthorizationContractDtos.CapabilityContract.class),
                readDescriptors(
                        "auth_product_access_policy", "access_policy_key", bundle.bundleId(),
                        ProductAuthorizationContractDtos.AccessPolicy.class),
                readDescriptors(
                        "auth_product_entitlement_expression", "expression_key", bundle.bundleId(),
                        ProductAuthorizationContractDtos.EntitlementExpression.class),
                readDescriptors(
                        "auth_product_predicate_policy", "predicate_policy_key", bundle.bundleId(),
                        ProductAuthorizationContractDtos.PredicatePolicy.class),
                readDescriptors(
                        "auth_governed_route_contract", "route_contract_key", bundle.bundleId(),
                        ProductAuthorizationContractDtos.GovernedRoute.class),
                readDescriptors(
                        "auth_product_authority_endpoint", "endpoint_key", bundle.bundleId(),
                        ProductAuthorizationContractDtos.AuthorityEndpoint.class));
    }

    private <T> List<T> readDescriptors(
            String table, String keyColumn, UUID bundleId, Class<T> type) {
        String sql = "SELECT descriptor::text FROM " + table
                + " WHERE bundle_id = ? ORDER BY " + keyColumn;
        return jdbc.query(sql, (result, ignored) -> read(result.getString(1), type), bundleId);
    }

    private Optional<StoredBundle> queryBundle(String sql, Object... arguments) {
        return jdbc.query(sql, this::storedBundle, arguments).stream().findFirst();
    }

    private Optional<ActivePointer> queryPointer(String sql, Object... arguments) {
        return jdbc.query(sql, (result, ignored) -> new ActivePointer(
                result.getString("bundle_key"),
                result.getObject("bundle_id", UUID.class),
                result.getLong("revision"),
                result.getString("activated_by"),
                result.getObject("activated_at", OffsetDateTime.class)), arguments).stream().findFirst();
    }

    private StoredBundle storedBundle(ResultSet result, int ignored) throws SQLException {
        return new StoredBundle(
                result.getObject("bundle_id", UUID.class),
                result.getString("bundle_key"),
                result.getLong("version"),
                result.getString("bundle_status"),
                result.getInt("schema_version"),
                result.getString("checksum_algorithm"),
                result.getString("checksum"),
                result.getString("owner"),
                result.getString("approved_by"),
                result.getObject("approved_at", OffsetDateTime.class),
                result.getObject("activated_at", OffsetDateTime.class),
                result.getObject("created_at", OffsetDateTime.class));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Authorization descriptor serialization failed.", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored authorization descriptor is invalid.", exception);
        }
    }

    public record StoredBundle(
            UUID bundleId,
            String bundleKey,
            long version,
            String bundleStatus,
            int schemaVersion,
            String checksumAlgorithm,
            String checksum,
            String owner,
            String approvedBy,
            OffsetDateTime approvedAt,
            OffsetDateTime activatedAt,
            OffsetDateTime createdAt) {
    }

    public record ActivePointer(
            String bundleKey,
            UUID bundleId,
            long revision,
            String activatedBy,
            OffsetDateTime activatedAt) {
    }
}
