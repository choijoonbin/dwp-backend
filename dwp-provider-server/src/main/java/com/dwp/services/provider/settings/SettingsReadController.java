package com.dwp.services.provider.settings;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/settings")
public class SettingsReadController {

    private final SettingsReadService service;

    public SettingsReadController(SettingsReadService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<SettingsContracts.Definition>> catalog(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String ownerService,
            @RequestParam(required = false) SettingsContracts.ScopeType scopeType) {
        return ApiResponse.success(service.catalog(query, ownerService, scopeType));
    }

    @GetMapping("/{settingId}/effective")
    public ApiResponse<SettingsContracts.Resolution> effective(
            @PathVariable String settingId,
            @RequestParam SettingsContracts.ScopeType scopeType,
            @RequestParam String scopeId,
            @RequestParam(required = false) String environment) {
        return ApiResponse.success(service.effective(
                settingId,
                new SettingsContracts.ScopeTarget(scopeType, scopeId, environment)));
    }
}
