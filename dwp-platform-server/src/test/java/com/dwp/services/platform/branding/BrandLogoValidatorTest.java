package com.dwp.services.platform.branding;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandLogoValidatorTest {

    @Test
    void acceptsAContainedSvgAndDerivesItsViewport() {
        String svg = """
                <?xml version="1.0" encoding="UTF-8"?>
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 106 56">
                  <path d="M0 0h106v56H0z" fill="#EE7501"/>
                </svg>
                """;
        BrandLogoValidator validator = new BrandLogoValidator(2_097_152);

        BrandLogoValidator.ValidatedLogo result = validator.validate(new MockMultipartFile(
                "file", "company.svg", "text/plain", svg.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.contentType()).isEqualTo("image/svg+xml");
        assertThat(result.extension()).isEqualTo("svg");
        assertThat(result.width()).isEqualTo(106);
        assertThat(result.height()).isEqualTo(56);
        assertThat(result.sha256()).hasSize(64);
    }

    @Test
    void rejectsScriptsAndExternalReferencesInSvg() {
        BrandLogoValidator validator = new BrandLogoValidator(2_097_152);
        MockMultipartFile scripted = new MockMultipartFile(
                "file",
                "unsafe.svg",
                "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8));
        MockMultipartFile external = new MockMultipartFile(
                "file",
                "external.svg",
                "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><use href=\"https://example.com/a.svg#x\"/></svg>"
                        .getBytes(StandardCharsets.UTF_8));

        assertInvalid(() -> validator.validate(scripted));
        assertInvalid(() -> validator.validate(external));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }
}
