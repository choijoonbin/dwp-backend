package com.dwp.services.synapsex.client;

import com.dwp.services.synapsex.dto.auth.InternalAuditLogRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "auth-server-audit-log",
        url = "${auth.server.url:http://localhost:8001}"
)
public interface AuthServerAuditLogClient {

    @PostMapping("/internal/audit-logs")
    ResponseEntity<Void> recordAuditLog(@RequestBody InternalAuditLogRequest request);
}
