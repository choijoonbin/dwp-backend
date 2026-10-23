package com.dwp.services.people.hris.performance.per008;

import com.dwp.core.crypto.EnvelopeEncryptionService;
import com.dwp.core.crypto.EnvelopeCiphertextCodec;
import com.dwp.core.crypto.KeyContext;
import com.dwp.core.crypto.KeyProvider;
import com.dwp.core.crypto.KeySlot;
import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.ProtectedContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per008ProtectedNarrativeTest {
    private static final UUID REQUEST = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNMENT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID QUESTION = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final String TEMPLATE_HASH = "a".repeat(64);

    @Test
    void bindsCiphertextToTenantRequestAssignmentTemplateAndDomainEnvelope() {
        RotatingDigest digest = new RotatingDigest();
        Per008Protection protection = protection(digest);
        byte[] canonical = protection.canonicalResponse(new FeedbackResponse(
                "PerformanceFeedbackResponse.v1", List.of(new FeedbackAnswer(
                QUESTION, AnswerType.TEXT, "  좋은 성과  ", null, List.of()))),
                List.of(new Question(QUESTION, "의견", AnswerType.TEXT, true, List.of())));

        ProtectedContent protectedContent = protection.protect(7, ASSIGNMENT, REQUEST,
                TEMPLATE_HASH, "PERFORMANCE_FEEDBACK_RESPONSE", canonical);

        assertThat(new String(protectedContent.encodedEnvelope(), StandardCharsets.US_ASCII))
                .startsWith(EnvelopeCiphertextCodec.PREFIX).doesNotContain("좋은 성과");
        assertThat(protectedContent.nonce()).hasSize(12);
        assertThat(protectedContent.authenticationTag()).hasSize(16);
        assertThat(protection.reveal(7, ASSIGNMENT, REQUEST, TEMPLATE_HASH,
                "PERFORMANCE_FEEDBACK_RESPONSE", protectedContent)).isEqualTo(canonical);

        assertThatThrownBy(() -> protection.reveal(7, UUID.randomUUID(), REQUEST, TEMPLATE_HASH,
                "PERFORMANCE_FEEDBACK_RESPONSE", protectedContent)).isInstanceOf(Per008Problem.class);
        assertThatThrownBy(() -> protection.reveal(7, ASSIGNMENT, UUID.randomUUID(), TEMPLATE_HASH,
                "PERFORMANCE_FEEDBACK_RESPONSE", protectedContent)).isInstanceOf(Per008Problem.class);
        assertThatThrownBy(() -> protection.reveal(7, ASSIGNMENT, REQUEST, "b".repeat(64),
                "PERFORMANCE_FEEDBACK_RESPONSE", protectedContent)).isInstanceOf(Per008Problem.class);
    }

    @Test
    void historicalDigestKeyStillVerifiesAfterRotationAndNarrativesAreNeverAggregated() {
        RotatingDigest digest = new RotatingDigest();
        Per008Protection protection = protection(digest);
        byte[] canonical = "sealed".getBytes(StandardCharsets.UTF_8);
        ProtectedContent content = protection.protect(7, ASSIGNMENT, REQUEST, TEMPLATE_HASH,
                "PERFORMANCE_FEEDBACK_RESPONSE", canonical);

        digest.rotate();

        assertThat(protection.reveal(7, ASSIGNMENT, REQUEST, TEMPLATE_HASH,
                "PERFORMANCE_FEEDBACK_RESPONSE", content)).isEqualTo(canonical);
        JsonNode aggregate = protection.json(protection.canonicalAggregate("Feedback", List.of(
                new ReleaseSection(QUESTION, "의견", "first\n\nsecond", null),
                new ReleaseSection(UUID.randomUUID(), "점수", null, BigDecimal.valueOf(4.5)))));
        assertThat(aggregate.toString()).doesNotContain("first", "second");
        assertThat(aggregate.path("sections").get(0).path("narrative").isNull()).isTrue();
    }

    static Per008Protection protection(RotatingDigest digest) {
        return protection(digest, new InMemoryKeyProvider());
    }

    static Per008Protection protection(RotatingDigest digest, InMemoryKeyProvider keyProvider) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("encryption", new EnvelopeEncryptionService(keyProvider));
        beans.addBean("digest", digest);
        return new Per008Protection(new ObjectMapper(),
                beans.getBeanProvider(EnvelopeEncryptionService.class),
                beans.getBeanProvider(Per008Protection.TenantDigestKeyProvider.class), "local");
    }

    static final class InMemoryKeyProvider implements KeyProvider {
        private int unwrapCalls;
        @Override public String providerId() { return "per008-test"; }
        @Override public KeySlot wrapDek(byte[] plaintextDek, KeyContext context) {
            return new KeySlot(providerId(), "tenant-kek", "1", "TEST-IDENTITY",
                    plaintextDek.clone());
        }
        @Override public byte[] unwrapDek(KeySlot keySlot, KeyContext context) {
            unwrapCalls++;
            return keySlot.wrappedDek();
        }
        int unwrapCalls() { return unwrapCalls; }
    }

    static final class RotatingDigest implements Per008Protection.TenantDigestKeyProvider {
        private static final UUID FIRST = UUID.fromString("40000000-0000-0000-0000-000000000004");
        private static final UUID SECOND = UUID.fromString("50000000-0000-0000-0000-000000000005");
        private final Map<UUID, byte[]> keys = Map.of(
                FIRST, "first-tenant-digest-key-material-32".getBytes(StandardCharsets.UTF_8),
                SECOND, "second-tenant-digest-key-material32".getBytes(StandardCharsets.UTF_8));
        private UUID current = FIRST;
        private UUID lastVerifiedVersion;

        void rotate() { current = SECOND; }

        @Override public Per008Protection.SignedDigest sign(long tenantId, byte[] canonical) {
            return new Per008Protection.SignedDigest(hmac(tenantId, keys.get(current), canonical), current);
        }

        @Override public byte[] canonicalHmac(long tenantId, UUID version, byte[] canonical) {
            lastVerifiedVersion = version;
            byte[] key = keys.get(version);
            return key == null ? null : HexFormat.of().parseHex(hmac(tenantId, key, canonical));
        }

        UUID lastVerifiedVersion() { return lastVerifiedVersion; }

        private String hmac(long tenantId, byte[] key, byte[] canonical) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                mac.update(Long.toString(tenantId).getBytes(StandardCharsets.US_ASCII));
                mac.update((byte) 0);
                return HexFormat.of().formatHex(mac.doFinal(canonical));
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
