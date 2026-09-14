package com.dwp.services.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ApprovalPolicyRuleIntegerContractTest {
    private final ApprovalCommandJdbcRepository repository = new ApprovalCommandJdbcRepository(
            mock(NamedParameterJdbcTemplate.class), new ObjectMapper()) { };

    @Test void acceptsExactBoundedIntegerAndLongWithoutChangingTheirValue() {
        assertThat(repository.boundedInteger(12, 4, 1000)).isEqualTo(12);
        assertThat(repository.boundedInteger(12L, 4, 1000)).isEqualTo(12);
        repository.validatePolicyRule("SLA_ESCALATION", Map.of("warningPercent", 80, "breachPercent", 100L));
    }

    @Test void rejectsFractionsAndOverflowBeforePolicyMutation() {
        for (Object invalid : new Object[] {12.5, 12.0, Float.NaN, Double.POSITIVE_INFINITY,
                4294967308L, Long.MAX_VALUE, new BigDecimal("12.5"), new BigInteger("4294967308"), "12", null}) {
            assertThatThrownBy(() -> repository.boundedInteger(invalid, 4, 1000))
                    .isInstanceOfSatisfying(BaseException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        }
    }

    @Test void requiresClosedRulesAndBoundedWarningBeforeBreach() {
        assertThatThrownBy(() -> repository.validatePolicyRule("REQUIRE_REJECT_REASON", Map.of("minimumLength", 12.5)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> repository.validatePolicyRule("SLA_ESCALATION", Map.of("warningPercent", 4294967376L, "breachPercent", 100)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> repository.validatePolicyRule("SLA_ESCALATION", Map.of("warningPercent", 80, "breachPercent", 79)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> repository.validatePolicyRule("REQUIRE_REJECT_REASON", Map.of("minimumLength", 12, "unreviewed", true)))
                .isInstanceOf(BaseException.class);
    }
}
