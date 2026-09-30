package com.dwp.services.platform.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CappedListTest {

    @Test
    void detectsTheOneHundredAndFirstTemplateWithoutReturningIt() {
        CappedList<Integer> result = CappedList.from(
                IntStream.rangeClosed(1, 101).boxed().toList(), 100);

        assertThat(result.items()).hasSize(100).containsExactlyElementsOf(
                IntStream.rangeClosed(1, 100).boxed().toList());
        assertThat(result.hasMore()).isTrue();
        assertThat(result.limit()).isEqualTo(100);
    }

    @Test
    void detectsTheFiftyFirstRevisionAndKeepsTrueEmptyDistinct() {
        CappedList<Integer> partial = CappedList.from(
                IntStream.rangeClosed(1, 51).boxed().toList(), 50);
        CappedList<Integer> empty = CappedList.from(List.of(), 50);

        assertThat(partial.items()).hasSize(50);
        assertThat(partial.hasMore()).isTrue();
        assertThat(empty.items()).isEmpty();
        assertThat(empty.hasMore()).isFalse();
    }
}
