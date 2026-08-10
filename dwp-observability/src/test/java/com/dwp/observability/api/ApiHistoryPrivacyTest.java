package com.dwp.observability.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiHistoryPrivacyTest {

    @Test
    void createsAChildSpanFromValidW3cTraceContext() {
        TraceContext context = TraceContext.childOf(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertThat(context.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(context.parentSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(context.spanId()).hasSize(16).isNotEqualTo(context.parentSpanId());
        assertThat(context.traceParent()).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");
    }

    @Test
    void rejectsInvalidAndAllZeroTraceContext() {
        TraceContext invalid = TraceContext.childOf("invalid");
        TraceContext allZero = TraceContext.childOf(
                "00-00000000000000000000000000000000-0000000000000000-01");

        assertThat(invalid.parentSpanId()).isNull();
        assertThat(allZero.parentSpanId()).isNull();
        assertThat(invalid.traceId()).hasSize(32).isNotEqualTo(allZero.traceId());
    }

    @Test
    void removesQueryAndMasksPathIdentifiers() {
        assertThat(ApiHistorySanitizer.normalizePath(
                "/v1/users/123/550e8400-e29b-41d4-a716-446655440000?token=secret"))
                .isEqualTo("/v1/users/{id}/{id}");
        assertThat(ApiHistorySanitizer.normalizePath("/users/private@example.com"))
                .isEqualTo("/users/{value}");
        assertThat(ApiHistorySanitizer.normalizePath("/download/abcdefghijklmnopqrstuvwxyz123"))
                .isEqualTo("/download/{token}");
    }

    @Test
    void hashesNetworkIdentifiersWithoutRetainingRawValues() {
        ApiHistoryPrivacyHasher hasher = new ApiHistoryPrivacyHasher("tenant-secret");

        String first = hasher.hash("192.0.2.10");

        assertThat(first).hasSize(64).doesNotContain("192.0.2.10");
        assertThat(hasher.hash("192.0.2.10")).isEqualTo(first);
        assertThat(hasher.hash("192.0.2.11")).isNotEqualTo(first);
        assertThat(new ApiHistoryPrivacyHasher("").hash("192.0.2.10")).isNull();
    }
}
