package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/calendar/settings")
public class CalendarSettingsController {

    private final CalendarSettingsService service;

    public CalendarSettingsController(CalendarSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CalendarSettingsDtos.Settings> settings(
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.settings(CalendarSettingsAccess.require(request)));
    }

    @PutMapping
    public ApiResponse<CalendarSettingsDtos.Settings> updateSettings(
            HttpServletRequest request,
            HttpServletResponse response,
            @Valid @RequestBody CalendarSettingsDtos.UpdateSettingsRequest body) {
        noStore(response);
        return ApiResponse.success(service.updateSettings(
                CalendarSettingsAccess.require(request),
                CalendarSettingsAccess.correlationId(request),
                body));
    }

    @PostMapping("/reset")
    public ApiResponse<CalendarSettingsDtos.Settings> resetSettings(
            HttpServletRequest request,
            HttpServletResponse response,
            @Valid @RequestBody CalendarSettingsDtos.ResetSettingsRequest body) {
        noStore(response);
        return ApiResponse.success(service.resetSettings(
                CalendarSettingsAccess.require(request),
                CalendarSettingsAccess.correlationId(request),
                body));
    }

    @GetMapping("/delegations")
    public ApiResponse<List<CalendarSettingsDtos.Delegation>> delegations(
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.delegations(CalendarSettingsAccess.require(request)));
    }

    @PostMapping("/delegations")
    public ApiResponse<CalendarSettingsDtos.Delegation> createDelegation(
            HttpServletRequest request,
            HttpServletResponse response,
            @Valid @RequestBody CalendarSettingsDtos.CreateDelegationRequest body) {
        noStore(response);
        return ApiResponse.success(service.createDelegation(
                CalendarSettingsAccess.require(request),
                CalendarSettingsAccess.correlationId(request),
                body));
    }

    @PostMapping("/delegations/{delegationId}/revoke")
    public ApiResponse<CalendarSettingsDtos.Delegation> revokeDelegation(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID delegationId,
            @Valid @RequestBody CalendarSettingsDtos.RevokeDelegationRequest body) {
        noStore(response);
        return ApiResponse.success(service.revokeDelegation(
                CalendarSettingsAccess.require(request),
                delegationId,
                CalendarSettingsAccess.correlationId(request),
                body));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
