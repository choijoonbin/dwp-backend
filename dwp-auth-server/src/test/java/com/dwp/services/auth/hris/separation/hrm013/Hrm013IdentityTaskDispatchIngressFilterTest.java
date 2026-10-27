package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class Hrm013IdentityTaskDispatchIngressFilterTest {
    @Test
    void acceptsOnlyTheFiveApplicationHeadersAndRejectsTenantHints() {
        MockHttpServletRequest valid = request();
        assertThat(Hrm013IdentityTaskDispatchIngressFilter.exactHeaders(valid))
                .hasSize(5);

        MockHttpServletRequest invalid = request();
        invalid.addHeader("X-Tenant-ID", "91");
        assertThatThrownBy(() ->
                Hrm013IdentityTaskDispatchIngressFilter.exactHeaders(invalid))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void rejectsNonTls13BeforeAnyAuthorityOrBusinessRead() throws Exception {
        AtomicBoolean certificateRead = new AtomicBoolean();
        var filter = new Hrm013IdentityTaskDispatchIngressFilter(
                Clock.systemUTC(), (chain, clock) -> {
                    certificateRead.set(true);
                    return chain[0];
                });
        MockHttpServletRequest request = request();
        request.setSecure(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(certificateRead).isFalse();
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void stableKeysetPaginationEventuallyServicesTenantOneHundredOneAndWraps() {
        var store = mock(Hrm013IdentityTaskDispatchStore.class);
        when(store.executeNext(anyLong())).thenReturn(Optional.empty());
        List<Long> tenants = java.util.stream.LongStream.rangeClosed(1, 101)
                .boxed().toList();
        List<Long> cursors = new ArrayList<>();
        Hrm013IdentityTaskDispatchService.TenantWorkSource source = (after, limit) -> {
            cursors.add(after);
            return tenants.stream().filter(tenant -> tenant > after)
                    .limit(limit).toList();
        };
        var service = new Hrm013IdentityTaskDispatchService(store, source);

        service.executeScheduledBatch();
        service.executeScheduledBatch();
        service.executeScheduledBatch();

        assertThat(cursors).containsExactly(0L, 100L, 101L, 0L);
        verify(store, times(1)).executeNext(101L);
        verify(store, times(2)).executeNext(1L);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/auth/v1/separation-task-dispatches");
        request.setContent("{}".getBytes());
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Accept", "application/json");
        request.addHeader("X-DWP-Service-Identity", "dwp-people-server");
        request.addHeader("X-DWP-Workload-Proof", "a.b.c");
        request.addHeader("X-Correlation-ID", UUID.randomUUID().toString());
        return request;
    }
}
