package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthoritySnapshot;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.HrisExplorerReceipt;

/**
 * Shared command-receipt boundary used by Explorer and non-Explorer HRIS
 * commands. The Explorer endpoints remain independently feature-gated.
 */
@RestController
@ConditionalOnExpression(
        "'${dwp.hris.explorer.enabled:false}' == 'true' || "
                + "'${dwp.hris.controlled-transfer.enabled:false}' == 'true' || "
                + "'${dwp.hris.automation-control-plane.enabled:false}' == 'true' || "
                + "'${dwp.hris.integration-control-plane.enabled:false}' == 'true'")
final class HrisCommandReceiptController {

    private final HrisExplorerService service;

    HrisCommandReceiptController(HrisExplorerService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/hris/command-receipts/{receiptId}")
    ResponseEntity<HrisExplorerReceipt> receipt(
            HttpServletRequest request, @PathVariable UUID receiptId) {
        Object authorityValue = request.getAttribute(
                HcmPlatformPepRegistry.class.getName() + ".authority");
        if (!(authorityValue instanceof HcmPlatformPepRegistry.RouteAuthority authority)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Exact HRIS receipt authority is required");
        }
        HrisExplorerReceipt receipt;
        if ("explorer-originating-receipt-reader".equals(authority.profileKey())) {
            receipt = service.explorerReceipt(snapshot(request), receiptId);
        } else if ("platform-originating-command-receipt-reader".equals(
                authority.profileKey())) {
            Object verifiedValue = request.getAttribute(
                    HrisPlatformReceiptAuthorityAdapter.VERIFIED_ATTRIBUTE);
            if (!(verifiedValue instanceof
                    HrisPlatformReceiptAuthorityAdapter.VerifiedReceiptAuthority verified)) {
                throw new BaseException(ErrorCode.NOT_FOUND,
                        "HRIS command receipt not found");
            }
            receipt = service.platformReceipt(
                    verified.tenantId(), verified.subjectPrincipalPublicId(),
                    receiptId);
        } else {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Exact HRIS receipt profile is required");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(receipt);
    }

    private AuthoritySnapshot snapshot(HttpServletRequest request) {
        Object value = request.getAttribute(
                HrisExplorerOwnerEvidenceAdapter.SNAPSHOT_ATTRIBUTE);
        if (value instanceof AuthoritySnapshot snapshot) return snapshot;
        throw new BaseException(ErrorCode.FORBIDDEN,
                "Verified Explorer owner evidence is required");
    }
}
