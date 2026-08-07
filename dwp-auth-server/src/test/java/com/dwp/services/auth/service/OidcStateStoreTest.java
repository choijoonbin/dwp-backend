package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcStateStoreTest {

    @Test
    void stateCanOnlyBeConsumedOnce() {
        OidcStateStore store = new OidcStateStore();
        String state = store.create(1L, "provider");

        OidcStateStore.StateContext context = store.consume(state);

        assertThat(context.tenantId()).isEqualTo(1L);
        assertThat(context.providerKey()).isEqualTo("provider");
        assertThatThrownBy(() -> store.consume(state)).isInstanceOf(BaseException.class);
    }
}
