package com.dwp.services.auth.scim;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimProtocolContractTest {

    @Test
    void preservesAnArbitraryScimStartIndexOffset() {
        ScimOffsetPageRequest request = new ScimOffsetPageRequest(
                7, 50, Sort.by("userId").ascending());

        assertThat(request.getOffset()).isEqualTo(7);
        assertThat(request.getPageSize()).isEqualTo(50);
        assertThat(request.next().getOffset()).isEqualTo(57);
    }

    @Test
    void acceptsMatchingWeakEtagAndWildcard() {
        ScimVersionPrecondition.verify("W/\"4\"", 4L);
        ScimVersionPrecondition.verify("W/\"3\", W/\"4\"", 4L);
        ScimVersionPrecondition.verify("*", 4L);
        ScimVersionPrecondition.verify(null, 4L);
    }

    @Test
    void rejectsStaleIfMatchVersion() {
        assertThatThrownBy(() -> ScimVersionPrecondition.verify("W/\"3\"", 4L))
                .isInstanceOf(ScimException.class)
                .satisfies(error -> assertThat(((ScimException) error).status()).isEqualTo(412));
    }
}
