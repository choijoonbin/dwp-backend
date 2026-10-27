package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.util.UUID;

/** Read-only terminal command view; public command records retain their identity. */
interface Hrm013IdentityTerminalCommand {
    long tenantId();
    UUID dispatchEventId();
    UUID callbackEventId();
    UUID externalReceiptPublicId();
    String status();
    Hrm013WorkloadProofV1.AuthorityMode authorityMode();
    Hrm013WorkloadProofV1.DrainClaims drainClaims();
}
