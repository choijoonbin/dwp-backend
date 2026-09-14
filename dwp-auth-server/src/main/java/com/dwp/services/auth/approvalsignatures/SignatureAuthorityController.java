package com.dwp.services.auth.approvalsignatures;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public final class SignatureAuthorityController {
    static final String PROOF = SignatureAuthorityController.class.getName() + ".verified";
    private final SignatureAuthorityService service;
    public SignatureAuthorityController(SignatureAuthorityService service) { this.service = service; }
    public record Attestation(String assertion) { }
    @PostMapping(SignatureAuthorityProtocol.PATH)
    public ApiResponse<Attestation> evaluate(HttpServletRequest request) {
        if (!(request.getAttribute(PROOF) instanceof SignatureAuthorityProofVerifier.Verified proof)) throw SignatureAuthorityJson.denied();
        return ApiResponse.success(new Attestation(service.evaluate(proof)));
    }
}
