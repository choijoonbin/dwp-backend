package com.dwp.services.provider.governance;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin/data-governance")
public class DataGovernanceController {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final DataGovernanceService service;
    private final ProviderAuditService audit;

    public DataGovernanceController(
            DataGovernanceService service,
            ProviderAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    public ApiResponse<DataGovernanceDtos.Snapshot> snapshot() {
        ProviderRequestContext.requirePermission("DATA_GOVERNANCE_READ");
        return ApiResponse.success(service.snapshot());
    }

    @PostMapping("/refresh")
    public ApiResponse<DataGovernanceDtos.Snapshot> refresh(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        ProviderRequestContext.requirePermission("DATA_GOVERNANCE_READ");
        DataGovernanceDtos.Snapshot snapshot = service.refresh();
        audit.success(
                "provider.data-governance.refreshed",
                "DATA_CATALOG",
                "global",
                correlationId,
                Map.of(
                        "databases", snapshot.summary().databases(),
                        "logicalTables", snapshot.summary().logicalTables(),
                        "findings", snapshot.summary().reviewRequired()));
        return ApiResponse.success(snapshot);
    }
}
