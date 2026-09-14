package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.field;
import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.schema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ApprovalFormPayloadNormalizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApprovalFormReferenceNormalizer references = mock(ApprovalFormReferenceNormalizer.class);
    private final ApprovalFormPayloadNormalizer.LegacyNormalization legacy = mock(ApprovalFormPayloadNormalizer.LegacyNormalization.class);
    private final ApprovalFormPayloadNormalizer normalizer = new ApprovalFormPayloadNormalizer(mapper, references, legacy);
    private final UUID request = UUID.randomUUID();
    private final UUID formVersion = UUID.randomUUID();
    private final Actor actor = new Actor(99L, 42L, null, "Owner", Set.of(), Set.of());

    @Test
    void delegatesAllLockedTypedPinsAndPreservesPartialMode() throws Exception {
        String json = mapper.writeValueAsString(schema(field("summary", "TEXTAREA")));
        String hash = new ApprovalFormSchemaV2Compiler().compile(schema(field("summary", "TEXTAREA"))).sha256();
        Map<String, Object> input = Map.of("summary", "Ready");
        Map<String, Object> output = Map.of("summary", "Normalized");
        when(references.normalize(actor, request, formVersion, json, input, false, 7, false, false)).thenReturn(output);

        assertThat(normalizer.normalize(actor, request, formVersion, hash, json, input, false, 7)).isSameAs(output);
        verify(references).normalize(actor, request, formVersion, json, input, false, 7, false, false);
        verifyNoInteractions(legacy);
    }

    @Test
    void propagatesCurrentSourceFailureWithoutLegacyFallback() throws Exception {
        var definition = schema(field("summary", "TEXTAREA"));
        String json = mapper.writeValueAsString(definition);
        String hash = new ApprovalFormSchemaV2Compiler().compile(definition).sha256();
        Map<String, Object> input = Map.of("summary", "Ready");
        when(references.normalize(actor, request, formVersion, json, input, true, 7, false, false))
                .thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        assertThatThrownBy(() -> normalizer.normalize(actor, request, formVersion, hash, json, input, true, 7))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(legacy);
    }

    @Test
    void rejectsTypedHashMismatchBeforeAnySourceOrLegacyCall() throws Exception {
        String json = mapper.writeValueAsString(schema(field("summary", "TEXTAREA")));
        assertThatThrownBy(() -> normalizer.normalize(actor, request, formVersion, "a".repeat(64), json, Map.of(), true, 7))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(references, legacy);
    }

    @Test
    void rejectsNonStandardRootSummaryBeforeSourceLookup() throws Exception {
        for (var definition : java.util.List.of(schema(field("amount", "NUMBER")), schema(field("summary", "NUMBER")))) {
            String json = mapper.writeValueAsString(definition);
            String hash = new ApprovalFormSchemaV2Compiler().compile(definition).sha256();
            assertThatThrownBy(() -> normalizer.normalize(actor, request, formVersion, hash, json, Map.of(), true, 7))
                    .isInstanceOf(BaseException.class);
        }
        verifyNoInteractions(references, legacy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"UNKNOWN\"", "\"DWP_APPROVAL_FORM_TYPED_V3\""})
    void rejectsUnknownOrNullMarkersWithoutLegacyFallback(String marker) {
        String json = "{\"schemaContract\":" + marker + ",\"schemaVersion\":2,\"fields\":[]}";
        assertThatThrownBy(() -> normalizer.normalize(actor, request, formVersion, "a".repeat(64), json, Map.of(), true, 7))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(references, legacy);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void delegatesUnmarkedLegacyToActualExistingValidatorWithoutRehashing(int version) {
        var commands = new ApprovalCommandRepository(mock(NamedParameterJdbcTemplate.class), mapper);
        var composed = new ApprovalFormPayloadNormalizer(mapper, references, commands::normalizeRequestPayload);
        String json = "{\"schemaVersion\":" + version + ",\"fields\":[{\"key\":\"summary\",\"type\":\"TEXTAREA\",\"required\":true}]}";
        Map<String, Object> partial = Map.of();
        assertThat(composed.normalize(actor, request, formVersion, "b".repeat(64), json, partial, false, 7)).isSameAs(partial);
        assertThatThrownBy(() -> composed.normalize(actor, request, formVersion, "b".repeat(64), json, partial, true, 7))
                .isInstanceOf(BaseException.class);
        Map<String, Object> full = Map.of("summary", "Ready", "createdFrom", "legacy");
        assertThat(composed.normalize(actor, request, formVersion, "b".repeat(64), json, full, true, 7)).isSameAs(full);
        assertThatThrownBy(() -> composed.normalize(actor, request, formVersion, "b".repeat(64), json,
                Map.of("summary", "Ready", "unknown", "stale"), true, 7)).isInstanceOf(BaseException.class);
        verifyNoInteractions(references);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{", "{\"schemaContract\":null,\"schemaContract\":\"DWP_APPROVAL_FORM_TYPED_V2\"}"})
    void rejectsMalformedOrDuplicateStoredSchema(String json) {
        assertThatThrownBy(() -> normalizer.normalize(actor, request, formVersion, "a".repeat(64), json, Map.of(), true, 7))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(references, legacy);
    }

    @Test
    void rejectsUnsafeRequestVersionAndMissingPinsBeforeDelegation() {
        for (long version : new long[] {-1, 9_007_199_254_740_992L}) {
            assertThatThrownBy(() -> normalizer.normalize(actor, request, formVersion, "a".repeat(64), "{}", Map.of(), true, version))
                    .isInstanceOf(BaseException.class);
        }
        assertThatThrownBy(() -> normalizer.normalize(actor, request, null, "a".repeat(64), "{}", Map.of(), true, 7))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(references, legacy);
    }
}
