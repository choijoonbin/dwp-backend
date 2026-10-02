package com.dwp.services.provider.rollout;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates only disposable tenant mappings and governed HCM rollout revisions.
 * It deliberately reuses the production rollout state machine and maker/checker
 * evidence instead of writing active rollout rows directly.
 */
@Service
@ConditionalOnProperty(
        name = "dwp.provider.synthetic-product-surface-bootstrap.enabled",
        havingValue = "true")
public class LocalSyntheticProductSurfaceBootstrapService {
    private static final UUID URL_NAMESPACE =
            UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private static final List<String> HCM_FLAGS = List.of(
            "access.product-surfaces.context-shadow.v1",
            "access.product-surfaces.capability-enforcement.hcm.v1",
            "ux.product-surfaces.hcm.v1");
    private static final long REQUESTER = 9_100_001L;
    private static final long APPROVER = 9_100_002L;

    private final JdbcTemplate jdbc;
    private final FeatureRolloutRepository rollouts;
    private final FeatureRolloutDecisionOutboxRepository decisions;
    private final ObjectMapper objectMapper;
    private final String expectedRunId;

    public LocalSyntheticProductSurfaceBootstrapService(
            JdbcTemplate jdbc,
            FeatureRolloutRepository rollouts,
            FeatureRolloutDecisionOutboxRepository decisions,
            ObjectMapper objectMapper,
            @Value("${dwp.provider.synthetic-product-surface-bootstrap.run-id:}")
                    String expectedRunId) {
        this.jdbc = jdbc;
        this.rollouts = rollouts;
        this.decisions = decisions;
        this.objectMapper = objectMapper;
        this.expectedRunId = expectedRunId == null ? "" : expectedRunId;
        if (!this.expectedRunId.matches("w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}")) {
            throw new IllegalStateException(
                    "Local synthetic rollout bootstrap requires an exact W1 run binding.");
        }
    }

    @Transactional
    public LocalSyntheticProductSurfaceBootstrapDtos.BootstrapResponse bootstrap(
            LocalSyntheticProductSurfaceBootstrapDtos.BootstrapRequest request) {
        if (!expectedRunId.equals(request.runId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic rollout request is not bound to this runtime.");
        }
        requireRunBoundIdentity(request);
        requireFreshIdentities(request);
        insertTenantMapping(request);

        Map<String, String> revisions = new LinkedHashMap<>();
        if ("111".equals(request.hcmState())) {
            for (String flagKey : HCM_FLAGS) {
                revisions.put(flagKey, activate(flagKey, request.providerTenantId()));
            }
        } else {
            for (String flagKey : HCM_FLAGS) {
                FeatureRolloutRepository.FlagRow flag = rollouts.flag(flagKey)
                        .orElseThrow(() -> new IllegalStateException(
                                "Required product-surface flag is missing: " + flagKey));
                revisions.put(flagKey, "rev-%020d".formatted(decisions.revision(flag.flagId())));
            }
        }
        String receipt = sha256(String.join("|",
                request.runId(),
                request.providerTenantId().toString(),
                request.authTenantId().toString(),
                request.tenantKey(),
                request.hcmState(),
                revisions.toString()));
        return new LocalSyntheticProductSurfaceBootstrapDtos.BootstrapResponse(
                request.runId(),
                request.providerTenantId(),
                request.authTenantId(),
                request.tenantKey(),
                request.hcmState(),
                Map.copyOf(revisions),
                receipt);
    }

    private void requireFreshIdentities(
            LocalSyntheticProductSurfaceBootstrapDtos.BootstrapRequest request) {
        Integer conflicts = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_tenants
                 WHERE provider_tenant_id=? OR auth_tenant_id=? OR tenant_key=?
                """, Integer.class, request.providerTenantId(),
                request.authTenantId(), request.tenantKey());
        if (conflicts == null || conflicts != 0) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Synthetic rollout bootstrap requires fresh tenant identities.");
        }
    }

    private void requireRunBoundIdentity(
            LocalSyntheticProductSurfaceBootstrapDtos.BootstrapRequest request) {
        String suffix = expectedRunId.substring(expectedRunId.lastIndexOf('-') + 1);
        String lane;
        if (request.tenantKey().equals("w1-a-" + suffix)) {
            lane = "a";
        } else if (request.tenantKey().equals("w1-b-" + suffix)) {
            lane = "b";
        } else {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic rollout tenant key is not bound to this runtime.");
        }
        UUID expectedTenant = uuidV5("dwp:" + expectedRunId + ":tenant-" + lane);
        if (!expectedTenant.equals(request.providerTenantId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic rollout tenant identity is not bound to this runtime.");
        }
    }

    private static UUID uuidV5(String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            ByteBuffer namespace = ByteBuffer.allocate(16)
                    .putLong(URL_NAMESPACE.getMostSignificantBits())
                    .putLong(URL_NAMESPACE.getLeastSignificantBits());
            digest.update(namespace.array());
            byte[] bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            ByteBuffer value = ByteBuffer.wrap(bytes, 0, 16);
            return new UUID(value.getLong(), value.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private void insertTenantMapping(
            LocalSyntheticProductSurfaceBootstrapDtos.BootstrapRequest request) {
        ObjectNode binding = objectMapper.createObjectNode();
        binding.put("syntheticRunId", request.runId());
        binding.put("hcmState", request.hcmState());
        jdbc.update("""
                INSERT INTO prv_organizations (
                    organization_id, organization_key, display_name,
                    lifecycle_state, attributes, created_by, updated_by)
                VALUES (?, ?, ?, 'ACTIVE', CAST(? AS JSONB), ?, ?)
                """, request.providerTenantId(), request.tenantKey(),
                request.displayName().trim(), binding.toString(), REQUESTER, REQUESTER);
        jdbc.update("""
                INSERT INTO prv_tenants (
                    provider_tenant_id, tenant_key, display_name, service_tier,
                    data_region, isolation_model, lifecycle_state, onboarding_state,
                    auth_tenant_id, organization_id, environment_key,
                    default_locale, time_zone, schema_version, configuration,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'STANDARD', 'local', 'POOL', 'ACTIVE', 'READY',
                        ?, ?, 'local', 'ko', 'Asia/Seoul', 1, CAST(? AS JSONB), ?, ?)
                """, request.providerTenantId(), request.tenantKey(),
                request.displayName().trim(), request.authTenantId(),
                request.providerTenantId(), binding.toString(), REQUESTER, REQUESTER);
    }

    private String activate(String flagKey, UUID providerTenantId) {
        FeatureRolloutRepository.FlagRow flag = rollouts.lockFlag(flagKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Required product-surface flag is missing: " + flagKey));
        if (!"ACTIVE".equals(flag.lifecycleState())
                || !"BOOLEAN".equals(flag.valueType())
                || !flag.defaultValue().isBoolean()
                || flag.defaultValue().booleanValue()) {
            throw new IllegalStateException(
                    "Synthetic rollout requires an active, default-off boolean flag: "
                            + flagKey);
        }
        ObjectNode targeting = objectMapper.createObjectNode();
        ArrayNode tenantIds = targeting.putArray("tenantIds");
        tenantIds.add(providerTenantId.toString());
        FeatureRolloutDtos.CreateRolloutRequest request =
                new FeatureRolloutDtos.CreateRolloutRequest(
                        "Synthetic W1 HCM rollout",
                        BooleanNode.TRUE,
                        targeting,
                        "ALL_AT_ONCE",
                        "Run-bound disposable W1 acceptance fixture.",
                        List.of(new FeatureRolloutDtos.StageRequest(
                                "Exact synthetic tenant",
                                new BigDecimal("100.00"),
                                0,
                                objectMapper.createObjectNode())));
        UUID rolloutId = UUID.nameUUIDFromBytes(
                (expectedRunId + "|" + providerTenantId + "|" + flagKey)
                        .getBytes(StandardCharsets.UTF_8));
        FeatureRolloutRepository.RolloutRow draft =
                rollouts.createRollout(flag, rolloutId, request, REQUESTER);
        if (!rollouts.submit(rolloutId, draft.version(), REQUESTER)) {
            throw new IllegalStateException("Synthetic rollout submission was rejected");
        }
        FeatureRolloutRepository.RolloutRow pending = rollouts.rollout(rolloutId)
                .orElseThrow();
        if (!rollouts.decide(
                rolloutId,
                pending.version(),
                "APPROVED",
                "Independent synthetic acceptance approval.",
                APPROVER)) {
            throw new IllegalStateException("Synthetic rollout approval was rejected");
        }
        FeatureRolloutRepository.RolloutRow approved = rollouts.rollout(rolloutId)
                .orElseThrow();
        if (!rollouts.activate(rolloutId, approved.version())) {
            throw new IllegalStateException("Synthetic rollout activation was rejected");
        }
        long revision = decisions.appendAllTenants(flag.flagId(), flagKey, "ENABLED");
        return "rev-%020d".formatted(revision);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
