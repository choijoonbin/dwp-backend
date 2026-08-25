package com.dwp.services.auth.repository;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductAuthorizationAuthorityEndpointRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void v2AuthorityEndpointSurvivesInsertAndLoadWhileV1RemainsEmpty()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ProductAuthorizationContractDtos.BundleContract v2;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "product-authorization/product-surfaces-v1.bundle-v2.generated.json")) {
            v2 = objectMapper.readValue(
                    java.util.Objects.requireNonNull(input),
                    ProductAuthorizationContractDtos.BundleContract.class);
        }
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductAuthorizationContractRepository repository =
                new ProductAuthorizationContractRepository(jdbc, objectMapper);

        UUID insertedId = repository.insertDraft(v2);

        ProductAuthorizationContractDtos.AuthorityEndpoint endpoint =
                v2.authorityEndpoints().getFirst();
        verify(jdbc).update(
                contains("INSERT INTO auth_product_authority_endpoint"),
                eq(insertedId), eq(endpoint.endpointKey()), eq(endpoint.serviceKey()), any());

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(objectMapper.writeValueAsString(endpoint));
        when(jdbc.query(
                contains("auth_product_authority_endpoint"),
                any(RowMapper.class),
                eq(insertedId))).thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        ProductAuthorizationContractRepository.StoredBundle stored = stored(insertedId, v2);

        ProductAuthorizationContractDtos.BundleContract loaded = repository.loadContract(stored);

        assertThat(loaded.authorityEndpoints()).containsExactly(endpoint);

        UUID oldId = UUID.randomUUID();
        ProductAuthorizationContractDtos.BundleContract older = repository.loadContract(
                new ProductAuthorizationContractRepository.StoredBundle(
                        oldId, "product-surfaces", 1, "APPROVED", 1, "SHA-256",
                        "a".repeat(64), "owner", "approver", OffsetDateTime.now(), null,
                        OffsetDateTime.now()));
        assertThat(older.authorityEndpoints()).isEmpty();
    }

    private ProductAuthorizationContractRepository.StoredBundle stored(
            UUID bundleId,
            ProductAuthorizationContractDtos.BundleContract contract) {
        return new ProductAuthorizationContractRepository.StoredBundle(
                bundleId, contract.bundleKey(), contract.version(), "ACTIVE",
                contract.schemaVersion(), contract.checksumAlgorithm(), contract.checksum(),
                contract.owner(), "approver", OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now());
    }
}
