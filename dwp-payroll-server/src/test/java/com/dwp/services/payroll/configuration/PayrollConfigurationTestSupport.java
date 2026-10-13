package com.dwp.services.payroll.configuration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Action;
import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Actor;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CommandReceipt;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CommandType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ElementVersion;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaNode;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaOperator;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaVersion;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayFormulaAst;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayValueType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayrollConfigurationPublished;

final class PayrollConfigurationTestSupport {

    static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final UUID LEGAL_ENTITY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID PAYROLL_GROUP = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final String GOLDEN_DIGEST = "a".repeat(64);

    private PayrollConfigurationTestSupport() {
    }

    static Actor actor(long tenantId, long actorId, Action... actions) {
        return actor(
                tenantId, actorId, "scope-v1", "auth-v1",
                EnumSet.copyOf(List.of(actions)));
    }

    static Actor actor(
            long tenantId,
            long actorId,
            String scopeRevision,
            String authorizationRevision,
            Set<Action> actions) {
        UUID subjectId = UUID.nameUUIDFromBytes(
                (tenantId + ":" + actorId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Actor(
                tenantId, actorId, subjectId, actions,
                Set.of(LEGAL_ENTITY), Set.of(PAYROLL_GROUP),
                "PAYROLL_CONFIGURATION",
                PayrollConfigurationCanonical.textDigest(
                        tenantId + "|" + LEGAL_ENTITY + "|" + PAYROLL_GROUP),
                "field-v1", scopeRevision, authorizationRevision);
    }

    static PayFormulaAst grossFormula() {
        return new PayFormulaAst(
                "PAY_RULE_AST_V1", 1, PayValueType.MONEY, "gross",
                List.of(
                        new FormulaNode(
                                "hours", FormulaOperator.INPUT, List.of(),
                                PayValueType.QUANTITY_HOUR, null, null,
                                "TIM.REGULAR_HOURS", null, null, null),
                        new FormulaNode(
                                "rate", FormulaOperator.INPUT, List.of(),
                                PayValueType.UNIT_PRICE_PER_HOUR, null, "USD",
                                "HRM.HOURLY_RATE", null, null, null),
                        new FormulaNode(
                                "gross", FormulaOperator.MULTIPLY,
                                List.of("hours", "rate"), PayValueType.MONEY,
                                null, "USD", null, null, null, null)));
    }

    static PayrollConfigurationService service(InMemoryStore store) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PayrollConfigurationCanonical canonical = new PayrollConfigurationCanonical(mapper);
        PayrollFormulaEngine engine = new PayrollFormulaEngine(canonical, System::nanoTime);
        return new PayrollConfigurationService(store, engine, canonical, CLOCK);
    }

    static final class InMemoryStore implements PayrollConfigurationStore {
        private final Map<Long, Map<UUID, TreeMap<Long, ElementVersion>>> elements =
                new LinkedHashMap<>();
        private final Map<Long, Map<UUID, TreeMap<Long, FormulaVersion>>> formulas =
                new LinkedHashMap<>();
        private final Map<CommandKey, CommandReceipt> commands = new LinkedHashMap<>();
        private final Map<EventKey, PayrollConfigurationPublished> events = new LinkedHashMap<>();

        @Override
        public synchronized Optional<ElementVersion> currentElement(
                long tenantId, UUID elementId) {
            TreeMap<Long, ElementVersion> versions = elements
                    .getOrDefault(tenantId, Map.of()).get(elementId);
            return versions == null || versions.isEmpty()
                    ? Optional.empty() : Optional.of(versions.lastEntry().getValue());
        }

        @Override
        public synchronized Optional<ElementVersion> elementVersion(
                long tenantId, UUID elementId, long version) {
            TreeMap<Long, ElementVersion> versions = elements
                    .getOrDefault(tenantId, Map.of()).get(elementId);
            return versions == null ? Optional.empty()
                    : Optional.ofNullable(versions.get(version));
        }

        @Override
        public synchronized List<ElementVersion> currentElements(long tenantId) {
            List<ElementVersion> values = new ArrayList<>();
            elements.getOrDefault(tenantId, Map.of()).values().forEach(versions -> {
                if (!versions.isEmpty()) values.add(versions.lastEntry().getValue());
            });
            return List.copyOf(values);
        }

        @Override
        public synchronized Optional<FormulaVersion> currentFormula(
                long tenantId, UUID formulaId) {
            TreeMap<Long, FormulaVersion> versions = formulas
                    .getOrDefault(tenantId, Map.of()).get(formulaId);
            return versions == null || versions.isEmpty()
                    ? Optional.empty() : Optional.of(versions.lastEntry().getValue());
        }

        @Override
        public synchronized Optional<FormulaVersion> formulaVersion(
                long tenantId, UUID formulaId, long version) {
            TreeMap<Long, FormulaVersion> versions = formulas
                    .getOrDefault(tenantId, Map.of()).get(formulaId);
            return versions == null ? Optional.empty()
                    : Optional.ofNullable(versions.get(version));
        }

        @Override
        public synchronized List<FormulaVersion> currentFormulas(long tenantId) {
            List<FormulaVersion> values = new ArrayList<>();
            formulas.getOrDefault(tenantId, Map.of()).values().forEach(versions -> {
                if (!versions.isEmpty()) values.add(versions.lastEntry().getValue());
            });
            return List.copyOf(values);
        }

        @Override
        public synchronized Optional<CommandReceipt> command(
                long tenantId,
                UUID subjectPrincipalPublicId,
                String originatingAction,
                UUID commandId) {
            return Optional.ofNullable(commands.get(new CommandKey(
                    tenantId, subjectPrincipalPublicId, originatingAction, commandId)));
        }

        @Override
        public synchronized Optional<PayrollConfigurationPublished> publicationEvent(
                long tenantId, UUID receiptId) {
            return Optional.ofNullable(events.get(new EventKey(tenantId, receiptId)));
        }

        @Override
        public synchronized void saveElement(
                ElementVersion previous,
                ElementVersion updated,
                CommandReceipt receipt) {
            assertElement(previous, updated);
            reserve(receipt);
            elements.computeIfAbsent(updated.tenantId(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(updated.elementId(), ignored -> new TreeMap<>())
                    .put(updated.version(), updated);
        }

        @Override
        public synchronized void saveFormula(
                FormulaVersion previous,
                FormulaVersion updated,
                CommandReceipt receipt,
                PayrollConfigurationPublished event) {
            assertFormula(previous, updated);
            reserve(receipt);
            formulas.computeIfAbsent(updated.tenantId(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(updated.formulaId(), ignored -> new TreeMap<>())
                    .put(updated.version(), updated);
            if (event != null) {
                EventKey key = new EventKey(updated.tenantId(), receipt.receiptId());
                if (events.putIfAbsent(key, event) != null) throw conflict();
            }
        }

        int formulaVersionCount(long tenantId, UUID formulaId) {
            TreeMap<Long, FormulaVersion> values = formulas
                    .getOrDefault(tenantId, Map.of()).get(formulaId);
            return values == null ? 0 : values.size();
        }

        int eventCount() {
            return events.size();
        }

        private void reserve(CommandReceipt receipt) {
            CommandKey key = new CommandKey(
                    receipt.tenantId(), receipt.subjectPrincipalPublicId(),
                    receipt.originatingAction(), receipt.commandId());
            if (commands.putIfAbsent(key, receipt) != null) throw conflict();
        }

        private void assertElement(ElementVersion previous, ElementVersion updated) {
            Optional<ElementVersion> current = currentElement(
                    updated.tenantId(), updated.elementId());
            if (previous == null ? current.isPresent()
                    : current.isEmpty() || current.get().version() != previous.version()) {
                throw conflict();
            }
        }

        private void assertFormula(FormulaVersion previous, FormulaVersion updated) {
            Optional<FormulaVersion> current = currentFormula(
                    updated.tenantId(), updated.formulaId());
            if (previous == null ? current.isPresent()
                    : current.isEmpty() || current.get().version() != previous.version()) {
                throw conflict();
            }
        }

        private BaseException conflict() {
            return new BaseException(ErrorCode.RESOURCE_CONFLICT, "in-memory CAS conflict");
        }

        private record CommandKey(
                long tenantId,
                UUID subjectPrincipalPublicId,
                String originatingAction,
                UUID commandId) {
        }

        private record EventKey(long tenantId, UUID receiptId) {
        }
    }
}
