package com.dwp.services.auth.hrisperformancefeature;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PerformanceFeatureController {
    private final PerformanceFeatureWorkloadVerifier verifier;
    private final PerformanceFeatureService service;
    public PerformanceFeatureController(PerformanceFeatureWorkloadVerifier verifier,
            PerformanceFeatureService service) {
        this.verifier = verifier; this.service = service;
    }

    @PostMapping(value = PerformanceFeatureContract.PATH, consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> query(@RequestBody byte[] body, HttpServletRequest servlet) {
        try {
            var headers = new ArrayList<PerformanceFeatureWorkloadVerifier.Header>();
            for (String name : Collections.list(servlet.getHeaderNames())) {
                for (String value : Collections.list(servlet.getHeaders(name))) {
                    headers.add(new PerformanceFeatureWorkloadVerifier.Header(name, value));
                }
            }
            var request = verifier.verify(servlet.getMethod(), servlet.getRequestURI(),
                    servlet.getQueryString(), headers, body);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.evaluate(request));
        } catch (IllegalArgumentException denied) {
            return ResponseEntity.status(403).header("Cache-Control", "no-store").build();
        }
    }
}
