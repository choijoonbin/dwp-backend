package com.dwp.services.messaging.presence;

import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/presence")
public class MessagingPresenceController {

    public static final String LEASE_TTL_HEADER = "X-DWP-Presence-Lease-TTL-Seconds";
    public static final String REFRESH_AFTER_HEADER = "X-DWP-Presence-Refresh-After-Seconds";

    private final MessagingPresenceService presence;

    public MessagingPresenceController(MessagingPresenceService presence) {
        this.presence = presence;
    }

    @PutMapping("/heartbeat")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Presence lease renewed",
                    headers = {
                            @Header(
                                    name = LEASE_TTL_HEADER,
                                    description = "Authoritative renewed lease lifetime in whole seconds",
                                    schema = @Schema(type = "integer", format = "int64", minimum = "30", maximum = "300", example = "75")),
                            @Header(
                                    name = REFRESH_AFTER_HEADER,
                                    description = "Server-recommended delay before the next heartbeat in whole seconds",
                                    schema = @Schema(type = "integer", format = "int64", minimum = "5", example = "20"))
                    }),
            @ApiResponse(responseCode = "503", description = "Presence authority unavailable")
    })
    public ResponseEntity<Void> heartbeat() {
        if (!presence.heartbeat()) return ResponseEntity.status(503).build();
        MessagingPresenceService.LeaseTiming timing = presence.leaseTiming();
        return ResponseEntity.noContent()
                .header(LEASE_TTL_HEADER, Long.toString(timing.leaseTtlSeconds()))
                .header(REFRESH_AFTER_HEADER, Long.toString(timing.refreshAfterSeconds()))
                .build();
    }
}
