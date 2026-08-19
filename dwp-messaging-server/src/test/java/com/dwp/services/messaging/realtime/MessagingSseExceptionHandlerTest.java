package com.dwp.services.messaging.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingSseExceptionHandlerTest {

    @Test
    void disconnectedClientIsResolvedBeforeTheGlobalJsonErrorHandler() {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(MessagingSseExceptionHandler.class);
        Order order = AnnotationUtils.findAnnotation(MessagingSseExceptionHandler.class, Order.class);
        Method method = resolver.resolveMethod(new AsyncRequestNotUsableException("client disconnected"));

        assertThat(method).isNotNull();
        assertThat(method.getName()).isEqualTo("handleDisconnectedClient");
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
