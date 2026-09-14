package com.dwp.services.notification.domain;

record NotificationAdminDeliverySnapshot(
        long retryQueue,
        long deadLetterQueue,
        long unknownOutcomes) {
}
