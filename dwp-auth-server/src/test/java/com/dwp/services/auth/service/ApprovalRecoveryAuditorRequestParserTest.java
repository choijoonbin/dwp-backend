package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalRecoveryAuditorRequestParserTest {

    private final ApprovalRecoveryAuditorRequestParser parser =
            new ApprovalRecoveryAuditorRequestParser(
                    new ObjectMapper().findAndRegisterModules(),
                    Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void acceptsOnlyTheExactFourFieldRequest() {
        var result = parser.parse("""
                {"tenantId":7,"outboxId":"7c2d52d7-559f-4b31-b097-0e92a51edc90",
                 "originatorUserId":41,"resourceSetKey":"RS_TEAM_A"}
                """);

        assertThat(result.tenantId()).isEqualTo(7L);
        assertThat(result.outboxId())
                .isEqualTo("7c2d52d7-559f-4b31-b097-0e92a51edc90");
        assertThat(result.originatorUserId()).isEqualTo(41L);
        assertThat(result.resourceSetKey()).isEqualTo("RS_TEAM_A");
    }

    @Test
    void rejectsUnknownMissingDuplicateAndTrailingFields() {
        assertInvalid("""
                {"tenantId":7,"outboxId":"outbox-1","originatorUserId":41,
                 "resourceSetKey":"RS_APPROVALS","unknown":true}
                """);
        assertInvalid("{" + "\"tenantId\":7,\"outboxId\":\"outbox-1\"}");
        assertInvalid("""
                {"tenantId":7,"tenantId":8,"outboxId":"outbox-1",
                 "originatorUserId":41}
                """);
        assertInvalid("""
                {"tenantId":7,"outboxId":"outbox-1","originatorUserId":41,
                 "resourceSetKey":"RS_APPROVALS"} {}
                """);
    }

    @Test
    void rejectsInvalidTenantOriginatorAndOutboxIdentifiers() {
        assertInvalid("""
                {"tenantId":0,"outboxId":"outbox-1","originatorUserId":41,
                 "resourceSetKey":"RS_APPROVALS"}
                """);
        assertInvalid("""
                {"tenantId":7,"outboxId":" outbox-1 ","originatorUserId":41,
                 "resourceSetKey":"RS_APPROVALS"}
                """);
        assertInvalid("""
                {"tenantId":7,"outboxId":"outbox-1","originatorUserId":0,
                 "resourceSetKey":"RS_APPROVALS"}
                """);
        assertInvalid("""
                {"tenantId":7,"outboxId":"outbox-1","originatorUserId":41,
                 "resourceSetKey":"rs_team_a"}
                """);
    }

    private void assertInvalid(String body) {
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_FORMAT));
    }
}
