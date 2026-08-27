package com.dwp.services.platform.widgetregistry.internal.security;

import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryInternalRoutes.Match;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryCommandSemanticBinding.Fields;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryCommandPayloadValidator.Validation;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.CommandTargetBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.OriginalArtifactBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ProviderAssertionClaims;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ReconcileBinding;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds the exact request values that a verified Provider assertion must bind. */
final class WidgetRegistryRequestBinding {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_SEAL_BODY_BYTES = 32 * 1024;
    private static final int MAX_TYPED_CANONICAL_BODY_BYTES = 48 * 1024;
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern NEGATIVE_ZERO = Pattern.compile(
            "^-0(?:\\.0+)?(?:[eE][+-]?[0-9]+)?$");
    private static final Set<String> COMMAND_FIELDS = Set.of(
            "schemaVersion", "commandId", "operationId", "commandType", "target", "payload",
            "publicIdempotencyKey", "publicRequestFingerprint", "expectedVersion", "correlationId",
            "operatorRef", "sessionRef", "permissionSetHash", "sodArtifactIds");
    private static final Set<String> TARGET_FIELDS = Set.of(
            "targetType", "targetId", "definitionId", "versionId", "evidenceId", "controlId", "channel",
            "controlScope", "runtimeTargetType", "runtimeTargetId");
    private static final Set<String> SEAL_FIELDS = Set.of(
            "schemaVersion", "commandId", "operationId", "target", "publicRequestFingerprint",
            "actorRefSha256", "originalServiceTokenSha256", "originalServiceTokenJti",
            "originalServiceTokenExpiresAt", "originalWidgetAssertionSha256", "originalWidgetAssertionJti",
            "originalWidgetAssertionExpiresAt", "providerReceiptCreatedAt", "originalArtifacts");
    private static final Set<String> ORIGINAL_ARTIFACT_FIELDS = Set.of(
            "serviceTokenCompact", "widgetAssertionCompact");

    private final ObjectMapper objectMapper;
    private final ObjectMapper strictObjectMapper;

    WidgetRegistryRequestBinding(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.strictObjectMapper = new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(
                        JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(),
                        JsonReadFeature.ALLOW_YAML_COMMENTS.mappedFeature(),
                        JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(),
                        JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(),
                        JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(),
                        JsonReadFeature.ALLOW_MISSING_VALUES.mappedFeature(),
                        JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS.mappedFeature(),
                        JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature(),
                        JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(),
                        JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    PreparedRequest prepare(HttpServletRequest request, Match match) throws IOException, BindingException {
        validateContentEncoding(request);
        validateRawQuery(request.getQueryString(), match.route().allowedQueryKeys());

        String correlationId = requiredUuidHeader(request, CORRELATION_HEADER);
        String idempotencyKey = resolveIdempotencyKey(request, match);
        int maximumBodyBytes = match.route() == WidgetRegistryInternalRoutes.Route.SEAL_COMMAND_NOT_EXECUTED
                ? MAX_SEAL_BODY_BYTES
                : MAX_BODY_BYTES;
        byte[] body = readBody(request, maximumBodyBytes);
        JsonNode bodyNode = validateBody(request, match, body);
        SealMetadata seal = validateRouteSpecificBody(match, bodyNode);
        CommandMetadata command = commandMetadata(match, bodyNode, idempotencyKey, correlationId);

        String requestTarget = match.actualPath();
        if (request.getQueryString() != null) requestTarget += "?" + request.getQueryString();
        byte[] canonicalBody = bodyNode == null ? new byte[0] : canonicalJson(body);
        if (match.route() == WidgetRegistryInternalRoutes.Route.EXECUTE_COMMAND
                && canonicalBody.length > MAX_TYPED_CANONICAL_BODY_BYTES) {
            throw new BindingException(WidgetRegistryIngressFailure.PAYLOAD_TOO_LARGE);
        }
        ActualBinding binding = new ActualBinding(
                match.method(),
                match.route().pathTemplate(),
                match.actualPath(),
                sha256(requestTarget.getBytes(StandardCharsets.UTF_8)),
                sha256(canonicalBody),
                idempotencyKey,
                correlationId);
        byte[] downstreamBody = bodyNode == null ? body : canonicalBody;
        return new PreparedRequest(
                new WidgetRegistryCachedBodyRequest(request, downstreamBody), binding, command, seal);
    }

    private static void validateContentEncoding(HttpServletRequest request) throws BindingException {
        List<String> values = headerValues(request, "Content-Encoding");
        if (values.size() > 1) throw invalidBinding();
        if (!values.isEmpty() && !"identity".equalsIgnoreCase(values.get(0).trim())) {
            throw invalidBinding();
        }
    }

    private static String resolveIdempotencyKey(HttpServletRequest request, Match match)
            throws BindingException {
        List<String> values = headerValues(request, IDEMPOTENCY_HEADER);
        if (match.route() == WidgetRegistryInternalRoutes.Route.EXECUTE_COMMAND) {
            if (values.size() != 1 || !UUID.matcher(values.get(0)).matches()) throw invalidBinding();
            return values.get(0);
        }
        if (!values.isEmpty()) throw invalidBinding();
        return null;
    }

    private static String requiredUuidHeader(HttpServletRequest request, String name) throws BindingException {
        List<String> values = headerValues(request, name);
        if (values.size() != 1 || !UUID.matcher(values.get(0)).matches()) throw invalidBinding();
        return values.get(0);
    }

    private static List<String> headerValues(HttpServletRequest request, String name) {
        var headers = request.getHeaders(name);
        return headers == null ? List.of() : Collections.list(headers);
    }

    private static void validateRawQuery(String rawQuery, Set<String> allowedKeys) throws BindingException {
        if (rawQuery == null) return;
        if (rawQuery.isEmpty() || rawQuery.indexOf('#') >= 0 || rawQuery.indexOf('\r') >= 0
                || rawQuery.indexOf('\n') >= 0) {
            throw invalidBinding();
        }
        Set<String> seen = new HashSet<>();
        for (String entry : rawQuery.split("&", -1)) {
            if (entry.isEmpty()) throw invalidBinding();
            validatePercentEncoding(entry);
            int equals = entry.indexOf('=');
            String key = equals < 0 ? entry : entry.substring(0, equals);
            if (!key.matches("[A-Za-z][A-Za-z0-9]*") || !allowedKeys.contains(key) || !seen.add(key)) {
                throw invalidBinding();
            }
        }
    }

    private static void validatePercentEncoding(String value) throws BindingException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '%') {
                if (current > 0x7f) throw invalidBinding();
                decoded.write(current);
                continue;
            }
            if (index + 2 >= value.length()) throw invalidBinding();
            int high = Character.digit(value.charAt(index + 1), 16);
            int low = Character.digit(value.charAt(index + 2), 16);
            if (high < 0 || low < 0) {
                throw invalidBinding();
            }
            decoded.write((high << 4) | low);
            index += 2;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded.toByteArray()));
        } catch (CharacterCodingException exception) {
            throw invalidBinding(exception);
        }
    }

    private static byte[] readBody(HttpServletRequest request, int maximumBytes)
            throws IOException, BindingException {
        if (request.getContentLengthLong() > maximumBytes) {
            throw new BindingException(WidgetRegistryIngressFailure.PAYLOAD_TOO_LARGE);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = request.getInputStream().read(buffer)) >= 0) {
            if (read == 0) continue;
            if (output.size() + read > maximumBytes) {
                throw new BindingException(WidgetRegistryIngressFailure.PAYLOAD_TOO_LARGE);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private JsonNode validateBody(HttpServletRequest request, Match match, byte[] body) throws BindingException {
        if ("GET".equals(match.method()) || "HEAD".equals(match.method())) {
            if (body.length != 0) throw invalidBinding();
            return null;
        }
        String contentType = request.getContentType();
        if (!isJsonContentType(contentType)) {
            throw invalidBinding();
        }
        validateUtf8(body);
        try {
            validateRawNumbers(body);
            JsonNode value = strictObjectMapper.readTree(body);
            if (value == null || !value.isObject()) throw invalidBinding();
            validateIJson(value);
            return value;
        } catch (IOException exception) {
            throw invalidBinding(exception);
        }
    }

    private static void validateIJson(JsonNode value) throws BindingException {
        if (value.isObject()) {
            var fields = value.properties().iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                validateUnicodeScalarSequence(field.getKey());
                if (!Normalizer.isNormalized(field.getKey(), Normalizer.Form.NFC)) {
                    throw invalidBinding();
                }
                validateIJson(field.getValue());
            }
            return;
        }
        if (value.isArray()) {
            for (JsonNode item : value) validateIJson(item);
            return;
        }
        if (value.isTextual()) {
            validateUnicodeScalarSequence(value.textValue());
            if (!Normalizer.isNormalized(value.textValue(), Normalizer.Form.NFC)) {
                throw invalidBinding();
            }
            return;
        }
        if (value.isIntegralNumber()) {
            if (value.bigIntegerValue().abs().compareTo(MAX_SAFE_INTEGER) > 0) throw invalidBinding();
            return;
        }
        if (value.isFloatingPointNumber()) {
            double binary64 = value.doubleValue();
            if (!Double.isFinite(binary64)
                    || value.decimalValue().signum() != 0 && binary64 == 0d
                    || value.decimalValue().stripTrailingZeros().scale() <= 0
                    && value.decimalValue().toBigIntegerExact().abs().compareTo(MAX_SAFE_INTEGER) > 0) {
                throw invalidBinding();
            }
            return;
        }
        if (!value.isBoolean() && !value.isNull()) throw invalidBinding();
    }

    private static void validateUnicodeScalarSequence(String value) throws BindingException {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalidBinding();
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw invalidBinding();
            }
        }
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(parsed)
                    && (parsed.getCharset() == null || StandardCharsets.UTF_8.equals(parsed.getCharset()));
        } catch (InvalidMediaTypeException exception) {
            return false;
        }
    }

    private static void validateUtf8(byte[] body) throws BindingException {
        if (body.length == 0 || startsWithUtf8Bom(body)) throw invalidBinding();
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
        } catch (CharacterCodingException exception) {
            throw invalidBinding(exception);
        }
    }

    private void validateRawNumbers(byte[] body) throws BindingException {
        try (JsonParser parser = strictObjectMapper.getFactory().createParser(body)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken().isNumeric()
                        && NEGATIVE_ZERO.matcher(parser.getText()).matches()) {
                    throw invalidBinding();
                }
            }
        } catch (IOException exception) {
            throw invalidBinding(exception);
        }
    }

    private static boolean startsWithUtf8Bom(byte[] body) {
        return body.length >= 3 && body[0] == (byte) 0xef && body[1] == (byte) 0xbb && body[2] == (byte) 0xbf;
    }

    private CommandMetadata commandMetadata(
            Match match,
            JsonNode body,
            String idempotencyKey,
            String correlationId) throws BindingException {
        if (match.route() != WidgetRegistryInternalRoutes.Route.EXECUTE_COMMAND) return null;
        if (!hasExactFields(body, COMMAND_FIELDS)
                || nonNegativeLong(body, "schemaVersion") != 1) {
            throw invalidBinding();
        }
        String commandId = requiredUuid(body, "commandId");
        String operationId = requiredText(body, "operationId");
        String commandType = requiredText(body, "commandType");
        if (!idempotencyKey.equals(requiredText(body, "publicIdempotencyKey"))
                || !correlationId.equals(requiredText(body, "correlationId"))) {
            throw invalidBinding();
        }
        JsonNode payload = body.get("payload");
        if (payload == null || !payload.isObject()) throw invalidBinding();
        long expectedVersion = nonNegativeLong(body, "expectedVersion");
        if (nonNegativeLong(payload, "expectedVersion") != expectedVersion) throw invalidBinding();
        Validation payloadValidation = WidgetRegistryCommandPayloadValidator.validate(
                operationId, commandType, payload);
        String publicRequestFingerprint = requiredSha256(body, "publicRequestFingerprint");
        String operatorRef = requiredOpaque(body, "operatorRef", 128);
        String sessionRef = requiredOpaque(body, "sessionRef", 128);
        String permissionSetHash = requiredSha256(body, "permissionSetHash");
        List<String> sodArtifactIds = sortedOpaqueList(body, "sodArtifactIds", 128, 32);
        CommandTargetBinding target = commandTarget(body.get("target"));
        Fields semanticFields = WidgetRegistryCommandSemanticBinding.preserve(operationId, payload);
        String reasonDigest = reasonDigest(payload);
        return new CommandMetadata(
                commandId,
                operationId,
                commandType,
                target,
                semanticFields,
                payloadValidation.ownerProductKey(),
                expectedVersion,
                publicRequestFingerprint,
                operatorRef,
                sessionRef,
                permissionSetHash,
                sodArtifactIds,
                reasonDigest);
    }

    private static SealMetadata validateRouteSpecificBody(Match match, JsonNode body)
            throws BindingException {
        if (match.route() != WidgetRegistryInternalRoutes.Route.SEAL_COMMAND_NOT_EXECUTED) return null;
        if (!hasExactFields(body, SEAL_FIELDS)
                || nonNegativeLong(body, "schemaVersion") != 1) {
            throw invalidBinding();
        }
        JsonNode artifacts = body.get("originalArtifacts");
        if (!hasExactFields(artifacts, ORIGINAL_ARTIFACT_FIELDS)) throw invalidBinding();
        String serviceToken = validateAsciiCompact(artifacts.get("serviceTokenCompact"), 8192);
        String widgetAssertion = validateAsciiCompact(artifacts.get("widgetAssertionCompact"), 16_384);
        String serviceTokenSha256 = sha256(serviceToken.getBytes(StandardCharsets.US_ASCII));
        String widgetAssertionSha256 = sha256(widgetAssertion.getBytes(StandardCharsets.US_ASCII));
        if (!serviceTokenSha256.equals(requiredSha256(body, "originalServiceTokenSha256"))
                || !widgetAssertionSha256.equals(requiredSha256(body, "originalWidgetAssertionSha256"))) {
            throw invalidBinding();
        }
        JsonNode target = body.get("target");
        if (!hasExactFields(target, Set.of("targetType", "targetId"))) throw invalidBinding();
        OriginalArtifactBinding originalArtifactBinding = new OriginalArtifactBinding(
                serviceTokenSha256,
                requiredOpaque(body, "originalServiceTokenJti", 128),
                requiredInstant(body, "originalServiceTokenExpiresAt"),
                widgetAssertionSha256,
                requiredOpaque(body, "originalWidgetAssertionJti", 128),
                requiredInstant(body, "originalWidgetAssertionExpiresAt"));
        ReconcileBinding reconcile = new ReconcileBinding(
                requiredUuid(body, "commandId"),
                requiredSha256(body, "publicRequestFingerprint"),
                requiredSha256(body, "actorRefSha256"),
                requiredOpaque(body, "operationId", 128),
                requiredOpaque(target, "targetType", 128),
                requiredOpaque(target, "targetId", 128),
                requiredInstant(body, "providerReceiptCreatedAt"),
                originalArtifactBinding);
        return new SealMetadata(reconcile);
    }

    private static String validateAsciiCompact(JsonNode value, int maximumBytes) throws BindingException {
        if (value == null || !value.isTextual()) throw invalidBinding();
        String compact = value.textValue();
        if (compact.isEmpty()
                || compact.length() > maximumBytes
                || compact.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw invalidBinding();
        }
        return compact;
    }

    private static String requiredText(JsonNode body, String field) throws BindingException {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalidBinding();
        return value.textValue();
    }

    private static String requiredUuid(JsonNode body, String field) throws BindingException {
        String value = requiredText(body, field);
        if (!UUID.matcher(value).matches()) throw invalidBinding();
        return value;
    }

    private static String requiredSha256(JsonNode body, String field) throws BindingException {
        String value = requiredText(body, field);
        if (!SHA256.matcher(value).matches()) throw invalidBinding();
        return value;
    }

    private static String requiredOpaque(JsonNode body, String field, int maximumLength)
            throws BindingException {
        String value = requiredText(body, field);
        if (value.codePointCount(0, value.length()) > maximumLength
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw invalidBinding();
        }
        return value;
    }

    private static long nonNegativeLong(JsonNode body, String field) throws BindingException {
        return WidgetRegistryJsonContract.nonNegativeInteger(body, field);
    }

    private static Instant requiredInstant(JsonNode body, String field) throws BindingException {
        String value = requiredText(body, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalidBinding(exception);
        }
    }

    private static List<String> sortedOpaqueList(
            JsonNode body,
            String field,
            int maximumItemLength,
            int maximumItems) throws BindingException {
        JsonNode values = body.get(field);
        if (values == null || !values.isArray() || values.size() > maximumItems) {
            throw invalidBinding();
        }
        List<String> result = new java.util.ArrayList<>(values.size());
        String previous = null;
        for (JsonNode item : values) {
            if (!item.isTextual()) throw invalidBinding();
            String current = item.textValue();
            if (current.isBlank()
                    || current.codePointCount(0, current.length()) > maximumItemLength
                    || current.chars().anyMatch(character -> character < 0x20 || character == 0x7f)
                    || previous != null && previous.compareTo(current) >= 0) {
                throw invalidBinding();
            }
            result.add(current);
            previous = current;
        }
        return List.copyOf(result);
    }

    private static CommandTargetBinding commandTarget(JsonNode target) throws BindingException {
        if (target == null || !target.isObject()) throw invalidBinding();
        Set<String> fields = fieldNames(target);
        if (!fields.containsAll(Set.of("targetType", "targetId")) || !TARGET_FIELDS.containsAll(fields)) {
            throw invalidBinding();
        }
        return new CommandTargetBinding(
                fields,
                requiredOpaque(target, "targetType", 128),
                requiredOpaque(target, "targetId", 128),
                optionalOpaque(target, "definitionId", false),
                optionalOpaque(target, "versionId", false),
                optionalOpaque(target, "evidenceId", false),
                optionalOpaque(target, "controlId", false),
                optionalOpaque(target, "channel", false),
                optionalOpaque(target, "controlScope", false),
                optionalOpaque(target, "runtimeTargetType", false),
                optionalOpaque(target, "runtimeTargetId", true));
    }

    private static String optionalOpaque(JsonNode body, String field, boolean nullable)
            throws BindingException {
        if (!body.has(field)) return null;
        JsonNode value = body.get(field);
        if (nullable && value.isNull()) return null;
        return requiredOpaque(body, field, 128);
    }

    private String reasonDigest(JsonNode payload) throws BindingException {
        var reason = objectMapper.createObjectNode();
        reason.put("reasonCode", requiredOpaque(payload, "reasonCode", 128));
        reason.put("reasonText", requiredOpaque(payload, "reasonText", 4096));
        try {
            return sha256(canonicalJson(objectMapper.writeValueAsBytes(reason)));
        } catch (IOException exception) {
            throw invalidBinding(exception);
        }
    }

    boolean matchesSignedCommand(CommandMetadata actual, ProviderAssertionClaims assertion) {
        if (actual == null || assertion == null || assertion.command() == null) return false;
        var signed = assertion.command();
        try {
            return Objects.equals(signed.commandId(), actual.commandId())
                    && Objects.equals(signed.target(), actual.target())
                    && Objects.equals(signed.expectedVersion(), actual.expectedVersion())
                    && Objects.equals(signed.publicRequestFingerprint(), actual.publicRequestFingerprint())
                    && Objects.equals(signed.reasonDigest(), actual.reasonDigest())
                    && Objects.equals(signed.sodArtifactIds(), actual.sodArtifactIds())
                    && Objects.equals(assertion.actorRef(), actual.operatorRef())
                    && Objects.equals(assertion.sessionRef(), actual.sessionRef())
                    && (actual.ownerProductKey() == null
                    || assertion.ownerProductKeys() != null
                    && assertion.ownerProductKeys().contains(actual.ownerProductKey()))
                    && Objects.equals(authorityHash(assertion), actual.permissionSetHash());
        } catch (BindingException exception) {
            return false;
        }
    }

    private String authorityHash(ProviderAssertionClaims assertion) throws BindingException {
        if (assertion.permissionCodes() == null
                || assertion.ownerProductKeys() == null
                || assertion.providerAuthorityRevision() == null) {
            throw invalidBinding();
        }
        var authority = objectMapper.createObjectNode();
        authority.put("schemaVersion", 1);
        var permissions = authority.putArray("permissionCodes");
        assertion.permissionCodes().forEach(permissions::add);
        var owners = authority.putArray("ownerProductKeys");
        assertion.ownerProductKeys().forEach(owners::add);
        authority.put("providerAuthorityRevision", assertion.providerAuthorityRevision());
        try {
            return sha256(canonicalJson(objectMapper.writeValueAsBytes(authority)));
        } catch (IOException exception) {
            throw invalidBinding(exception);
        }
    }

    private static boolean hasExactFields(JsonNode object, Set<String> expected) {
        return object != null && object.isObject() && fieldNames(object).equals(expected);
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> fields = new HashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        return Set.copyOf(fields);
    }

    static byte[] canonicalJson(byte[] rawJson) throws BindingException {
        try {
            return new JsonCanonicalizer(rawJson).getEncodedUTF8();
        } catch (IOException | RuntimeException exception) {
            throw invalidBinding(exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available.", exception);
        }
    }

    private static BindingException invalidBinding() {
        return new BindingException(WidgetRegistryIngressFailure.REQUEST_BINDING_INVALID);
    }

    private static BindingException invalidBinding(Exception cause) {
        return new BindingException(WidgetRegistryIngressFailure.REQUEST_BINDING_INVALID, cause);
    }

    record ActualBinding(
            String method,
            String pathTemplate,
            String actualPath,
            String requestTargetSha256,
            String bodySha256,
            String idempotencyKey,
            String correlationId) {
    }

    record CommandMetadata(
            String commandId,
            String operationId,
            String commandType,
            CommandTargetBinding target,
            Fields semanticFields,
            String ownerProductKey,
            long expectedVersion,
            String publicRequestFingerprint,
            String operatorRef,
            String sessionRef,
            String permissionSetHash,
            List<String> sodArtifactIds,
            String reasonDigest) {
    }

    record SealMetadata(ReconcileBinding reconcile) {
    }

    record PreparedRequest(
            WidgetRegistryCachedBodyRequest request,
            ActualBinding binding,
            CommandMetadata command,
            SealMetadata seal) {
    }

    static final class BindingException extends Exception {
        private static final long serialVersionUID = 1L;
        private final WidgetRegistryIngressFailure failure;

        BindingException(WidgetRegistryIngressFailure failure) {
            super(failure.message());
            this.failure = failure;
        }

        BindingException(WidgetRegistryIngressFailure failure, Throwable cause) {
            super(failure.message(), cause);
            this.failure = failure;
        }

        WidgetRegistryIngressFailure failure() {
            return failure;
        }
    }

}
