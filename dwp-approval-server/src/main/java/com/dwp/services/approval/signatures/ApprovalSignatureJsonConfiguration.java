package com.dwp.services.approval.signatures;

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

/** Strictness is scoped to the four new commands; all other DTOs retain their mapper policy. */
@Configuration(proxyBeanMethods=false)
public class ApprovalSignatureJsonConfiguration {
    @Bean Jackson2ObjectMapperBuilderCustomizer approvalSignatureStrictInputCustomizer() {
        Set<Class<?>> inputs=Set.of(ApprovalSignatureDtos.Create.class,ApprovalSignatureDtos.Consent.class,
                ApprovalSignatureDtos.Sign.class,ApprovalSignatureDtos.Cancel.class);
        return builder -> builder.postConfigurer(mapper -> mapper.addHandler(new DeserializationProblemHandler() {
            @Override public boolean handleUnknownProperty(DeserializationContext context,JsonParser parser,
                    JsonDeserializer<?> deserializer,Object bean,String property) throws IOException {
                Class<?> type=bean instanceof Class<?> value?value:bean.getClass();
                if (!inputs.contains(type)) return false;
                throw UnrecognizedPropertyException.from(parser,bean,property,java.util.List.of());
            }
        }));
    }
}
