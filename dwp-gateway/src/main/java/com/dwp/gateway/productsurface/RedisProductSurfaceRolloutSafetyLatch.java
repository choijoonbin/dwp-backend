package com.dwp.gateway.productsurface;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class RedisProductSurfaceRolloutSafetyLatch
        implements ProductSurfaceRolloutSafetyLatch {

    private static final String KEY_PREFIX =
            "dwp:gateway:product-surface:se-latch:v2:";
    private static final String LEGACY_KEY_PREFIX =
            "dwp:gateway:product-surface:se-latch:v1:";
    private static final Pattern REVISION = Pattern.compile("^rev-\\d{20}$");
    private static final Set<String> REASON_CODES = Set.of(
            "DEFAULT", "TARGET_MISS", "PERCENTAGE_EXCLUDED", "ROLLOUT_MATCH");
    private static final Set<String> COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> DISABLED_COHORTS = Set.of("baseline", "holdout");

    private static final RedisScript<String> LOAD_SCRIPT = new DefaultRedisScript<>("""
            local key_type = redis.call('TYPE', KEYS[1]).ok
            if key_type == 'none' then
              return 'MISSING'
            end
            if key_type ~= 'hash' or redis.call('HLEN', KEYS[1]) ~= 6 then
              return 'CORRUPT'
            end

            local values = redis.call('HMGET', KEYS[1],
              'schema', 'productKey', 'shadowEnabled', 'shadowRevision',
              'enforcementEnabled', 'enforcementRevision')
            local function valid_revision(value)
              return value and string.len(value) == 24
                and string.sub(value, 1, 4) == 'rev-'
                and string.match(string.sub(value, 5), '^%d+$') ~= nil
            end
            local valid_bits = (values[3] == '0' or values[3] == '1')
              and (values[5] == '0' or values[5] == '1')
              and not (values[3] == '0' and values[5] == '1')
            if values[1] ~= '2' or values[2] ~= ARGV[1] or not valid_bits
              or not valid_revision(values[4]) or not valid_revision(values[6]) then
              return 'CORRUPT'
            end
            if redis.call('PTTL', KEYS[1]) ~= -1 then
              return 'CORRUPT'
            end
            return 'FOUND|' .. values[3] .. '|' .. values[4]
              .. '|' .. values[5] .. '|' .. values[6]
            """, String.class);

    private static final RedisScript<String> LEGACY_LOAD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('TYPE', KEYS[1]).ok == 'none' then
              return 'MISSING'
            end
            return 'MIGRATION_REQUIRED'
            """, String.class);

    private static final RedisScript<String> APPROVE_SCRIPT = new DefaultRedisScript<>("""
            local function valid_revision(value)
              return value and string.len(value) == 24
                and string.sub(value, 1, 4) == 'rev-'
                and string.match(string.sub(value, 5), '^%d+$') ~= nil
            end
            local function encoded(status, shadow, shadow_revision,
                enforcement, enforcement_revision)
              return status .. '|' .. shadow .. '|' .. shadow_revision
                .. '|' .. enforcement .. '|' .. enforcement_revision
            end
            local incoming_bits = (ARGV[2] == '0' or ARGV[2] == '1')
              and (ARGV[4] == '0' or ARGV[4] == '1')
              and not (ARGV[2] == '0' and ARGV[4] == '1')
            if not incoming_bits or not valid_revision(ARGV[3])
              or not valid_revision(ARGV[5]) then
              return 'INVALID_DECISION'
            end

            local key_type = redis.call('TYPE', KEYS[1]).ok
            if key_type == 'none' then
              redis.call('HSET', KEYS[1],
                'schema', '2',
                'productKey', ARGV[1],
                'shadowEnabled', ARGV[2],
                'shadowRevision', ARGV[3],
                'enforcementEnabled', ARGV[4],
                'enforcementRevision', ARGV[5])
              redis.call('PERSIST', KEYS[1])
              return encoded('CREATED', ARGV[2], ARGV[3], ARGV[4], ARGV[5])
            end
            if key_type ~= 'hash' or redis.call('HLEN', KEYS[1]) ~= 6 then
              return 'CORRUPT'
            end

            local current = redis.call('HMGET', KEYS[1],
              'schema', 'productKey', 'shadowEnabled', 'shadowRevision',
              'enforcementEnabled', 'enforcementRevision')
            local current_bits = (current[3] == '0' or current[3] == '1')
              and (current[5] == '0' or current[5] == '1')
              and not (current[3] == '0' and current[5] == '1')
            if current[1] ~= '2' or current[2] ~= ARGV[1] or not current_bits
              or not valid_revision(current[4]) or not valid_revision(current[6])
              or redis.call('PTTL', KEYS[1]) ~= -1 then
              return 'CORRUPT'
            end

            if ARGV[3] < current[4] or ARGV[5] < current[6] then
              redis.call('PERSIST', KEYS[1])
              return encoded('OUT_OF_ORDER',
                current[3], current[4], current[5], current[6])
            end
            if (ARGV[3] == current[4] and ARGV[2] ~= current[3])
              or (ARGV[5] == current[6] and ARGV[4] ~= current[5]) then
              redis.call('PERSIST', KEYS[1])
              return 'REVISION_CONFLICT'
            end
            if ARGV[3] == current[4] and ARGV[5] == current[6] then
              redis.call('PERSIST', KEYS[1])
              return encoded('UNCHANGED',
                current[3], current[4], current[5], current[6])
            end

            redis.call('HSET', KEYS[1],
              'schema', '2',
              'productKey', ARGV[1],
              'shadowEnabled', ARGV[2],
              'shadowRevision', ARGV[3],
              'enforcementEnabled', ARGV[4],
              'enforcementRevision', ARGV[5])
            redis.call('PERSIST', KEYS[1])
            return encoded('UPDATED', ARGV[2], ARGV[3], ARGV[4], ARGV[5])
            """, String.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final Duration timeout;

    public RedisProductSurfaceRolloutSafetyLatch(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${dwp.product-surface.rollout-latch-timeout:1s}") Duration timeout) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("A reactive Redis template is required");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException(
                    "Product Surface rollout latch timeout must be between 1ms and 5s");
        }
        this.redisTemplate = redisTemplate;
        this.timeout = timeout;
    }

    @Override
    public Mono<LoadResult> load(long authTenantId, String productKey) {
        if (authTenantId <= 0 || !validProductKey(productKey)) {
            return Mono.just(new LoadResult(LoadStatus.CORRUPT, null));
        }
        return loadV2(authTenantId, productKey)
                .flatMap(result -> result.status() == LoadStatus.MISSING
                        ? loadLegacyThenRecheckV2(authTenantId, productKey)
                        : Mono.just(result))
                .timeout(timeout)
                .onErrorReturn(unavailableLoad());
    }

    private Mono<LoadResult> loadV2(long authTenantId, String productKey) {
        return redisTemplate.execute(
                        LOAD_SCRIPT,
                        List.of(key(authTenantId, productKey)),
                        List.of(productKey))
                .singleOrEmpty()
                .map(this::decodeLoad)
                .switchIfEmpty(Mono.just(unavailableLoad()));
    }

    private Mono<LoadResult> loadLegacyThenRecheckV2(
            long authTenantId,
            String productKey) {
        return loadLegacy(authTenantId)
                .flatMap(legacy -> loadV2(authTenantId, productKey)
                        .map(current -> current.status() == LoadStatus.MISSING
                                ? legacy
                                : current));
    }

    @Override
    public Mono<ApprovalResult> approve(
            long authTenantId,
            String productKey,
            FeatureRolloutDecisionCache.FlagDecision shadow,
            FeatureRolloutDecisionCache.FlagDecision productEnforcement) {
        if (authTenantId <= 0 || !validProductKey(productKey)
                || !validDecisions(productKey, shadow, productEnforcement)) {
            return Mono.just(new ApprovalResult(ApprovalStatus.INVALID_DECISION, null));
        }
        List<String> arguments = List.of(
                productKey,
                bit(shadow.enabled()), shadow.opaqueRevision(),
                bit(productEnforcement.enabled()), productEnforcement.opaqueRevision());
        return redisTemplate.execute(
                        APPROVE_SCRIPT, List.of(key(authTenantId, productKey)), arguments)
                .singleOrEmpty()
                .map(this::decodeApproval)
                .switchIfEmpty(Mono.just(unavailableApproval()))
                .timeout(timeout)
                .onErrorReturn(unavailableApproval());
    }

    private boolean validDecisions(
            String productKey,
            FeatureRolloutDecisionCache.FlagDecision shadow,
            FeatureRolloutDecisionCache.FlagDecision productEnforcement) {
        return validDecision(
                        shadow, FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG)
                && validDecision(
                        productEnforcement,
                        ProductSurfaceRolloutFlagCatalog.productEnforcementFlag(productKey))
                && (!productEnforcement.enabled() || shadow.enabled());
    }

    private boolean validDecision(
            FeatureRolloutDecisionCache.FlagDecision decision,
            String expectedFlag) {
        if (decision == null || !decision.authoritative()
                || !expectedFlag.equals(decision.flagKey())
                || decision.evaluatedAt() == null
                || !validRevision(decision.opaqueRevision())
                || !REASON_CODES.contains(decision.reasonCode())
                || !COHORTS.contains(decision.cohort())) {
            return false;
        }
        return decision.enabled() != DISABLED_COHORTS.contains(decision.cohort());
    }

    private LoadResult decodeLoad(String encoded) {
        if ("MISSING".equals(encoded)) {
            return new LoadResult(LoadStatus.MISSING, null);
        }
        if ("MIGRATION_REQUIRED".equals(encoded)) {
            return new LoadResult(LoadStatus.MIGRATION_REQUIRED, null);
        }
        if ("CORRUPT".equals(encoded)) {
            return new LoadResult(LoadStatus.CORRUPT, null);
        }
        Snapshot snapshot = decodeSnapshot(encoded, "FOUND");
        return snapshot == null
                ? new LoadResult(LoadStatus.CORRUPT, null)
                : new LoadResult(LoadStatus.FOUND, snapshot);
    }

    private Mono<LoadResult> loadLegacy(long authTenantId) {
        return redisTemplate.execute(
                        LEGACY_LOAD_SCRIPT,
                        List.of(legacyKey(authTenantId)),
                        List.of())
                .singleOrEmpty()
                .map(this::decodeLegacyLoad)
                .switchIfEmpty(Mono.just(unavailableLoad()));
    }

    private LoadResult decodeLegacyLoad(String encoded) {
        if ("MISSING".equals(encoded)) {
            return new LoadResult(LoadStatus.MISSING, null);
        }
        if ("MIGRATION_REQUIRED".equals(encoded)) {
            return new LoadResult(LoadStatus.MIGRATION_REQUIRED, null);
        }
        return new LoadResult(LoadStatus.CORRUPT, null);
    }

    private ApprovalResult decodeApproval(String encoded) {
        for (ApprovalStatus status : Set.of(
                ApprovalStatus.REVISION_CONFLICT,
                ApprovalStatus.INVALID_DECISION,
                ApprovalStatus.CORRUPT)) {
            if (status.name().equals(encoded)) return new ApprovalResult(status, null);
        }
        for (ApprovalStatus status : Set.of(
                ApprovalStatus.CREATED,
                ApprovalStatus.UPDATED,
                ApprovalStatus.UNCHANGED,
                ApprovalStatus.OUT_OF_ORDER)) {
            Snapshot snapshot = decodeSnapshot(encoded, status.name());
            if (snapshot != null) return new ApprovalResult(status, snapshot);
        }
        return new ApprovalResult(ApprovalStatus.CORRUPT, null);
    }

    private Snapshot decodeSnapshot(String encoded, String expectedStatus) {
        if (encoded == null) return null;
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 5 || !expectedStatus.equals(parts[0])
                || !("0".equals(parts[1]) || "1".equals(parts[1]))
                || !("0".equals(parts[3]) || "1".equals(parts[3]))
                || !validRevision(parts[2])
                || !validRevision(parts[4])
                || ("0".equals(parts[1]) && "1".equals(parts[3]))) {
            return null;
        }
        return new Snapshot(
                "1".equals(parts[1]),
                parts[2],
                "1".equals(parts[3]),
                parts[4]);
    }

    private String key(long authTenantId, String productKey) {
        return KEY_PREFIX + authTenantId + ':' + productKey;
    }

    private String legacyKey(long authTenantId) {
        return LEGACY_KEY_PREFIX + authTenantId;
    }

    private boolean validProductKey(String productKey) {
        return ProductSurfaceRolloutFlagCatalog.supportsProduct(productKey);
    }

    private String bit(boolean enabled) {
        return enabled ? "1" : "0";
    }

    private boolean validRevision(String revision) {
        if (!REVISION.matcher(revision == null ? "" : revision).matches()) return false;
        try {
            FeatureRolloutDecisionCache.revisionNumber(revision);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private LoadResult unavailableLoad() {
        return new LoadResult(LoadStatus.UNAVAILABLE, null);
    }

    private ApprovalResult unavailableApproval() {
        return new ApprovalResult(ApprovalStatus.UNAVAILABLE, null);
    }
}
