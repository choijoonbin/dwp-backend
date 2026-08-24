package com.dwp.gateway.productsurface;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    @Test
    void restartAndProviderOutageRecoverTheLastDurableEnforcementState() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch firstProcess = latch(redis);

        ProductSurfaceRolloutSafetyLatch.ApprovalResult created = firstProcess.approve(
                        71L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                true, 10, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                true, 11, true))
                .block();

        assertThat(created.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.CREATED);
        assertThat(created.snapshot().capabilityEnforcement()).isTrue();
        assertThat(redis.calls()).isEqualTo(1);
        assertThat(redis.lastArguments()).containsExactly(
                "1", "rev-00000000000000000010",
                "1", "rev-00000000000000000011");

        // A new latch instance models a Gateway restart. No Provider decision is approved
        // before loading, which models the Provider-outage recovery path.
        RedisProductSurfaceRolloutSafetyLatch restartedProcess = latch(redis);
        ProductSurfaceRolloutSafetyLatch.LoadResult recovered =
                restartedProcess.load(71L).block();

        assertThat(recovered.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.FOUND);
        assertThat(recovered.snapshot()).isEqualTo(created.snapshot());
        assertThat(recovered.snapshot().contextShadow()).isTrue();
        assertThat(recovered.snapshot().capabilityEnforcement()).isTrue();
        assertThat(redis.lastKey()).isEqualTo(
                "dwp:gateway:product-surface:se-latch:v1:71");
        assertThat(redis.approveScript()).contains("redis.call('PERSIST', KEYS[1])");
        assertThat(redis.approveScript())
                .doesNotContain("redis.call('EXPIRE'", "redis.call('PEXPIRE'");
    }

    @Test
    void outOfOrderProviderResultIsIgnoredAndReturnsTheStoredSnapshot() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);
        ProductSurfaceRolloutSafetyLatch.ApprovalResult current = latch.approve(
                        9L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                true, 20, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                true, 30, true))
                .block();

        ProductSurfaceRolloutSafetyLatch.ApprovalResult stale = latch.approve(
                        9L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                true, 21, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                false, 29, true))
                .block();

        assertThat(stale.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.OUT_OF_ORDER);
        assertThat(stale.snapshot()).isEqualTo(current.snapshot());
        assertThat(stale.snapshot().capabilityEnforcement()).isTrue();
        assertThat(latch.load(9L).block().snapshot()).isEqualTo(current.snapshot());
    }

    @Test
    void monotonicUpdateAndDuplicateReturnTheEffectiveStoredState() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);
        latch.approve(
                        5L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                false, 1, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                false, 1, true))
                .block();

        ProductSurfaceRolloutSafetyLatch.ApprovalResult updated = latch.approve(
                        5L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                true, 2, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                true, 3, true))
                .block();
        ProductSurfaceRolloutSafetyLatch.ApprovalResult unchanged = latch.approve(
                        5L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                true, 2, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                true, 3, true))
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
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                false, 4, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                false, 4, true))
                .block();

        ProductSurfaceRolloutSafetyLatch.ApprovalResult conflict = latch.approve(
                        8L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                true, 4, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                false, 5, true))
                .block();

        assertThat(conflict.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.REVISION_CONFLICT);
        assertThat(conflict.snapshot()).isNull();
        assertThat(latch.load(8L).block().snapshot().contextShadow()).isFalse();
    }

    @Test
    void nonAuthoritativeOrInvalidProviderDecisionsNeverReachRedis() {
        RedisHarness redis = new RedisHarness();
        RedisProductSurfaceRolloutSafetyLatch latch = latch(redis);

        ProductSurfaceRolloutSafetyLatch.ApprovalResult unavailable = latch.approve(
                        12L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                true, 1, false),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                true, 1, true))
                .block();
        ProductSurfaceRolloutSafetyLatch.ApprovalResult invalidPair = latch.approve(
                        12L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                false, 2, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                true, 2, true))
                .block();

        assertThat(unavailable.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.INVALID_DECISION);
        assertThat(invalidPair.status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.INVALID_DECISION);
        assertThat(redis.calls()).isZero();
    }

    @Test
    void missingCorruptAndUnavailableRedisStatesRemainDistinguishable() {
        RedisHarness missing = new RedisHarness();
        assertThat(latch(missing).load(14L).block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.MISSING);

        RedisHarness corrupt = new RedisHarness();
        corrupt.corrupt();
        assertThat(latch(corrupt).load(14L).block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.CORRUPT);

        RedisHarness unavailable = new RedisHarness();
        unavailable.fail();
        assertThat(latch(unavailable).load(14L).block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.UNAVAILABLE);
        assertThat(latch(unavailable).approve(
                        14L,
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                true, 1, true),
                        decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                                true, 1, true))
                .block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.ApprovalStatus.UNAVAILABLE);
    }

    @Test
    void hungRedisIsBoundedByTheLatchOperationTimeout() {
        RedisHarness hung = new RedisHarness();
        hung.hang();
        RedisProductSurfaceRolloutSafetyLatch latch =
                new RedisProductSurfaceRolloutSafetyLatch(
                        hung.template(), Duration.ofMillis(5));

        assertThat(latch.load(19L).block().status())
                .isEqualTo(ProductSurfaceRolloutSafetyLatch.LoadStatus.UNAVAILABLE);
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

        private final ReactiveStringRedisTemplate template =
                mock(ReactiveStringRedisTemplate.class);
        private final AtomicReference<Stored> stored = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> lastKey = new AtomicReference<>();
        private final AtomicReference<List<String>> lastArguments = new AtomicReference<>();
        private final AtomicReference<String> approveScript = new AtomicReference<>();
        private Mode mode = Mode.AVAILABLE;

        RedisHarness() {
            when(template.execute(any(), anyList(), anyList()))
                    .thenAnswer(invocation -> {
                        calls.incrementAndGet();
                        RedisScript<?> script = invocation.getArgument(0);
                        List<?> keys = invocation.getArgument(1);
                        List<?> arguments = invocation.getArgument(2);
                        lastKey.set(String.valueOf(keys.getFirst()));
                        lastArguments.set(arguments.stream()
                                .map(String::valueOf)
                                .toList());
                        if (mode == Mode.FAIL) {
                            return Flux.error(new IllegalStateException("Redis unavailable"));
                        }
                        if (mode == Mode.HANG) return Flux.never();
                        if (arguments.isEmpty()) return Flux.just(loadResult());
                        approveScript.set(script.getScriptAsString());
                        return Flux.just(approve(arguments));
                    });
        }

        ReactiveStringRedisTemplate template() {
            return template;
        }

        int calls() {
            return calls.get();
        }

        String lastKey() {
            return lastKey.get();
        }

        String approveScript() {
            return approveScript.get();
        }

        List<String> lastArguments() {
            return lastArguments.get();
        }

        void corrupt() {
            mode = Mode.CORRUPT;
        }

        void fail() {
            mode = Mode.FAIL;
        }

        void hang() {
            mode = Mode.HANG;
        }

        private String loadResult() {
            if (mode == Mode.CORRUPT) return "CORRUPT";
            Stored current = stored.get();
            return current == null ? "MISSING" : current.encoded("FOUND");
        }

        private String approve(List<?> arguments) {
            if (mode == Mode.CORRUPT) return "CORRUPT";
            Stored incoming = new Stored(
                    String.valueOf(arguments.get(0)),
                    String.valueOf(arguments.get(1)),
                    String.valueOf(arguments.get(2)),
                    String.valueOf(arguments.get(3)));
            Stored current = stored.get();
            if (current == null) {
                stored.set(incoming);
                return incoming.encoded("CREATED");
            }
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
            stored.set(incoming);
            return incoming.encoded("UPDATED");
        }

        private enum Mode {
            AVAILABLE,
            CORRUPT,
            FAIL,
            HANG
        }

        private record Stored(
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
