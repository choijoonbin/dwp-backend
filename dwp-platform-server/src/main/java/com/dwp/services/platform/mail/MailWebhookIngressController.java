package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;

/** Authentication is provider-specific; this controller never logs or persists callback bytes. */
@RestController
@RequestMapping("/internal/v1/mail/webhooks")
class MailWebhookIngressController {

    private final MailWebhookIngressService ingress;

    MailWebhookIngressController(MailWebhookIngressService ingress) {
        this.ingress = ingress;
    }

    @PostMapping("/{provider}")
    ResponseEntity<?> accept(
            @PathVariable String provider,
            HttpServletRequest request) {
        String validationToken = MailWebhookSecurityFilter.validationToken(request);
        if (validationToken != null) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .header("Cache-Control", "no-store")
                    .body(validationToken);
        }
        MailWebhookVerificationPort.Verification verification =
                MailWebhookSecurityFilter.verification(request);
        MailSyncJobRepository.WebhookEnqueue accepted =
                ingress.acceptVerified(verification);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                new WebhookAccepted(
                        accepted.operationId(), accepted.jobId(), "ACCEPTED")));
    }

    record WebhookAccepted(UUID operationId, UUID jobId, String state) { }
}
