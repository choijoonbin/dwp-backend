package com.dwp.platform.contracts.hris.home;

import com.dwp.platform.contracts.hris.generated.CanonicalHrisJson;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Closed owner-to-Platform response profile for all four HRIS Home producers.
 *
 * <p>The response deliberately has no free-form payload map. The module, widget,
 * data discriminant, field decisions and partial failures are one sealed unit.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonDeserialize(using = HrisHomeContributionMaterializationV1.Deserializer.class)
public record HrisHomeContributionMaterializationV1(
        String profile,
        Module module,
        String widgetKey,
        WidgetType widgetType,
        BrowserAudience browserAudience,
        OwnerPopulationScope ownerPopulationScope,
        long scopeRevision,
        String populationScopeDigest,
        long fieldPolicyRevision,
        String purposeCode,
        State state,
        @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant generatedAt,
        @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant freshUntil,
        @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant staleAfter,
        String deepLink,
        long sourceVersion,
        String payloadRef,
        String payloadDigest,
        @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant asOf,
        HomeData data,
        List<FieldDecision> fieldDecisions,
        List<PartialFailure> partialFailures) {

    public static final String PROFILE = "HrisHomeContributionMaterialization.v1";
    public static final String PURPOSE = "HRIS_HOME_MATERIALIZATION";
    public static final String MASKED_DISPLAY = "••••";

    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern PAYLOAD_REF =
            Pattern.compile("^href_[A-Za-z0-9_-]{16,128}$");
    private static final Pattern DEEP_LINK = Pattern.compile("^/hr(?:/|$).{0,296}$");
    private static final Pattern MONEY =
            Pattern.compile("^(0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,4})?$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "profile", "module", "widgetKey", "widgetType", "browserAudience",
            "ownerPopulationScope", "scopeRevision", "populationScopeDigest",
            "fieldPolicyRevision", "purposeCode", "state", "generatedAt",
            "freshUntil", "staleAfter", "deepLink", "sourceVersion", "payloadRef",
            "payloadDigest", "asOf", "data", "fieldDecisions", "partialFailures");

    public HrisHomeContributionMaterializationV1 {
        requireEqual(PROFILE, profile, "profile");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(widgetType, "widgetType");
        Objects.requireNonNull(browserAudience, "browserAudience");
        Objects.requireNonNull(ownerPopulationScope, "ownerPopulationScope");
        positive(scopeRevision, "scopeRevision");
        requireDigest(populationScopeDigest, "populationScopeDigest");
        positive(fieldPolicyRevision, "fieldPolicyRevision");
        requireEqual(PURPOSE, purposeCode, "purposeCode");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(freshUntil, "freshUntil");
        Objects.requireNonNull(staleAfter, "staleAfter");
        Objects.requireNonNull(asOf, "asOf");
        if (asOf.isAfter(generatedAt)
                || generatedAt.isAfter(freshUntil)
                || freshUntil.isAfter(staleAfter)) {
            throw invalid("asOf/generatedAt/freshUntil/staleAfter must be ordered");
        }
        if (deepLink == null || deepLink.length() > 300 || !DEEP_LINK.matcher(deepLink).matches()
                || deepLink.indexOf('?') >= 0 || deepLink.indexOf('#') >= 0
                || deepLink.contains("//") || deepLink.contains("..")) {
            throw invalid("deepLink must be a bounded canonical /hr route");
        }
        positive(sourceVersion, "sourceVersion");
        if (payloadRef == null || !PAYLOAD_REF.matcher(payloadRef).matches()) {
            throw invalid("payloadRef is not a canonical opaque reference");
        }
        requireDigest(payloadDigest, "payloadDigest");
        Objects.requireNonNull(data, "data");
        module.validate(widgetKey, widgetType, browserAudience, ownerPopulationScope, data);

        fieldDecisions = immutable(fieldDecisions, "fieldDecisions");
        Map<String, Decision> expectedDecisions = data.decisions();
        if (fieldDecisions.size() != expectedDecisions.size()) {
            throw invalid("fieldDecisions must contain exactly one entry per data field");
        }
        Map<String, Decision> actualDecisions = new LinkedHashMap<>();
        for (FieldDecision decision : fieldDecisions) {
            Objects.requireNonNull(decision, "fieldDecisions contains null");
            if (actualDecisions.putIfAbsent(decision.fieldKey(), decision.decision()) != null) {
                throw invalid("fieldDecisions contains a duplicate fieldKey");
            }
        }
        if (!expectedDecisions.equals(actualDecisions)) {
            throw invalid("fieldDecisions must exactly match embedded data decisions");
        }

        partialFailures = immutable(partialFailures, "partialFailures");
        if (partialFailures.size() > 8) {
            throw invalid("partialFailures exceeds the canonical maximum");
        }
        if ((state == State.FRESH || state == State.STALE) && !partialFailures.isEmpty()) {
            throw invalid("FRESH and STALE materializations cannot contain partial failures");
        }
        if ((state == State.DEGRADED || state == State.UNAVAILABLE)
                && partialFailures.isEmpty()) {
            throw invalid("DEGRADED and UNAVAILABLE materializations require a failure");
        }
        Set<UUID> failureIds = new HashSet<>();
        for (PartialFailure failure : partialFailures) {
            Objects.requireNonNull(failure, "partialFailures contains null");
            if (failure.module() != module || !failure.widgetKey().equals(widgetKey)
                    || failure.browserAudience() != browserAudience
                    || failure.ownerPopulationScope() != ownerPopulationScope
                    || failure.scopeRevision() != scopeRevision
                    || failure.fieldPolicyRevision() != fieldPolicyRevision
                    || !PURPOSE.equals(failure.purposeCode())) {
                throw invalid("partial failure authority does not match its materialization");
            }
            if (failure.occurredAt().isAfter(generatedAt)) {
                throw invalid("partial failure cannot occur after generation");
            }
            if (!failureIds.add(failure.correlationId())) {
                throw invalid("partialFailures contains a duplicate correlationId");
            }
        }
    }

    public enum Module {
        HRM("^people-[a-z0-9-]{1,33}$", WidgetType.PEOPLE_SUMMARY,
                Set.of(OwnerPopulationScope.SELF, OwnerPopulationScope.TEAM,
                        OwnerPopulationScope.NAMED_POPULATION), PeopleHomeData.class),
        PER("^performance-[a-z0-9-]{1,28}$", WidgetType.PERFORMANCE_SUMMARY,
                Set.of(OwnerPopulationScope.SELF, OwnerPopulationScope.TEAM,
                        OwnerPopulationScope.NAMED_POPULATION), PerformanceHomeData.class),
        TIM("^(?:time|leave)-[a-z0-9-]{1,31}$", WidgetType.TIME_LEAVE_SUMMARY,
                Set.of(OwnerPopulationScope.SELF, OwnerPopulationScope.TEAM,
                        OwnerPopulationScope.TIME_GROUP), TimeLeaveHomeData.class),
        PAY("^payroll-[a-z0-9-]{1,30}$", WidgetType.PAYROLL_SUMMARY,
                Set.of(OwnerPopulationScope.SELF, OwnerPopulationScope.PAY_GROUP,
                        OwnerPopulationScope.LEGAL_ENTITY), PayrollHomeData.class);

        private final Pattern widgetKey;
        private final WidgetType widgetType;
        private final Set<OwnerPopulationScope> scopes;
        private final Class<? extends HomeData> dataType;

        Module(String widgetKey, WidgetType widgetType, Set<OwnerPopulationScope> scopes,
                Class<? extends HomeData> dataType) {
            this.widgetKey = Pattern.compile(widgetKey);
            this.widgetType = widgetType;
            this.scopes = scopes;
            this.dataType = dataType;
        }

        private void validate(String key, WidgetType type, BrowserAudience audience,
                OwnerPopulationScope scope, HomeData data) {
            if (key == null || !widgetKey.matcher(key).matches()) {
                throw invalid("widgetKey does not match module " + this);
            }
            if (type != widgetType || data.kind() != widgetType || !dataType.isInstance(data)) {
                throw invalid("module/widgetType/data discriminants do not match");
            }
            if (!scopes.contains(scope) || !scope.matches(audience, this)) {
                throw invalid("browserAudience/ownerPopulationScope is not allowed for module");
            }
        }
    }

    public enum WidgetType {
        PEOPLE_SUMMARY,
        PERFORMANCE_SUMMARY,
        TIME_LEAVE_SUMMARY,
        PAYROLL_SUMMARY
    }

    public enum BrowserAudience {
        SELF,
        TEAM,
        OPERATIONS,
        EXECUTIVE
    }

    public enum OwnerPopulationScope {
        SELF,
        TEAM,
        NAMED_POPULATION,
        TIME_GROUP,
        PAY_GROUP,
        LEGAL_ENTITY;

        public boolean matches(BrowserAudience audience, Module module) {
            return switch (audience) {
                case SELF -> this == SELF;
                case TEAM -> this == TEAM && module != Module.PAY;
                case OPERATIONS -> switch (module) {
                    case HRM, PER -> this == NAMED_POPULATION;
                    case TIM -> this == TIME_GROUP;
                    case PAY -> this == PAY_GROUP;
                };
                case EXECUTIVE -> switch (module) {
                    case HRM, PER -> this == NAMED_POPULATION;
                    case TIM -> this == TIME_GROUP;
                    case PAY -> this == LEGAL_ENTITY;
                };
            };
        }
    }

    public enum State {
        FRESH,
        STALE,
        DEGRADED,
        UNAVAILABLE
    }

    public enum Decision {
        VIEW,
        MASK,
        OMIT
    }

    public enum FailureCode {
        OWNER_TIMEOUT,
        OWNER_UNAVAILABLE,
        AUTHORIZATION_CHANGED,
        SCOPE_CHANGED,
        FIELD_POLICY_CHANGED,
        STALE_BEYOND_LIMIT,
        CONTRACT_INVALID
    }

    public sealed interface Projection
            permits TextProjection, NumberProjection, BooleanProjection, MoneyProjection {
        Decision decision();
    }

    public sealed interface TextProjection extends Projection
            permits ViewedText, MaskedField, OmittedField {
    }

    public sealed interface NumberProjection extends Projection
            permits ViewedNumber, MaskedField, OmittedField {
    }

    public sealed interface BooleanProjection extends Projection
            permits ViewedBoolean, MaskedField, OmittedField {
    }

    public sealed interface MoneyProjection extends Projection
            permits ViewedMoney, MaskedField, OmittedField {
    }

    public record ViewedText(Decision decision, String value) implements TextProjection {
        public ViewedText {
            requireDecision(Decision.VIEW, decision, "ViewedText");
            if (!safeText(value)) {
                throw invalid("viewed text must be bounded display-safe text");
            }
        }
    }

    public record ViewedNumber(Decision decision, long value) implements NumberProjection {
        public ViewedNumber {
            requireDecision(Decision.VIEW, decision, "ViewedNumber");
            if (value < 0 || value > 5_259_600L) {
                throw invalid("viewed number is outside the closed projection range");
            }
        }
    }

    public record ViewedBoolean(Decision decision, boolean value) implements BooleanProjection {
        public ViewedBoolean {
            requireDecision(Decision.VIEW, decision, "ViewedBoolean");
        }
    }

    public record ViewedMoney(Decision decision, String amount, String currency)
            implements MoneyProjection {
        public ViewedMoney {
            requireDecision(Decision.VIEW, decision, "ViewedMoney");
            if (amount == null || !MONEY.matcher(amount).matches()) {
                throw invalid("money amount must be a canonical posted-money decimal");
            }
            if (currency == null || !CURRENCY.matcher(currency).matches()) {
                throw invalid("money currency must be an ISO-style three-letter code");
            }
        }
    }

    public record MaskedField(Decision decision, String maskedDisplay)
            implements TextProjection, NumberProjection, BooleanProjection, MoneyProjection {
        public MaskedField {
            requireDecision(Decision.MASK, decision, "MaskedField");
            requireEqual(MASKED_DISPLAY, maskedDisplay, "maskedDisplay");
        }
    }

    public record OmittedField(Decision decision)
            implements TextProjection, NumberProjection, BooleanProjection, MoneyProjection {
        public OmittedField {
            requireDecision(Decision.OMIT, decision, "OmittedField");
        }
    }

    public sealed interface HomeData
            permits PeopleHomeData, PerformanceHomeData, TimeLeaveHomeData, PayrollHomeData {
        WidgetType kind();

        Map<String, Decision> decisions();
    }

    public record PeopleHomeData(
            WidgetType kind,
            TextProjection employmentStatus,
            TextProjection organizationLabel,
            NumberProjection pendingActionCount) implements HomeData {
        public PeopleHomeData {
            requireKind(WidgetType.PEOPLE_SUMMARY, kind);
            requireTextEnum(employmentStatus,
                    Set.of("ACTIVE", "LEAVE", "NOTICE", "INACTIVE"), "employmentStatus");
            Objects.requireNonNull(organizationLabel, "organizationLabel");
            requireCount(pendingActionCount, "pendingActionCount");
        }

        @Override
        public Map<String, Decision> decisions() {
            return orderedDecisions(
                    "employmentStatus", employmentStatus,
                    "organizationLabel", organizationLabel,
                    "pendingActionCount", pendingActionCount);
        }
    }

    public record PerformanceHomeData(
            WidgetType kind,
            TextProjection goalProgress,
            TextProjection reviewStatus,
            NumberProjection pendingActionCount) implements HomeData {
        public PerformanceHomeData {
            requireKind(WidgetType.PERFORMANCE_SUMMARY, kind);
            requireTextEnum(goalProgress, Set.of(
                    "NOT_STARTED", "ON_TRACK", "AT_RISK", "COMPLETE", "NOT_APPLICABLE"),
                    "goalProgress");
            requireTextEnum(reviewStatus, Set.of(
                    "NOT_STARTED", "SELF_REVIEW", "MANAGER_REVIEW", "CALIBRATION",
                    "RELEASED", "NOT_APPLICABLE"), "reviewStatus");
            requireCount(pendingActionCount, "pendingActionCount");
        }

        @Override
        public Map<String, Decision> decisions() {
            return orderedDecisions(
                    "goalProgress", goalProgress,
                    "reviewStatus", reviewStatus,
                    "pendingActionCount", pendingActionCount);
        }
    }

    public record TimeLeaveHomeData(
            WidgetType kind,
            TextProjection clockState,
            NumberProjection leaveBalanceMinutes,
            NumberProjection openTimecardCount,
            NumberProjection pendingApprovalCount) implements HomeData {
        public TimeLeaveHomeData {
            requireKind(WidgetType.TIME_LEAVE_SUMMARY, kind);
            requireTextEnum(clockState, Set.of(
                    "NOT_CLOCKED_IN", "CLOCKED_IN", "ON_BREAK", "CLOCKED_OUT",
                    "NOT_APPLICABLE"), "clockState");
            Objects.requireNonNull(leaveBalanceMinutes, "leaveBalanceMinutes");
            requireCount(openTimecardCount, "openTimecardCount");
            requireCount(pendingApprovalCount, "pendingApprovalCount");
        }

        @Override
        public Map<String, Decision> decisions() {
            return orderedDecisions(
                    "clockState", clockState,
                    "leaveBalanceMinutes", leaveBalanceMinutes,
                    "openTimecardCount", openTimecardCount,
                    "pendingApprovalCount", pendingApprovalCount);
        }
    }

    public record PayrollHomeData(
            WidgetType kind,
            TextProjection payPeriodLabel,
            MoneyProjection grossPay,
            MoneyProjection netPay,
            BooleanProjection payslipAvailable) implements HomeData {
        public PayrollHomeData {
            requireKind(WidgetType.PAYROLL_SUMMARY, kind);
            Objects.requireNonNull(payPeriodLabel, "payPeriodLabel");
            Objects.requireNonNull(grossPay, "grossPay");
            Objects.requireNonNull(netPay, "netPay");
            Objects.requireNonNull(payslipAvailable, "payslipAvailable");
        }

        @Override
        public Map<String, Decision> decisions() {
            return orderedDecisions(
                    "payPeriodLabel", payPeriodLabel,
                    "grossPay", grossPay,
                    "netPay", netPay,
                    "payslipAvailable", payslipAvailable);
        }
    }

    public record FieldDecision(String fieldKey, Decision decision) {
        private static final Set<String> KEYS = Set.of(
                "employmentStatus", "organizationLabel", "pendingActionCount",
                "goalProgress", "reviewStatus", "clockState", "leaveBalanceMinutes",
                "openTimecardCount", "pendingApprovalCount", "payPeriodLabel",
                "grossPay", "netPay", "payslipAvailable");

        public FieldDecision {
            if (!KEYS.contains(fieldKey)) {
                throw invalid("fieldKey is not part of the closed Home profile");
            }
            Objects.requireNonNull(decision, "decision");
        }
    }

    public record PartialFailure(
            Module module,
            String widgetKey,
            BrowserAudience browserAudience,
            OwnerPopulationScope ownerPopulationScope,
            long scopeRevision,
            long fieldPolicyRevision,
            String purposeCode,
            FailureCode code,
            boolean retryable,
            UUID correlationId,
            @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant occurredAt) {
        public PartialFailure {
            Objects.requireNonNull(module, "module");
            if (widgetKey == null || widgetKey.isBlank()) {
                throw invalid("failure widgetKey is required");
            }
            Objects.requireNonNull(browserAudience, "browserAudience");
            Objects.requireNonNull(ownerPopulationScope, "ownerPopulationScope");
            positive(scopeRevision, "failure scopeRevision");
            positive(fieldPolicyRevision, "failure fieldPolicyRevision");
            requireEqual(PURPOSE, purposeCode, "failure purposeCode");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    public static final class Deserializer
            extends JsonDeserializer<HrisHomeContributionMaterializationV1> {
        @Override
        public HrisHomeContributionMaterializationV1 deserialize(
                JsonParser parser, DeserializationContext context) throws IOException {
            try {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode root = parser.readValueAsTree();
                exactObject(root, TOP_LEVEL_FIELDS, "materialization");
                WidgetType widgetType = enumValue(root, "widgetType", WidgetType.class);
                HomeData data = parseData(required(root, "data"), widgetType);
                List<FieldDecision> decisions = parseFieldDecisions(required(root, "fieldDecisions"));
                List<PartialFailure> failures = parseFailures(required(root, "partialFailures"));
                return new HrisHomeContributionMaterializationV1(
                        text(root, "profile"), enumValue(root, "module", Module.class),
                        text(root, "widgetKey"), widgetType,
                        enumValue(root, "browserAudience", BrowserAudience.class),
                        enumValue(root, "ownerPopulationScope", OwnerPopulationScope.class),
                        integer(root, "scopeRevision"), text(root, "populationScopeDigest"),
                        integer(root, "fieldPolicyRevision"), text(root, "purposeCode"),
                        enumValue(root, "state", State.class), instant(root, "generatedAt"),
                        instant(root, "freshUntil"), instant(root, "staleAfter"),
                        text(root, "deepLink"), integer(root, "sourceVersion"),
                        text(root, "payloadRef"), text(root, "payloadDigest"),
                        instant(root, "asOf"), data, decisions, failures);
            } catch (IllegalArgumentException exception) {
                return context.reportInputMismatch(
                        HrisHomeContributionMaterializationV1.class,
                        "invalid %s: %s", PROFILE, exception.getMessage());
            }
        }

        private static HomeData parseData(JsonNode node, WidgetType expectedType) {
            WidgetType kind = enumValue(node, "kind", WidgetType.class);
            if (kind != expectedType) {
                throw invalid("data.kind does not match widgetType");
            }
            return switch (kind) {
                case PEOPLE_SUMMARY -> {
                    exactObject(node, Set.of("kind", "employmentStatus", "organizationLabel",
                            "pendingActionCount"), "PeopleHomeData");
                    yield new PeopleHomeData(kind,
                            textProjection(required(node, "employmentStatus")),
                            textProjection(required(node, "organizationLabel")),
                            numberProjection(required(node, "pendingActionCount")));
                }
                case PERFORMANCE_SUMMARY -> {
                    exactObject(node, Set.of("kind", "goalProgress", "reviewStatus",
                            "pendingActionCount"), "PerformanceHomeData");
                    yield new PerformanceHomeData(kind,
                            textProjection(required(node, "goalProgress")),
                            textProjection(required(node, "reviewStatus")),
                            numberProjection(required(node, "pendingActionCount")));
                }
                case TIME_LEAVE_SUMMARY -> {
                    exactObject(node, Set.of("kind", "clockState", "leaveBalanceMinutes",
                            "openTimecardCount", "pendingApprovalCount"), "TimeLeaveHomeData");
                    yield new TimeLeaveHomeData(kind,
                            textProjection(required(node, "clockState")),
                            numberProjection(required(node, "leaveBalanceMinutes")),
                            numberProjection(required(node, "openTimecardCount")),
                            numberProjection(required(node, "pendingApprovalCount")));
                }
                case PAYROLL_SUMMARY -> {
                    exactObject(node, Set.of("kind", "payPeriodLabel", "grossPay", "netPay",
                            "payslipAvailable"), "PayrollHomeData");
                    yield new PayrollHomeData(kind,
                            textProjection(required(node, "payPeriodLabel")),
                            moneyProjection(required(node, "grossPay")),
                            moneyProjection(required(node, "netPay")),
                            booleanProjection(required(node, "payslipAvailable")));
                }
            };
        }

        private static TextProjection textProjection(JsonNode node) {
            return switch (projectionDecision(node)) {
                case VIEW -> {
                    exactObject(node, Set.of("decision", "value"), "viewed text");
                    yield new ViewedText(Decision.VIEW, text(node, "value"));
                }
                case MASK -> masked(node);
                case OMIT -> omitted(node);
            };
        }

        private static NumberProjection numberProjection(JsonNode node) {
            return switch (projectionDecision(node)) {
                case VIEW -> {
                    exactObject(node, Set.of("decision", "value"), "viewed number");
                    yield new ViewedNumber(Decision.VIEW, integer(node, "value"));
                }
                case MASK -> masked(node);
                case OMIT -> omitted(node);
            };
        }

        private static BooleanProjection booleanProjection(JsonNode node) {
            return switch (projectionDecision(node)) {
                case VIEW -> {
                    exactObject(node, Set.of("decision", "value"), "viewed boolean");
                    JsonNode value = required(node, "value");
                    if (!value.isBoolean()) throw invalid("value must be boolean");
                    yield new ViewedBoolean(Decision.VIEW, value.booleanValue());
                }
                case MASK -> masked(node);
                case OMIT -> omitted(node);
            };
        }

        private static MoneyProjection moneyProjection(JsonNode node) {
            return switch (projectionDecision(node)) {
                case VIEW -> {
                    exactObject(node, Set.of("decision", "amount", "currency"), "viewed money");
                    yield new ViewedMoney(Decision.VIEW,
                            text(node, "amount"), text(node, "currency"));
                }
                case MASK -> masked(node);
                case OMIT -> omitted(node);
            };
        }

        private static Decision projectionDecision(JsonNode node) {
            if (node == null || !node.isObject()) throw invalid("projection must be an object");
            return enumValue(node, "decision", Decision.class);
        }

        private static MaskedField masked(JsonNode node) {
            exactObject(node, Set.of("decision", "maskedDisplay"), "masked field");
            return new MaskedField(Decision.MASK, text(node, "maskedDisplay"));
        }

        private static OmittedField omitted(JsonNode node) {
            exactObject(node, Set.of("decision"), "omitted field");
            return new OmittedField(Decision.OMIT);
        }

        private static List<FieldDecision> parseFieldDecisions(JsonNode node) {
            if (!node.isArray()) throw invalid("fieldDecisions must be an array");
            List<FieldDecision> result = new ArrayList<>();
            for (JsonNode value : node) {
                exactObject(value, Set.of("fieldKey", "decision"), "field decision");
                result.add(new FieldDecision(text(value, "fieldKey"),
                        enumValue(value, "decision", Decision.class)));
            }
            return result;
        }

        private static List<PartialFailure> parseFailures(JsonNode node) {
            if (!node.isArray()) throw invalid("partialFailures must be an array");
            List<PartialFailure> result = new ArrayList<>();
            Set<String> fields = Set.of("module", "widgetKey", "browserAudience",
                    "ownerPopulationScope", "scopeRevision", "fieldPolicyRevision",
                    "purposeCode", "code", "retryable", "correlationId", "occurredAt");
            for (JsonNode value : node) {
                exactObject(value, fields, "partial failure");
                JsonNode retryable = required(value, "retryable");
                if (!retryable.isBoolean()) throw invalid("retryable must be boolean");
                result.add(new PartialFailure(
                        enumValue(value, "module", Module.class), text(value, "widgetKey"),
                        enumValue(value, "browserAudience", BrowserAudience.class),
                        enumValue(value, "ownerPopulationScope", OwnerPopulationScope.class),
                        integer(value, "scopeRevision"), integer(value, "fieldPolicyRevision"),
                        text(value, "purposeCode"),
                        enumValue(value, "code", FailureCode.class), retryable.booleanValue(),
                        uuid(value, "correlationId"), instant(value, "occurredAt")));
            }
            return result;
        }
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        if (values == null) throw invalid(name + " is required");
        return List.copyOf(values);
    }

    private static Map<String, Decision> orderedDecisions(Object... values) {
        Map<String, Decision> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            String key = (String) values[index];
            Projection projection = Objects.requireNonNull(
                    (Projection) values[index + 1], key + " projection");
            result.put(key, projection.decision());
        }
        return Map.copyOf(result);
    }

    private static void requireTextEnum(
            TextProjection projection, Set<String> allowed, String field) {
        Objects.requireNonNull(projection, field);
        if (projection instanceof ViewedText viewed && !allowed.contains(viewed.value())) {
            throw invalid(field + " is not a closed canonical value");
        }
    }

    private static void requireCount(NumberProjection projection, String field) {
        Objects.requireNonNull(projection, field);
        if (projection instanceof ViewedNumber viewed && viewed.value() > 999) {
            throw invalid(field + " exceeds the closed count range");
        }
    }

    private static void requireKind(WidgetType expected, WidgetType actual) {
        if (actual != expected) throw invalid("data.kind does not match its closed data type");
    }

    private static void requireDecision(Decision expected, Decision actual, String type) {
        if (actual != expected) throw invalid(type + " has an invalid decision discriminant");
    }

    private static void requireEqual(String expected, String actual, String field) {
        if (!expected.equals(actual)) throw invalid(field + " must equal " + expected);
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw invalid(field + " must be a lowercase SHA-256 digest");
        }
    }

    private static void positive(long value, String field) {
        if (value < 1) throw invalid(field + " must be positive");
    }

    private static boolean safeText(String value) {
        if (value == null || value.isBlank() || value.length() > 120
                || !value.equals(value.trim()) || value.indexOf('<') >= 0
                || value.indexOf('>') >= 0) return false;
        return value.codePoints().noneMatch(Character::isISOControl);
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) throw invalid("missing required field " + field);
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) throw invalid(field + " must be text");
        return value.textValue();
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " must be an integer");
        }
        return value.longValue();
    }

    private static Instant instant(JsonNode node, String field) {
        return Instant.parse(text(node, field));
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(text(node, field));
    }

    private static <E extends Enum<E>> E enumValue(
            JsonNode node, String field, Class<E> enumType) {
        String value = text(node, field);
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " is not a closed canonical value");
        }
    }

    private static void exactObject(JsonNode node, Set<String> fields, String name) {
        if (node == null || !node.isObject()) throw invalid(name + " must be an object");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            Set<String> unknown = new HashSet<>(actual);
            unknown.removeAll(fields);
            Set<String> missing = new HashSet<>(fields);
            missing.removeAll(actual);
            throw invalid(name + " has unknown=" + unknown + " missing=" + missing);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
