package com.dwp.gateway.productsurface;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

final class ProductSurfaceContextAggregationSupport {

    private ProductSurfaceContextAggregationSupport() {
    }

    static ProductSurfaceContextDtos.SourceRevisions aggregateRevisions(
            ProductSurfaceContextDtos.RequestContext requestContext,
            List<Resolution> resolutions) {
        return new ProductSurfaceContextDtos.SourceRevisions(
                collapse(resolutions.stream().map(value -> value.revisions().auth()).toList()),
                collapse(resolutions.stream().map(value -> value.revisions().policy()).toList()),
                collapse(resolutions.stream()
                        .map(value -> value.revisions().productRelationship()).toList()),
                collapse(resolutions.stream()
                        .map(value -> value.revisions().targetPopulation()).toList()),
                requestContext.supportRevision());
    }

    private static String collapse(List<String> revisions) {
        List<String> values = revisions.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (values.isEmpty()) return null;
        if (values.size() == 1) return values.getFirst();
        return "multi-" + digest(String.join("\n", values));
    }

    static String compositeRevision(
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.SourceRevisions revisions) {
        return compositeRevision(requestContext, revisions, List.of());
    }

    static String compositeRevision(
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.SourceRevisions revisions,
            List<ProductSurfaceContextDtos.ProductRollout> rollouts) {
        StringBuilder material = new StringBuilder(String.join("\n",
                Long.toString(requestContext.tenantId()),
                Long.toString(requestContext.actorId()),
                requestContext.activeAccessMode().name(),
                Objects.toString(revisions.auth(), ""),
                Objects.toString(revisions.policy(), ""),
                Objects.toString(revisions.productRelationship(), ""),
                Objects.toString(revisions.targetPopulation(), ""),
                Objects.toString(revisions.support(), "")));
        rollouts.forEach(value -> material.append('\n')
                .append(value.productKey()).append(':').append(value.state()).append(':')
                .append(value.opaqueRevision()).append(':').append(value.authorityStatus()));
        return "psr-" + digest(material.toString());
    }

    private static String digest(String material) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    static OffsetDateTime earliest(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
