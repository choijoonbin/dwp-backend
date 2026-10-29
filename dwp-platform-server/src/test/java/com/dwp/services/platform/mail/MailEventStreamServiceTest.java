package com.dwp.services.platform.mail;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailEventStreamServiceTest {

    @Test
    void trustedAuthorityExpiryBoundsEmitterAndPreventsPostExpiryEvents() {
        MailEventStreamRepository repository = mock(MailEventStreamRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(
                Instant.parse("2026-10-28T00:00:00Z"));
        MailEventStreamService service = new MailEventStreamService(
                repository, new MailMetrics(registry),
                10, 10, 5_000, 1_000, clock);
        OffsetDateTime authorityExpiry = OffsetDateTime.ofInstant(
                clock.instant().plusMillis(100), ZoneOffset.UTC);
        MailEventStreamRepository.EventEnvelope event =
                new MailEventStreamRepository.EventEnvelope(
                        11, "mail.message.received", UUID.randomUUID(),
                        OffsetDateTime.now(clock));
        when(repository.catchUp(7, 91, 0, 10)).thenAnswer(invocation -> {
            clock.advance(Duration.ofMillis(101));
            return List.of(event);
        });

        SseEmitter emitter = service.connect(7, 91, 0, authorityExpiry);
        service.poll();

        assertThat(emitter.getTimeout()).isEqualTo(100L);
        verify(repository, times(1)).catchUp(7, 91, 0, 10);
        io.micrometer.core.instrument.Counter expired = registry
                .find("dwp.mail.events.stream")
                .tag("outcome", "authority_expired").counter();
        assertThat(expired).isNotNull();
        assertThat(expired.count()).isEqualTo(1.0);
        assertThat(registry.find("dwp.mail.events.stream")
                .tag("outcome", "event").counter()).isNull();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
