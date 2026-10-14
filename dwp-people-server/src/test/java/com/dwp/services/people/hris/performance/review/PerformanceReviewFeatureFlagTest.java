package com.dwp.services.people.hris.performance.review;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PerformanceReviewFeatureFlagTest {

    private final WebApplicationContextRunner context =
            new WebApplicationContextRunner()
                    .withBean(PerformanceReviewService.class,
                            () -> mock(PerformanceReviewService.class))
                    .withUserConfiguration(PerformanceAssignedReviewController.class);

    @Test
    void assignedReviewOwnerRouteIsAbsentByDefault() {
        context.run(application -> assertThat(application)
                .doesNotHaveBean(PerformanceAssignedReviewController.class));
    }

    @Test
    void assignedReviewOwnerRouteExistsOnlyWhenExplicitlyEnabled() {
        context.withPropertyValues(
                        "dwp.hris.performance.assigned-review.enabled=true")
                .run(application -> assertThat(application)
                        .hasSingleBean(PerformanceAssignedReviewController.class));
    }
}
