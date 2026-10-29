package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/mail/proposals")
final class InternalMailProposalIngestController {

    private final MailProposalIngestService service;

    InternalMailProposalIngestController(MailProposalIngestService service) {
        this.service = service;
    }

    @PostMapping
    ApiResponse<MailProposalIngestDtos.Receipt> ingest(
            @RequestHeader("X-DWP-Service-Identity") String producerIdentity,
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false)
            String correlationId,
            @Valid @RequestBody MailProposalIngestDtos.Request request) {
        return ApiResponse.success(service.ingest(
                producerIdentity, tenantId, actorId, correlationId, request));
    }
}
