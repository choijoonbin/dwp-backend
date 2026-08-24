package com.dwp.services.auth.support;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestProductAuthorizationOverrideLoaderTest {

    @Test
    void loadsOnlyTheTwoSignedDescriptorsForTheirExactTestIds() {
        TestProductAuthorizationOverrideLoader loader =
                new TestProductAuthorizationOverrideLoader(Set.of("contract-test"));

        assertThat(loader.loadAll())
                .extracting(TestProductAuthorizationOverrideLoader.OverrideDescriptor::testId)
                .containsExactly("PS-G006", "PS-G010");
        assertThat(loader.loadForTestId("PS-G006"))
                .satisfies(value -> {
                    assertThat(value.key()).isEqualTo("test.management-and-app.v1");
                    assertThat(value.routeContractKey())
                            .isEqualTo("route.test.management-and-app.page");
                    assertThat(value.canonicalDescriptorJson())
                            .contains("\"requiresProductEntitlement\":true");
                });
        assertThat(loader.loadForTestId("PS-G010"))
                .satisfies(value -> {
                    assertThat(value.key()).isEqualTo("test.services-catalog-jit.v1");
                    assertThat(value.routeContractKey())
                            .isEqualTo("route.test.services-catalog-jit.page");
                    assertThat(value.canonicalDescriptorJson())
                            .contains("\"activationState\":\"REQUIRED\"");
                });
    }

    @Test
    void rejectsEveryOtherTestIdAndProfileCombination() {
        TestProductAuthorizationOverrideLoader loader =
                new TestProductAuthorizationOverrideLoader(Set.of("contract-test"));

        assertThatThrownBy(() -> loader.loadForTestId("PS-A002"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolved 0 descriptors");
        assertThatThrownBy(() ->
                new TestProductAuthorizationOverrideLoader(Set.of()).loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exclusive contract-test profile");
        assertThatThrownBy(() ->
                new TestProductAuthorizationOverrideLoader(Set.of("test")).loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exclusive contract-test profile");
        assertThatThrownBy(() ->
                new TestProductAuthorizationOverrideLoader(
                        Set.of("contract-test", "test")).loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exclusive contract-test profile");
    }

    @Test
    void keepsTheLoaderClassAndOverrideResourceOutOfTheMainOutputClasspath()
            throws Exception {
        Path testClasses = Path.of(
                TestProductAuthorizationOverrideLoader.class
                        .getProtectionDomain().getCodeSource().getLocation().toURI());
        Path buildDirectory = testClasses.getParent().getParent().getParent();
        URL[] mainOutput = {
                buildDirectory.resolve("classes/java/main").toUri().toURL(),
                buildDirectory.resolve("resources/main").toUri().toURL()
        };

        try (URLClassLoader mainOnly = new URLClassLoader(mainOutput, null)) {
            assertThatThrownBy(() -> Class.forName(
                    TestProductAuthorizationOverrideLoader.class.getName(), false, mainOnly))
                    .isInstanceOf(ClassNotFoundException.class);
            assertThat(mainOnly.getResource(TestProductAuthorizationOverrideLoader.RESOURCE))
                    .isNull();
        }
    }
}
