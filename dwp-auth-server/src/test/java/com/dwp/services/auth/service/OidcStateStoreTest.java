package com.dwp.services.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OidcStateStoreTest {

    @Test
    void stateCanOnlyBeConsumedOnce() {
        Map<String, String> values = new HashMap<>();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        when(operations.getAndDelete(anyString()))
                .thenAnswer(invocation -> values.remove(invocation.getArgument(0)));
        OidcStateStore store = new OidcStateStore(
                redisTemplate, new ObjectMapper().findAndRegisterModules());
        OidcStateStore.AuthorizationRequest authorization = store.create(1L, "provider");

        OidcStateStore.StateContext context = store.consume(authorization.state());

        assertThat(context.tenantId()).isEqualTo(1L);
        assertThat(context.providerKey()).isEqualTo("provider");
        assertThat(context.nonce()).isEqualTo(authorization.nonce());
        assertThat(context.codeVerifier()).isEqualTo(authorization.codeVerifier());
        assertThatThrownBy(() -> store.consume(authorization.state())).isInstanceOf(BaseException.class);
    }
}
