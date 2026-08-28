package com.dwp.services.meeting.videomeeting.provider;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

final class CapturingHttpClient extends HttpClient {

    private HttpRequest request;
    private int status = 200;
    private byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private String contentType = "application/json";
    private Long contentLength;
    private int sendCount;
    private TrackingInputStream responseStream;

    void respond(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
        this.contentLength = null;
    }

    void respondWithContentLength(
            int status, String contentType, byte[] body, long declaredLength) {
        respond(status, contentType, body);
        this.contentLength = declaredLength;
    }

    HttpRequest request() {
        return request;
    }

    byte[] requestBody() {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<byte[]> completed = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }
            @Override public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }
            @Override public void onComplete() {
                completed.complete(output.toByteArray());
            }
        });
        return completed.join();
    }

    int sendCount() {
        return sendCount;
    }

    int responseBytesRead() {
        return responseStream == null ? 0 : responseStream.bytesRead;
    }

    boolean responseClosed() {
        return responseStream != null && responseStream.closed;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        this.request = request;
        sendCount++;
        responseStream = new TrackingInputStream(body);
        return (HttpResponse<T>) new Response<>(
                request, status, contentType, contentLength, responseStream);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        return CompletableFuture.completedFuture(send(request, responseBodyHandler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return CompletableFuture.completedFuture(send(request, responseBodyHandler));
    }

    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public SSLContext sslContext() { return defaultSslContext(); }
    @Override public SSLParameters sslParameters() { return new SSLParameters(); }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public Version version() { return Version.HTTP_2; }
    @Override public Optional<Executor> executor() { return Optional.empty(); }

    private SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Response<T>(
            HttpRequest request,
            int statusCode,
            String contentType,
            Long contentLength,
            T body) implements HttpResponse<T> {

        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            java.util.LinkedHashMap<String, List<String>> headers = new java.util.LinkedHashMap<>();
            headers.put("Content-Type", List.of(contentType));
            if (contentLength != null) {
                headers.put("Content-Length", List.of(Long.toString(contentLength)));
            }
            return HttpHeaders.of(headers, (left, right) -> true);
        }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private int bytesRead;
        private boolean closed;

        private TrackingInputStream(byte[] body) {
            super(body);
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            int count = super.read(bytes, offset, length);
            if (count > 0) bytesRead += count;
            return count;
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override
        public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }
    }
}
