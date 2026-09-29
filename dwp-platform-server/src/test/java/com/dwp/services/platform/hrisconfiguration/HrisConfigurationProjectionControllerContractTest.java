package com.dwp.services.platform.hrisconfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class HrisConfigurationProjectionControllerContractTest {

    @Test
    void exposesDisplayProjectionOnlyAndLeavesCommandsToOwnerApis() {
        var methods = Arrays.asList(
                HrisConfigurationProjectionController.class.getDeclaredMethods());

        assertThat(methods).anyMatch(method -> method.isAnnotationPresent(GetMapping.class));
        assertThat(methods).noneMatch(method ->
                method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class));
    }
}
