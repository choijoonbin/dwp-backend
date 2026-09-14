package com.dwp.services.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalFormLegacySchemaValidationConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApprovalCommandPayloadSupport original = new ApprovalCommandPayloadSupport(mapper);

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void composesOriginalDtoAndPayloadValidationWithoutChangingLegacyCounter(int counter) {
        var field = new ApprovalDtos.FormFieldInput("summary", "Summary", "Summary", null, null, " text ", true, List.of());
        var expected = new java.util.LinkedHashMap<>(original.formSchema(List.of(field)));
        expected.put("schemaVersion", counter);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var callback = new ApprovalFormLegacySchemaValidationConfig().approvalFormLegacySchemaValidator(mapper, factory.getValidator());
            assertThat(callback.validate(Map.of("schemaVersion", counter, "fields", List.of(field)))).isEqualTo(expected);
        }
    }

    @Test void keepsOriginalOneToFiftyKeyLabelsOptionBoundsAndUniqueSelectRules() {
        var valid = Map.of("key", "summary", "labelKo", "Summary", "labelEn", "Summary", "type", "TEXT", "required", true);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var callback = new ApprovalFormLegacySchemaValidationConfig().approvalFormLegacySchemaValidator(mapper, factory.getValidator());
            for (var fields : List.of(List.of(), java.util.Collections.nCopies(51, valid), List.of(valid, valid),
                    List.of(Map.of("key", "bad key", "labelKo", "S", "labelEn", "S", "type", "TEXT")),
                    List.of(Map.of("key", "summary", "labelKo", "x".repeat(161), "labelEn", "S", "type", "TEXT")),
                    List.of(Map.of("key", "summary", "labelKo", "S", "labelEn", "S", "type", "SELECT", "options", List.of("A", "A"))),
                    List.of(Map.of("key", "summary", "labelKo", "S", "labelEn", "S", "type", "TEXT", "options", List.of("A", "B"))))) {
                assertThatThrownBy(() -> callback.validate(Map.of("schemaVersion", 2, "fields", fields))).isInstanceOf(BaseException.class);
            }
            assertThatThrownBy(() -> callback.validate(Map.of("schemaVersion", 2, "schemaContract", "UNKNOWN", "fields", List.of(valid))))
                    .isInstanceOf(BaseException.class);
        }
    }
}
