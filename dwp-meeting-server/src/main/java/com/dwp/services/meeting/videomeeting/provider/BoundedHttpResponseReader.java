package com.dwp.services.meeting.videomeeting.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reads at most the configured payload plus one sentinel byte, then closes the stream. */
final class BoundedHttpResponseReader {

    private BoundedHttpResponseReader() {
    }

    static byte[] read(HttpResponse<InputStream> response, int maximumBytes)
            throws IOException {
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maximumBytes) {
            close(response.body());
            throw new IOException("Response payload exceeds the configured limit.");
        }
        try (InputStream input = response.body()) {
            if (input == null) throw new IOException("Response body is unavailable.");
            byte[] payload = input.readNBytes(maximumBytes + 1);
            if (payload.length == 0 || payload.length > maximumBytes) {
                throw new IOException("Response payload exceeds the configured limit.");
            }
            return payload;
        }
    }

    static byte[] readBeforeDeadline(
            HttpResponse<InputStream> response, int maximumBytes, long responseDeadline)
            throws IOException {
        long remaining = responseDeadline - System.nanoTime();
        if (remaining <= 0) {
            close(response.body());
            throw new IOException("Response body deadline exceeded.");
        }
        FutureTask<byte[]> read = new FutureTask<>(() -> read(response, maximumBytes));
        Thread reader = Thread.ofVirtual()
                .name("meeting-http-response")
                .unstarted(read);
        reader.start();
        try {
            return read.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            read.cancel(true);
            close(response.body());
            throw new IOException("Response body deadline exceeded.", timeout);
        } catch (InterruptedException interrupted) {
            read.cancel(true);
            close(response.body());
            Thread.currentThread().interrupt();
            throw new IOException("Response body read was interrupted.", interrupted);
        } catch (ExecutionException failedRead) {
            Throwable cause = failedRead.getCause();
            if (cause instanceof IOException ioException) throw ioException;
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IOException("Response body read failed.", cause);
        }
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // The stable adapter error intentionally omits remote transport details.
        }
    }
}
