package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Fixed-purpose transport: no redirects, ambient credentials, arbitrary paths, or unbounded response readers. */
public final class WorkflowRuntimeAuthorityClient {
    private static final String ENDPOINT_PATH="/internal/approval-workflow/runtime-authority";
    private static final String TOKEN_HEADER="X-DWP-Approval-Workflow-Runtime-Token";
    private final URI endpoint;
    private final HttpClient http;
    private final WorkflowRuntimeAttestationVerifier verifier;
    public WorkflowRuntimeAuthorityClient(URI endpoint, WorkflowRuntimeAttestationVerifier verifier) {
        if(!ENDPOINT_PATH.equals(PATH) || !TOKEN_HEADER.equals(HEADER)) throw unavailable();
        if (endpoint==null || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                || !ENDPOINT_PATH.equals(endpoint.getRawPath()) || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())
                && java.util.Set.of("localhost","127.0.0.1","::1","[::1]").contains(endpoint.getHost()))) throw unavailable();
        this.endpoint=endpoint;this.verifier=verifier;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public WorkflowRuntimeAttestationVerifier.Verified evaluate(WorkflowRuntimeProofIssuer.Exchange exchange) {
        if (exchange.body().length>BODY_MAX || exchange.transportToken().length()>TRANSPORT_MAX) throw denied();
        var builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .header("X-DWP-Service-Identity","dwp-approval-server").header(TOKEN_HEADER,exchange.transportToken());
        var trace=new org.springframework.http.HttpHeaders();com.dwp.core.http.OutboundHttpHeaders.propagateObservability(trace);
        trace.forEach((name,values)->{
            if(java.util.Set.of("traceparent","tracestate","x-correlation-id").contains(name.toLowerCase(java.util.Locale.ROOT)))
                values.forEach(value->builder.header(name,value));
        });
        var request=builder.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build();
        var body=new WorkflowRuntimeResponseBody();var pending=http.sendAsync(request,info->body);
        try {
            var response=pending.get(5,TimeUnit.SECONDS);
                if (response.statusCode()==403 || response.statusCode()==401) throw denied();
                if (response.statusCode()!=200) throw unavailable();
                var content=response.headers().allValues("Content-Type");
                if (content.size()!=1 || !content.getFirst().split(";",2)[0].strip().equals("application/json")) throw denied();
                return verifier.verify(response.body(),exchange);
        } catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt();throw unavailable(); }
        catch (java.util.concurrent.ExecutionException error) {
            if(error.getCause() instanceof com.dwp.core.exception.BaseException failure) throw failure;
            throw unavailable();
        }
        catch (java.util.concurrent.TimeoutException error) {throw unavailable();}
        finally {pending.cancel(true);body.cancel();}
    }
}
