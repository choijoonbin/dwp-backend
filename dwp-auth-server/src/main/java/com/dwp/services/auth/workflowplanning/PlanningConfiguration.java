package com.dwp.services.auth.workflowplanning;

import com.dwp.services.auth.service.PlanningIdentityAuthorityBridge;
import java.time.Clock;
import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class PlanningConfiguration {
    private static final String PREFIX="dwp.auth.approval-workflow-planning.";
    @Bean PlanningJson planningJson() {return new PlanningJson();}
    @Bean @Lazy PlanningKeys planningKeys(PlanningJson json,ConfigurableEnvironment environment) {
        var forbidden=new ArrayList<String>(); var names=new java.util.TreeSet<String>();
        for(var source:environment.getPropertySources()) if(source instanceof EnumerablePropertySource<?> enumerable)
            java.util.Collections.addAll(names,enumerable.getPropertyNames());
        for(String name:names) if(name.startsWith("dwp.") && !name.startsWith(PREFIX) && name.matches(".*(key|jwk|jwks|pem).*")) {
            String value=environment.getProperty(name,"");
            if(!value.isBlank() && (value.stripLeading().startsWith("{") || value.startsWith("-----BEGIN"))) forbidden.add(value);
            else if(name.endsWith("key-id") && !value.isBlank()) forbidden.add("kid:"+value);
        }
        return new PlanningKeys(json,environment.getProperty(PREFIX+"owner-public-jwks",""),environment.getProperty(PREFIX+"transport-public-jwks",""),
                environment.getProperty(PREFIX+"attestation-private-jwk",""),environment.getProperty(PREFIX+"attestation-public-jwks",""),forbidden);
    }
    @Bean @Lazy PlanningProofVerifier planningProofVerifier(PlanningJson json,PlanningKeys keys) {return new PlanningProofVerifier(json,keys,Clock.systemUTC());}
    @Bean PlanningReplayStore planningReplayStore(ObjectProvider<StringRedisTemplate> redis) {return new PlanningReplayStore(redis.getIfAvailable(),Clock.systemUTC());}
    @Bean PlanningAuthorityIssuer planningAuthorityIssuer(PlanningJson json,ObjectProvider<PlanningKeys> keys) {return new PlanningAuthorityIssuer(json,keys::getObject,Clock.systemUTC());}
    @Bean PlanningAuthorityService planningAuthorityService(ObjectProvider<PlanningProofVerifier> verifier,PlanningIdentityAuthorityBridge authority,
            PlanningRoleRepository roles,PlanningReplayStore replay,PlanningAuthorityIssuer issuer,PlanningJson json,ConfigurableEnvironment environment) {
        return new PlanningAuthorityService(verifier::getObject,authority,roles,replay,issuer,json,Clock.systemUTC(),environment.getProperty(PREFIX+"enabled",Boolean.class,false));
    }
}
