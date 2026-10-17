package com.dwp.services.platform.hrisconfiguration.operations;

import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.*;

@Component
@ConditionalOnProperty(
        name = "dwp.hris.operations-exception-center.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HrisOperationalExceptionEventContract {

    static final String TYPE =
            "dwp.hris.operational-exception.projection-changed.v1";
    static final String AGGREGATE = "HRIS_OPERATIONAL_EXCEPTION";
    static final String CONSUMER =
            "dwp-platform-server.hris-operational-exception-v1";
    static final String GROUP =
            "dwp-platform-hris-operational-exception-v1";
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "specVersion", "id", "source", "type", "schemaVersion", "time",
            "subject", "tenantId", "aggregateType", "aggregateId",
            "aggregateSequence", "correlationId", "causationId", "traceParent",
            "data", "extensions");
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "exceptionPublicId", "ownerService", "ownerModule", "sourceRef",
            "sourceRevision", "type", "severity", "state", "code",
            "summaryKey", "ownerRouteKey", "evidenceRefs", "correlationId",
            "occurredAt");
    private static final Pattern TYPE_OR_CODE =
            Pattern.compile("^[A-Z][A-Z0-9._-]{0,79}$");
    private static final Pattern EVIDENCE = Pattern.compile(
            "^urn:dwp:hris:evidence:(audit|command-receipt|domain-event|owner-projection):"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$");

    private final ObjectMapper strictJson;
    private final HrisOperationalExceptionSummaryKeyRegistry summaryKeys;
    private final Map<String, SourceSeal> sources;

    HrisOperationalExceptionEventContract(
            ObjectMapper objectMapper,
            HrisOperationalExceptionSummaryKeyRegistry summaryKeys) {
        this.strictJson = objectMapper.copy()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.summaryKeys = summaryKeys;
        this.sources = sourceSeals();
    }

    ValidatedEvent parse(byte[] bytes) {
        return validate(parseEnvelope(bytes));
    }

    boolean supports(byte[] bytes) {
        JsonNode tree = strictTree(bytes);
        JsonNode type = tree.get("type");
        return type != null && type.isTextual() && TYPE.equals(type.textValue());
    }

    DomainEventEnvelope parseEnvelope(byte[] bytes) {
        try {
            JsonNode tree = strictTree(bytes);
            require(fields(tree).equals(ENVELOPE_FIELDS));
            require(tree.path("extensions").isObject()
                    && tree.path("extensions").isEmpty());
            canonicalUuid(text(tree, "id"));
            canonicalInstant(text(tree, "time"));
            require(tree.path("schemaVersion").isIntegralNumber()
                    && tree.path("schemaVersion").intValue() == 1);
            positiveLong(tree.get("tenantId"));
            positiveLong(tree.get("aggregateSequence"));
            nullableCanonicalUuid(tree.get("causationId"));
            nullableTraceParent(tree.get("traceParent"));
            return strictJson.treeToValue(tree, DomainEventEnvelope.class);
        } catch (java.io.IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegal
                    && "Invalid SYS-EVT-009 envelope".equals(illegal.getMessage())) {
                throw illegal;
            }
            throw invalid();
        }
    }

    void requirePartitionKey(String actual, DomainEventEnvelope event) {
        require(event != null);
        String expected = event.tenantId() + "|" + event.source() + "|"
                + event.aggregateType() + "|" + event.aggregateId();
        require(expected.equals(actual));
    }

    private JsonNode strictTree(byte[] bytes) {
        try {
            String payload = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JsonNode tree = strictJson.readTree(payload);
            require(tree != null && tree.isObject());
            return tree;
        } catch (CharacterCodingException exception) {
            throw invalid();
        } catch (java.io.IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegal
                    && "Invalid SYS-EVT-009 envelope".equals(illegal.getMessage())) {
                throw illegal;
            }
            throw invalid();
        }
    }

    ValidatedEvent validate(DomainEventEnvelope event) {
        require(event != null
                && "1.0".equals(event.specVersion())
                && event.id() != null
                && TYPE.equals(event.type())
                && event.schemaVersion() == 1
                && event.time() != null
                && event.tenantId() != null
                && event.tenantId() > 0
                && AGGREGATE.equals(event.aggregateType())
                && event.aggregateSequence() > 0
                && event.extensions() != null
                && event.extensions().isEmpty()
                && event.data() != null
                && event.data().isObject()
                && fields(event.data()).equals(PAYLOAD_FIELDS));

        SourceSeal source = sources.get(event.source());
        require(source != null);
        JsonNode data = event.data();
        UUID exceptionId = canonicalUuid(text(data, "exceptionPublicId"));
        UUID correlationId = canonicalUuid(text(data, "correlationId"));
        Instant occurredAt = canonicalInstant(text(data, "occurredAt"));
        require(exceptionId.toString().equals(event.aggregateId())
                && (AGGREGATE + "/" + exceptionId).equals(event.subject())
                && correlationId.toString().equals(event.correlationId())
                && occurredAt.equals(event.time()));

        String ownerService = text(data, "ownerService");
        OwnerModule ownerModule = enumValue(
                OwnerModule.class, text(data, "ownerModule"));
        require(source.ownerService().equals(ownerService)
                && source.ownerModule() == ownerModule);

        String sourceRef = text(data, "sourceRef");
        require(sourceRef.length() <= 240
                && source.sourceRef().matcher(sourceRef).matches()
                && sourceRef.indexOf('?') < 0
                && sourceRef.indexOf('#') < 0);
        long revision = positiveLong(data.get("sourceRevision"));
        require(revision == event.aggregateSequence());
        String exceptionType = text(data, "type");
        String code = text(data, "code");
        require(TYPE_OR_CODE.matcher(exceptionType).matches()
                && TYPE_OR_CODE.matcher(code).matches());
        Severity severity = enumValue(Severity.class, text(data, "severity"));
        InternalState state = enumValue(
                InternalState.class, text(data, "state"));
        String summaryKey = text(data, "summaryKey");
        String moduleSegment = ownerModule.name().toLowerCase(java.util.Locale.ROOT);
        require(summaryKey.matches("^hris\\.operations\\.exception\\."
                        + moduleSegment + "\\.[a-z][a-z0-9._-]{0,119}$")
                && summaryKeys.allows(event.source(), code, summaryKey));
        String ownerRouteKey = text(data, "ownerRouteKey");
        require(source.ownerRouteKeys().contains(ownerRouteKey));
        List<String> evidenceRefs = evidenceRefs(data.get("evidenceRefs"));

        String canonical;
        try {
            canonical = strictJson.writeValueAsString(event);
        } catch (java.io.IOException exception) {
            throw invalid();
        }
        return new ValidatedEvent(
                event, exceptionId, ownerService, ownerModule, sourceRef, revision,
                exceptionType, severity, state, code, summaryKey, ownerRouteKey,
                evidenceRefs, correlationId, occurredAt, sha256(canonical), canonical);
    }

    boolean supports(DomainEventEnvelope event) {
        return event != null && TYPE.equals(event.type());
    }

    private List<String> evidenceRefs(JsonNode value) {
        require(value != null && value.isArray()
                && value.size() >= 1 && value.size() <= 20);
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            require(item.isTextual() && EVIDENCE.matcher(item.textValue()).matches());
            result.add(item.textValue());
        });
        List<String> sorted = result.stream().sorted().toList();
        require(result.equals(sorted)
                && result.stream().distinct().count() == result.size());
        return List.copyOf(result);
    }

    private static Map<String, SourceSeal> sourceSeals() {
        Map<String, SourceSeal> result = new LinkedHashMap<>();
        add(result, new SourceSeal(
                OwnerModule.HRM, "dwp-people-server",
                "urn:dwp:people:hrm:operational-exception",
                sourceRef("hrm"), Set.of(
                "route.hcm.operations.employee-change-requests.page",
                "route.hcm.operations.employment-assignments.page",
                "route.hcm.operations.organization-positions.page")));
        add(result, new SourceSeal(
                OwnerModule.PER, "dwp-people-server",
                "urn:dwp:people:performance:operational-exception",
                sourceRef("per"), Set.of(
                "route.hcm.operations.compensation-planning.page",
                "route.hcm.operations.performance-goal-cycles.page",
                "route.hcm.operations.performance-multirater-surveys.page",
                "route.hcm.operations.performance-review-operations.page")));
        add(result, new SourceSeal(
                OwnerModule.PAY, "dwp-payroll-server",
                "urn:dwp:payroll:operational-exception",
                sourceRef("pay"), Set.of(
                "route.hcm.configuration.payroll.page",
                "route.hcm.operations.pay.page",
                "route.hcm.operations.payroll-population.page")));
        add(result, new SourceSeal(
                OwnerModule.SYS, "dwp-platform-server",
                "urn:dwp:platform:hris:operational-exception",
                sourceRef("sys"), Set.of(
                "route.hcm.configuration.navigation-policy-workspace.page",
                "route.hcm.configuration.settings-operations.page",
                "route.hcm.configuration.settings-reference.page",
                "route.hcm.management.controlled-export.page")));
        add(result, new SourceSeal(
                OwnerModule.TIM, "dwp-time-server",
                "urn:dwp:time:operational-exception",
                sourceRef("tim"), Set.of(
                "route.hcm.operations.time-command-center.page",
                "route.hcm.operations.time-daily-close.page",
                "route.hcm.operations.time-exceptions.page",
                "route.hcm.operations.time-interpretation.page",
                "route.hcm.operations.time-monthly-close.page")));
        return Map.copyOf(result);
    }

    private static Pattern sourceRef(String module) {
        return Pattern.compile("^urn:dwp:hris:" + module
                + ":[a-z][a-z0-9-]{0,63}:"
                + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    private static void add(Map<String, SourceSeal> values, SourceSeal seal) {
        values.put(seal.envelopeSource(), seal);
    }

    private static Set<String> fields(JsonNode node) {
        return node.propertyStream().map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual()
                && !value.textValue().isBlank()
                && value.textValue().equals(value.textValue().strip()));
        return value.textValue();
    }

    private static long positiveLong(JsonNode value) {
        require(value != null && value.isIntegralNumber()
                && value.canConvertToLong() && value.longValue() > 0);
        return value.longValue();
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            require(parsed.toString().equals(value));
            return parsed;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static void nullableCanonicalUuid(JsonNode value) {
        if (value == null || value.isNull()) return;
        require(value.isTextual());
        canonicalUuid(value.textValue());
    }

    private static Instant canonicalInstant(String value) {
        try {
            Instant parsed = Instant.parse(value);
            require(parsed.toString().equals(value));
            return parsed;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static void nullableTraceParent(JsonNode value) {
        if (value == null || value.isNull()) return;
        require(value.isTextual());
        String traceParent = value.textValue();
        require(TRACE_PARENT.matcher(traceParent).matches()
                && !traceParent.substring(3, 35).equals("0".repeat(32))
                && !traceParent.substring(36, 52).equals("0".repeat(16)));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid SYS-EVT-009 envelope");
    }

    private record SourceSeal(
            OwnerModule ownerModule,
            String ownerService,
            String envelopeSource,
            Pattern sourceRef,
            Set<String> ownerRouteKeys) {
    }
}

@FunctionalInterface
interface HrisOperationalExceptionSummaryKeyRegistry {
    boolean allows(String eventSource, String code, String summaryKey);
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.hris.operations-exception-center.enabled",
        havingValue = "true",
        matchIfMissing = false)
class HrisOperationalExceptionSummaryKeyConfiguration {

    @Bean
    @ConditionalOnMissingBean(HrisOperationalExceptionSummaryKeyRegistry.class)
    HrisOperationalExceptionSummaryKeyRegistry noLiveProducerSummaryKeys() {
        // The sealed live registry contains zero entries until an owner-specific packet
        // onboards a producer with a finite code-to-summaryKey map.
        return (eventSource, code, summaryKey) -> false;
    }
}
