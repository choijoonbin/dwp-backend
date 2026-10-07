package com.dwp.services.time.workregime;

import io.swagger.v3.oas.annotations.Parameter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WorkRegimeOwnerOpenApiContractTest {

    @Test
    void transitionPublishesTheExactSupportedActionValues() {
        var transition = Arrays.stream(WorkRegimeOwnerController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("transition"))
                .findFirst()
                .orElseThrow();
        Parameter action = transition.getParameters()[3].getAnnotation(Parameter.class);

        assertThat(action).isNotNull();
        assertThat(action.schema().allowableValues()).containsExactly(
                "apply-approval", "publish", "submit-review", "validate");
    }
}
