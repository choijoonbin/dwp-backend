package com.dwp.services.payroll.foundation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Transport and domain values for the bounded payroll-foundation slice. */
public final class PayrollFoundationModels {

    private PayrollFoundationModels() {
    }

    public enum Lifecycle {
        DRAFT,
        SIMULATED,
        PUBLISHED,
        REVERSED
    }

    public enum ReceiptStatus {
        PENDING,
        SUCCEEDED,
        RESULT_UNKNOWN,
        REVERSAL_FAILED
    }

    public enum CommandType {
        CREATE,
        UPDATE,
        SIMULATE,
        PUBLISH,
        REVERSE
    }

    public enum Cadence {
        WEEKLY,
        BIWEEKLY,
        SEMIMONTHLY,
        MONTHLY,
        CUSTOM
    }

    public enum FoundationAction {
        VIEW,
        CREATE,
        UPDATE,
        SIMULATE,
        PUBLISH,
        REVERSE,
        RECONCILE
    }

    public enum FreshnessState {
        LIVE,
        STALE,
        PARTIAL,
        UNAVAILABLE
    }

    public record CurrencyCode(String value) implements Comparable<CurrencyCode> {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public CurrencyCode {
            value = requireCode(value, "currency").toUpperCase(Locale.ROOT);
            try {
                Currency.getInstance(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("currency must be an ISO 4217 code", exception);
            }
        }

        @JsonValue
        public String jsonValue() {
            return value;
        }

        @Override
        public int compareTo(CurrencyCode other) {
            return value.compareTo(other.value);
        }
    }

    public record RoundingPolicy(
            int scale,
            @NotNull RoundingMode mode,
            @NotNull @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal increment) {

        public RoundingPolicy {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(increment, "increment");
            if (scale < 0 || scale > 12) {
                throw new IllegalArgumentException("rounding scale must be between 0 and 12");
            }
            if (mode == RoundingMode.UNNECESSARY) {
                throw new IllegalArgumentException("UNNECESSARY is not a usable payroll rounding mode");
            }
            increment = increment.stripTrailingZeros();
            if (increment.signum() <= 0 || increment.scale() > scale) {
                throw new IllegalArgumentException(
                        "rounding increment must be positive and fit the configured scale");
            }
        }

        public BigDecimal apply(BigDecimal amount) {
            Objects.requireNonNull(amount, "amount");
            BigDecimal units = amount.divide(increment, 0, mode);
            return units.multiply(increment).setScale(scale, mode);
        }
    }

    public record Money(
            @NotNull @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal amount,
            @NotNull CurrencyCode currency) {

        public Money {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
        }

        public Money rounded(RoundingPolicy policy) {
            return new Money(policy.apply(amount), currency);
        }
    }

    public record EffectivePeriod(
            @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate startsOn,
            @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate endsOn) {

        public EffectivePeriod {
            Objects.requireNonNull(startsOn, "startsOn");
            if (endsOn != null && endsOn.isBefore(startsOn)) {
                throw new IllegalArgumentException("effective period end cannot precede start");
            }
        }

        public boolean overlaps(EffectivePeriod other) {
            Objects.requireNonNull(other, "other");
            boolean startsBeforeOtherEnds = other.endsOn == null
                    || !startsOn.isAfter(other.endsOn);
            boolean otherStartsBeforeEnd = endsOn == null
                    || !other.startsOn.isAfter(endsOn);
            return startsBeforeOtherEnds && otherStartsBeforeEnd;
        }
    }

    public record VersionedCountryPackReference(
            @NotBlank String packId,
            @Positive long version,
            @NotBlank String digest) {

        public VersionedCountryPackReference {
            packId = requireCode(packId, "country pack id");
            if (version <= 0) {
                throw new IllegalArgumentException("country pack version must be positive");
            }
            digest = requireText(digest, "country pack digest").toLowerCase(Locale.ROOT);
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("country pack digest must be SHA-256");
            }
        }
    }

    public record LegalEntity(
            @NotNull UUID id,
            @NotBlank String code,
            @NotBlank String displayName,
            @NotNull @Valid VersionedCountryPackReference countryPack) {

        public LegalEntity {
            Objects.requireNonNull(id, "legal entity id");
            code = requireCode(code, "legal entity code");
            displayName = requireText(displayName, "legal entity display name");
            Objects.requireNonNull(countryPack, "countryPack");
        }
    }

    public record PayrollGroup(
            @NotNull UUID id,
            @NotBlank String code,
            @NotNull UUID legalEntityId,
            @NotNull @Size(min = 1) Set<CurrencyCode> currencies,
            @NotNull CurrencyCode settlementCurrency) {

        public PayrollGroup {
            Objects.requireNonNull(id, "payroll group id");
            code = requireCode(code, "payroll group code");
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            currencies = immutableSet(currencies, "currencies");
            Objects.requireNonNull(settlementCurrency, "settlementCurrency");
            if (!currencies.contains(settlementCurrency)) {
                throw new IllegalArgumentException(
                        "settlement currency must be present in supported currencies");
            }
        }
    }

    public record CalendarPeriod(
            @NotBlank String code,
            @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate startsOn,
            @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate endsOn,
            @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate paymentDate) {

        public CalendarPeriod {
            code = requireCode(code, "calendar period code");
            Objects.requireNonNull(startsOn, "startsOn");
            Objects.requireNonNull(endsOn, "endsOn");
            Objects.requireNonNull(paymentDate, "paymentDate");
            if (endsOn.isBefore(startsOn)) {
                throw new IllegalArgumentException("calendar period end cannot precede start");
            }
        }

        boolean overlaps(CalendarPeriod other) {
            return !startsOn.isAfter(other.endsOn) && !other.startsOn.isAfter(endsOn);
        }
    }

    public record PayCalendar(
            @NotNull UUID id,
            @NotNull UUID payrollGroupId,
            @NotNull Cadence cadence,
            @NotNull @Size(min = 1) List<@Valid CalendarPeriod> periods) {

        public PayCalendar {
            Objects.requireNonNull(id, "calendar id");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            Objects.requireNonNull(cadence, "cadence");
            periods = immutableList(periods, "periods");
            Set<String> codes = new LinkedHashSet<>();
            List<CalendarPeriod> ordered = new ArrayList<>(periods);
            ordered.sort(Comparator.comparing(CalendarPeriod::startsOn));
            for (int index = 0; index < ordered.size(); index++) {
                CalendarPeriod current = ordered.get(index);
                if (!codes.add(current.code())) {
                    throw new IllegalArgumentException("calendar period codes must be unique");
                }
                if (index > 0 && ordered.get(index - 1).overlaps(current)) {
                    throw new IllegalArgumentException("calendar periods cannot overlap");
                }
            }
        }
    }

    public record DependencyPin(
            @NotBlank String owner,
            @NotNull UUID resourceId,
            @Positive long version) {

        public DependencyPin {
            owner = requireCode(owner, "dependency owner");
            Objects.requireNonNull(resourceId, "resourceId");
            if (version <= 0) {
                throw new IllegalArgumentException("dependency version must be positive");
            }
        }

        public String key() {
            return owner + ":" + resourceId;
        }
    }

    public record FoundationDefinition(
            @NotNull @Valid LegalEntity legalEntity,
            @NotNull @Valid PayrollGroup payrollGroup,
            @NotNull @Valid PayCalendar payCalendar,
            @NotNull @Valid EffectivePeriod effectivePeriod,
            @NotNull Map<CurrencyCode, @Valid RoundingPolicy> roundingPolicies,
            @NotNull List<@Valid DependencyPin> dependencies) {

        public FoundationDefinition {
            Objects.requireNonNull(legalEntity, "legalEntity");
            Objects.requireNonNull(payrollGroup, "payrollGroup");
            Objects.requireNonNull(payCalendar, "payCalendar");
            Objects.requireNonNull(effectivePeriod, "effectivePeriod");
            roundingPolicies = immutableMap(roundingPolicies, "roundingPolicies");
            dependencies = immutableList(dependencies, "dependencies");
            if (!payrollGroup.legalEntityId().equals(legalEntity.id())) {
                throw new IllegalArgumentException("payroll group must reference its legal entity");
            }
            if (!payCalendar.payrollGroupId().equals(payrollGroup.id())) {
                throw new IllegalArgumentException("calendar must reference its payroll group");
            }
            if (!roundingPolicies.keySet().equals(payrollGroup.currencies())) {
                throw new IllegalArgumentException(
                        "every supported currency must have exactly one rounding policy");
            }
            Set<String> dependencyKeys = new LinkedHashSet<>();
            for (DependencyPin pin : dependencies) {
                if (!dependencyKeys.add(pin.key())) {
                    throw new IllegalArgumentException("dependency pins must be unique");
                }
            }
        }
    }

    public record CreateConfigurationRequest(@NotNull @Valid FoundationDefinition definition) {
    }

    public record UpdateConfigurationRequest(
            @Positive long expectedVersion,
            @NotNull @Valid FoundationDefinition definition) {
    }

    public record VersionCommand(@Positive long expectedVersion) {
    }

    public record ReversalCommand(
            @Positive long expectedVersion,
            @NotNull UUID publishCommandId) {
    }

    public record SimulationReport(
            UUID simulationId,
            long configurationVersion,
            String definitionDigest,
            String dependencyDigest,
            boolean successful,
            List<String> findings,
            Instant simulatedAt,
            long simulatedBy) {

        public SimulationReport {
            findings = immutableList(findings, "findings");
        }
    }

    public record ConfigurationSnapshot(
            long tenantId,
            UUID configurationId,
            long version,
            Lifecycle status,
            FoundationDefinition definition,
            long authorId,
            Long publisherId,
            Instant createdAt,
            Instant updatedAt,
            SimulationReport simulation,
            UUID lastCommandId) {

        public ConfigurationSnapshot {
            if (tenantId <= 0 || version <= 0 || authorId <= 0) {
                throw new IllegalArgumentException("tenant, version and author must be positive");
            }
            Objects.requireNonNull(configurationId, "configurationId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(lastCommandId, "lastCommandId");
        }
    }

    public record CommandReceipt(
            long tenantId,
            long actorId,
            UUID commandId,
            CommandType commandType,
            ReceiptStatus status,
            String requestDigest,
            UUID configurationId,
            Long resultVersion,
            UUID reversalOfCommandId,
            String failureCode,
            String correlationId,
            String authorityPurpose,
            String legalEntityScopeDigest,
            String policyRevision,
            String authorizationRevision,
            Instant createdAt,
            Instant completedAt) {

        public CommandReceipt {
            if (tenantId <= 0 || actorId <= 0) {
                throw new IllegalArgumentException("receipt tenant and actor must be positive");
            }
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(commandType, "commandType");
            Objects.requireNonNull(status, "status");
            requestDigest = requireText(requestDigest, "requestDigest");
            authorityPurpose = requireCode(authorityPurpose, "authorityPurpose");
            legalEntityScopeDigest = requireText(
                    legalEntityScopeDigest, "legalEntityScopeDigest").toLowerCase(Locale.ROOT);
            if (!legalEntityScopeDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "legalEntityScopeDigest must be a SHA-256 digest");
            }
            policyRevision = requireEvidenceRevision(policyRevision, "policyRevision");
            authorizationRevision = requireEvidenceRevision(
                    authorizationRevision, "authorizationRevision");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        CommandReceipt complete(UUID targetId, long targetVersion, Instant completed) {
            return new CommandReceipt(
                    tenantId, actorId, commandId, commandType, ReceiptStatus.SUCCEEDED,
                    requestDigest, targetId, targetVersion, reversalOfCommandId, null,
                    correlationId, authorityPurpose, legalEntityScopeDigest,
                    policyRevision, authorizationRevision, createdAt, completed);
        }

        CommandReceipt unknown(UUID targetId, Long targetVersion, Instant completed) {
            return new CommandReceipt(
                    tenantId, actorId, commandId, commandType, ReceiptStatus.RESULT_UNKNOWN,
                    requestDigest, targetId, targetVersion, reversalOfCommandId,
                    "RESULT_UNKNOWN", correlationId, authorityPurpose,
                    legalEntityScopeDigest, policyRevision, authorizationRevision,
                    createdAt, completed);
        }

        CommandReceipt reversalFailed(UUID targetId, Long targetVersion, Instant completed) {
            return new CommandReceipt(
                    tenantId, actorId, commandId, commandType, ReceiptStatus.REVERSAL_FAILED,
                    requestDigest, targetId, targetVersion, reversalOfCommandId,
                    "REVERSAL_PRECONDITION_FAILED", correlationId, authorityPurpose,
                    legalEntityScopeDigest, policyRevision, authorizationRevision,
                    createdAt, completed);
        }
    }

    public record AccessProjection(
            boolean canCreate,
            boolean canEdit,
            boolean canSimulate,
            boolean canPublish,
            boolean canReverse,
            boolean canReconcile,
            String publishDenialCode) {
    }

    public record Freshness(FreshnessState state, Instant lastSuccessfulRefreshAt) {
    }

    public record ConfigurationView(
            UUID configurationId,
            long version,
            Lifecycle status,
            FoundationDefinition definition,
            long authorId,
            Long publisherId,
            Instant createdAt,
            Instant updatedAt,
            SimulationReport simulation,
            UUID lastCommandId,
            AccessProjection access,
            Freshness freshness) {
    }

    public record WorkspaceView(
            List<ConfigurationView> configurations,
            AccessProjection access,
            List<PartialFailure> partialFailures) {

        public WorkspaceView {
            configurations = immutableList(configurations, "configurations");
            partialFailures = immutableList(partialFailures, "partialFailures");
        }
    }

    public record PartialFailure(String source, String code) {

        public PartialFailure {
            source = requireCode(source, "partial failure source");
            code = requireCode(code, "partial failure code");
        }
    }

    public record CommandReceiptView(
            UUID commandId,
            CommandType commandType,
            ReceiptStatus status,
            UUID configurationId,
            Long resultVersion,
            UUID reversalOfCommandId,
            String failureCode,
            String correlationId,
            Instant createdAt,
            Instant completedAt) {
    }

    public record MutationResult(CommandReceiptView receipt, ConfigurationView configuration) {
    }

    private static String requireCode(String value, String label) {
        String normalized = requireText(value, label).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9._:-]{0,79}")) {
            throw new IllegalArgumentException(label + " contains unsupported characters");
        }
        return normalized;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException(label + " is required and must be at most 200 characters");
        }
        return value.strip();
    }

    private static String requireEvidenceRevision(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 240) {
            throw new IllegalArgumentException(label + " is required");
        }
        String normalized = value.strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}")) {
            throw new IllegalArgumentException(label + " contains unsupported characters");
        }
        return normalized;
    }

    private static <T> List<T> immutableList(List<T> values, String label) {
        if (values == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return List.copyOf(values);
    }

    private static <T> Set<T> immutableSet(Set<T> values, String label) {
        if (values == null || values.isEmpty()
                || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + " must contain at least one non-null value");
        }
        return Set.copyOf(values);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values, String label) {
        if (values == null || values.isEmpty()
                || values.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException(label + " must contain non-null entries");
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }
}
