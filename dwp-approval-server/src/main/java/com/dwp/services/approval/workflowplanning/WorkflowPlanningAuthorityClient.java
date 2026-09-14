package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class WorkflowPlanningAuthorityClient {
    private static final String ENDPOINT_PATH="/internal/approval-workflow/admin-planning";
    private static final String TOKEN_HEADER="X-DWP-Approval-Workflow-Planning-Token";
    private final URI endpoint;
    private final HttpClient http;
    private final WorkflowPlanningAttestationVerifier verifier;
    public WorkflowPlanningAuthorityClient(URI endpoint,WorkflowPlanningAttestationVerifier verifier) {
        if(!ENDPOINT_PATH.equals(PATH) || !TOKEN_HEADER.equals(HEADER) || verifier==null || endpoint==null || endpoint.getHost()==null || endpoint.getUserInfo()!=null
                || endpoint.getQuery()!=null || endpoint.getFragment()!=null || !ENDPOINT_PATH.equals(endpoint.getRawPath())
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()) && Set.of("localhost","127.0.0.1","::1","[::1]").contains(endpoint.getHost()))) throw unavailable();
        this.endpoint=endpoint;this.verifier=verifier;http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public WorkflowPlanningAttestationVerifier.Verified evaluate(WorkflowPlanningProofIssuer.Exchange exchange) {
        if(exchange==null || exchange.body().length>BODY_MAX || exchange.token().length()>TOKEN_MAX) throw denied();
        var builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .header("X-DWP-Service-Identity","dwp-approval-server").header(TOKEN_HEADER,exchange.token());
        var trace=new org.springframework.http.HttpHeaders();com.dwp.core.http.OutboundHttpHeaders.propagateObservability(trace);
        trace.forEach((name,values)->{if(Set.of("traceparent","tracestate","x-correlation-id").contains(name.toLowerCase(java.util.Locale.ROOT))) values.forEach(value->builder.header(name,value));});
        var body=new WorkflowPlanningResponseBody();var pending=http.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(),info->body);
        try {
            var response=pending.get(5,TimeUnit.SECONDS);if(response.statusCode()==401 || response.statusCode()==403) throw denied();if(response.statusCode()!=200) throw unavailable();
            var types=response.headers().allValues("Content-Type");if(types.size()!=1 || !"application/json".equals(types.getFirst().split(";",2)[0].strip())) throw denied();
            return verifier.verify(response.body(),exchange);
        } catch(com.dwp.core.exception.BaseException error) {throw error;} catch(InterruptedException error) {Thread.currentThread().interrupt();throw unavailable();}
        catch(java.util.concurrent.ExecutionException error) {if(error.getCause() instanceof com.dwp.core.exception.BaseException failure) throw failure;throw unavailable();}
        catch(java.util.concurrent.TimeoutException error) {throw unavailable();} finally {pending.cancel(true);body.cancel();}
    }
}
