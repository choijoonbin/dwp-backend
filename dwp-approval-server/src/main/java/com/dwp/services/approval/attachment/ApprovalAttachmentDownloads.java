package com.dwp.services.approval.attachment;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class ApprovalAttachmentDownloads {
    public record Content(String fileName,byte[] bytes,String sha256) { }
    private final ApprovalAttachmentDownloadCommands commands;
    private final ApprovalAttachmentProviderGate providers;
    private final Semaphore transfers=new Semaphore(2);
    public ApprovalAttachmentDownloads(ApprovalAttachmentDownloadCommands commands,ApprovalAttachmentProviderGate providers) {this.commands=commands;this.providers=providers;}
    public Content load(UUID grant) {
        if(!transfers.tryAcquire()) throw ApprovalDocumentCanonical.unavailable("Attachment transfer capacity is unavailable.");
        try {
            var pin=commands.begin(grant);byte[] bytes=providers.storage().load(pin.stored());
            commands.finish(pin,bytes);return new Content(pin.fileName(),bytes,pin.stored().sha256());
        } finally {transfers.release();}
    }
}
