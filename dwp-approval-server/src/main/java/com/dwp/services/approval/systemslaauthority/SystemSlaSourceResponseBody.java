package com.dwp.services.approval.systemslaauthority;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class SystemSlaSourceResponseBody implements HttpResponse.BodySubscriber<byte[]> {
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
            if (buffer.remaining() > SystemSlaSourceProtocol.BODY_LIMIT - bytes.size()) { result.completeExceptionally(SystemSlaJson.denied()); cancel(); return; }
            var copy = new byte[buffer.remaining()]; buffer.get(copy); bytes.writeBytes(copy);
        }
        subscription.request(1);
    }
    @Override public synchronized void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
    synchronized void cancel() { result.completeExceptionally(new CancellationException()); if (subscription != null) subscription.cancel(); }
}
