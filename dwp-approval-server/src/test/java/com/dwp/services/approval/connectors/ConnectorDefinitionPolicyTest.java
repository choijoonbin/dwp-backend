package com.dwp.services.approval.connectors;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.connectors.ConnectorModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorDefinitionPolicyTest {
    @Test
    void acceptsVaultReferencesAndRedactsTokenLikeDiagnostics() {
        ConnectorDefinitionPolicy.validate(draft(
                "https://erp.example.com/v1/approval", "vault://tenant-42/approval/erp",
                List.of("X-Correlation-Id"), Map.of("requestId", "$.requestId")));

        Map<String, Object> safe = ConnectorDefinitionPolicy.sanitizeDiagnostics(Map.of(
                "status", 503,
                "message", "Bearer abcdefghijklmnopqrstuvwxyz0123456789",
                "ignoredSecret", "must not survive"));

        assertThat(safe).containsEntry("status", 503)
                .containsEntry("message", "[REDACTED]")
                .doesNotContainKey("ignoredSecret");
    }

    @Test
    void rejectsPrivateEndpointsRawCredentialsDangerousHeadersAndSensitiveMappings() {
        assertThatThrownBy(() -> ConnectorDefinitionPolicy.validate(draft(
                "https://127.0.0.1/admin", "vault://tenant/path",
                List.of("X-Correlation-Id"), Map.of("requestId", "$.id"))))
                .isInstanceOf(ConnectorRejected.class);
        assertThatThrownBy(() -> ConnectorDefinitionPolicy.validate(draft(
                "https://erp.example.com/api", "plain-secret",
                List.of("X-Correlation-Id"), Map.of("requestId", "$.id"))))
                .isInstanceOf(ConnectorRejected.class);
        assertThatThrownBy(() -> ConnectorDefinitionPolicy.validate(draft(
                "https://erp.example.com/api", "vault://tenant/path",
                List.of("Authorization"), Map.of("requestId", "$.id"))))
                .isInstanceOf(ConnectorRejected.class);
        assertThatThrownBy(() -> ConnectorDefinitionPolicy.validate(draft(
                "https://erp.example.com/api", "vault://tenant/path",
                List.of("X-Correlation-Id"), Map.of("accessToken", "$.token"))))
                .isInstanceOf(ConnectorRejected.class);
    }

    private ConnectorDraft draft(
            String endpoint, String credential, List<String> headers, Map<String, Object> mapping) {
        return new ConnectorDraft(UUID.randomUUID(), "ERP.PRIMARY", "ERP", ConnectorType.ERP,
                endpoint, credential, headers, mapping, Map.of("remoteId", "$.id"),
                2_000, 120, 3, 100, 2_000,
                IdempotencyMode.HEADER, SigningMode.HMAC_SHA256, 0);
    }
}
