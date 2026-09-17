package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyEmergencyContactDtos.EmergencyContactView;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyUserController.*;

@RestController
@RequestMapping("/v1/workplace/safety")
public class SafetyEmergencyContactUserController {
    private final SafetyEmergencyContactService service;

    public SafetyEmergencyContactUserController(SafetyEmergencyContactService service) {
        this.service = service;
    }

    @GetMapping("/incidents/{incidentId}/emergency-contacts")
    public ApiResponse<List<EmergencyContactView>> contacts(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.contactsForUser(tenantId, userId, incidentId));
    }
}
