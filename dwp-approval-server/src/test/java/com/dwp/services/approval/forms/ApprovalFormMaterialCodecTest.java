package com.dwp.services.approval.forms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.Branch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalFormMaterialCodecTest {
    public record Captured(OffsetDateTime observedAt) { }

    @Test void receiptRoundTripPreservesOffsetAndNanosecondsRegardlessOfInjectedMapperTimestampDefaults() {
        var codec = new ApprovalFormMaterialCodec(new ObjectMapper().findAndRegisterModules(), (ApprovalFormLegacySchemaValidator) null);
        var original = new Captured(OffsetDateTime.parse("2026-09-14T12:53:40.261555123+09:00"));
        var stored = codec.object(codec.json(original));
        assertThat(stored).containsEntry("observedAt", "2026-09-14T12:53:40.261555123+09:00");
        assertThat(codec.project(stored, Captured.class)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1.0", "\"1\"", "true", "9007199254740992", "-1"})
    void authoringRevisionRejectsNonExactOrUnsafeWireNumbers(String raw) {
        var mapper = new ObjectMapper();
        String json = "{\"expectedFormRevision\":" + raw + ",\"expectedWorkspaceRevision\":null}";
        assertThatThrownBy(() -> mapper.readValue(json, Branch.class)).isInstanceOf(JsonProcessingException.class);
    }

    @Test void requiredFormRevisionAndOptionalInitialWorkspaceRevisionRemainDistinct() throws Exception {
        var mapper = new ObjectMapper();
        var valid = mapper.readValue("{\"expectedFormRevision\":0,\"expectedWorkspaceRevision\":null}", Branch.class);
        assertThat(valid.expectedFormRevision()).isZero();
        assertThat(valid.expectedWorkspaceRevision()).isNull();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(valid)).isEmpty();
            assertThat(factory.getValidator().validate(mapper.readValue("{\"expectedFormRevision\":null}", Branch.class)))
                    .isNotEmpty();
        }
    }
}
