package com.dwp.services.notification.domain;

/** Minimal context contract consumed by inbox SQL composition. */
interface NotificationInboxContext {
    String kind();

    String key();
}
