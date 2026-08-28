package com.dwp.services.meeting.videomeeting.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

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

    private static void close(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // The stable adapter error intentionally omits remote transport details.
        }
    }
}
