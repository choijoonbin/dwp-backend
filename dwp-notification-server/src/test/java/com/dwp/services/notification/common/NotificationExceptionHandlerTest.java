package com.dwp.services.notification.common;

import com.dwp.services.notification.realtime.NotificationStreamCapacityException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationExceptionHandlerTest {

    @Test
    void streamCapacityUsesAContentFreeRetryableResponseForEventStreamClients() {
        NotificationExceptionHandler handler = new NotificationExceptionHandler();

        var response = handler.streamCapacity(new NotificationStreamCapacityException());

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(Integer.toString(NotificationStreamCapacityException.RETRY_AFTER_SECONDS));
        assertThat(response.getBody()).isNull();
    }
}
