package com.dwp.services.payroll.foundation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandType;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.DependencyPin;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationDefinition;

/** Stable request and definition fingerprints used by idempotency and simulation bindings. */
final class PayrollFoundationCanonical {

    private PayrollFoundationCanonical() {
    }

    static String definitionDigest(FoundationDefinition definition) {
        StringBuilder value = new StringBuilder();
        append(value, definition.legalEntity().id());
        append(value, definition.legalEntity().code());
        append(value, definition.legalEntity().displayName());
        append(value, definition.legalEntity().countryPack().packId());
        append(value, definition.legalEntity().countryPack().version());
        append(value, definition.legalEntity().countryPack().digest());
        append(value, definition.payrollGroup().id());
        append(value, definition.payrollGroup().code());
        append(value, definition.payrollGroup().legalEntityId());
        definition.payrollGroup().currencies().stream().sorted()
                .forEach(currency -> append(value, currency.value()));
        append(value, definition.payrollGroup().settlementCurrency().value());
        append(value, definition.payCalendar().id());
        append(value, definition.payCalendar().payrollGroupId());
        append(value, definition.payCalendar().cadence());
        definition.payCalendar().periods().stream()
                .sorted(Comparator.comparing(PayrollFoundationModels.CalendarPeriod::startsOn)
                        .thenComparing(PayrollFoundationModels.CalendarPeriod::code))
                .forEach(period -> {
                    append(value, period.code());
                    append(value, period.startsOn());
                    append(value, period.endsOn());
                    append(value, period.paymentDate());
                });
        append(value, definition.effectivePeriod().startsOn());
        append(value, definition.effectivePeriod().endsOn());
        definition.roundingPolicies().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    append(value, entry.getKey().value());
                    append(value, entry.getValue().scale());
                    append(value, entry.getValue().mode());
                    append(value, entry.getValue().increment().toPlainString());
                });
        definition.dependencies().stream()
                .sorted(Comparator.comparing(DependencyPin::key))
                .forEach(pin -> {
                    append(value, pin.owner());
                    append(value, pin.resourceId());
                    append(value, pin.version());
                });
        return sha256(value.toString());
    }

    static String dependencyDigest(FoundationDefinition definition) {
        StringBuilder value = new StringBuilder();
        definition.dependencies().stream()
                .sorted(Comparator.comparing(DependencyPin::key))
                .forEach(pin -> {
                    append(value, pin.owner());
                    append(value, pin.resourceId());
                    append(value, pin.version());
                });
        return sha256(value.toString());
    }

    static String requestDigest(
            CommandType type,
            java.util.UUID configurationId,
            Long expectedVersion,
            FoundationDefinition definition,
            java.util.UUID relatedCommandId) {
        StringBuilder value = new StringBuilder();
        append(value, type);
        append(value, configurationId);
        append(value, expectedVersion);
        append(value, definition == null ? null : definitionDigest(definition));
        append(value, relatedCommandId);
        return sha256(value.toString());
    }

    static String textDigest(String value) {
        return sha256(value);
    }

    private static void append(StringBuilder target, Object value) {
        String text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append('|');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
