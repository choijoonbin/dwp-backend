package com.dwp.services.provider.widgetregistry;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Hidden
class ProviderWidgetRegistryController {
    private final ProviderWidgetRegistryClient client;

    ProviderWidgetRegistryController(ProviderWidgetRegistryClient client) {
        this.client = client;
    }

    @RequestMapping(
            path = {
                "/v1/admin/widget-definitions",
                "/v1/admin/widget-definitions/**",
                "/v1/admin/widget-definition-versions/**",
                "/v1/admin/widget-runtime-controls",
                "/v1/admin/widget-runtime-controls/**",
                "/v1/admin/widget-registry/**"
            },
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT})
    ResponseEntity<byte[]> proxy(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        return client.forward(request, body);
    }
}
