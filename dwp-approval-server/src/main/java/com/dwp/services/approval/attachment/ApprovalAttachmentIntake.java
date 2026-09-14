package com.dwp.services.approval.attachment;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class ApprovalAttachmentIntake {
    private final ApprovalAttachmentIntakeCommands commands;
    private final ApprovalAttachmentProviderGate providers;
    private final ApprovalAttachmentInput reader;
    private final Semaphore transfers=new Semaphore(2);
    public ApprovalAttachmentIntake(ApprovalAttachmentIntakeCommands commands,ApprovalAttachmentProviderGate providers,ApprovalAttachmentInput reader){this.commands=commands;this.providers=providers;this.reader=reader;}
    public ApprovalAttachmentDtos.Upload upload(UUID id,Long version,String key,InputStream input) throws IOException {
        return transfer(id,version,key,input,false);
    }
    public ApprovalAttachmentDtos.Upload reconcile(UUID id,Long version,String key) {
        try{return transfer(id,version,key,null,true);}catch(IOException impossible){throw ApprovalDocumentCanonical.unavailable("Attachment reconciliation failed.");}
    }
    private ApprovalAttachmentDtos.Upload transfer(UUID id,Long version,String key,InputStream input,boolean reconcile) throws IOException {
        if(!transfers.tryAcquire()) throw ApprovalDocumentCanonical.unavailable("Attachment transfer capacity is unavailable.");
        try {
            var transfer=commands.begin(id,version,key,reconcile);var lease=transfer.lease();if(lease==null) return transfer.upload();
            byte[] bytes=null;
            if(!reconcile) {
                try{bytes=reader.read(input,lease.sizeBytes(),java.time.Duration.ofSeconds(30));ApprovalAttachmentIntegrity.require(bytes,lease.sizeBytes(),lease.sha256());}
                catch(RuntimeException|IOException rejected){commands.inputRejected(lease);throw rejected;}
            }
            ApprovalAttachmentStorage.Stored stored;
            try {
                if(reconcile) stored=providers.storage().reconcile(lease.objectKey(),lease.sizeBytes(),lease.sha256());
                else stored=providers.storage().put(lease.objectKey(),bytes,lease.sha256());
            } catch(RuntimeException uncertain){commands.uncertain(lease);throw uncertain;}
            return commands.stored(lease,stored);
        } finally {transfers.release();}
    }
}
