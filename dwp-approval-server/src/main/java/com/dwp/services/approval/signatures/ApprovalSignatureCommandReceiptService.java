package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

final class ApprovalSignatureCommandReceiptService {
    private final boolean enabled;private final Supplier<ApprovalSignatureCommandReceiptAuthority> authority;
    private final ApprovalSignatureReceiptInstalledSource installed;private final Supplier<HttpServletRequest> requests;
    private final ApprovalSignatureCommandReceiptRepository repository;private final ApprovalSignatureCanonical json;private final TransactionTemplate reads;
    ApprovalSignatureCommandReceiptService(boolean enabled,Supplier<ApprovalSignatureCommandReceiptAuthority> authority,ApprovalSignatureReceiptInstalledSource installed,Supplier<HttpServletRequest> requests,
            ApprovalSignatureCommandReceiptRepository repository,ApprovalSignatureCanonical json,TransactionTemplate reads){this.enabled=enabled;this.authority=authority;this.installed=installed;this.requests=requests;this.repository=repository;this.json=json;this.reads=reads;}
    CommandReceipt read(Query query){
        if(!enabled)throw unavailable();var request=requests.get();var seal=installed.capture(request,query);var trusted=authority.get();var proof=trusted.require(query);
        return reads.execute(status->{
            if(!seal.equals(installed.capture(request,query)))throw conflict();var snapshot=repository.capture(seal,query);
            if(!proof.resourceSetKey().equals(snapshot.resourceSetKey()) || !proof.requestId().equals(snapshot.receipt().requestId())
                    || !proof.sourceSha().equals(json.digest(snapshot.source())) || proof.sourceCurrent()!=snapshot.receipt().sourceCurrent())throw conflict();
            trusted.unchanged(proof);if(!seal.equals(installed.capture(request,query)) || !snapshot.equals(repository.capture(seal,query)))throw conflict();return snapshot.receipt();
        });
    }
}
