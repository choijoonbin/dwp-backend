package com.dwp.services.approval.signatureproviders;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;

/** Single attempt, no redirects/proxy/cookies, original-host TLS and a complete-call five-second deadline. */
public final class SignatureProviderHttpTransport implements Closeable {
    public static final int MAX_BYTES = 524_288;
    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final ThreadPoolExecutor dns = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> { var t = new Thread(task, "approval-signature-provider-dns"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        var t = new Thread(task, "approval-signature-provider-deadline"); t.setDaemon(true); return t;
    });

    public static final class AccessToken {
        private final String value;
        private AccessToken(String value) { this.value = value; }
        static AccessToken fromCredentialExchange(String value) {
            if (value == null || value.length() > 16_384 || !value.matches("[A-Za-z0-9._~+/-]+={0,2}"))
                throw SignatureProviderModel.invalid("Invalid provider access token");
            return new AccessToken(value);
        }
        @Override public String toString() { return "[REDACTED_PROVIDER_ACCESS_TOKEN]"; }
    }

    public record Response(int status, String contentType, byte[] body, Instant observedAt) {
        public Response { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }

    Response exchange(SignatureProviderEgressRegistry.Binding binding, String method, String rawTarget,
                      AccessToken token, byte[] body, ContentType contentType) throws IOException {
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        URI uri = target(binding, method, rawTarget); requireBody(body);
        SignatureProviderPinnedDnsResolver pinned = resolve(uri.getHost(), deadline);
        var request = new HttpUriRequestBase(method, uri);
        request.setHeader("Accept", "application/json");
        request.setHeader("Accept-Encoding", "identity");
        if (token != null) request.setHeader("Authorization", "Bearer " + token.value);
        if (body != null) {
            if (contentType == null) throw SignatureProviderModel.invalid("Request media type required");
            request.setEntity(new ByteArrayEntity(body.clone(), contentType));
        }
        return executePinned(request, pinned, DefaultClientTlsStrategy.createDefault(), deadline);
    }

    // Native callers use the default-TLS entry above; this lower-level composition is not an authority or configuration registry.
    Response executePinned(HttpUriRequestBase request, DnsResolver pinned, TlsSocketStrategy tls, long deadline) throws IOException {
        if (deadline - System.nanoTime() > DEADLINE_NANOS) throw new IOException("Provider complete-call deadline exceeds bounds");
        var cancellation = deadlines.schedule(request::cancel, remaining(deadline), TimeUnit.NANOSECONDS);
        // Per-call manager prevents a pooled connection from outliving its DNS/configuration snapshot.
        try (var manager = connections(pinned, tls); var client = client(manager)) {
            return client.execute(request, response -> {
                if (response.getCode() >= 300 && response.getCode() < 400) {
                    request.cancel(); throw new IOException("Provider redirect denied");
                }
                var encoding = response.getFirstHeader("Content-Encoding");
                if (encoding != null && !"identity".equalsIgnoreCase(encoding.getValue())) {
                    request.cancel(); throw new IOException("Encoded provider response denied");
                }
                var entity = response.getEntity(); byte[] bytes = new byte[0]; String type = null;
                if (entity != null) {
                    if (entity.getContentLength() > MAX_BYTES) {
                        request.cancel(); throw new IOException("Provider response exceeds bounds");
                    }
                    try (var stream = entity.getContent()) {
                        bytes = stream.readNBytes(MAX_BYTES + 1);
                        if (bytes.length > MAX_BYTES) {
                            request.cancel(); throw new IOException("Provider response exceeds bounds");
                        }
                    }
                    type = entity.getContentType();
                }
                remaining(deadline);
                return new Response(response.getCode(), type, bytes, Instant.now());
            });
        } finally { cancellation.cancel(false); }
    }

    static PoolingHttpClientConnectionManager connections(DnsResolver resolver, TlsSocketStrategy tls) {
        return PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).setTlsSocketStrategy(tls)
                .setMaxConnTotal(1).setMaxConnPerRoute(1)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3))
                        .setSocketTimeout(Timeout.ofSeconds(5)).build())
                .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofSeconds(5)).build()).build();
    }

    static CloseableHttpClient client(PoolingHttpClientConnectionManager manager) {
        return HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching()
                .disableContentCompression().disableConnectionState()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(3))
                        .setResponseTimeout(Timeout.ofSeconds(5)).build()).build();
    }

    static URI target(SignatureProviderEgressRegistry.Binding binding, String method, String rawTarget) {
        SignatureProviderModel.required(binding);
        if (!Set.of("GET", "POST", "PUT").contains(method) || rawTarget == null || rawTarget.length() > 4096
                || !rawTarget.matches("/[\\x21-\\x7e]*") || rawTarget.contains("#") || rawTarget.contains("\\")
                || rawTarget.startsWith("//")) throw SignatureProviderModel.invalid("Invalid native provider request target");
        URI relative = URI.create(rawTarget); String path = relative.getRawPath();
        if (relative.isAbsolute() || relative.getRawAuthority() != null || path.contains("%") || path.contains("//")
                || java.util.Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."))
                || !relative.normalize().equals(relative)) throw SignatureProviderModel.invalid("Encoded or noncanonical provider path denied");
        URI uri = binding.origin().resolve(relative);
        if (!binding.origin().getRawAuthority().equals(uri.getRawAuthority()) || !"https".equals(uri.getScheme()))
            throw SignatureProviderModel.invalid("Provider origin changed");
        return uri;
    }

    private SignatureProviderPinnedDnsResolver resolve(String host, long deadline) throws IOException {
        java.util.concurrent.Future<SignatureProviderPinnedDnsResolver> lookup;
        try { lookup = dns.submit(() -> SignatureProviderPinnedDnsResolver.lookup(host)); }
        catch (java.util.concurrent.RejectedExecutionException unavailable) { throw new IOException("Provider DNS capacity unavailable", unavailable); }
        try { return lookup.get(remaining(deadline), TimeUnit.NANOSECONDS); }
        catch (TimeoutException timedOut) { throw new IOException("Provider DNS deadline exceeded", timedOut); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Provider DNS interrupted", interrupted); }
        catch (ExecutionException failed) { throw new IOException("Provider DNS denied or unavailable", failed.getCause()); }
        finally { if (!lookup.isDone()) lookup.cancel(true); }
    }

    private static void requireBody(byte[] body) {
        if (body != null && body.length > MAX_BYTES) throw SignatureProviderModel.invalid("Provider request exceeds bounds");
    }
    private static long remaining(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Provider complete-call deadline exceeded");
        return remaining;
    }
    @Override public void close() { dns.shutdownNow(); deadlines.shutdownNow(); }
}
