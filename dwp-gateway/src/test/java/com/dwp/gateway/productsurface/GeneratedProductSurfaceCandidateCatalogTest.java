package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedProductSurfaceCandidateCatalogTest {

    @Test
    void projectsTheExactSortedProductPageSupersetFromTheGeneratedBundle() {
        GeneratedProductSurfaceCandidateCatalog catalog = catalog();

        assertThat(catalog.activeCandidates()).containsExactly(
                candidate("approvals", "approvals.admin"),
                candidate("approvals", "approvals.work"),
                candidate("communications", "communications.management"),
                candidate("communications", "communications.work"),
                candidate("hcm", "hcm.management"),
                candidate("hcm", "hcm.operations"),
                candidate("hcm", "hcm.personal"),
                candidate("hcm", "hcm.team"),
                candidate("services", "services.management"),
                candidate("services", "services.work"));
    }

    @Test
    void failsClosedWhenTheGeneratedIndexDoesNotBindTheCatalog() {
        byte[] malformedIndex = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new GeneratedProductSurfaceCandidateCatalog(
                new ObjectMapper(),
                contractResource(),
                new ByteArrayResource(malformedIndex)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void springOwnsExactlyOneGeneratedCandidateCatalogAdapter() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    ObjectMapper.class,
                    (Supplier<ObjectMapper>) ObjectMapper::new);
            context.register(GeneratedProductSurfaceCandidateCatalog.class);
            context.refresh();

            assertThat(context.getBeansOfType(ProductSurfaceCandidateCatalog.class))
                    .hasSize(1)
                    .containsValue(context.getBean(
                            GeneratedProductSurfaceCandidateCatalog.class));
        }
    }

    private GeneratedProductSurfaceCandidateCatalog catalog() {
        return new GeneratedProductSurfaceCandidateCatalog(
                new ObjectMapper(), contractResource(), indexResource());
    }

    private ClassPathResource contractResource() {
        return new ClassPathResource(
                "product-authorization/product-surfaces-v1.generated.json");
    }

    private ClassPathResource indexResource() {
        return new ClassPathResource(
                "product-authorization/product-surfaces-v1.index.generated.json");
    }

    private ProductSurfaceContextDtos.ProductCandidate candidate(
            String productKey,
            String surfaceKey) {
        return new ProductSurfaceContextDtos.ProductCandidate(productKey, surfaceKey);
    }
}
