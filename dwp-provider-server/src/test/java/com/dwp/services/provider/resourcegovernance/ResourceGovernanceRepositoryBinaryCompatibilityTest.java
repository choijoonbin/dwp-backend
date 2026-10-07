package com.dwp.services.provider.resourcegovernance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceGovernanceRepositoryBinaryCompatibilityTest {

    @Test
    void preservesPublishedConcreteRepositoryConstructor() throws Exception {
        assertThat(ResourceGovernanceRepository.class.getConstructor(
                ResourceCommitmentJdbcRepository.class,
                ArtifactGovernanceJdbcRepository.class,
                TenantLifecycleGovernanceJdbcRepository.class)).isNotNull();
    }

    @Test
    void selectsOnlyTheInternalInterfaceConstructorForSpringInjection() throws Exception {
        var constructor = ResourceGovernanceRepository.class.getDeclaredConstructor(
                ResourceCommitmentPersistence.class,
                ArtifactGovernancePersistence.class,
                TenantLifecycleGovernancePersistence.class);

        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
        assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse();
        assertThat(ResourceGovernanceRepository.class.getConstructors())
                .hasSize(1);
    }
}
