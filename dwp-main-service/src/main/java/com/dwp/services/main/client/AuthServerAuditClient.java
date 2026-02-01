package com.dwp.services.main.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * auth-server 내부 감사 API Feign 클라이언트
 *
 * HITL 승인/거절 등 이벤트를 com_audit_logs에 기록하기 위해 호출합니다.
 * auth-server는 204 No Content를 반환하므로 Void로 수신 (ApiResponse 디코딩 이슈 회피).
 */
@FeignClient(
        name = "auth-server-audit",
        url = "${auth.server.url:http://localhost:8001}"
)
public interface AuthServerAuditClient {

    @PostMapping("/internal/audit-logs")
    ResponseEntity<Void> recordAuditLog(@RequestBody InternalAuditLogRequest request);
}
