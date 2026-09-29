package com.dwp.services.auth.controller;

import static com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.*;
import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1;
import com.dwp.services.auth.service.WorkforcePolicyGovernanceAuthorityAdapterV1;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal S1 READ only; the exact dedicated chain verifies service and actor JWT. */
@RestController
public final class WorkforcePolicyGovernanceAuthorityControllerV1 {
    private final WorkforcePolicyGovernanceAuthorityAdapterV1 adapter;
    public WorkforcePolicyGovernanceAuthorityControllerV1(WorkforcePolicyGovernanceAuthorityAdapterV1 adapter) {
        this.adapter=adapter;
    }
    @PostMapping(PATH)
    public ResponseEntity<Response> evaluate(HttpServletRequest request) {
        Response result;
        try {
            if(request.getContentLengthLong()>32768)return ResponseEntity.badRequest().body(WorkforcePolicyGovernanceV1.denied(Code.MALFORMED_REQUEST));
            var body=new ByteArrayOutputStream();var input=request.getInputStream();byte[] buffer=new byte[4096];
            int read;while((read=input.read(buffer))!=-1) {
                if(body.size()+read>32768)return ResponseEntity.badRequest().body(WorkforcePolicyGovernanceV1.denied(Code.MALFORMED_REQUEST));
                body.write(buffer,0,read);
            }
            String header=request.getHeader(HttpHeaders.AUTHORIZATION);
            result=adapter.evaluate(body.toByteArray(),header!=null&&header.startsWith("Bearer ")?header.substring(7):null);
        }catch(Exception unavailable){result=WorkforcePolicyGovernanceV1.denied(Code.OWNER_SOURCE_UNAVAILABLE);}
        int status=result instanceof Success?200:status(((Failure)result).code());
        return ResponseEntity.status(status).body(result);
    }
    private static int status(Code code) {
        return switch(code) {
            case MALFORMED_REQUEST,UNSUPPORTED_SCHEMA,BODY_DIGEST_MISMATCH,ROLE_CODE_INVALID->400;
            case ACTOR_JWT_INVALID,CALLER_UNAUTHORIZED->401;
            case OWNER_SOURCE_UNAVAILABLE->503;
            default->403;
        };
    }
}
