package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedProductSurfaceCandidateCatalogTest {

    @Test
    void projectsOnlySortedPagesFromProductsWithEveryRequiredActiveRouteKind() {
        GeneratedProductSurfaceCandidateCatalog catalog = catalog();

        assertThat(catalog.activeCandidates()).containsExactly(
                candidate("approvals", "approvals.admin"),
                candidate("approvals", "approvals.work"),
                candidate("calendar", "calendar.work"),
                candidate("communications", "communications.management"),
                candidate("communications", "communications.work"),
                candidate("dwaion", "dwaion.work"),
                candidate("hcm", "hcm.management"),
                candidate("hcm", "hcm.operations"),
                candidate("hcm", "hcm.personal"),
                candidate("hcm", "hcm.team"),
                candidate("mail", "mail.work"),
                candidate("meetings", "meetings.work"),
                candidate("messaging", "messaging.work"),
                candidate("notifications", "notifications.work"),
                candidate("services", "services.management"),
                candidate("services", "services.work"),
                candidate("spaces", "spaces.work"),
                candidate("workplace", "workplace.work"));
        assertThat(catalog.activeCandidates().stream()
                        .map(ProductSurfaceContextDtos.ProductCandidate::productKey)
                        .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "approvals", "calendar", "communications", "dwaion", "hcm", "mail",
                        "meetings", "messaging", "notifications", "services", "spaces",
                        "workplace");
        assertThat(catalog.rolloutProductKeys()).containsExactly(
                "approvals", "calendar", "communications", "dwaion", "hcm", "mail",
                "meetings", "messaging", "notifications", "services", "spaces", "workplace");
    }

    @Test
    void failsClosedWhenTheGeneratedIndexDoesNotBindTheCatalog() {
        byte[] malformedIndex = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new GeneratedProductSurfaceCandidateCatalog(
                new ObjectMapper(),
                contractResource(),
                new ByteArrayResource(malformedIndex),
                inventoryResource()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsClosedWhenTheRolloutInventoryIsNotChecksummed() {
        byte[] malformedInventory = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new GeneratedProductSurfaceCandidateCatalog(
                new ObjectMapper(),
                contractResource(),
                indexResource(),
                new ByteArrayResource(malformedInventory)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsClosedWhenARechecksummedInventoryReplacesAnExpectedProduct() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode inventory = (ObjectNode) objectMapper.readTree(
                inventoryResource().getInputStream());
        ((ArrayNode) inventory.get("products")).set(0, objectMapper.getNodeFactory()
                .textNode("replacement"));
        ObjectNode payload = inventory.deepCopy();
        payload.remove("checksum");
        inventory.put("checksum", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        objectMapper.writeValueAsBytes(payload))));

        assertThatThrownBy(() -> new GeneratedProductSurfaceCandidateCatalog(
                objectMapper,
                contractResource(),
                indexResource(),
                new ByteArrayResource(objectMapper.writeValueAsBytes(inventory))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact v1 set");
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
                new ObjectMapper(), contractResource(), indexResource(), inventoryResource());
    }

    private ClassPathResource contractResource() {
        return new ClassPathResource(
                "product-authorization/product-surfaces-v1.generated.json");
    }

    private ClassPathResource indexResource() {
        return new ClassPathResource(
                "product-authorization/product-surfaces-v1.index.generated.json");
    }

    private ClassPathResource inventoryResource() {
        return new ClassPathResource(
                "product-authorization/"
                        + "product-surface-rollout-inventory.v1.generated.json");
    }

    private ProductSurfaceContextDtos.ProductCandidate candidate(
            String productKey,
            String surfaceKey) {
        return new ProductSurfaceContextDtos.ProductCandidate(productKey, surfaceKey);
    }
}
