package com.dwp.services.approval.policyimpactsource;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels allocation at the byte limit and at the whole-exchange deadline. */
final class PolicyImpactSourceResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public synchronized void onSubscribe(Flow.Subscription value) {
        if (subscription != null || result.isDone()) { value.cancel(); return; } subscription = value; value.request(1);
    }
    @Override public synchronized void onNext(List<ByteBuffer> buffers) {
        if (result.isDone()) return;
        for (var buffer : buffers) {
            if (buffer.remaining() > PolicyImpactSourceProtocol.BODY_LIMIT - bytes.size()) {
                result.completeExceptionally(PolicyImpactSourceJson.denied()); cancel(); return;
            }
            var copy = new byte[buffer.remaining()]; buffer.get(copy); bytes.writeBytes(copy);
        }
        subscription.request(1);
    }
    @Override public synchronized void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
    synchronized void cancel() {
        result.completeExceptionally(new java.util.concurrent.CancellationException()); if (subscription != null) subscription.cancel();
    }
}
