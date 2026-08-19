package com.dwp.core.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void successResponseDoesNotEmbedUserFacingLanguage() {
        ApiResponse<String> response = ApiResponse.success("payload");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getMessage()).isNull();
        assertThat(response.getData()).isEqualTo("payload");
        assertThat(response.getSuccess()).isTrue();
    }
}
