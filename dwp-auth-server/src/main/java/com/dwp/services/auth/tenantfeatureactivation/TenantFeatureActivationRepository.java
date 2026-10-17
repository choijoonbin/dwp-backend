package com.dwp.services.auth.tenantfeatureactivation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** PostgreSQL-backed positive feature gate. Every malformed or ambiguous tuple fails closed. */
@Repository
public class TenantFeatureActivationRepository implements TenantFeatureActivationReader {

    private final JdbcTemplate jdbc;

    public TenantFeatureActivationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public GateResult evaluate(GateRequest request) {
        if (request == null || request.tenantId() == null || request.tenantId() <= 0
                || blank(request.productKey()) || blank(request.surfaceKey())) {
            return GateResult.unavailable();
        }
        if (blank(request.routeContractKey())) {
            return GateResult.ungoverned(null);
        }

        List<Binding> bindings = jdbc.query("""
                SELECT owner.route_contract_key AS owned_route_contract_key,
                       owner.feature_key AS owned_feature_key,
                       policy.policy_version,
                       binding.route_contract_key AS bound_route_contract_key,
                       binding.product_key, binding.app_resource_key,
                       binding.minimum_bundle_version, binding.route_kind,
                       binding.subject_type, binding.surface_key,
                       binding.lifecycle_state AS binding_state,
                       policy.baseline_state
                  FROM sys_product_feature_route_owners owner
                  LEFT JOIN LATERAL (
                       SELECT candidate.policy_version, candidate.baseline_state
                         FROM sys_product_feature_activation_policies candidate
                        WHERE candidate.feature_key = owner.feature_key
                          AND candidate.lifecycle_state = 'PUBLISHED'
                        ORDER BY candidate.policy_version DESC
                        LIMIT 1
                  ) policy ON TRUE
                  LEFT JOIN sys_product_feature_route_bindings binding
                    ON binding.route_contract_key = owner.route_contract_key
                   AND binding.feature_key = owner.feature_key
                   AND binding.policy_version = policy.policy_version
                 WHERE owner.route_contract_key = ?
                """, (result, ignored) -> new Binding(
                result.getString("owned_route_contract_key"),
                result.getString("bound_route_contract_key"),
                result.getString("owned_feature_key"), result.getLong("policy_version"),
                result.getString("product_key"), result.getString("app_resource_key"),
                result.getLong("minimum_bundle_version"), result.getString("route_kind"),
                result.getString("subject_type"), result.getString("surface_key"),
                result.getString("binding_state"), result.getString("baseline_state")),
                request.routeContractKey());
        if (bindings.isEmpty()) {
            return GateResult.ungoverned(request.routeContractKey());
        }
        if (bindings.size() != 1) {
            return GateResult.unavailable();
        }
        Binding binding = bindings.getFirst();
        if (!binding.validFor(request)) {
            return GateResult.unavailable();
        }
        if ("RETIRED".equals(binding.bindingState())) {
            return result(
                    State.UNGATED,
                    "UNGATED",
                    binding,
                    request,
                    "binding=RETIRED");
        }
        if (!"ACTIVE".equals(binding.bindingState())) {
            return GateResult.unavailable();
        }

        List<AppAdoption> adoptions = jdbc.query("""
                SELECT lifecycle_state, version
                  FROM com_tenant_app_installations
                 WHERE tenant_id = ? AND product_key = ? AND app_resource_key = ?
                """, (result, ignored) -> new AppAdoption(
                result.getString("lifecycle_state"), result.getLong("version")),
                request.tenantId(), binding.productKey(), binding.appResourceKey());
        if (adoptions.size() > 1) {
            return GateResult.unavailable();
        }
        if (adoptions.isEmpty() || !"ENABLED".equals(adoptions.getFirst().state())) {
            String adoption = adoptions.isEmpty()
                    ? "ABSENT"
                    : adoptions.getFirst().state() + ':' + adoptions.getFirst().version();
            return result(
                    State.FEATURE_NOT_ACTIVATED,
                    "TENANT_APP_ADOPTION_REQUIRED",
                    binding,
                    request,
                    "app=" + adoption + "|global=NOT_READ|tenant=NOT_READ");
        }

        AppAdoption adoption = adoptions.getFirst();
        List<Winner> global = jdbc.query("""
                SELECT activation_change_id, version, target_bundle_version,
                       activation_receipt_id, activated_at
                  FROM auth_product_feature_activation_changes
                 WHERE feature_key = ? AND policy_version = ?
                   AND lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> new Winner(
                result.getObject("activation_change_id", UUID.class),
                result.getLong("version"), result.getLong("target_bundle_version"),
                result.getObject("activation_receipt_id", UUID.class),
                instant(result.getObject("activated_at", OffsetDateTime.class))),
                binding.featureKey(), binding.policyVersion());
        if (global.size() > 1) {
            return GateResult.unavailable();
        }
        if (global.isEmpty() || global.getFirst().targetBundleVersion() > request.bundleVersion()) {
            String globalState = global.isEmpty()
                    ? "ABSENT"
                    : "BUNDLE_AHEAD:" + winner(global.getFirst());
            return result(
                    State.FEATURE_NOT_ACTIVATED,
                    "FEATURE_ACTIVATION_REQUIRED",
                    binding,
                    request,
                    "app=ENABLED:" + adoption.version()
                            + "|global=" + globalState
                            + "|tenant=NOT_READ");
        }

        Winner active = global.getFirst();
        List<Winner> enrollment = jdbc.query("""
                SELECT enrollment_change_id, version, 0 AS target_bundle_version,
                       activation_receipt_id, activated_at
                  FROM com_tenant_feature_enrollment_changes
                 WHERE tenant_id = ? AND feature_key = ? AND policy_version = ?
                   AND lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> new Winner(
                result.getObject("enrollment_change_id", UUID.class),
                result.getLong("version"), result.getLong("target_bundle_version"),
                result.getObject("activation_receipt_id", UUID.class),
                instant(result.getObject("activated_at", OffsetDateTime.class))),
                request.tenantId(), binding.featureKey(), binding.policyVersion());
        if (enrollment.size() > 1) {
            return GateResult.unavailable();
        }
        String globalRevision = winner(active);
        if (enrollment.isEmpty()) {
            return result(
                    State.TENANT_NOT_ENROLLED,
                    "TENANT_FEATURE_ENROLLMENT_REQUIRED",
                    binding,
                    request,
                    "app=ENABLED:" + adoption.version()
                            + "|global=" + globalRevision + "|tenant=ABSENT");
        }
        return result(
                State.ACTIVE,
                "ACTIVE",
                binding,
                request,
                "app=ENABLED:" + adoption.version()
                        + "|global=" + globalRevision
                        + "|tenant=" + winner(enrollment.getFirst()));
    }

    private GateResult result(
            State state,
            String reasonCode,
            Binding binding,
            GateRequest request,
            String stateRevision) {
        String source = String.join("|",
                "v259",
                binding.featureKey(),
                String.valueOf(binding.policyVersion()),
                binding.productKey(),
                binding.appResourceKey(),
                String.valueOf(binding.minimumBundleVersion()),
                binding.routeContractKey(),
                binding.routeKind(),
                binding.subjectType(),
                binding.surfaceKey(),
                String.valueOf(request.tenantId()),
                stateRevision);
        return new GateResult(
                state,
                reasonCode,
                binding.featureKey(),
                TenantFeatureActivationReader.revision(source));
    }

    private static String winner(Winner winner) {
        return winner.id() + ":" + winner.version() + ':'
                + winner.targetBundleVersion() + ':' + winner.receiptId() + ':'
                + winner.activatedAt();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Binding(
            String routeContractKey,
            String boundRouteContractKey,
            String featureKey,
            long policyVersion,
            String productKey,
            String appResourceKey,
            long minimumBundleVersion,
            String routeKind,
            String subjectType,
            String surfaceKey,
            String bindingState,
            String baselineState) {

        private boolean validFor(GateRequest request) {
            return routeContractKey.equals(request.routeContractKey())
                    && boundRouteContractKey != null
                    && boundRouteContractKey.equals(routeContractKey)
                    && productKey != null && productKey.equals(request.productKey())
                    && surfaceKey != null && surfaceKey.equals(request.surfaceKey())
                    && "APP.HCM".equals(appResourceKey)
                    && policyVersion > 0
                    && minimumBundleVersion == 50
                    && request.bundleVersion() >= minimumBundleVersion
                    && List.of("PAGE", "DATA", "ACTION").contains(routeKind)
                    && "PRODUCT".equals(subjectType)
                    && "OFF".equals(baselineState);
        }
    }

    private record AppAdoption(String state, long version) {
    }

    private record Winner(
            UUID id,
            long version,
            long targetBundleVersion,
            UUID receiptId,
            Instant activatedAt) {
    }
}
