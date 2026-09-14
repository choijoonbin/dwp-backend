package com.dwp.services.approval.attachment;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class ApprovalAttachmentProviderGate {
    private final Optional<ApprovalAttachmentStorage> storage;
    private final Optional<ApprovalAttachmentScanner> scanner;
    public ApprovalAttachmentProviderGate(Optional<ApprovalAttachmentStorage> storage,Optional<ApprovalAttachmentScanner> scanner) {this.storage=storage;this.scanner=scanner;}
    public String readiness() {
        if(storage.isEmpty() || scanner.isEmpty()) return "NOT_CONFIGURED";
        String stored=storage.get().readiness();if(!"VERSIONING_VERIFIED".equals(stored)) return stored;
        String scanned=scanner.get().readiness();if(!"ENGINE_VERIFIED".equals(scanned)) return scanned;
        return "COMPONENTS_VERIFIED_NOT_SANITIZED";
    }
    public ApprovalAttachmentStorage storage() {return storage.orElseThrow(()->ApprovalDocumentCanonical.unavailable("Attachment storage is not configured."));}
    public String downloadReadiness(){return storage.map(ApprovalAttachmentStorage::readiness).orElse("NOT_CONFIGURED");}
    public void requireDownload(){if(!"VERSIONING_VERIFIED".equals(downloadReadiness())) throw ApprovalDocumentCanonical.unavailable("Attachment download storage evidence is unavailable.");}
    public void requireIngestion() {if(!"COMPONENTS_VERIFIED_NOT_SANITIZED".equals(readiness())) throw ApprovalDocumentCanonical.unavailable("Attachment storage/scanner evidence is unavailable.");}
}
