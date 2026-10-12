package com.dwp.services.people.hr.performance.populationfreeze;

import com.dwp.services.people.security.HcmStepUpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PopulationFreezeControllerContractTest {

    @Test
    void exposesOnlyTheExactPepPer009TransportWithRequiredPreconditions()
            throws NoSuchMethodException {
        Method method = PopulationFreezeController.class.getMethod(
                "freeze", UUID.class, String.class, long.class, String.class,
                String.class, String.class, Long.class,
                PopulationFreezeDtos.FreezePopulationRequest.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);

        assertThat(mapping.value())
                .containsExactly("/cycles/{cycleId}/population-freezes");
        assertHeader(method, 1, PopulationFreezeController.IDEMPOTENCY_HEADER, true);
        assertHeader(method, 2, PopulationFreezeController.IF_MATCH_HEADER, true);
        assertHeader(method, 3, PopulationFreezeController.CORRELATION_HEADER, false);
        assertHeader(method, 4, HcmStepUpHeaders.CHALLENGE, true);
        assertHeader(method, 5, HcmStepUpHeaders.DECISION_REVISION, true);
        assertHeader(method, 6, HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, true);
    }

    @Test
    void endpointIsAbsentUnlessTheDedicatedDefaultOffFlagIsEnabled() {
        ConditionalOnProperty flag = PopulationFreezeController.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(flag.name())
                .containsExactly("dwp.hris.performance.population-freeze.enabled");
        assertThat(flag.havingValue()).isEqualTo("true");
        assertThat(flag.matchIfMissing()).isFalse();
    }

    private void assertHeader(Method method, int index, String name, boolean required) {
        RequestHeader header = method.getParameters()[index].getAnnotation(RequestHeader.class);
        assertThat(header).isNotNull();
        assertThat(header.value()).isEqualTo(name);
        assertThat(header.required()).isEqualTo(required);
    }
}
