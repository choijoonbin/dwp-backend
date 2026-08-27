package com.dwp.services.platform.widgetregistry.internal.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Replays the exact RFC 8785 bytes authenticated by the Widget Registry ingress. */
final class WidgetRegistryCachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    WidgetRegistryCachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        return new ByteArrayServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            if (readListener == null) throw new IllegalArgumentException("ReadListener is required.");
            try {
                if (isFinished()) readListener.onAllDataRead();
                else readListener.onDataAvailable();
            } catch (IOException exception) {
                readListener.onError(exception);
            }
        }

        @Override
        public int read() {
            return input.read();
        }
    }
}
