package com.dwp.services.people.hris.performance.per004;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per004PayloadCodecTest {
    private static final String POLICY = "dwp.hris.performance.policy.v1";
    private static final String FORM = "dwp.hris.performance.review-form.v1";
    private static final String SCALE = "dwp.hris.performance.rating-scale.v1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Per004PayloadCodec codec = new Per004PayloadCodec(mapper);
    private final String formId = UUID.randomUUID().toString();
    private final String scaleId = UUID.randomUUID().toString();

    @Test
    void policyCanonicalDigestIgnoresObjectOrderButBindsExactImmutableReferences() throws Exception {
        String first = """
                {"schemaVersion":1,"form":{"artifactPublicId":"%s","payloadDigest":"%s"},
                 "ratingScale":{"artifactPublicId":"%s","payloadDigest":"%s"},
                 "minimumReviewers":2,"allowSelfReview":false}
                """.formatted(formId, "a".repeat(64), scaleId, "b".repeat(64));
        String reordered = """
                {"allowSelfReview":false,"minimumReviewers":2,
                 "ratingScale":{"payloadDigest":"%s","artifactPublicId":"%s"},
                 "form":{"payloadDigest":"%s","artifactPublicId":"%s"},"schemaVersion":1}
                """.formatted("b".repeat(64), scaleId, "a".repeat(64), formId);
        var one = codec.canonicalize(POLICY, mapper.readTree(first));
        var two = codec.canonicalize(POLICY, mapper.readTree(reordered));
        assertThat(one.digest()).isEqualTo(two.digest());
        assertThat(one.bytes()).containsExactly(two.bytes());
        assertThat(one.digest()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> codec.canonicalize(POLICY,
                mapper.readTree(first.replace("\"minimumReviewers\":2", "\"minimumReviewers\":0"))))
                .hasMessage("PER004_TYPED_PAYLOAD_INVALID");
        assertThatThrownBy(() -> codec.canonicalize(POLICY,
                mapper.readTree(first.replace("\"allowSelfReview\":false", "\"allowSelfReview\":false,\"extra\":1"))))
                .hasMessage("PER004_TYPED_PAYLOAD_INVALID");
    }

    @Test
    void formAndScaleAreClosedVersionedAndRequireExactTemplateEvidence() throws Exception {
        String template = UUID.randomUUID().toString();
        var form = codec.canonicalize(FORM, mapper.readTree("""
                {"schemaVersion":1,"templateVersionId":"%s","templateDigest":"%s","title":"Review",
                 "sections":[{"key":"outcomes","title":"Outcomes","items":[
                   {"key":"impact","label":"Impact","answerKind":"TEXT","required":true}]}]}
                """.formatted(template, "c".repeat(64))));
        var scale = codec.canonicalize(SCALE, mapper.readTree("""
                {"schemaVersion":1,"templateVersionId":"%s","templateDigest":"%s","title":"Rating",
                 "levels":[{"key":"low","label":"Low","score":1},{"key":"high","label":"High","score":5}]}
                """.formatted(template, "c".repeat(64))));
        assertThat(form.digest()).isNotEqualTo(scale.digest());
        assertThatThrownBy(() -> codec.canonicalize(SCALE, mapper.readTree("""
                {"schemaVersion":1,"templateVersionId":"%s","templateDigest":"%s","title":"Rating",
                 "levels":[{"key":"low","label":"Low","score":1},{"key":"low","label":"High","score":5}]}
                """.formatted(template, "c".repeat(64))))).hasMessage("PER004_TYPED_PAYLOAD_INVALID");
        assertThatThrownBy(() -> codec.canonicalize(FORM, mapper.readTree("{}")))
                .hasMessage("PER004_TYPED_PAYLOAD_INVALID");
        assertThatThrownBy(() -> codec.canonicalize(SCALE, mapper.readTree("""
                {"schemaVersion":4294967297,"templateVersionId":"%s","templateDigest":"%s",
                 "title":"Rating","levels":[{"key":"low","label":"Low","score":1},
                   {"key":"high","label":"High","score":5}]}
                """.formatted(template, "c".repeat(64)))))
                .hasMessage("PER004_TYPED_PAYLOAD_INVALID");
    }
}
