package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalFormPayloadNormalization;
import com.dwp.services.approval.domain.ApprovalFormPayloadNormalizationConfig;
import com.dwp.services.approval.domain.ApprovalFormReferenceNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalFormPayloadNormalizationPostgresTest extends ApprovalFormUserCommandPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("approval_form_information_normalization").withLabel("dwp.approval.owner", "apr12-form-normalization");
    private ApprovalFormPayloadNormalization port;
    private UUID requestId;
    private long requestVersion;

    @BeforeEach void setUp() {
        initializeUserCommands(POSTGRES);
        Object normalization = ReflectionTestUtils.getField(commands, "formNormalization");
        var references = (ApprovalFormReferenceNormalizer) ReflectionTestUtils.getField(normalization, "normalizer");
        port = new ApprovalFormPayloadNormalizationConfig().approvalFormPayloadNormalization(commands, references,
                new ObjectMapper().findAndRegisterModules());
        var request = createUserRequest("information-create");
        requestId = request.requestId(); requestVersion = request.version();
        jdbc.update("UPDATE apr_requests SET status='NEEDS_INFO' WHERE request_id=?", requestId);
        action("request-information-response.action", requestId);
        sourceCalls.set(0);
    }

    @AfterEach void tearDown() { clear(); }

    @Test void exactLockedInformationPinsNormalizeComputedDecimalAndSourceBeforeAnyWrite() {
        Map<String, Object> before = databaseState();
        var result = tx(() -> normalize(formVersionId, schemaSha, requestVersion, Map.of(
                "summary", "More evidence", "mode", "REVIEW", "units", "4", "reviewer", person.toString())));
        assertThat(result).containsEntry("total", "8").containsEntry("reviewer", person.toString());
        assertThat(sourceCalls).hasValue(1);
        assertThat(lastPins.targetRequestId()).isEqualTo(requestId);
        assertThat(lastPins.targetRequestVersion()).isEqualTo(requestVersion);
        assertThat(lastAuthority.routeContractKey()).isEqualTo("route.approvals.work.request-information-response.action");
        assertThat(databaseState()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = SourceResult.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void currentSourceFailureRollsBackAndCannotWriteHashAuditEventsOutboxOrReceipts(SourceResult source) {
        sourceResult = source;
        Map<String, Object> before = databaseState();
        assertThatThrownBy(() -> tx(() -> normalize(formVersionId, schemaSha, requestVersion, Map.of(
                "summary", "More evidence", "mode", "REVIEW", "units", "4", "reviewer", person.toString()))))
                .isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test void hiddenStaleUserIsStrippedWithoutSourceLookupOrInventedSourceGrant() {
        sourcePermissions(false); http.removeHeader("Idempotency-Key");
        Map<String, Object> before = databaseState();
        var result = tx(() -> normalize(formVersionId, schemaSha, requestVersion, Map.of(
                "summary", "More evidence", "mode", "OTHER", "units", "4", "reviewer", "stale-hidden")));
        assertThat(result).doesNotContainKey("reviewer").containsEntry("total", "8");
        assertThat(sourceCalls).hasValue(0);
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test void currentDraftHeadAdvanceDoesNotReplaceExistingImmutableInformationFormPin() {
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,lifecycle_state,schema_payload,schema_sha256,created_by) "
                + "SELECT ?,tenant_id,form_id,version_number+1,'DRAFT',schema_payload,schema_sha256,99 FROM apr_form_versions WHERE form_version_id=?",
                UUID.randomUUID(), formVersionId);
        jdbc.update("UPDATE apr_forms SET current_version=current_version+1,lifecycle_state='DRAFT',version=version+1 WHERE form_id=?", formId);
        Map<String, Object> before = databaseState();
        var result = tx(() -> normalize(formVersionId, schemaSha, requestVersion, Map.of(
                "summary", "More evidence", "mode", "REVIEW", "units", "4", "reviewer", person.toString())));
        assertThat(result).containsEntry("total", "8");
        assertThat(jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?", UUID.class, requestId))
                .isEqualTo(formVersionId);
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test void alternateVersionHashStaleRequestAndWrongOwnerFailBeforeSourceAndWrite() {
        Map<String, Object> before = databaseState();
        for (UUID version : java.util.List.of(formVersionId, UUID.randomUUID())) {
            String hash = version.equals(formVersionId) ? "a".repeat(64) : schemaSha;
            assertThatThrownBy(() -> tx(() -> normalize(version, hash, requestVersion, Map.of("summary", "Ready"))))
                    .isInstanceOf(BaseException.class);
        }
        assertThatThrownBy(() -> tx(() -> normalize(formVersionId, schemaSha, requestVersion + 1, Map.of("summary", "Ready"))))
                .isInstanceOf(BaseException.class);
        context(100, true);
        assertThatThrownBy(() -> tx(() -> normalize(formVersionId, schemaSha, requestVersion, Map.of("summary", "Ready"))))
                .isInstanceOf(BaseException.class);
        assertThat(sourceCalls).hasValue(0);
        assertThat(databaseState()).isEqualTo(before);
    }

    private Map<String, Object> normalize(UUID versionId, String hash, long expectedVersion, Map<String, Object> values) {
        String immutable = jdbc.queryForObject("SELECT version.schema_payload::text FROM apr_requests request "
                + "JOIN apr_form_versions version ON version.tenant_id=request.tenant_id AND version.form_version_id=request.form_version_id "
                + "WHERE request.request_id=? AND request.tenant_id=42 FOR UPDATE OF request FOR SHARE OF version", String.class, requestId);
        return port.normalize(ApprovalRequestContext.require(), requestId, versionId, hash, immutable, values, true, expectedVersion);
    }
}
