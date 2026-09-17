package com.dwp.services.platform.workplace.workplaceservices;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceServicesRepositoryTest {
    @Test
    void advisoryLockIdentityIsPostgresTextSafeAndTupleUnambiguous() {
        String identity = WorkplaceServicesRepository.advisoryLockIdentity(
                12L, 34L, "scope\0관리", "key:with:separator");

        assertThat(identity).doesNotContain("\0");
        assertThat(new String(identity.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(identity);
        assertThat(WorkplaceServicesRepository.advisoryLockIdentity(12L, 34L, "a", "b:c"))
                .isNotEqualTo(WorkplaceServicesRepository.advisoryLockIdentity(
                        12L, 34L, "a:b", "c"));
        assertThat(WorkplaceServicesRepository.advisoryLockIdentity(12L, 34L, null, "null"))
                .isNotEqualTo(WorkplaceServicesRepository.advisoryLockIdentity(
                        12L, 34L, "null", null));
    }
}
