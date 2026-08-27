package com.dwp.gateway.productsurface;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisProductSurfaceRolloutSafetyLatchTest {

    private static final Instant EVALUATED_AT =
            Instant.parse("2026-08-24T00:00:00Z");
    private static final Set<String> PRODUCT_KEYS = Set.of(
            "approvals", "calendar", "communications", "dwaion", "hcm", "mail",
            "meetings", "messaging", "notifications", "services", "spaces", "workplace");

    @Test
    void restartAndProviderOutageRecoverTheLastDurableProductState() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch firstProcess = latch(redis);

        ProductSurfaceRolloutSafetyLatch.ApprovalResult created = firstProcess.approve(
                        71L,
                        "approvals",
                        shadow(true, 10, true),
                        productEnforcement("approvals", true, 11, true))
                .block();

        assertThat(created.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.CREATED);
        assertThat(created.snapshot().capabilityEnforcement()).isTrue();
        assertThat(redis.calls()).isEqualTo(1);
        assertThat(redis.lastArguments()).containsExactly(
                "approvals",
                "1", "rev-00000000000000000010",
                "1", "rev-00000000000000000011");

        // A new latch instance models a Gateway restart. Loading before any new approval models
        // recovery while Provider evaluation is unavailable but durable Redis is healthy.
        RedisProductSurfaceRolloutSafetyLatch restartedProcess = latch(redis);
        ProductSurfaceRolloutSafetyLatch.LoadResult recovered =
                restartedProcess.load(71L, "approvals").block();

        assertThat(recovered.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.FOUND);
        assertThat(recovered.snapshot()).isEqualTo(created.snapshot());
        assertThat(redis.lastKeys()).containsExactly(
                "dwp:gateway:product-surface:se-latch:v2:71:approvals");
        assertThat(redis.approveScript())
                .contains("'schema', '2'", "'productKey', ARGV[1]",
                        "redis.call('PERSIST', KEYS[1])")
                .doesNotContain("redis.call('EXPIRE'", "redis.call('PEXPIRE'");
    }

    @Test
    void tenantProductsHaveIndependentDurableEnforcementState() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);

        latch.approve(
                        33L,
                        "approvals",
                        shadow(true, 10, true),
                        productEnforcement("approvals", true, 11, true))
                .block();
        latch.approve(
                        33L,
                        "hcm",
                        shadow(true, 10, true),
                        productEnforcement("hcm", false, 12, true))
                .block();

        ProductSurfaceRolloutSafetyLatch.Snapshot approvals =
                latch.load(33L, "approvals").block().snapshot();
        ProductSurfaceRolloutSafetyLatch.Snapshot hcm =
                latch.load(33L, "hcm").block().snapshot();

        assertThat(approvals.contextShadow()).isTrue();
        assertThat(approvals.capabilityEnforcement()).isTrue();
        assertThat(hcm.contextShadow()).isTrue();
        assertThat(hcm.capabilityEnforcement()).isFalse();
        assertThat(redis.storedKeys()).containsExactlyInAnyOrder(
                "dwp:gateway:product-surface:se-latch:v2:33:approvals",
                "dwp:gateway:product-surface:se-latch:v2:33:hcm");
    }

    @Test
    void legacyTenantLatchRequiresMigrationAndIsNeverDecodedAsProductState() {
        RedisHarness redis = new RedisHarness();
        redis.legacy(44L);
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);

        ProductSurfaceRolloutSafetyLatch.LoadResult legacyOnly =
                latch.load(44L, "communications").block();

        assertThat(legacyOnly.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.MIGRATION_REQUIRED);
        assertThat(legacyOnly.snapshot()).isNull();
        assertThat(redis.legacyScript()).contains(
                "redis.call('TYPE', KEYS[1]).ok == 'none'",
                "return 'MIGRATION_REQUIRED'");

        latch.approve(
                        44L,
                        "communications",
                        shadow(true, 20, true),
                        productEnforcement("communications", true, 21, true))
                .block();

        assertThat(latch.load(44L, "communications").block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.FOUND);
        assertThat(latch.load(44L, "services").block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.MIGRATION_REQUIRED);
    }

    @Test
    void missingV2AndLegacyKeysReturnsMissing() {
        RedisHarness redis = new RedisHarness();

        ProductSurfaceRolloutSafetyLatch.LoadResult missing =
                latch(redis).load(14L, "calendar").block();

        assertThat(missing.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.MISSING);
        assertThat(missing.snapshot()).isNull();
    }

    @Test
    void aConcurrentV2ApprovalWinsOverTheLegacyMigrationProbe() {
        RedisHarness redis = new RedisHarness();
        redis.legacy(45L);
        redis.createV2DuringNextLegacyProbe(
                45L, "communications", true, 30, true, 31);

        ProductSurfaceRolloutSafetyLatch.LoadResult result =
                latch(redis).load(45L, "communications").block();

        assertThat(result.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.FOUND);
        assertThat(result.snapshot().contextShadow()).isTrue();
        assertThat(result.snapshot().capabilityEnforcement()).isTrue();
        assertThat(redis.calls()).isEqualTo(3);
    }

    @Test
    void outOfOrderProductResultIsIgnoredAndReturnsTheStoredSnapshot() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);
        ProductSurfaceRolloutSafetyLatch.ApprovalResult current = latch.approve(
                        9L,
                        "services",
                        shadow(true, 20, true),
                        productEnforcement("services", true, 30, true))
                .block();

        ProductSurfaceRolloutSafetyLatch.ApprovalResult stale = latch.approve(
                        9L,
                        "services",
                        shadow(true, 21, true),
                        productEnforcement("services", false, 29, true))
                .block();

        assertThat(stale.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.OUT_OF_ORDER);
        assertThat(stale.snapshot()).isEqualTo(current.snapshot());
        assertThat(stale.snapshot().capabilityEnforcement()).isTrue();
        assertThat(latch.load(9L, "services").block().snapshot())
                .isEqualTo(current.snapshot());
    }

    @Test
    void monotonicUpdateAndDuplicateReturnTheEffectiveStoredProductState() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);
        latch.approve(
                        5L,
                        "spaces",
                        shadow(false, 1, true),
                        productEnforcement("spaces", false, 1, true))
                .block();

        ProductSurfaceRolloutSafetyLatch.ApprovalResult updated = latch.approve(
                        5L,
                        "spaces",
                        shadow(true, 2, true),
                        productEnforcement("spaces", true, 3, true))
                .block();
        ProductSurfaceRolloutSafetyLatch.ApprovalResult unchanged = latch.approve(
                        5L,
                        "spaces",
                        shadow(true, 2, true),
                        productEnforcement("spaces", true, 3, true))
                .block();

        assertThat(updated.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.UPDATED);
        assertThat(updated.snapshot().capabilityEnforcement()).isTrue();
        assertThat(unchanged.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.UNCHANGED);
        assertThat(unchanged.snapshot()).isEqualTo(updated.snapshot());
    }

    @Test
    void sameRevisionWithDifferentValueIsAConflictAndNeverOverwrites() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);
        latch.approve(
                        8L,
                        "notifications",
                        shadow(false, 4, true),
                        productEnforcement("notifications", false, 4, true))
                .block();

        ProductSurfaceRolloutSafetyLatch.ApprovalResult conflict = latch.approve(
                        8L,
                        "notifications",
                        shadow(true, 4, true),
                        productEnforcement("notifications", false, 5, true))
                .block();

        assertThat(conflict.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.REVISION_CONFLICT);
        assertThat(conflict.snapshot()).isNull();
        assertThat(latch.load(8L, "notifications").block().snapshot().contextShadow())
                .isFalse();
    }

    @Test
    void exactProductAllowlistAndProductEnforcementFlagAreRequired() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);

        for (String productKey : PRODUCT_KEYS) {
            assertThat(latch.approve(
                                    21L,
                                    productKey,
                                    shadow(true, 1, true),
                                    productEnforcement(productKey, false, 1, true))
                            .block().status())
                    .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.CREATED);
        }
        int callsAfterAllowlist = redis.calls();

        ProductSurfaceRolloutSafetyLatch.ApprovalResult unknownProduct = latch.approve(
                        21L,
                        "unknown",
                        shadow(true, 2, true),
                        productEnforcement("unknown", true, 2, true))
                .block();
        ProductSurfaceRolloutSafetyLatch.ApprovalResult sharedLegacyFlag = latch.approve(
                        21L,
                        "approvals",
                        shadow(true, 2, true),
                        decision(
                                "access.product-surfaces.capability-enforcement.v1",
                                true,
                                2,
                                true))
                .block();
        ProductSurfaceRolloutSafetyLatch.ApprovalResult anotherProductFlag = latch.approve(
                        21L,
                        "approvals",
                        shadow(true, 2, true),
                        productEnforcement("hcm", true, 2, true))
                .block();

        assertThat(unknownProduct.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.INVALID_DECISION);
        assertThat(sharedLegacyFlag.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.INVALID_DECISION);
        assertThat(anotherProductFlag.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.INVALID_DECISION);
        assertThat(latch.load(21L, "calendar ").block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.CORRUPT);
        assertThat(redis.calls()).isEqualTo(callsAfterAllowlist);
    }

    @Test
    void nonAuthoritativeOrInvalidDecisionPairNeverReachesRedis() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);

        ProductSurfaceRolloutSafetyLatch.ApprovalResult unavailable = latch.approve(
                        12L,
                        "mail",
                        shadow(true, 1, false),
                        productEnforcement("mail", true, 1, true))
                .block();
        ProductSurfaceRolloutSafetyLatch.ApprovalResult invalidPair = latch.approve(
                        12L,
                        "mail",
                        shadow(false, 2, true),
                        productEnforcement("mail", true, 2, true))
                .block();

        assertThat(unavailable.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.INVALID_DECISION);
        assertThat(invalidPair.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.INVALID_DECISION);
        assertThat(redis.calls()).isZero();
    }

    @Test
    void schemaProductTtlAndExactHashShapeAreValidatedFailClosed() {
        RedisHarness redis = new RedisHarness();
        redis.corruptV2(18L, "hcm");
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);

        assertThat(latch.load(18L, "hcm").block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.CORRUPT);
        assertThat(latch.approve(
                                18L,
                                "hcm",
                                shadow(true, 5, true),
                                productEnforcement("hcm", true, 6, true))
                        .block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.CORRUPT);
        assertThat(redis.loadScript()).contains(
                "redis.call('HLEN', KEYS[1]) ~= 6",
                "values[1] ~= '2'",
                "values[2] ~= ARGV[1]",
                "redis.call('PTTL', KEYS[1]) ~= -1");
        assertThat(redis.approveScript()).contains(
                "redis.call('HLEN', KEYS[1]) ~= 6",
                "current[1] ~= '2'",
                "current[2] ~= ARGV[1]",
                "redis.call('PTTL', KEYS[1]) ~= -1");
    }

    @Test
    void unavailableEmptyAndMalformedRedisResponsesRemainFailClosed() {
        RedisHarness unavailable = new RedisHarness();
        unavailable.fail();
        assertThat(latch(unavailable).load(14L, "dwaion").block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.UNAVAILABLE);
        assertThat(latch(unavailable).approve(
                                14L,
                                "dwaion",
                                shadow(true, 1, true),
                                productEnforcement("dwaion", true, 1, true))
                        .block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.UNAVAILABLE);

        RedisHarness empty = new RedisHarness();
        empty.empty();
        assertThat(latch(empty).load(14L, "dwaion").block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.UNAVAILABLE);

        RedisHarness malformed = new RedisHarness();
        malformed.malformed();
        assertThat(latch(malformed).load(14L, "dwaion").block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.CORRUPT);
        assertThat(latch(malformed).approve(
                                14L,
                                "dwaion",
                                shadow(true, 1, true),
                                productEnforcement("dwaion", true, 1, true))
                        .block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.CORRUPT);
    }

    @Test
    void hungRedisLoadAndApprovalAreBoundedByTheOperationTimeout() {
        RedisHarness hung = new RedisHarness();
        hung.hang();
        RedisProductSurfaceRolloutSafetyLatch latch =
                new RedisProductSurfaceRolloutSafetyLatch(
                        hung.template(), Duration.ofMillis(5));

        assertThat(latch.load(19L, "workplace").block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.UNAVAILABLE);
        assertThat(latch.approve(
                                19L,
                                "workplace",
                                shadow(true, 1, true),
                                productEnforcement("workplace", true, 1, true))
                        .block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.UNAVAILABLE);
    }

    @Test
    void rejectsAnUnboundedLatchTimeout() {
        RedisHarness redis = new RedisHarness();

        assertThatThrownBy(() -> new RedisProductSurfaceRolloutSafetyLatch(
                redis.template(), Duration.ofSeconds(6)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RedisProductSurfaceRolloutSafetyLatch latch(RedisHarness redis) {
        return new RedisProductSurfaceRolloutSafetyLatch(
                redis.template(), Duration.ofSeconds(1));
    }

    private FeatureRolloutDecisionCache.FlagDecision shadow(
            boolean enabled,
            long revision,
            boolean authoritative) {
        return decision(
                FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                enabled,
                revision,
                authoritative);
    }

    private FeatureRolloutDecisionCache.FlagDecision productEnforcement(
            String productKey,
            boolean enabled,
            long revision,
            boolean authoritative) {
        return decision(
                "access.product-surfaces.capability-enforcement." + productKey + ".v1",
                enabled,
                revision,
                authoritative);
    }

    private FeatureRolloutDecisionCache.FlagDecision decision(
            String flagKey,
            boolean enabled,
            long revision,
            boolean authoritative) {
        return new FeatureRolloutDecisionCache.FlagDecision(
                flagKey,
                enabled,
                enabled ? "ROLLOUT_MATCH" : "TARGET_MISS",
                String.format(Locale.ROOT, "rev-%020d", revision),
                enabled ? "full" : "baseline",
                EVALUATED_AT,
                authoritative);
    }

    private static final class RedisHarness {

        private static final String V2_PREFIX =
                "dwp:gateway:product-surface:se-latch:v2:";
        private static final String V1_PREFIX =
                "dwp:gateway:product-surface:se-latch:v1:";

        private final ReactiveStringRedisTemplate template =
                mock(ReactiveStringRedisTemplate.class);
        private final Map<String, Stored> stored = new HashMap<>();
        private final Set<String> legacyKeys = new HashSet<>();
        private final Set<String> corruptKeys = new HashSet<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<List<String>> lastKeys = new AtomicReference<>();
        private final AtomicReference<List<String>> lastArguments = new AtomicReference<>();
        private final AtomicReference<String> loadScript = new AtomicReference<>();
        private final AtomicReference<String> legacyScript = new AtomicReference<>();
        private final AtomicReference<String> approveScript = new AtomicReference<>();
        private final AtomicReference<Runnable> duringLegacyProbe = new AtomicReference<>();
        private Mode mode = Mode.AVAILABLE;

        RedisHarness() {
            when(template.execute(any(), anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        calls.incrementAndGet();
                        RedisScript<?> script = invocation.getArgument(0);
                        List<?> rawKeys = invocation.getArgument(1);
                        List<?> rawArguments = invocation.getArgument(2);
                        List<String> keys = rawKeys.stream().map(String::valueOf).toList();
                        List<String> arguments = rawArguments.stream()
                                .map(String::valueOf)
                                .toList();
                        lastKeys.set(keys);
                        lastArguments.set(arguments);
                        if (mode == Mode.FAIL) {
                            return Flux.error(new IllegalStateException("Redis unavailable"));
                        }
                        if (mode == Mode.HANG) return Flux.never();
                        if (mode == Mode.EMPTY) return Flux.empty();
                        if (arguments.isEmpty()) {
                            legacyScript.set(script.getScriptAsString());
                            return Flux.just(legacyResult(keys));
                        }
                        if (arguments.size() == 1) {
                            loadScript.set(script.getScriptAsString());
                            return Flux.just(loadResult(keys, arguments));
                        }
                        approveScript.set(script.getScriptAsString());
                        return Flux.just(approve(keys, arguments));
                    });
        }

        ReactiveStringRedisTemplate template() {
            return template;
        }

        int calls() {
            return calls.get();
        }

        List<String> lastKeys() {
            return lastKeys.get();
        }

        String loadScript() {
            return loadScript.get();
        }

        String approveScript() {
            return approveScript.get();
        }

        String legacyScript() {
            return legacyScript.get();
        }

        List<String> lastArguments() {
            return lastArguments.get();
        }

        Set<String> storedKeys() {
            return Set.copyOf(stored.keySet());
        }

        void legacy(long authTenantId) {
            legacyKeys.add(V1_PREFIX + authTenantId);
        }

        void createV2DuringNextLegacyProbe(
                long authTenantId,
                String productKey,
                boolean shadow,
                long shadowRevision,
                boolean enforcement,
                long enforcementRevision) {
            duringLegacyProbe.set(() -> stored.put(
                    V2_PREFIX + authTenantId + ':' + productKey,
                    new Stored(
                            productKey,
                            shadow ? "1" : "0",
                            String.format(Locale.ROOT, "rev-%020d", shadowRevision),
                            enforcement ? "1" : "0",
                            String.format(Locale.ROOT, "rev-%020d", enforcementRevision))));
        }

        void corruptV2(long authTenantId, String productKey) {
            corruptKeys.add(V2_PREFIX + authTenantId + ':' + productKey);
        }

        void fail() {
            mode = Mode.FAIL;
        }

        void hang() {
            mode = Mode.HANG;
        }

        void empty() {
            mode = Mode.EMPTY;
        }

        void malformed() {
            mode = Mode.MALFORMED;
        }

        private String loadResult(List<String> keys, List<String> arguments) {
            if (mode == Mode.MALFORMED) return "FOUND|1|invalid|1|invalid";
            String v2Key = keys.get(0);
            if (corruptKeys.contains(v2Key)) return "CORRUPT";
            Stored current = stored.get(v2Key);
            if (current == null) return "MISSING";
            if (!current.productKey().equals(arguments.get(0))) return "CORRUPT";
            return current.encoded("FOUND");
        }

        private String legacyResult(List<String> keys) {
            String result = legacyKeys.contains(keys.get(0))
                    ? "MIGRATION_REQUIRED"
                    : "MISSING";
            Runnable concurrentApproval = duringLegacyProbe.getAndSet(null);
            if (concurrentApproval != null) concurrentApproval.run();
            return result;
        }

        private String approve(List<String> keys, List<String> arguments) {
            if (mode == Mode.MALFORMED) return "UPDATED|1|invalid|1|invalid";
            String key = keys.get(0);
            if (corruptKeys.contains(key)) return "CORRUPT";
            Stored incoming = new Stored(
                    arguments.get(0),
                    arguments.get(1),
                    arguments.get(2),
                    arguments.get(3),
                    arguments.get(4));
            Stored current = stored.get(key);
            if (current == null) {
                stored.put(key, incoming);
                return incoming.encoded("CREATED");
            }
            if (!current.productKey().equals(incoming.productKey())) return "CORRUPT";
            if (incoming.shadowRevision().compareTo(current.shadowRevision()) < 0
                    || incoming.enforcementRevision()
                    .compareTo(current.enforcementRevision()) < 0) {
                return current.encoded("OUT_OF_ORDER");
            }
            if ((incoming.shadowRevision().equals(current.shadowRevision())
                    && !incoming.shadow().equals(current.shadow()))
                    || (incoming.enforcementRevision().equals(current.enforcementRevision())
                    && !incoming.enforcement().equals(current.enforcement()))) {
                return "REVISION_CONFLICT";
            }
            if (incoming.shadowRevision().equals(current.shadowRevision())
                    && incoming.enforcementRevision().equals(current.enforcementRevision())) {
                return current.encoded("UNCHANGED");
            }
            stored.put(key, incoming);
            return incoming.encoded("UPDATED");
        }

        private enum Mode {
            AVAILABLE,
            EMPTY,
            FAIL,
            HANG,
            MALFORMED
        }

        private record Stored(
                String productKey,
                String shadow,
                String shadowRevision,
                String enforcement,
                String enforcementRevision) {

            String encoded(String status) {
                return status + '|' + shadow + '|' + shadowRevision
                        + '|' + enforcement + '|' + enforcementRevision;
            }
        }
    }
}
