package com.dwp.services.people.hris.performance.per008;

import com.dwp.core.crypto.EnvelopeCiphertext;
import com.dwp.core.crypto.EnvelopeCiphertextCodec;
import com.dwp.core.crypto.EnvelopeEncryptionService;
import com.dwp.core.crypto.KeyContext;
import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.ProtectedContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Local authenticated-envelope wrapper. It has no plaintext fallback and no key material.
 * A tenant-keyed digest provider is deliberately a required production binding.
 */
@Component
final class Per008Protection {
    interface TenantDigestKeyProvider {
        SignedDigest sign(long tenantId, byte[] canonicalContent);
        byte[] canonicalHmac(long tenantId, UUID keyVersionPublicId, byte[] canonicalContent);
    }
    record SignedDigest(String hmacSha256, UUID keyVersionPublicId) { }

    private final ObjectMapper mapper;
    private final ObjectProvider<EnvelopeEncryptionService> encryption;
    private final ObjectProvider<TenantDigestKeyProvider> digests;
    private final String environment;
    private final EnvelopeCiphertextCodec codec = new EnvelopeCiphertextCodec();

    Per008Protection(ObjectMapper mapper,
            ObjectProvider<EnvelopeEncryptionService> encryption,
            ObjectProvider<TenantDigestKeyProvider> digests,
            @Value("${dwp.environment:local}") String environment) {
        this.mapper = mapper;
        this.encryption = encryption;
        this.digests = digests;
        String value = environment == null ? "local" : environment.toLowerCase(Locale.ROOT).trim();
        this.environment = value.startsWith("prod") ? "prod"
                : value.startsWith("qa") || value.startsWith("stage") ? "qa"
                : value.startsWith("dev") ? "dev" : "local";
    }

    byte[] canonicalResponse(FeedbackResponse response, List<Question> questions) {
        validateResponse(response, questions);
        return canonicalResponseUnchecked(response);
    }

    byte[] canonicalResponseForReplay(FeedbackResponse response) {
        validateResponseShape(response);
        return canonicalResponseUnchecked(response);
    }

    private byte[] canonicalResponseUnchecked(FeedbackResponse response) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaKey", "PerformanceFeedbackResponse.v1");
        ArrayNode answers = root.putArray("answers");
        response.answers().stream().sorted(Comparator.comparing(FeedbackAnswer::questionPublicId))
                .forEach(answer -> {
                    ObjectNode out = answers.addObject();
                    out.put("questionPublicId", answer.questionPublicId().toString());
                    out.put("answerType", answer.answerType().name());
                    if (answer.textValue() == null) out.putNull("textValue");
                    else out.put("textValue", normalize(answer.textValue(), 20_000));
                    if (answer.numberValue() == null) out.putNull("numberValue");
                    else out.put("numberValue", answer.numberValue().stripTrailingZeros());
                    ArrayNode choices = out.putArray("choiceOptionPublicIds");
                    answer.choiceOptionPublicIds().stream().sorted()
                            .forEach(id -> choices.add(id.toString()));
                });
        return bytes(root);
    }

    byte[] canonicalAggregate(String title, List<ReleaseSection> sections) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaKey", "PerformanceFeedbackAggregate.v1");
        root.put("title", normalize(title, 240));
        ArrayNode array = root.putArray("sections");
        sections.stream().sorted(Comparator.comparing(ReleaseSection::sectionPublicId))
                .forEach(section -> {
                    ObjectNode out = array.addObject();
                    out.put("sectionPublicId", section.sectionPublicId().toString());
                    out.put("label", normalize(section.label(), 512));
                    // v1 intentionally has no narrative-synthesis authority. Never persist
                    // concatenated per-response text or response boundaries in the snapshot.
                    out.putNull("narrative");
                    if (section.score() == null) out.putNull("score");
                    else out.put("score", section.score().stripTrailingZeros());
                });
        return bytes(root);
    }

    ProtectedContent protect(long tenantId, UUID resourceId, UUID requestId,
            String templateContentHash, String contentType, byte[] canonical) {
        requireProtectedBinding(resourceId, requestId, templateContentHash, contentType);
        EnvelopeCiphertext envelope = requireEncryption().encrypt(canonical,
                context(tenantId, resourceId, requestId, templateContentHash, contentType));
        validateCoreEnvelope(envelope);
        SignedDigest signed = requireDigests().sign(tenantId, canonical);
        if (signed == null || signed.keyVersionPublicId() == null
                || signed.hmacSha256() == null
                || !signed.hmacSha256().matches("^[0-9a-f]{64}$")) {
            throw Per008Problem.unavailable("PER008_DIGEST_KEY_UNAVAILABLE");
        }
        byte[] combined = envelope.ciphertext();
        if (combined.length < 17) throw Per008Problem.unavailable("PER008_ENCRYPTION_UNAVAILABLE");
        byte[] tag = java.util.Arrays.copyOfRange(combined, combined.length - 16, combined.length);
        String encodedValue = codec.encode(envelope);
        if (!encodedValue.startsWith(EnvelopeCiphertextCodec.PREFIX)) {
            throw Per008Problem.unavailable("PER008_ENCRYPTION_UNAVAILABLE");
        }
        byte[] encoded = encodedValue.getBytes(StandardCharsets.US_ASCII);
        return new ProtectedContent(encoded, envelope.nonce(), tag, keyId(envelope),
                signed.hmacSha256(), signed.keyVersionPublicId());
    }

    byte[] reveal(long tenantId, UUID resourceId, UUID requestId,
            String templateContentHash, String contentType, ProtectedContent content) {
        requireProtectedBinding(resourceId, requestId, templateContentHash, contentType);
        EnvelopeCiphertext envelope;
        try {
            envelope = codec.decode(new String(content.encodedEnvelope(), StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException invalid) {
            throw Per008Problem.unavailable("PER008_PROTECTED_CONTENT_INVALID");
        }
        validateCoreEnvelope(envelope);
        byte[] combined = envelope.ciphertext();
        byte[] tag = java.util.Arrays.copyOfRange(combined, combined.length - 16, combined.length);
        if (!keyId(envelope).equals(content.encryptionKeyVersionPublicId())
                || !MessageDigest.isEqual(envelope.nonce(), content.nonce())
                || !MessageDigest.isEqual(tag, content.authenticationTag())) {
            throw Per008Problem.unavailable("PER008_PROTECTED_CONTENT_INVALID");
        }
        byte[] plaintext;
        try {
            plaintext = requireEncryption().decrypt(envelope,
                    context(tenantId, resourceId, requestId, templateContentHash, contentType));
        } catch (RuntimeException unavailable) {
            throw Per008Problem.unavailable("PER008_ENCRYPTION_UNAVAILABLE");
        }
        if (!matchesStoredCanonicalHmac(tenantId, plaintext,
                content.digestKeyVersionPublicId(), content.canonicalContentHmac())) {
            throw Per008Problem.unavailable("PER008_PROTECTED_CONTENT_INVALID");
        }
        return plaintext;
    }

    boolean matchesStoredCanonicalHmac(long tenantId, byte[] canonicalContent,
            UUID digestKeyVersionPublicId, String expectedHmac) {
        if (tenantId < 1 || canonicalContent == null || digestKeyVersionPublicId == null
                || expectedHmac == null || !expectedHmac.matches("^[0-9a-f]{64}$")) {
            throw Per008Problem.unavailable("PER008_DIGEST_BINDING_INVALID");
        }
        byte[] expected;
        byte[] actual;
        try {
            expected = HexFormat.of().parseHex(expectedHmac);
            actual = requireDigests().canonicalHmac(
                    tenantId, digestKeyVersionPublicId, canonicalContent);
        } catch (RuntimeException unavailable) {
            throw Per008Problem.unavailable("PER008_DIGEST_KEY_UNAVAILABLE");
        }
        if (actual == null || actual.length != expected.length) {
            throw Per008Problem.unavailable("PER008_DIGEST_KEY_UNAVAILABLE");
        }
        return MessageDigest.isEqual(actual, expected);
    }

    JsonNode revealV19Template(long tenantId, UUID artifactId, byte[] encrypted, UUID expectedKeyId) {
        EnvelopeCiphertext envelope;
        try {
            envelope = codec.decode(new String(encrypted, StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException invalid) {
            throw Per008Problem.unavailable("PER008_TEMPLATE_UNAVAILABLE");
        }
        if (!keyId(envelope).equals(expectedKeyId)) {
            throw Per008Problem.unavailable("PER008_TEMPLATE_UNAVAILABLE");
        }
        try {
            byte[] bytes = requireEncryption().decrypt(envelope,
                    new KeyContext(environment, "dwp-people-server", "HRIS_PER_TYPED_ARTIFACT",
                            tenantId, "PERFORMANCE_REVIEW_FORM", artifactId.toString(),
                            "canonical-content"));
            return mapper.readTree(bytes);
        } catch (IOException | RuntimeException unavailable) {
            throw Per008Problem.unavailable("PER008_TEMPLATE_UNAVAILABLE");
        }
    }

    List<Question> questions(UUID artifactId, JsonNode template) {
        if (template == null || !template.isObject() || template.path("schemaVersion").asInt(-1) != 1
                || !template.path("sections").isArray()) {
            throw Per008Problem.unavailable("PER008_TEMPLATE_UNAVAILABLE");
        }
        List<Question> questions = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode section : template.path("sections")) {
            if (!section.isObject() || !section.path("key").isTextual()
                    || !section.path("items").isArray()) throw Per008Problem.unavailable(
                    "PER008_TEMPLATE_UNAVAILABLE");
            String sectionKey = section.path("key").asText();
            for (JsonNode item : section.path("items")) {
                String itemKey = item.path("key").asText(null);
                String prompt = item.path("label").asText(null);
                String kind = item.path("answerKind").asText(null);
                if (itemKey == null || prompt == null || !item.path("required").isBoolean()) {
                    throw Per008Problem.unavailable("PER008_TEMPLATE_UNAVAILABLE");
                }
                AnswerType type = switch (kind) {
                    case "TEXT" -> AnswerType.TEXT;
                    case "DECIMAL" -> AnswerType.NUMBER;
                    // V19 form v1 has neither a sealed rating scale nor choice-option binding.
                    default -> throw Per008Problem.unavailable("PER008_TEMPLATE_CHOICE_AUTHORITY_UNAVAILABLE");
                };
                UUID id = UUID.nameUUIDFromBytes((artifactId + ":" + sectionKey + ":" + itemKey)
                        .getBytes(StandardCharsets.UTF_8));
                if (!ids.add(id)) throw Per008Problem.unavailable("PER008_TEMPLATE_UNAVAILABLE");
                questions.add(new Question(id, normalize(prompt, 4_000), type,
                        item.path("required").booleanValue(), List.of()));
            }
        }
        if (questions.isEmpty() || questions.size() > 100) {
            throw Per008Problem.unavailable("PER008_TEMPLATE_UNAVAILABLE");
        }
        return List.copyOf(questions);
    }

    JsonNode json(byte[] canonical) {
        try { return mapper.readTree(canonical); }
        catch (IOException invalid) { throw Per008Problem.unavailable("PER008_PROTECTED_CONTENT_INVALID"); }
    }

    String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void validateResponse(FeedbackResponse response, List<Question> questions) {
        validateResponseShape(response);
        if (questions == null || questions.isEmpty()) {
            throw Per008Problem.invalid("PER008_RESPONSE_INVALID");
        }
        var byId = questions.stream().collect(java.util.stream.Collectors.toMap(
                Question::questionPublicId, value -> value));
        Set<UUID> answered = new HashSet<>();
        for (FeedbackAnswer answer : response.answers()) {
            Question question = answer == null ? null : byId.get(answer.questionPublicId());
            if (question == null || !answered.add(answer.questionPublicId())
                    || answer.answerType() != question.answerType()) {
                throw Per008Problem.invalid("PER008_RESPONSE_INVALID");
            }
            validateAnswer(answer, question);
        }
        if (questions.stream().filter(Question::required)
                .anyMatch(question -> !answered.contains(question.questionPublicId()))) {
            throw Per008Problem.invalid("PER008_RESPONSE_REQUIRED_ANSWER_MISSING");
        }
    }

    private void validateResponseShape(FeedbackResponse response) {
        if (response == null || !"PerformanceFeedbackResponse.v1".equals(response.schemaKey())
                || response.answers() == null || response.answers().isEmpty()
                || response.answers().size() > 100) {
            throw Per008Problem.invalid("PER008_RESPONSE_INVALID");
        }
        Set<UUID> answered = new HashSet<>();
        for (FeedbackAnswer answer : response.answers()) {
            if (answer == null || answer.questionPublicId() == null || answer.answerType() == null
                    || !answered.add(answer.questionPublicId())) {
                throw Per008Problem.invalid("PER008_RESPONSE_INVALID");
            }
            validateAnswer(answer, null);
        }
    }

    private void validateAnswer(FeedbackAnswer answer, Question question) {
        List<UUID> choices = answer.choiceOptionPublicIds();
        boolean sortedUnique = choices != null && choices.stream().noneMatch(java.util.Objects::isNull)
                && new HashSet<>(choices).size() == choices.size()
                && choices.equals(choices.stream().sorted().toList());
        boolean valid = switch (answer.answerType()) {
            case TEXT -> answer.textValue() != null && !answer.textValue().isBlank()
                    && answer.numberValue() == null && choices.isEmpty();
            case NUMBER -> answer.textValue() == null && answer.numberValue() != null
                    && choices.isEmpty();
            case SINGLE_CHOICE -> answer.textValue() == null && answer.numberValue() == null
                    && sortedUnique && choices.size() == 1
                    && (question == null || question.choiceOptions().stream().anyMatch(option ->
                    option.choiceOptionPublicId().equals(choices.getFirst())));
            case MULTI_CHOICE -> answer.textValue() == null && answer.numberValue() == null
                    && sortedUnique && !choices.isEmpty()
                    && (question == null || question.choiceOptions().stream()
                    .map(ChoiceOption::choiceOptionPublicId)
                    .collect(java.util.stream.Collectors.toSet()).containsAll(choices));
        };
        if (!valid) throw Per008Problem.invalid("PER008_RESPONSE_INVALID");
    }

    private EnvelopeEncryptionService requireEncryption() {
        EnvelopeEncryptionService value = encryption.getIfAvailable();
        if (value == null) throw Per008Problem.unavailable("PER008_ENCRYPTION_UNAVAILABLE");
        return value;
    }
    private TenantDigestKeyProvider requireDigests() {
        TenantDigestKeyProvider value = digests.getIfAvailable();
        if (value == null) throw Per008Problem.unavailable("PER008_DIGEST_KEY_UNAVAILABLE");
        return value;
    }
    private KeyContext context(long tenantId, UUID resourceId, UUID requestId,
            String templateContentHash, String type) {
        // Domain envelope v1/AES_256_GCM is intentionally persisted around the core dwp2/A256GCM
        // envelope; this composite resource ID is the sealed PER-008 AAD binding.
        String binding = "v1:" + requestId + ':' + resourceId + ':' + templateContentHash;
        return new KeyContext(environment, "dwp-people-server", "HRIS_PER_FEEDBACK_V1",
                tenantId, type, binding, "protected-content");
    }

    private void requireProtectedBinding(UUID resourceId, UUID requestId,
            String templateContentHash, String contentType) {
        if (resourceId == null || requestId == null || contentType == null || contentType.isBlank()
                || templateContentHash == null
                || !templateContentHash.matches("^[0-9a-f]{64}$")) {
            throw Per008Problem.unavailable("PER008_PROTECTED_BINDING_INVALID");
        }
    }

    private void validateCoreEnvelope(EnvelopeCiphertext envelope) {
        if (envelope == null || envelope.formatVersion() != EnvelopeCiphertext.FORMAT_VERSION
                || !EnvelopeCiphertext.CONTENT_ALGORITHM.equals(envelope.contentAlgorithm())
                || !KeyContext.AAD_PROFILE.equals(envelope.aadProfile())
                || envelope.nonce().length != 12 || envelope.ciphertext().length < 16) {
            throw Per008Problem.unavailable("PER008_PROTECTED_CONTENT_INVALID");
        }
    }
    private UUID keyId(EnvelopeCiphertext envelope) {
        String identity = envelope.keySlot().provider() + ':' + envelope.keySlot().immutableKeyId()
                + ':' + String.valueOf(envelope.keySlot().keyVersion());
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
    private byte[] bytes(JsonNode node) {
        try { return mapper.writeValueAsBytes(node); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String normalize(String value, int maximum) {
        if (value == null) throw Per008Problem.invalid("PER008_CONTENT_INVALID");
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > maximum
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw Per008Problem.invalid("PER008_CONTENT_INVALID");
        }
        return normalized;
    }
}
