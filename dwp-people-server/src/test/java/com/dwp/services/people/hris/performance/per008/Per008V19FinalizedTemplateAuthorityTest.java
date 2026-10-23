package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.AnswerType;
import com.dwp.services.people.hris.performance.per008.Per008Contracts.Question;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per008V19FinalizedTemplateAuthorityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Per008Protection protection = protection();

    @Test
    void derivesStableTenantArtifactBoundQuestionIdsOnlyForSealedTextAndDecimalKinds()
            throws Exception {
        UUID artifact = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var payload = mapper.readTree("""
                {"schemaVersion":1,"sections":[{"key":"growth","items":[
                  {"key":"comment","label":"Comment","answerKind":"TEXT","required":true},
                  {"key":"score","label":"Score","answerKind":"DECIMAL","required":false}
                ]}]}
                """);

        List<Question> first = protection.questions(artifact, payload);
        List<Question> second = protection.questions(artifact, payload);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(Question::answerType)
                .containsExactly(AnswerType.TEXT, AnswerType.NUMBER);
        assertThat(first).extracting(Question::questionPublicId).doesNotHaveDuplicates();
        assertThat(protection.questions(UUID.randomUUID(), payload).getFirst().questionPublicId())
                .isNotEqualTo(first.getFirst().questionPublicId());
    }

    @Test
    void rejectsRatingAndChoiceWhenV19HasNoSealedScaleOrOptionBinding() throws Exception {
        for (String kind : List.of("RATING", "CHOICE")) {
            var payload = mapper.readTree("""
                    {"schemaVersion":1,"sections":[{"key":"s","items":[
                      {"key":"q","label":"Question","answerKind":"%s","required":true}
                    ]}]}
                    """.formatted(kind));
            assertThatThrownBy(() -> protection.questions(UUID.randomUUID(), payload))
                    .isInstanceOf(Per008Problem.class)
                    .hasMessage("PER008_TEMPLATE_CHOICE_AUTHORITY_UNAVAILABLE");
        }
    }

    private Per008Protection protection() {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        return new Per008Protection(mapper,
                empty.getBeanProvider(com.dwp.core.crypto.EnvelopeEncryptionService.class),
                empty.getBeanProvider(Per008Protection.TenantDigestKeyProvider.class), "local");
    }
}
