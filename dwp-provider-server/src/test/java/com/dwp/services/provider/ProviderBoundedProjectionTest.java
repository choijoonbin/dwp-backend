package com.dwp.services.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderBoundedProjectionTest {

    @Test
    void reportsCompleteCoverageBelowTheOwnerLimit() {
        ProviderBoundedProjection<Integer> projection = ProviderBoundedProjection.from(
                IntStream.range(0, 12).boxed().toList(), 12);

        assertThat(projection.items()).hasSize(12);
        assertThat(projection.hasMore()).isFalse();
    }

    @Test
    void preservesTheVisibleLimitAndReportsAdditionalOwnerRows() {
        ProviderBoundedProjection<Integer> projection = ProviderBoundedProjection.from(
                IntStream.range(0, 201).boxed().toList(), 200);

        assertThat(projection.items()).containsExactlyElementsOf(
                IntStream.range(0, 200).boxed().toList());
        assertThat(projection.hasMore()).isTrue();
    }
}
