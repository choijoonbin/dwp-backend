package com.dwp.services.people.organization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationPublishRolloutGateTest {

    private final OrganizationPublishRolloutGate gate = new OrganizationPublishRolloutGate();

    @Test
    void publicationRemainsFailClosedUntilExternalApprovalIsCommitted() {
        assertThatThrownBy(gate::requireEnabled)
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage())
                            .isEqualTo(OrganizationPublishRolloutGate.DISABLED_REASON);
                });
    }
}
