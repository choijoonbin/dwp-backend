package com.dwp.services.approval.signatures;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounds allocation before copying chunks and cancels the underlying response on failure. */
final class ApprovalSignatureResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private static final int RESPONSE_LIMIT = 32768;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public synchronized void onSubscribe(Flow.Subscription next) {
        if (subscription != null || result.isDone()) { next.cancel(); return; }
        subscription = next; next.request(1);
    }
    @Override public synchronized void onNext(List<ByteBuffer> chunks) {
        if (result.isDone()) return;
        long count = bytes.size();
        for (var chunk : chunks) {
            count += chunk.remaining();
            if (count > RESPONSE_LIMIT) {
                result.completeExceptionally(ApprovalSignatureCanonical.denied()); cancel(); return;
            }
        }
        for (var chunk : chunks) { byte[] copy = new byte[chunk.remaining()]; chunk.get(copy); bytes.writeBytes(copy); }
        subscription.request(1);
    }
    @Override public synchronized void onError(Throwable error) { result.completeExceptionally(error); cancel(); }
    @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
    synchronized void cancel() {
        if (subscription != null) subscription.cancel();
        if (!result.isDone()) result.cancel(true);
    }
}
