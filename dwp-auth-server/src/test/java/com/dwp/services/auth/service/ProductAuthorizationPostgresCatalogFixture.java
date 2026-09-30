package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;

final class ProductAuthorizationPostgresCatalogFixture {

    private ProductAuthorizationPostgresCatalogFixture() {
    }

    static void activateV3(
            PGSimpleDataSource source,
            JdbcTemplate jdbc,
            ObjectMapper mapper) {
        var repository = new ProductAuthorizationContractRepository(jdbc, mapper);
        var validator = new ProductAuthorizationContractValidator(mapper);
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v3.generated.json")
                .getInputStream()) {
            contract = validator.validateDocument(input);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        var catalog = new ProductAuthorizationContractService(repository, validator);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(ignored -> catalog.importDraft(contract));
        transaction.executeWithoutResult(ignored -> catalog.approveGoverned(
                contract.bundleKey(), contract.version(), contract.checksum(),
                "catalog-maker", "catalog-checker", "CHG-CATALOG-POSTGRES"));
        transaction.executeWithoutResult(ignored -> catalog.activateGoverned(
                contract.bundleKey(), contract.version(), contract.checksum(),
                "catalog-release", 0L, "CHG-CATALOG-POSTGRES"));
    }
}
