package com.dwp.services.approval.forms;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.io.IOException;
import java.util.Set;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApprovalFormLifecycleJsonConfiguration {
    private static final Set<Class<?>> INPUTS = Set.of(ApprovalFormLifecycleDtos.MetadataInput.class,
            ApprovalFormLifecycleDtos.Branch.class, ApprovalFormLifecycleDtos.AvailabilityChange.class,
            ApprovalFormLifecycleDtos.PublishReviewed.class, ApprovalFormLifecycleDtos.UpdateWorkingDraft.class,
            ApprovalFormLifecycleDtos.RequestPublishReview.class,
            ApprovalFormLifecycleDtos.RejectPublishReview.class);

    @Bean Jackson2ObjectMapperBuilderCustomizer approvalFormLifecycleStrictInputCustomizer() {
        return builder -> builder.postConfigurer(mapper -> mapper.addHandler(new DeserializationProblemHandler() {
            @Override public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
                    JsonDeserializer<?> deserializer, Object beanOrClass, String propertyName) throws IOException {
                Class<?> type = beanOrClass instanceof Class<?> candidate ? candidate
                        : beanOrClass == null ? null : beanOrClass.getClass();
                if (type == null || !INPUTS.contains(type)) return false;
                throw UnrecognizedPropertyException.from(parser, beanOrClass, propertyName, deserializer.getKnownPropertyNames());
            }
        }));
    }
}
