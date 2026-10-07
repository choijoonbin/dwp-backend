package com.dwp.services.people.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HcmPepBindingConstraintsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requiredQueryConstraintAcceptsOneNonBlankDynamicValueOnly() throws Exception {
        var constraints = HcmPepBindingConstraints.QueryConstraints.compile(
                objectMapper.readTree("{\"asOf\":{\"kind\":\"REQUIRED\"}}"));

        assertThat(constraints.matches("asOf=2026-09-29")).isTrue();
        assertThat(constraints.matches(null)).isFalse();
        assertThat(constraints.matches("asOf=")).isFalse();
        assertThat(constraints.matches("asOf=2026-09-29&asOf=2026-09-30")).isFalse();
    }

    @Test
    void requiredQueryConstraintRejectsUnknownShape() throws Exception {
        assertThatThrownBy(() -> HcmPepBindingConstraints.QueryConstraints.compile(
                objectMapper.readTree(
                        "{\"asOf\":{\"kind\":\"REQUIRED\",\"value\":\"x\"}}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required query constraint");
    }
}
