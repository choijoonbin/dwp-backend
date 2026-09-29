package com.dwp.services.auth.productaccess;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class HrisProductAccessControllerContractTest {

    @Test
    void exposesProjectionReadsOnlyAndNeverActsAsACommandPep() {
        var methods = Arrays.asList(HrisProductAccessController.class.getDeclaredMethods());

        assertThat(methods).anyMatch(method -> method.isAnnotationPresent(GetMapping.class));
        assertThat(methods).noneMatch(method ->
                method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class));
    }
}
