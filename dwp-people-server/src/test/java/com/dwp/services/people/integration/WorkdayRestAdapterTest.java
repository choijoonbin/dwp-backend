package com.dwp.services.people.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WorkdayRestAdapterTest {

    @Test
    void fetchesWorkdayReportWithOauthAndReturnsCanonicalBatch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HrisCredentialResolver resolver = new HrisCredentialResolver(
                objectMapper,
                Map.of("WD_SECRET", """
                        {"tokenUri":"https://auth.example.com/token","clientId":"client","clientSecret":"secret"}
                        """)::get);
        WorkdayRestAdapter adapter = new WorkdayRestAdapter(
                builder.build(), resolver, new WorkdayReferenceMapper(objectMapper),
                Set.of("auth.example.com", "api.example.com"), false);
        String fixture;
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                WorkdayReferenceMapper.SAMPLE_RESOURCE)) {
            fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        server.expect(once(), requestTo("https://auth.example.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"token-1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.example.com/workers?limit=100&offset=0"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        WorkdayRestAdapter.FetchResult result = adapter.fetch(
                connector(), mapping(), null, "FULL");

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.batch().synthetic()).isFalse();
        assertThat(result.batch().workers()).hasSize(3);
        assertThat(result.batch().workers().get(0).workerNumber()).isEqualTo("E100001");
        server.verify();
    }

    @Test
    void blocksEndpointOutsideExplicitEgressAllowlist() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkdayRestAdapter adapter = new WorkdayRestAdapter(
                RestClient.create(),
                new HrisCredentialResolver(objectMapper, ignored -> null),
                new WorkdayReferenceMapper(objectMapper), Set.of("approved.example.com"), false);

        assertThatThrownBy(() -> adapter.probe(connector()))
                .isInstanceOf(HrisConnectorBlockedException.class)
                .hasMessageContaining("allowlist");
    }

    private HrisDtos.ConnectorInstance connector() {
        return new HrisDtos.ConnectorInstance(
                UUID.randomUUID(), 17L, "workday-primary", "workday-rest", "WORKDAY_REST",
                "https://api.example.com/workers", "OAUTH2_CLIENT_CREDENTIALS",
                "env://WD_SECRET", null, "ACTIVE", "UNKNOWN",
                null, null, null, null, 0, 0L);
    }

    private HrisIntegrationRepository.MappingRuntime mapping() {
        return new HrisIntegrationRepository.MappingRuntime(
                UUID.randomUUID(), 17L, "workday-v1", "WORKDAY_REST",
                "synthetic-2026.1", "dwp.workforce-projection.v1", "{}", 0L);
    }
}
