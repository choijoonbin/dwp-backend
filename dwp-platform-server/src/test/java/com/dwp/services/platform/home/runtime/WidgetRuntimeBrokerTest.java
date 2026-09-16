package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WidgetRuntimeBrokerTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void returnsBoundFreshResultAndUsesRecipientCache() {
        FakeProvider provider = new FakeProvider();
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");
        provider.response = response(context, request, Duration.ofSeconds(20));
        WidgetRuntimeBroker broker = broker(provider, properties(400));

        List<HomeWidgetProviderContract.WidgetResult> first = broker.read(
                context, revisions(), List.of(request));
        List<HomeWidgetProviderContract.WidgetResult> second = broker.read(
                context, revisions(), List.of(request));

        assertThat(first).extracting(HomeWidgetProviderContract.WidgetResult::state)
                .containsExactly(HomeWidgetProviderContract.State.AVAILABLE);
        assertThat(second).extracting(HomeWidgetProviderContract.WidgetResult::state)
                .containsExactly(HomeWidgetProviderContract.State.AVAILABLE);
        assertThat(provider.calls).hasValue(1);
    }

    @Test
    void timeoutOrFiveHundredMayUseBoundedLastSuccessAsStale() throws Exception {
        FakeProvider provider = new FakeProvider();
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");
        provider.response = response(context, request, Duration.ofMillis(40));
        WidgetRuntimeBroker broker = broker(provider, properties(300));
        broker.read(context, revisions(), List.of(request));
        Thread.sleep(60);
        provider.failure = new WidgetProviderException(
                WidgetProviderException.Kind.UNAVAILABLE,
                "PROVIDER_HTTP_5XX", "failed");

        List<HomeWidgetProviderContract.WidgetResult> results = broker.read(
                context, revisions(), List.of(request));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.state()).isEqualTo(HomeWidgetProviderContract.State.STALE);
            assertThat(result.source().lastSuccessAt()).isNotNull();
            assertThat(result.source().reasonCode()).isEqualTo("PROVIDER_HTTP_5XX");
        });
    }

    @Test
    void forbiddenNeverFallsBackToPreviouslyCachedData() throws Exception {
        FakeProvider provider = new FakeProvider();
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");
        provider.response = response(context, request, Duration.ofMillis(40));
        WidgetRuntimeBroker broker = broker(provider, properties(300));
        broker.read(context, revisions(), List.of(request));
        Thread.sleep(60);
        provider.failure = new WidgetProviderException(
                WidgetProviderException.Kind.FORBIDDEN,
                "AUTHORIZATION_PROVIDER_FORBIDDEN", "denied");

        HomeWidgetProviderContract.WidgetResult result = broker.read(
                context, revisions(), List.of(request)).getFirst();

        assertThat(result.state()).isEqualTo(HomeWidgetProviderContract.State.FORBIDDEN);
        assertThat(result.payload()).isEmpty();
    }

    @Test
    void malformedRecipientPayloadFailsClosedWithoutStaleFallback() throws Exception {
        FakeProvider provider = new FakeProvider();
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");
        provider.response = response(context, request, Duration.ofMillis(40));
        WidgetRuntimeBroker broker = broker(provider, properties(300));
        broker.read(context, revisions(), List.of(request));
        Thread.sleep(60);
        provider.response = new HomeWidgetProviderContract.BatchResponse(
                1, context.tenantId(), context.userId() + 1,
                context.authorityDecisionRevision(),
                response(context, request, Duration.ofSeconds(20)).results());

        HomeWidgetProviderContract.WidgetResult result = broker.read(
                context, revisions(), List.of(request)).getFirst();

        assertThat(result.state()).isEqualTo(HomeWidgetProviderContract.State.UNAVAILABLE);
        assertThat(result.source().reasonCode()).isEqualTo("PROVIDER_CONTRACT_INVALID");
    }

    @Test
    void lateProviderCompletionCannotPopulateCache() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.delayMillis = 120;
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");
        provider.response = response(context, request, Duration.ofSeconds(20));
        WidgetRuntimeBroker broker = broker(provider, properties(50));

        HomeWidgetProviderContract.WidgetResult timedOut = broker.read(
                context, revisions(), List.of(request)).getFirst();
        assertThat(timedOut.state()).isEqualTo(HomeWidgetProviderContract.State.UNAVAILABLE);
        Thread.sleep(150);
        provider.delayMillis = 0;
        provider.failure = new WidgetProviderException(
                WidgetProviderException.Kind.UNAVAILABLE,
                "PROVIDER_HTTP_5XX", "failed");

        HomeWidgetProviderContract.WidgetResult second = broker.read(
                context, revisions(), List.of(request)).getFirst();
        assertThat(second.state()).isEqualTo(HomeWidgetProviderContract.State.UNAVAILABLE);
        assertThat(second.source().reasonCode()).isEqualTo("PROVIDER_HTTP_5XX");
        assertThat(provider.calls).hasValue(2);
    }

    @Test
    void providerDeadlineIsCappedByTheAuthorityLease() {
        FakeProvider provider = new FakeProvider();
        HomeRuntimeContext context = TestFixtures.context();
        HomeRuntimeContext shortLease = TestFixtures.withAuthorityRevalidateAt(
                context, OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2));
        WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");
        provider.response = response(shortLease, request, Duration.ofSeconds(20));
        WidgetRuntimeBroker broker = broker(provider, properties(10_000));

        broker.read(shortLease, revisions(), List.of(request));

        assertThat(provider.deadline.get()).isNotNull()
                .isBeforeOrEqualTo(shortLease.authorityRevalidateAt());
    }

    @Test
    void authorityExpiryDuringProviderCallPreventsResponseAndCacheDisclosure() {
        FakeProvider provider = new FakeProvider();
        HomeRuntimeContext context = TestFixtures.withAuthorityRevalidateAt(
                TestFixtures.context(),
                OffsetDateTime.now(ZoneOffset.UTC).plus(Duration.ofMillis(300)));
        WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");
        provider.delayMillis = 500;
        provider.response = response(context, request, Duration.ofSeconds(20));
        WidgetRuntimeBroker broker = broker(provider, properties(500));

        assertThatThrownBy(() -> broker.read(context, revisions(), List.of(request)))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(provider.deadline.get()).isBeforeOrEqualTo(context.authorityRevalidateAt());
    }

    @Test
    void expiredAuthorityCannotReuseARecipientCacheEntry() {
        FakeProvider provider = new FakeProvider();
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");
        provider.response = response(context, request, Duration.ofSeconds(20));
        WidgetRuntimeBroker broker = broker(provider, properties(400));
        broker.read(context, revisions(), List.of(request));
        HomeRuntimeContext expired = TestFixtures.withAuthorityRevalidateAt(
                context, OffsetDateTime.now(ZoneOffset.UTC).minusNanos(1));

        assertThatThrownBy(() -> broker.read(expired, revisions(), List.of(request)))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(provider.calls).hasValue(1);
    }

    private WidgetRuntimeBroker broker(FakeProvider provider, HomeRuntimeProperties properties) {
        RecipientBoundWidgetCache cache = new RecipientBoundWidgetCache(properties);
        return new WidgetRuntimeBroker(
                List.of(provider), cache,
                new ProviderResultValidator(objectMapper, properties), properties,
                new HomeRuntimeTelemetry(new SimpleMeterRegistry()),
                new HomeCanonicalJson(objectMapper), executor);
    }

    private HomeWidgetProviderContract.BatchResponse response(
            HomeRuntimeContext context,
            WidgetProviderPort.Request request,
            Duration lifetime) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeWidgetProviderContract.BatchResponse(
                1, context.tenantId(), context.userId(), context.authorityDecisionRevision(),
                List.of(new HomeWidgetProviderContract.WidgetResult(
                        request.instanceId(), request.definition().definitionKey(),
                        request.definition().manifestHash(),
                        request.definition().rendererBindingRevision(),
                        HomeWidgetProviderContract.State.AVAILABLE,
                        new HomeWidgetProviderContract.SourceState(
                                "PLATFORM_HOME", now, now.plus(lifetime), now,
                                null, false, "result-1"),
                        Map.of("count", 1), List.of(), List.of())));
    }

    private WidgetRuntimeBroker.Revisions revisions() {
        return new WidgetRuntimeBroker.Revisions(
                "CLASSIC", "DESKTOP_STANDARD", "view-1", "catalog-1", "policy-1", "safety-1");
    }

    private HomeRuntimeProperties properties(long providerTimeoutMillis) {
        return new HomeRuntimeProperties(
                true, false, Duration.ofMillis(900), Duration.ofMillis(providerTimeoutMillis),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }

    private static final class FakeProvider implements WidgetProviderPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<OffsetDateTime> deadline =
                new java.util.concurrent.atomic.AtomicReference<>();
        private volatile HomeWidgetProviderContract.BatchResponse response;
        private volatile WidgetProviderException failure;
        private volatile long delayMillis;

        @Override
        public String providerKey() {
            return "platform";
        }

        @Override
        public HomeWidgetProviderContract.BatchResponse readBatch(
                HomeRuntimeContext context,
                List<Request> requests,
                OffsetDateTime deadline) {
            calls.incrementAndGet();
            this.deadline.set(deadline);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) throw failure;
            return response;
        }
    }
}
