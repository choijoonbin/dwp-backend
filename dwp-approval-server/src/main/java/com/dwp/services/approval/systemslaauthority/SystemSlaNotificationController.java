package com.dwp.services.approval.systemslaauthority;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
public final class SystemSlaNotificationController {
    static final String PROOF = "dwp.approval.system-sla.notification-proof";
    private final SystemSlaCurrentSource source;
    public SystemSlaNotificationController(SystemSlaCurrentSource source) { this.source = source; }
    @PostMapping(SystemSlaNotificationProtocol.PATH)
    public Map<String, String> evaluate(@RequestAttribute(PROOF) SystemSlaNotificationVerifier.Verified proof) { return source.recipients(proof); }
}
