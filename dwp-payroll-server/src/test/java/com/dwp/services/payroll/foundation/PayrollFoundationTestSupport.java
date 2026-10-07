package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CalendarPeriod;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Cadence;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceipt;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ConfigurationSnapshot;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CurrencyCode;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.DependencyPin;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.EffectivePeriod;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationDefinition;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.LegalEntity;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Lifecycle;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.PayCalendar;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.PayrollGroup;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReceiptStatus;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.RoundingPolicy;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.VersionedCountryPackReference;

final class PayrollFoundationTestSupport {

    static final UUID LEGAL_ENTITY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID GROUP_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID CALENDAR_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID DEPENDENCY_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final String PACK_DIGEST = "a".repeat(64);

    private PayrollFoundationTestSupport() {
    }

    static FoundationDefinition definition(
            UUID legalEntityId,
            UUID groupId,
            LocalDate from,
            LocalDate to,
            Set<CurrencyCode> currencies,
            List<DependencyPin> dependencies) {
        CurrencyCode settlement = currencies.stream().sorted().findFirst().orElseThrow();
        Map<CurrencyCode, RoundingPolicy> rounding = new LinkedHashMap<>();
        currencies.stream().sorted().forEach(currency -> rounding.put(
                currency, new RoundingPolicy(2, RoundingMode.HALF_EVEN, new BigDecimal("0.01"))));
        UUID calendarId = UUID.nameUUIDFromBytes((groupId + ":calendar").getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        return new FoundationDefinition(
                new LegalEntity(
                        legalEntityId, "LE-" + legalEntityId.toString().substring(0, 8),
                        "Synthetic Legal Entity",
                        new VersionedCountryPackReference("PACK-GENERIC", 7, PACK_DIGEST)),
                new PayrollGroup(groupId, "GROUP-" + groupId.toString().substring(0, 8),
                        legalEntityId, currencies, settlement),
                new PayCalendar(calendarId, groupId, Cadence.CUSTOM, List.of(
                        new CalendarPeriod("P-001", from, from.plusDays(13), from.plusDays(16)))),
                new EffectivePeriod(from, to),
                rounding,
                dependencies);
    }

    static CurrencyCode currency(String value) {
        return new CurrencyCode(value);
    }

    static final class InMemoryStore implements PayrollFoundationStore {

        private final Map<TenantConfigurationKey, ConfigurationSnapshot> current = new LinkedHashMap<>();
        private final Map<TenantConfigurationKey, NavigableMap<Long, ConfigurationSnapshot>> versions =
                new LinkedHashMap<>();
        private final Map<TenantCommandKey, CommandReceipt> receipts = new LinkedHashMap<>();
        private boolean failNextSuccessfulReceiptWrite;
        private boolean hideNextByCommand;
        private CommandReceipt receiptBeforeNextReceiptWrite;

        @Override
        public synchronized Optional<ConfigurationSnapshot> current(
                long tenantId, UUID configurationId) {
            return Optional.ofNullable(current.get(
                    new TenantConfigurationKey(tenantId, configurationId)));
        }

        @Override
        public synchronized List<ConfigurationSnapshot> currentForTenant(long tenantId) {
            return current.entrySet().stream()
                    .filter(entry -> entry.getKey().tenantId == tenantId)
                    .map(Map.Entry::getValue)
                    .toList();
        }

        @Override
        public synchronized List<ConfigurationSnapshot> versions(
                long tenantId, UUID configurationId) {
            NavigableMap<Long, ConfigurationSnapshot> history = versions.get(
                    new TenantConfigurationKey(tenantId, configurationId));
            return history == null ? List.of() : new ArrayList<>(history.descendingMap().values());
        }

        @Override
        public synchronized void save(
                ConfigurationSnapshot previous,
                ConfigurationSnapshot updated,
                CommandReceipt command) {
            TenantConfigurationKey key = new TenantConfigurationKey(
                    updated.tenantId(), updated.configurationId());
            ConfigurationSnapshot stored = current.get(key);
            if (previous == null) {
                if (stored != null || updated.version() != 1) {
                    throw conflict();
                }
            } else if (stored == null
                    || stored.version() != previous.version()
                    || !stored.lastCommandId().equals(previous.lastCommandId())
                    || updated.version() != previous.version() + 1) {
                throw conflict();
            }
            for (ConfigurationSnapshot candidate : currentForTenant(updated.tenantId())) {
                if (!candidate.configurationId().equals(updated.configurationId())
                        && candidate.status() != Lifecycle.REVERSED
                        && candidate.definition().payrollGroup().id()
                        .equals(updated.definition().payrollGroup().id())
                        && candidate.definition().effectivePeriod()
                        .overlaps(updated.definition().effectivePeriod())) {
                    throw conflict();
                }
            }
            current.put(key, updated);
            versions.computeIfAbsent(key, ignored -> new TreeMap<>())
                    .put(updated.version(), updated);
        }

        @Override
        public synchronized ReceiptReservation reserve(CommandReceipt pendingReceipt) {
            TenantCommandKey key = new TenantCommandKey(
                    pendingReceipt.tenantId(), pendingReceipt.commandId());
            CommandReceipt existing = receipts.putIfAbsent(key, pendingReceipt);
            return new ReceiptReservation(
                    existing == null ? pendingReceipt : existing, existing == null);
        }

        @Override
        public synchronized void replaceReceipt(CommandReceipt receipt) {
            TenantCommandKey key = new TenantCommandKey(receipt.tenantId(), receipt.commandId());
            if (receiptBeforeNextReceiptWrite != null) {
                CommandReceipt racingReceipt = receiptBeforeNextReceiptWrite;
                receiptBeforeNextReceiptWrite = null;
                receipts.put(
                        new TenantCommandKey(racingReceipt.tenantId(), racingReceipt.commandId()),
                        racingReceipt);
            }
            CommandReceipt currentReceipt = receipts.get(key);
            if (currentReceipt == null) {
                throw new IllegalStateException("receipt is not reserved");
            }
            if (failNextSuccessfulReceiptWrite && receipt.status() == ReceiptStatus.SUCCEEDED) {
                failNextSuccessfulReceiptWrite = false;
                throw new IllegalStateException("synthetic acknowledgement loss");
            }
            if (currentReceipt.status() == ReceiptStatus.SUCCEEDED
                    || currentReceipt.status() == ReceiptStatus.REVERSAL_FAILED
                    || receipt.status() == ReceiptStatus.PENDING) {
                throw new IllegalStateException("receipt transition is not allowed");
            }
            receipts.put(key, receipt);
        }

        @Override
        public synchronized Optional<CommandReceipt> receipt(long tenantId, UUID commandId) {
            return Optional.ofNullable(receipts.get(new TenantCommandKey(tenantId, commandId)));
        }

        @Override
        public synchronized Optional<ConfigurationSnapshot> byCommand(
                long tenantId, UUID commandId) {
            if (hideNextByCommand) {
                hideNextByCommand = false;
                return Optional.empty();
            }
            return versions.entrySet().stream()
                    .filter(entry -> entry.getKey().tenantId == tenantId)
                    .flatMap(entry -> entry.getValue().values().stream())
                    .filter(snapshot -> snapshot.lastCommandId().equals(commandId))
                    .findFirst();
        }

        @Override
        public synchronized Optional<ConfigurationSnapshot> version(
                long tenantId, UUID configurationId, long version) {
            NavigableMap<Long, ConfigurationSnapshot> history = versions.get(
                    new TenantConfigurationKey(tenantId, configurationId));
            return history == null ? Optional.empty()
                    : Optional.ofNullable(history.get(version));
        }

        void failNextSuccessfulReceiptWrite() {
            failNextSuccessfulReceiptWrite = true;
        }

        void hideNextByCommand() {
            hideNextByCommand = true;
        }

        void receiptBeforeNextReceiptWrite(CommandReceipt receipt) {
            receiptBeforeNextReceiptWrite = receipt;
        }

        synchronized void replaceUnchecked(ConfigurationSnapshot snapshot) {
            TenantConfigurationKey key = new TenantConfigurationKey(
                    snapshot.tenantId(), snapshot.configurationId());
            current.put(key, snapshot);
            versions.computeIfAbsent(key, ignored -> new TreeMap<>())
                    .put(snapshot.version(), snapshot);
        }

        private BaseException conflict() {
            return new BaseException(ErrorCode.RESOURCE_CONFLICT, "synthetic conflict");
        }
    }

    static final class MutableDependencies implements PayrollDependencyVersionSource {

        private final Map<TenantDependencyKey, Long> versions = new LinkedHashMap<>();

        @Override
        public OptionalLong currentVersion(long tenantId, DependencyPin pin) {
            Long version = versions.get(new TenantDependencyKey(tenantId, pin.key()));
            return version == null ? OptionalLong.empty() : OptionalLong.of(version);
        }

        void set(long tenantId, DependencyPin pin, long version) {
            versions.put(new TenantDependencyKey(tenantId, pin.key()), version);
        }
    }

    private record TenantConfigurationKey(long tenantId, UUID configurationId) {
    }

    private record TenantCommandKey(long tenantId, UUID commandId) {
    }

    private record TenantDependencyKey(long tenantId, String key) {
    }
}
