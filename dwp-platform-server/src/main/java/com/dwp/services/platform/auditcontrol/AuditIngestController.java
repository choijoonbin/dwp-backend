package com.dwp.services.platform.auditcontrol;

import com.dwp.audit.AuditEvent;
import com.dwp.audit.HttpAuditEventPublisher;
import com.dwp.core.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(AuditIngestSecurityFilter.COLLECTOR_PATH)
public class AuditIngestController {

    private final AuditControlService service;

    public AuditIngestController(AuditControlService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<IngestResult> ingest(
            @RequestHeader(HttpAuditEventPublisher.SERVICE_NAME_HEADER) String claimedService,
            @RequestBody List<AuditEvent> events) {
        return ApiResponse.success(new IngestResult(service.ingest(claimedService, events)));
    }

    public record IngestResult(int accepted) { }
}
