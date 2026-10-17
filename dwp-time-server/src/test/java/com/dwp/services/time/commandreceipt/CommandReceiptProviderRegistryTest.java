package com.dwp.services.time.commandreceipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.services.time.api.HrisTimeApiException;
import com.dwp.services.time.api.HrisTimeProblemCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CommandReceiptProviderRegistryTest {

    private static final UUID RECEIPT =
            UUID.fromString("17900000-0000-4000-8000-000000000001");
    private static final UUID CORRELATION =
            UUID.fromString("17900000-0000-4000-8000-000000000002");

    @Test
    void asksEveryProviderBeforeTheUniqueOwnerReadsItsNativeReceipt() {
        List<CommandReceiptAuditEvent> audits = new ArrayList<>();
        StubProvider owner = new StubProvider("timecard", ignored -> true, ignored -> "native");
        StubProvider other = new StubProvider("close", ignored -> false, ignored -> "wrong");

        try (CommandReceiptProviderRegistry registry = new CommandReceiptProviderRegistry(
                List.of(owner, other), audits::add, Duration.ofSeconds(1))) {
            assertThat(registry.lookup(lookup())).isEqualTo("native");
        }

        assertThat(owner.probed).isTrue();
        assertThat(other.probed).isTrue();
        assertThat(owner.read).isTrue();
        assertThat(other.read).isFalse();
        assertThat(audits).isEmpty();
    }

    @Test
    void zeroOwnersIsOpaqueNotFoundOnlyAfterEverySuccessfulProbe() {
        List<CommandReceiptAuditEvent> audits = new ArrayList<>();
        StubProvider first = new StubProvider("timecard", ignored -> false, ignored -> null);
        StubProvider second = new StubProvider("close", ignored -> false, ignored -> null);

        try (CommandReceiptProviderRegistry registry = new CommandReceiptProviderRegistry(
                List.of(first, second), audits::add, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> registry.lookup(lookup()))
                    .isInstanceOfSatisfying(HrisTimeApiException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(HrisTimeProblemCode.TARGET_NOT_DISCLOSED));
        }

        assertThat(first.probed).isTrue();
        assertThat(second.probed).isTrue();
        assertThat(audits).isEmpty();
    }

    @Test
    void multipleOwnersAreAuditedAndNeverResolvedByProviderOrder() {
        List<CommandReceiptAuditEvent> audits = new ArrayList<>();
        StubProvider first = new StubProvider("timecard", ignored -> true, ignored -> "first");
        StubProvider second = new StubProvider("close", ignored -> true, ignored -> "second");

        try (CommandReceiptProviderRegistry registry = new CommandReceiptProviderRegistry(
                List.of(first, second), audits::add, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> registry.lookup(lookup()))
                    .isInstanceOfSatisfying(HrisTimeApiException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE));
        }

        assertThat(first.read).isFalse();
        assertThat(second.read).isFalse();
        assertThat(audits).singleElement().satisfies(event -> {
            assertThat(event.failure())
                    .isEqualTo(CommandReceiptAuditEvent.Failure.MULTIPLE_OWNERS);
            assertThat(event.providerIds()).containsExactly("timecard", "close");
        });
    }

    @Test
    void oneProviderErrorFailsClosedEvenWhenAnotherProviderOwnsTheReceipt() {
        List<CommandReceiptAuditEvent> audits = new ArrayList<>();
        StubProvider owner = new StubProvider("timecard", ignored -> true, ignored -> "native");
        StubProvider failed = new StubProvider("close", ignored -> {
            throw new IllegalStateException("dependency unavailable");
        }, ignored -> null);

        try (CommandReceiptProviderRegistry registry = new CommandReceiptProviderRegistry(
                List.of(owner, failed), audits::add, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> registry.lookup(lookup()))
                    .isInstanceOfSatisfying(HrisTimeApiException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE));
        }

        assertThat(owner.probed).isTrue();
        assertThat(failed.probed).isTrue();
        assertThat(owner.read).isFalse();
        assertThat(audits).singleElement().satisfies(event -> {
            assertThat(event.failure())
                    .isEqualTo(CommandReceiptAuditEvent.Failure.PROVIDER_LOOKUP_ERROR);
            assertThat(event.providerIds()).containsExactly("close");
        });
    }

    @Test
    void providerTimeoutIsAuditedAndFailsClosed() {
        List<CommandReceiptAuditEvent> audits = new ArrayList<>();
        StubProvider slow = new StubProvider("timecard", ignored -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
                return false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, ignored -> null);

        try (CommandReceiptProviderRegistry registry = new CommandReceiptProviderRegistry(
                List.of(slow), audits::add, Duration.ofMillis(25))) {
            assertThatThrownBy(() -> registry.lookup(lookup()))
                    .isInstanceOfSatisfying(HrisTimeApiException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE));
        }

        assertThat(slow.probed).isTrue();
        assertThat(audits).singleElement().satisfies(event ->
                assertThat(event.failure())
                        .isEqualTo(CommandReceiptAuditEvent.Failure.PROVIDER_LOOKUP_TIMEOUT));
    }

    @Test
    void uniqueOwnerAuthorityUnavailableReadIsAuditedAndFailsClosed() {
        List<CommandReceiptAuditEvent> audits = new ArrayList<>();
        StubProvider owner = new StubProvider("timecard", ignored -> true, ignored -> {
            throw HrisTimeApiException.of(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE);
        });

        try (CommandReceiptProviderRegistry registry = new CommandReceiptProviderRegistry(
                List.of(owner), audits::add, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> registry.lookup(lookup()))
                    .isInstanceOfSatisfying(HrisTimeApiException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE));
        }

        assertThat(owner.read).isTrue();
        assertThat(audits).singleElement().satisfies(event -> {
            assertThat(event.failure())
                    .isEqualTo(CommandReceiptAuditEvent.Failure.OWNER_READ_ERROR);
            assertThat(event.providerIds()).containsExactly("timecard");
        });
    }

    private CommandReceiptLookup lookup() {
        return new CommandReceiptLookup(41, RECEIPT, CORRELATION, new Object());
    }

    private static final class StubProvider implements CommandReceiptProvider {
        private final String identifier;
        private final Function<CommandReceiptLookup, Boolean> ownership;
        private final Function<CommandReceiptLookup, Object> result;
        private final AtomicBoolean probeState = new AtomicBoolean();
        private final AtomicBoolean readState = new AtomicBoolean();
        private boolean probed;
        private boolean read;

        private StubProvider(
                String identifier,
                Function<CommandReceiptLookup, Boolean> ownership,
                Function<CommandReceiptLookup, Object> result) {
            this.identifier = identifier;
            this.ownership = ownership;
            this.result = result;
        }

        @Override
        public String providerId() {
            return identifier;
        }

        @Override
        public boolean owns(CommandReceiptLookup lookup) {
            probed = probeState.compareAndSet(false, true) || probeState.get();
            return ownership.apply(lookup);
        }

        @Override
        public Object receipt(CommandReceiptLookup lookup) {
            read = readState.compareAndSet(false, true) || readState.get();
            return result.apply(lookup);
        }
    }
}
