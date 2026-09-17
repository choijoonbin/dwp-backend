package com.dwp.services.approval.deployment;

import com.dwp.services.approval.analytics.ApprovalAnalyticsController;
import com.dwp.services.approval.auditrecords.ApprovalAuditController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalOperationsEndpointMatrixTest {
    private static final Set<String> STEP_UP_COMMAND = Set.of(
            "Idempotency-Key", "X-DWP-Expected-Decision-Revision",
            "X-DWP-Expected-Object-Version", "X-DWP-Step-Up-Challenge");

    @Test
    void exactMethodPathBodyAndRequiredHeaderMatrixIsStable() {
        assertThat(endpoints(
                ApprovalAuditController.class,
                ApprovalAnalyticsController.class,
                ApprovalDeploymentController.class))
                .containsExactlyInAnyOrder(
                        get("/v1/admin/operations/audit-records/events"),
                        get("/v1/admin/operations/audit-records/events/{eventId}"),
                        get("/v1/admin/operations/audit-records/requests/{requestId}/retention-linkage"),
                        get("/v1/admin/operations/audit-records/saved-views"),
                        get("/v1/admin/operations/audit-records/saved-views/{savedViewId}"),
                        post("/v1/admin/operations/audit-records/saved-views",
                                "SavedViewCreate", STEP_UP_COMMAND),
                        get("/v1/admin/operations/audit-records/exports/{exportId}"),
                        get("/v1/admin/operations/audit-records/exports/{exportId}/verifications"),
                        post("/v1/admin/operations/audit-records/exports",
                                "ExportCreate", STEP_UP_COMMAND),
                        post("/v1/admin/operations/audit-records/exports/{exportId}/verifications",
                                "ExportVerification", STEP_UP_COMMAND),
                        post("/v1/admin/operations/audit-records/exports/{exportId}/external-attestations",
                                "ExternalAttestation", STEP_UP_COMMAND),

                        get("/v1/admin/operations/analytics/metric-definitions"),
                        get("/v1/admin/operations/analytics/dashboard"),
                        get("/v1/admin/operations/analytics/cohorts/{cohortKey}/representatives"),

                        get("/v1/admin/operations/deployments/dashboard"),
                        get("/v1/admin/operations/deployments/packages"),
                        get("/v1/admin/operations/deployments/packages/{packageId}"),
                        get("/v1/admin/operations/deployments/package-diff"),
                        post("/v1/admin/operations/deployments/packages",
                                "PackageCreate", STEP_UP_COMMAND),
                        get("/v1/admin/operations/deployments/promotions"),
                        get("/v1/admin/operations/deployments/promotions/{promotionId}"),
                        post("/v1/admin/operations/deployments/promotions",
                                "PromotionCreate", STEP_UP_COMMAND),
                        post("/v1/admin/operations/deployments/promotions/{promotionId}/approval",
                                "Review", STEP_UP_COMMAND),
                        post("/v1/admin/operations/deployments/promotions/{promotionId}/schedule",
                                "Schedule", STEP_UP_COMMAND),
                        post("/v1/admin/operations/deployments/promotions/{promotionId}/activation",
                                null, STEP_UP_COMMAND),
                        post("/v1/admin/operations/deployments/promotions/{promotionId}/activation-evidence",
                                "ExternalEvidence", STEP_UP_COMMAND),
                        get("/v1/admin/operations/deployments/promotions/{promotionId}/rollback-feasibility"),
                        post("/v1/admin/operations/deployments/promotions/{promotionId}/rollback",
                                "Rollback", STEP_UP_COMMAND),
                        post("/v1/admin/operations/deployments/promotions/{promotionId}/rollback-evidence",
                                "ExternalEvidence", STEP_UP_COMMAND));
    }

    private Set<Endpoint> endpoints(Class<?>... controllers) {
        Set<Endpoint> endpoints = new TreeSet<>();
        for (Class<?> controller : controllers) {
            String base = controller.getAnnotation(RequestMapping.class).value()[0];
            Arrays.stream(controller.getDeclaredMethods())
                    .map(method -> endpoint(base, method))
                    .flatMap(java.util.Optional::stream)
                    .forEach(endpoints::add);
        }
        return endpoints;
    }

    private java.util.Optional<Endpoint> endpoint(String base, Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (get == null && post == null) {
            return java.util.Optional.empty();
        }
        String httpMethod = get == null ? "POST" : "GET";
        String[] paths = get == null ? post.value() : get.value();
        String path = paths.length == 0 ? "" : paths[0];
        String body = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(RequestBody.class))
                .map(parameter -> parameter.getType().getSimpleName())
                .findFirst().orElse(null);
        Set<String> headers = new TreeSet<>();
        Arrays.stream(method.getParameters()).forEach(parameter -> {
            RequestHeader header = parameter.getAnnotation(RequestHeader.class);
            if (header != null && header.required()) {
                headers.add(header.name().isBlank() ? header.value() : header.name());
            }
        });
        return java.util.Optional.of(new Endpoint(
                httpMethod, base + path, body, Set.copyOf(headers)));
    }

    private static Endpoint get(String path) {
        return new Endpoint("GET", path, null, Set.of());
    }

    private static Endpoint post(String path, String body, Set<String> headers) {
        return new Endpoint("POST", path, body, headers);
    }

    private record Endpoint(
            String method,
            String path,
            String body,
            Set<String> requiredHeaders) implements Comparable<Endpoint> {
        @Override
        public int compareTo(Endpoint other) {
            return Stream.of(method, path, String.valueOf(body))
                    .collect(java.util.stream.Collectors.joining(" "))
                    .compareTo(Stream.of(other.method, other.path, String.valueOf(other.body))
                            .collect(java.util.stream.Collectors.joining(" ")));
        }
    }
}
