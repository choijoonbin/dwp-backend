package com.dwp.services.approval.workflowplanning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

class WorkflowPlanningReadinessTest {
    @Test void disabledDefaultDoesNotResolveKeysOrContactAuth() {
        var calls=new AtomicInteger();
        var health=new WorkflowPlanningReadiness(false,()->{calls.incrementAndGet();throw new AssertionError();}).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state","DISABLED");
        assertThat(calls).hasValue(0);
    }
    @Test void enabledReadinessRequiresConfiguredRuntimeAndExactEnabledAuthEndpoint() throws Exception {
        try(var fixture=new WorkflowPlanningSignedEndpointFixture()) {
            try(var enabled=new ProbeServer(403,"application/json",true)) {
                var keys=new WorkflowPlanningKeys(fixture.owner,fixture.transport,
                        new com.nimbusds.jose.jwk.JWKSet(fixture.auth.toPublicJWK()).toString(),
                        new com.nimbusds.jose.jwk.JWKSet(fixture.unrelated.toPublicJWK()).toString());
                var runtime=new WorkflowPlanningRuntime(new WorkflowPlanningProofIssuer(keys,Clock.systemUTC()),
                        new WorkflowPlanningAuthorityClient(enabled.endpoint(),new WorkflowPlanningAttestationVerifier(keys,Clock.systemUTC())));
                var health=new WorkflowPlanningReadiness(true,()->runtime).health();
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getDetails()).containsEntry("state","READY");
                assertThat(enabled.requests).hasValue(1);
            }
        }
    }
    @Test void disabledMalformedAndUnavailableAuthResponsesRemainDown() throws Exception {
        for(var response:java.util.List.of(new Probe(503,"application/json",true),new Probe(403,"text/plain",true),new Probe(403,"application/json",false))) {
            try(var fixture=new WorkflowPlanningSignedEndpointFixture();var server=new ProbeServer(response.status,response.contentType,response.body)) {
                var keys=new WorkflowPlanningKeys(fixture.owner,fixture.transport,
                        new com.nimbusds.jose.jwk.JWKSet(fixture.auth.toPublicJWK()).toString(),
                        new com.nimbusds.jose.jwk.JWKSet(fixture.unrelated.toPublicJWK()).toString());
                var runtime=new WorkflowPlanningRuntime(new WorkflowPlanningProofIssuer(keys,Clock.systemUTC()),
                        new WorkflowPlanningAuthorityClient(server.endpoint(),new WorkflowPlanningAttestationVerifier(keys,Clock.systemUTC())));
                assertThat(new WorkflowPlanningReadiness(true,()->runtime).health().getStatus()).isEqualTo(Status.DOWN);
            }
        }
        assertThat(new WorkflowPlanningReadiness(true,()->{throw new IllegalStateException("missing keys");}).health().getStatus()).isEqualTo(Status.DOWN);
    }
    @Test void productionPropertiesBuildTheReadyRuntimeAndMissingSecretsFailClosed() throws Exception {
        try(var fixture=new WorkflowPlanningSignedEndpointFixture();var auth=new ProbeServer(403,"application/json",true)) {
            String prefix="dwp.approval.workflow-planning.";
            var environment=new MockEnvironment()
                    .withProperty(prefix+"owner-private-key",fixture.owner.toJSONString())
                    .withProperty(prefix+"transport-private-key",fixture.transport.toJSONString())
                    .withProperty(prefix+"auth-trusted-keys",new JWKSet(fixture.auth.toPublicJWK()).toString())
                    .withProperty(prefix+"prohibited-key-catalogue",new JWKSet(fixture.unrelated.toPublicJWK()).toString())
                    .withProperty(prefix+"endpoint",auth.endpoint().toString());
            new WorkflowPlanningConfiguration().workflowPlanningRuntime(environment).requireReady();
            assertThat(auth.requests).hasValue(1);
        }
        assertThatThrownBy(()->new WorkflowPlanningConfiguration().workflowPlanningRuntime(new MockEnvironment()))
                .isInstanceOfSatisfying(BaseException.class,error->assertThat(error.getErrorCode())
                        .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
    private record Probe(int status,String contentType,boolean body) { }
    private static final class ProbeServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests=new AtomicInteger();
        private ProbeServer(int status,String contentType,boolean body) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext(WorkflowPlanningProtocol.PATH,exchange->{
                requests.incrementAndGet();byte[] response=body?"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8):new byte[0];
                exchange.getResponseHeaders().add("Content-Type",contentType);exchange.sendResponseHeaders(status,response.length);
                if(response.length>0) exchange.getResponseBody().write(response);exchange.close();
            });
            server.start();
        }
        private URI endpoint() {return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+WorkflowPlanningProtocol.PATH);}
        @Override public void close() {server.stop(0);}
    }
}
