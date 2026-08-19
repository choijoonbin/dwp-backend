package com.dwp.services.space.operations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpaceOperationsRepositoryTest {

    @Test
    void moderatorCanGovernContentButCannotManageMembershipOrPolicy() {
        assertThat(SpaceOperationsRepository.permissionsForRole("MODERATOR"))
                .containsExactly("VIEW", "CREATE", "UPDATE", "APPROVE")
                .doesNotContain("MANAGE");
    }

    @Test
    void ownerReceivesTheOnlyMembershipAndPolicyManagementCapability() {
        assertThat(SpaceOperationsRepository.permissionsForRole("owner"))
                .containsExactly("VIEW", "CREATE", "UPDATE", "APPROVE", "MANAGE");
        assertThat(SpaceOperationsRepository.permissionsForRole("unknown")).isEmpty();
    }
}
