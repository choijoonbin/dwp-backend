package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ApprovalDeploymentValidationTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    @Test
    void cyclicAndStaleDependencyGraphsAreRejectedBeforePersistence() {
        ApprovalDeploymentService service = new ApprovalDeploymentService(
                mock(ApprovalDeploymentRepository.class),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-09-16T05:00:00Z"), ZoneOffset.UTC),
                mock(ApprovalDeploymentAttestationVerifier.class));
        Asset first = new Asset(
                "form:a", AssetType.FORM, UUID.randomUUID(), "1", A,
                RollbackDisposition.REVERSIBLE, false);
        Asset second = new Asset(
                "workflow:b", AssetType.WORKFLOW, UUID.randomUUID(), "1", B,
                RollbackDisposition.REVERSIBLE, false);
        PackageCommand cycle = new PackageCommand(
                UUID.randomUUID(), "APR.CYCLE", 1, "Cycle",
                List.of(first, second), List.of(
                        new Dependency("form:a", "workflow:b", B, false),
                        new Dependency("workflow:b", "form:a", A, false)));

        assertThatThrownBy(() -> service.createPackage(scope(), cycle))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        PackageCommand stale = new PackageCommand(
                UUID.randomUUID(), "APR.STALE", 1, "Stale",
                List.of(first, second), List.of(
                        new Dependency("form:a", "workflow:b", A, false)));
        assertThatThrownBy(() -> service.createPackage(scope(), stale))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    private Scope scope() {
        return new Scope(
                42, "RS_APPROVALS", 17,
                Set.of(Capability.CREATE_PACKAGE));
    }
}
