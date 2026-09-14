package com.dwp.services.notification.integration;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class ApprovalSlaAuthorityResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private static final int MAXIMUM = 524288;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public synchronized void onSubscribe(Flow.Subscription value) {
        if (subscription != null || result.isDone()) { value.cancel(); return; }
        subscription = value; value.request(1);
    }
    @Override public synchronized void onNext(List<ByteBuffer> buffers) {
        if (result.isDone()) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > MAXIMUM - bytes.size()) {
                result.completeExceptionally(new IllegalArgumentException("SLA authority response exceeds its byte bound."));
                cancel(); return;
            }
            byte[] copy = new byte[buffer.remaining()]; buffer.get(copy); bytes.writeBytes(copy);
        }
        subscription.request(1);
    }
    @Override public synchronized void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
    synchronized void cancel() {
        result.completeExceptionally(new java.util.concurrent.CancellationException());
        if (subscription != null) subscription.cancel();
    }
}
