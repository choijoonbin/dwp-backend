package com.dwp.services.platform.workhub.assignment;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounds chunked bodies too; buffering a complete source report is never required. */
final class WorkSourceBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private static final int MAX_BYTES = 16 * 1024;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription value) {
        if (subscription != null) { value.cancel(); return; }
        subscription = value;
        value.request(1);
    }
    @Override public void onNext(List<ByteBuffer> buffers) {
        if (result.isDone()) return;
        for (ByteBuffer buffer : buffers) {
            int length = buffer.remaining();
            if (length > MAX_BYTES - output.size()) {
                subscription.cancel();
                result.completeExceptionally(new IllegalStateException("Work source response exceeds the size limit."));
                return;
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            output.writeBytes(bytes);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
    @Override public void onComplete() { result.complete(output.toByteArray()); }
}
