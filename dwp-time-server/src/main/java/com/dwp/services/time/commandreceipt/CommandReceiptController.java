package com.dwp.services.time.commandreceipt;

import static com.dwp.services.time.commandreceipt.CommandReceiptAccessFilter.VERIFIED_REQUEST_ATTRIBUTE;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Single HTTP owner for command-receipt lookup across Time providers. */
@RestController
@ConditionalOnExpression(
        "'${dwp.hris.time.timecard.enabled:false}' == 'true'"
                + " || '${dwp.hris.time.close.enabled:false}' == 'true'")
public final class CommandReceiptController {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String DECISION_REVISION_HEADER = "X-DWP-Decision-Revision";

    private final CommandReceiptProviderRegistry registry;

    public CommandReceiptController(CommandReceiptProviderRegistry registry) {
        this.registry = registry;
    }

    @Operation(operationId = "getTimeCommandReceipt")
    @GetMapping("/v1/command-receipts/{receiptId}")
    public ResponseEntity<Object> receipt(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE)
            VerifiedCommandReceiptRequest verified,
            @PathVariable UUID receiptId) {
        Object receipt = registry.lookup(new CommandReceiptLookup(
                verified.tenantId(), receiptId, verified.correlationId(), verified));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(CORRELATION_HEADER, verified.correlationId().toString())
                .header(DECISION_REVISION_HEADER, verified.decisionRevision())
                .body(receipt);
    }
}
