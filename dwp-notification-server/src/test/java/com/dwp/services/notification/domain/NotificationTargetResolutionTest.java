package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTargetResolutionTest {

    @Test
    void acceptsOnlyApplicationRelativeDeepLinks() {
        assertThat(NotificationQueryRepository.safeTargetHref("/messages/direct?id=7")).isTrue();
        assertThat(NotificationQueryRepository.safeTargetHref("//malicious.example/path")).isFalse();
        assertThat(NotificationQueryRepository.safeTargetHref("/\\\\malicious.example/path")).isFalse();
        assertThat(NotificationQueryRepository.safeTargetHref("/%5cmalicious.example/path")).isFalse();
        assertThat(NotificationQueryRepository.safeTargetHref("/%2Fmalicious.example/path")).isFalse();
        assertThat(NotificationQueryRepository.safeTargetHref("https://malicious.example")).isFalse();
        assertThat(NotificationQueryRepository.safeTargetHref("/messages/7\nInjected")).isFalse();
        assertThat(NotificationQueryRepository.safeTargetHref("/messages/7\u007fInjected")).isFalse();
        assertThat(NotificationQueryRepository.safeTargetHref("/messages/direct?id=7#message-9")).isTrue();
        assertThat(NotificationQueryRepository.safeTargetHref(null)).isFalse();
    }
}
