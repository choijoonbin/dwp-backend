package com.dwp.core.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailAddressNormalizerTest {

    @Test
    void normalizesCompanyEmailForComparison() {
        assertThat(EmailAddressNormalizer.requireValid("  Employee@Example.COM  "))
                .isEqualTo("employee@example.com");
    }

    @Test
    void rejectsMissingOrMalformedEmail() {
        assertThatThrownBy(() -> EmailAddressNormalizer.requireValid("employee"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailAddressNormalizer.requireValid("a@@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailAddressNormalizer.requireValid("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
