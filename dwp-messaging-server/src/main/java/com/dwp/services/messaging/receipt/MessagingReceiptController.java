package com.dwp.services.messaging.receipt;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/conversations/{conversationId}")
public class MessagingReceiptController {
    private final MessagingReceiptService service;

    public MessagingReceiptController(MessagingReceiptService service) {
        this.service = service;
    }

    @GetMapping("/read-receipts")
    public ResponseEntity<ApiResponse<List<MessagingReceiptDtos.ReceiptSummary>>> receipts(
            @PathVariable UUID conversationId, @RequestParam List<UUID> messageIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.receipts(conversationId, messageIds)));
    }

    @GetMapping("/messages/{messageId}/receipts")
    public ResponseEntity<ApiResponse<MessagingReceiptDtos.ReceiptSummary>> receipt(
            @PathVariable UUID conversationId, @PathVariable UUID messageId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.receipt(conversationId, messageId)));
    }

    @PostMapping("/read-receipts")
    public ResponseEntity<ApiResponse<MessagingReceiptDtos.ObservationResponse>> observe(
            @PathVariable UUID conversationId, @Valid @RequestBody MessagingReceiptDtos.ObserveRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.observe(conversationId, request)));
    }
}
