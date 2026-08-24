package com.dwp.services.auth.config;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.service.ProductAuthorizationContractService;
import com.dwp.services.auth.service.ProductAuthorizationContractValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class ProductAuthorizationSeedLoader implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductAuthorizationSeedLoader.class);

    private final boolean enabled;
    private final Resource seedIndexResource;
    private final ProductAuthorizationContractValidator validator;
    private final ProductAuthorizationContractService service;

    public ProductAuthorizationSeedLoader(
            @Value("${dwp.product-authorization.seed.enabled:false}") boolean enabled,
            @Value("${dwp.product-authorization.seed.index-location:classpath:product-authorization/product-surfaces-v1.index.generated.json}")
            String seedIndexLocation,
            ResourceLoader resourceLoader,
            ProductAuthorizationContractValidator validator,
            ProductAuthorizationContractService service) {
        this.enabled = enabled;
        this.seedIndexResource = resourceLoader.getResource(seedIndexLocation);
        this.validator = validator;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!enabled) {
            LOGGER.info("Product authorization seed import is disabled.");
            return;
        }
        ProductAuthorizationContractDtos.SeedIndex index = readIndex(seedIndexResource);
        List<ProductAuthorizationContractDtos.SeedIndexEntry> versions = index.versions();
        for (ProductAuthorizationContractDtos.SeedIndexEntry entry : versions) {
            Resource seedResource;
            try {
                seedResource = seedIndexResource.createRelative(entry.authSeedArtifact());
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Product authorization seed path could not be resolved: "
                                + entry.authSeedArtifact(), exception);
            }
            ProductAuthorizationContractDtos.BundleContract contract = read(seedResource);
            validateIndexBinding(entry, contract);
            ProductAuthorizationContractDtos.BundleView imported = service.importDraft(contract);
            LOGGER.info(
                    "Imported product authorization DRAFT bundle {}/{} status={} checksum={}",
                    imported.bundleKey(), imported.version(), imported.bundleStatus(), imported.checksum());
        }
        LOGGER.info(
                "Product authorization seed import completed without activation: versions={} latest={}",
                versions.size(), index.latestVersion());
    }

    public ProductAuthorizationContractDtos.SeedIndex readIndex(Resource resource) {
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException("Product authorization seed index is not readable: " + resource);
        }
        try (InputStream input = resource.getInputStream()) {
            return validator.validateSeedIndexDocument(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Product authorization seed index could not be read.", exception);
        }
    }

    public ProductAuthorizationContractDtos.BundleContract read(Resource resource) {
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException("Product authorization seed is not readable: " + resource);
        }
        try (InputStream input = resource.getInputStream()) {
            return validator.validateDocument(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Product authorization seed could not be read.", exception);
        }
    }

    private static void validateIndexBinding(
            ProductAuthorizationContractDtos.SeedIndexEntry entry,
            ProductAuthorizationContractDtos.BundleContract contract) {
        if (contract.version() != entry.version()
                || !contract.checksum().equals(entry.checksum())
                || !contract.bundleStatus().equals(entry.bundleStatus())
                || contract.capabilities().size() != entry.counts().get("capabilities")
                || contract.accessPolicies().size() != entry.counts().get("accessPolicies")
                || contract.entitlementExpressions().size()
                        != entry.counts().get("entitlementExpressions")
                || contract.predicatePolicies().size() != entry.counts().get("predicatePolicies")
                || contract.routes().size() != entry.counts().get("routes")) {
            throw new IllegalArgumentException(
                    "Product authorization seed does not match immutable index version "
                            + entry.version() + ".");
        }
    }
}
