package com.dwp.services.people.hr.performance.populationfreeze;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceReviewOperationsControllerContractTest {

    @Test
    void exposesTheExactIaq021OwnerPathAndDefaultOffFlag() throws NoSuchMethodException {
        RequestMapping root = PerformanceReviewOperationsController.class
                .getAnnotation(RequestMapping.class);
        ConditionalOnProperty flag = PerformanceReviewOperationsController.class
                .getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty serviceFlag = PerformanceReviewOperationsService.class
                .getAnnotation(ConditionalOnProperty.class);
        Method method = PerformanceReviewOperationsController.class.getMethod(
                "query", int.class, List.class, String.class, String.class,
                UUID.class, Instant.class, String.class, List.class, List.class,
                Integer.class, Integer.class, List.class, List.class, Long.class,
                LocalDate.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertThat(root.value()).containsExactly("/v1/hris/performance");
        assertThat(mapping.value()).containsExactly("/workbench/review-operations");
        assertThat(flag.name())
                .containsExactly("dwp.hris.performance.population-freeze.enabled");
        assertThat(flag.havingValue()).isEqualTo("true");
        assertThat(flag.matchIfMissing()).isFalse();
        assertThat(serviceFlag.name())
                .containsExactly("dwp.hris.performance.population-freeze.enabled");
        assertThat(serviceFlag.havingValue()).isEqualTo("true");
        assertThat(serviceFlag.matchIfMissing()).isFalse();
    }
}
