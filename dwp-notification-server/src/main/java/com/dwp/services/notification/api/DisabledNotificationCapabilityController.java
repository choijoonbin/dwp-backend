package com.dwp.services.notification.api;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class DisabledNotificationCapabilityController {

    @PostMapping("/intents/audience")
    public void organizationAudience() {
        throw disabled("Organization and role fan-out requires the People snapshot contract.");
    }

    @PostMapping("/delivery/{channel}")
    public void externalDelivery(@PathVariable String channel) {
        throw disabled("External notification channel adapters are disabled.");
    }

    private NotificationException disabled(String message) {
        return new NotificationException(
                NotificationErrorCode.NOTIFICATION_CAPABILITY_DISABLED, message);
    }
}
