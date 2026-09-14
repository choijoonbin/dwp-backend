package com.dwp.services.auth.retentionexecutionauthority;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.dwp.services.auth.service.RetentionExecutionIdentityAuthorityBridge;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RetentionExecutionConfiguration {
    @Bean RetentionExecutionJson retentionExecutionJson() {return new RetentionExecutionJson();}
    @Bean @Lazy RetentionExecutionKeys retentionExecutionKeys(RetentionExecutionJson json,ConfigurableEnvironment env) {
        var forbidden=new ArrayList<String>();var names=new TreeSet<String>();
        for(var source:env.getPropertySources()) if(source instanceof EnumerablePropertySource<?> enumerable) Collections.addAll(names,enumerable.getPropertyNames());
        for(String name:names) if(name.startsWith("dwp.") && !name.startsWith(PREFIX) && name.matches(".*(key|jwk|jwks|pem).*")) {
            String value=env.getProperty(name,"");
            if(!value.isBlank() && (value.stripLeading().startsWith("{") || value.startsWith("-----BEGIN") || name.endsWith("base64"))) forbidden.add(value);
            else if(name.endsWith("key-id") && !value.isBlank()) forbidden.add("kid:"+value);
        }
        return new RetentionExecutionKeys(json,env.getProperty(PREFIX+"owner-public-jwks",""),env.getProperty(PREFIX+"transport-public-jwks",""),
                env.getProperty(PREFIX+"execution-private-key-pkcs8-base64",""),env.getProperty(PREFIX+"execution-public-key-x509-base64",""),
                env.getProperty(PREFIX+"execution-issuer",""),env.getProperty(PREFIX+"execution-key-id",""),forbidden);
    }
    @Bean @Lazy RetentionExecutionProofVerifier retentionExecutionProofVerifier(RetentionExecutionJson json,RetentionExecutionKeys keys) {
        return new RetentionExecutionProofVerifier(json,keys,Clock.systemUTC());
    }
    @Bean RetentionExecutionReplayStore retentionExecutionReplayStore(ObjectProvider<StringRedisTemplate> redis) {return new RetentionExecutionReplayStore(redis.getIfAvailable(),Clock.systemUTC());}
    @Bean RetentionExecutionAuthorityProducer retentionExecutionAuthorityProducer(RetentionExecutionJson json,ObjectProvider<RetentionExecutionKeys> keys) {
        return new RetentionExecutionAuthorityProducer(json,keys::getObject,Clock.systemUTC());
    }
    @Bean RetentionExecutionAuthorityService retentionExecutionAuthorityService(ObjectProvider<RetentionExecutionProofVerifier> verifier,
            RetentionExecutionIdentityAuthorityBridge authority,RetentionExecutionReplayStore replay,RetentionExecutionAuthorityProducer producer,ConfigurableEnvironment env) {
        return new RetentionExecutionAuthorityService(verifier::getObject,authority,replay,producer,Clock.systemUTC(),env.getProperty(PREFIX+"enabled",Boolean.class,false));
    }
}
