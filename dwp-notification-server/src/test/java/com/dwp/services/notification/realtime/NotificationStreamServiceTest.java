package com.dwp.services.notification.realtime;

import com.dwp.services.notification.domain.NotificationModels.Summary;
import com.dwp.services.notification.domain.NotificationModels.SyncResponse;
import com.dwp.services.notification.security.NotificationRequestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationStreamServiceTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(1, 9L, Set.of(), Set.of(), false, null);

    @Test
    void changedEventCarriesRefreshAndArrivalIdentitySeparately() {
        String id = "40000000-0000-0000-0000-000000000001";

        Map<String, Object> payload = NotificationStreamService.livePayload(
                "9007199254740993",
                "9007199254740994",
                List.of(id),
                List.of(id));

        assertThat(payload).containsEntry("changeVersion", "9007199254740993")
                .containsEntry("counterVersion", "9007199254740994")
                .containsEntry("changedIds", List.of(id))
                .containsEntry("arrivalIds", List.of(id));
        assertThat(payload.keySet()).containsExactlyInAnyOrder(
                "changeVersion", "counterVersion", "changedIds", "arrivalIds");
    }

    @Test
    void catchUpPagesUntilEveryChangedProjectionHasBeenDelivered() {
        CapturingEmitter emitter = new CapturingEmitter();
        NotificationStreamService service = service(emitter);
        List<String> cursors = new ArrayList<>();
        List<UUID> firstPage = ids(1, 100);
        UUID finalId = id(101);

        service.open(ACTOR, "0", (after, limit) -> {
            cursors.add(after);
            assertThat(limit).isEqualTo(100);
            return "0".equals(after)
                    ? sync("100", firstPage, true)
                    : sync("101", List.of(finalId), false);
        });

        assertThat(cursors).containsExactly("0", "100");
        assertThat(emitter.payloads("notification.changed"))
                .extracting(payload -> ((List<?>) payload.get("changedIds")).size())
                .containsExactly(100, 1);
        assertThat(emitter.payloads("notification.changed"))
                .allSatisfy(payload -> assertThat(payload.get("arrivalIds")).isEqualTo(List.of()));
    }

    @Test
    void aFreshStreamInstanceRecoversDurableChangesMissedDuringRedisAndSseRestart() {
        CapturingEmitter emitter = new CapturingEmitter();
        NotificationStreamService restartedService = service(emitter);
        UUID missedWhileOffline = id(42);

        restartedService.open(ACTOR, "41", (after, limit) -> {
            assertThat(after).isEqualTo("41");
            return sync("42", List.of(missedWhileOffline), false);
        });

        assertThat(emitter.payloads("notification.changed")).singleElement()
                .satisfies(payload -> {
                    assertThat(payload.get("changeVersion")).isEqualTo("42");
                    assertThat(payload.get("changedIds"))
                            .isEqualTo(List.of(missedWhileOffline.toString()));
                    assertThat(payload.get("arrivalIds")).isEqualTo(List.of());
                });
    }

    @Test
    void registersBeforeCatchUpAndFlushesBufferedMaterializedSignalAfterRecovery() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        NotificationStreamService service = service(emitter);
        CountDownLatch catchUpStarted = new CountDownLatch(1);
        CountDownLatch releaseCatchUp = new CountDownLatch(1);
        UUID recoveredId = id(1);
        UUID liveId = id(2);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<SseEmitter> opened = executor.submit(() -> service.open(ACTOR, "0", (after, limit) -> {
                catchUpStarted.countDown();
                await(releaseCatchUp);
                return sync("1", List.of(recoveredId), false);
            }));

            assertThat(catchUpStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.connectionCount()).isEqualTo(1);
            service.dispatch(new NotificationRealtimeEnvelope(
                    1, 9, "2", "2", List.of(liveId), List.of(liveId)));
            releaseCatchUp.countDown();
            opened.get(2, TimeUnit.SECONDS);
        }

        List<Map<String, Object>> changed = emitter.payloads("notification.changed");
        assertThat(changed).hasSize(2);
        assertThat(changed.get(0).get("changedIds")).isEqualTo(List.of(recoveredId.toString()));
        assertThat(changed.get(0).get("arrivalIds")).isEqualTo(List.of());
        assertThat(changed.get(1).get("changedIds")).isEqualTo(List.of(liveId.toString()));
        assertThat(changed.get(1).get("arrivalIds")).isEqualTo(List.of(liveId.toString()));
    }

    @Test
    void dispatchesUserTriageRefreshWithoutTreatingItAsANewArrival() {
        CapturingEmitter emitter = new CapturingEmitter();
        NotificationStreamService service = service(emitter);
        UUID notificationId = id(1);

        service.open(ACTOR, "0", (after, limit) -> sync("0", List.of(), false));
        service.dispatch(new NotificationRealtimeEnvelope(
                1, 9, "1", "1", List.of(notificationId), List.of()));

        assertThat(emitter.payloads("notification.changed")).singleElement()
                .satisfies(payload -> {
                    assertThat(payload.get("changedIds"))
                            .isEqualTo(List.of(notificationId.toString()));
                    assertThat(payload.get("arrivalIds")).isEqualTo(List.of());
                });
    }

    @Test
    void rejectsConnectionsAboveThePerUserQuota() {
        CapturingEmitter emitter = new CapturingEmitter();
        NotificationStreamService service = new NotificationStreamService(
                TimeUnit.MINUTES.toMillis(30),
                new SimpleMeterRegistry(),
                ignored -> emitter,
                4,
                2,
                1,
                10);
        UUID firstClient = UUID.fromString("41000000-0000-0000-0000-000000000001");
        UUID secondClient = UUID.fromString("41000000-0000-0000-0000-000000000002");
        service.open(ACTOR, "0", firstClient, (after, limit) -> sync("0", List.of(), false));

        assertThatThrownBy(() ->
                service.open(ACTOR, "0", secondClient, (after, limit) -> sync("0", List.of(), false)))
                .isInstanceOf(NotificationStreamCapacityException.class);
        assertThat(service.connectionCount()).isEqualTo(1);
    }

    @Test
    void reconnectFromTheSameBrowserInstanceAtomicallySupersedesItsStaleStream() {
        CapturingEmitter firstEmitter = new CapturingEmitter();
        CapturingEmitter secondEmitter = new CapturingEmitter();
        List<CapturingEmitter> emitters = new ArrayList<>(List.of(firstEmitter, secondEmitter));
        NotificationStreamService service = new NotificationStreamService(
                TimeUnit.MINUTES.toMillis(30),
                new SimpleMeterRegistry(),
                ignored -> emitters.removeFirst(),
                1,
                1,
                1,
                10);
        UUID clientId = UUID.fromString("41000000-0000-0000-0000-000000000003");

        service.open(ACTOR, "0", clientId, (after, limit) -> sync("0", List.of(), false));
        service.open(ACTOR, "0", clientId, (after, limit) -> sync("0", List.of(), false));
        service.dispatch(new NotificationRealtimeEnvelope(
                1, 9, "1", "1", List.of(id(1)), List.of(id(1))));

        assertThat(service.connectionCount()).isEqualTo(1);
        assertThat(firstEmitter.payloads("notification.changed")).isEmpty();
        assertThat(secondEmitter.payloads("notification.changed")).singleElement();
    }

    @Test
    void boundsCatchUpBufferAndRequiresAuthoritativeResynchronization() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        NotificationStreamService service = new NotificationStreamService(
                TimeUnit.MINUTES.toMillis(30),
                new SimpleMeterRegistry(),
                ignored -> emitter,
                10,
                10,
                4,
                2);
        CountDownLatch catchUpStarted = new CountDownLatch(1);
        CountDownLatch releaseCatchUp = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<SseEmitter> opened = executor.submit(() -> service.open(ACTOR, "0", (after, limit) -> {
                catchUpStarted.countDown();
                await(releaseCatchUp);
                return sync("1", List.of(id(1)), false);
            }));
            assertThat(catchUpStarted.await(2, TimeUnit.SECONDS)).isTrue();
            for (int version = 2; version <= 4; version++) {
                service.dispatch(new NotificationRealtimeEnvelope(
                        1, 9, Integer.toString(version), Integer.toString(version),
                        List.of(id(version)), List.of(id(version))));
            }
            releaseCatchUp.countDown();
            opened.get(2, TimeUnit.SECONDS);
        }

        assertThat(emitter.payloads("notification.sync-reset"))
                .singleElement()
                .satisfies(payload -> assertThat(payload)
                        .containsEntry("errorCode", "NOTIFICATION_SYNC_RESET_REQUIRED"));
        assertThat(service.connectionCount()).isZero();
    }

    private NotificationStreamService service(CapturingEmitter emitter) {
        return new NotificationStreamService(
                TimeUnit.MINUTES.toMillis(30),
                new SimpleMeterRegistry(),
                ignored -> emitter);
    }

    private static SyncResponse sync(String version, List<UUID> changedIds, boolean hasMore) {
        Summary summary = new Summary(
                false,
                List.of(),
                null,
                0,
                0,
                Map.of(),
                version,
                version,
                Instant.parse("2026-08-21T00:00:00Z"));
        return new SyncResponse(version, version, changedIds, List.of(), hasMore, summary);
    }

    private static List<UUID> ids(int first, int last) {
        List<UUID> result = new ArrayList<>();
        for (int value = first; value <= last; value++) result.add(id(value));
        return List.copyOf(result);
    }

    private static UUID id(int value) {
        return UUID.fromString("40000000-0000-0000-0000-%012d".formatted(value));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", exception);
        }
    }

    private static final class CapturingEmitter extends SseEmitter {
        private final List<CapturedEvent> events = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            String name = "message";
            Map<String, Object> payload = Map.of();
            for (DataWithMediaType item : builder.build()) {
                Object data = item.getData();
                if (data instanceof String text) {
                    for (String line : text.split("\\n")) {
                        if (line.startsWith("event:")) {
                            name = line.substring("event:".length()).trim();
                        }
                    }
                } else if (data instanceof Map<?, ?> map) {
                    payload = stringMap(map);
                }
            }
            events.add(new CapturedEvent(name, payload));
        }

        List<Map<String, Object>> payloads(String name) {
            return events.stream()
                    .filter(event -> event.name().equals(name))
                    .map(CapturedEvent::payload)
                    .toList();
        }

        private static Map<String, Object> stringMap(Map<?, ?> source) {
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            source.forEach((key, value) -> result.put(String.valueOf(key), value));
            return Map.copyOf(result);
        }
    }

    private record CapturedEvent(String name, Map<String, Object> payload) {
    }
}
