package com.dwp.services.approval.informationreplay;

import static com.dwp.services.approval.informationreplay.InformationReplayProtocol.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Dedicated replay transport; never accepts an arbitrary path or ambient subject credentials. */
public final class InformationReplayAuthorityClient {
    private static final String ENDPOINT_PATH="/internal/approval-workflow/information-command-replay";
    private static final String TOKEN_HEADER="X-DWP-Approval-Information-Replay-Token";
    private final URI endpoint;
    private final HttpClient http;
    private final InformationReplayAttestationVerifier verifier;
    public InformationReplayAuthorityClient(URI endpoint,InformationReplayAttestationVerifier verifier) {
        if(!ENDPOINT_PATH.equals(PATH) || !TOKEN_HEADER.equals(HEADER) || verifier==null) throw unavailable();
        if(endpoint==null || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                || !ENDPOINT_PATH.equals(endpoint.getRawPath()) || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())
                && java.util.Set.of("localhost","127.0.0.1","::1","[::1]").contains(endpoint.getHost()))) throw unavailable();
        this.endpoint=endpoint;this.verifier=verifier;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public InformationReplayAttestationVerifier.Verified evaluate(InformationReplayProofIssuer.Exchange exchange) {
        if(exchange==null || exchange.body().length>BODY_MAX || exchange.token().length()>TOKEN_MAX) throw denied();
        var builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .header("X-DWP-Service-Identity","dwp-approval-server").header(TOKEN_HEADER,exchange.token());
        var trace=new org.springframework.http.HttpHeaders();com.dwp.core.http.OutboundHttpHeaders.propagateObservability(trace);
        trace.forEach((name,values)->{
            if(java.util.Set.of("traceparent","tracestate","x-correlation-id").contains(name.toLowerCase(java.util.Locale.ROOT)))
                values.forEach(value->builder.header(name,value));
        });
        var request=builder.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build();
        var body=new InformationReplayResponseBody();var pending=http.sendAsync(request,info->body);
        try {
            var response=pending.get(5,TimeUnit.SECONDS);
            if(response.statusCode()==401 || response.statusCode()==403) throw denied();
            if(response.statusCode()!=200) throw unavailable();
            var content=response.headers().allValues("Content-Type");
            if(content.size()!=1 || !content.getFirst().split(";",2)[0].strip().equals("application/json")) throw denied();
            return verifier.verify(response.body(),exchange);
        } catch(com.dwp.core.exception.BaseException error) {throw error;}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw unavailable();}
        catch(java.util.concurrent.ExecutionException error) {
            if(error.getCause() instanceof com.dwp.core.exception.BaseException failure) throw failure;throw unavailable();
        }
        catch(java.util.concurrent.TimeoutException error) {throw unavailable();}
        finally {pending.cancel(true);body.cancel();}
    }
}
