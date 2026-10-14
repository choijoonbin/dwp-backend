package com.dwp.services.people.hr.performance.compensationplanning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Produces a complete bounded response before HTTP response commitment. */
@Component
final class ApprovedCompensationSnapshotResponseSpool {

    private static final String PROPERTY =
            "dwp.hris.performance.compensation.snapshot-service.max-response-bytes";
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 64L * 1024L * 1024L;

    private final ObjectMapper mapper;
    private final long maxResponseBytes;
    private final Path spoolDirectory;

    @Autowired
    ApprovedCompensationSnapshotResponseSpool(
            ObjectMapper mapper,
            Environment environment) {
        this(
                mapper,
                bounded(environment.getProperty(
                        PROPERTY, Long.class, DEFAULT_MAX_RESPONSE_BYTES)),
                null);
    }

    ApprovedCompensationSnapshotResponseSpool(
            ObjectMapper mapper,
            long maxResponseBytes,
            Path spoolDirectory) {
        this.mapper = Objects.requireNonNull(mapper).copy();
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.spoolDirectory = spoolDirectory;
    }

    Artifact prepare(Object payload) throws IOException {
        Path path = spoolDirectory == null
                ? Files.createTempFile("dwp-xcon020-", ".json")
                : Files.createTempFile(spoolDirectory, "dwp-xcon020-", ".json");
        boolean complete = false;
        try {
            try (OutputStream file = Files.newOutputStream(
                    path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 OutputStream bounded = new BoundedOutputStream(file, maxResponseBytes)) {
                mapper.writeValue(bounded, payload);
            }
            long length = Files.size(path);
            complete = true;
            return new Artifact(path, length);
        } finally {
            if (!complete) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static long bounded(Long value) {
        long configured = value == null ? DEFAULT_MAX_RESPONSE_BYTES : value;
        if (configured < 1 || configured > 512L * 1024L * 1024L) {
            throw new IllegalStateException(
                    PROPERTY + " must be between 1 and 536870912.");
        }
        return configured;
    }

    record Artifact(Path path, long length) implements AutoCloseable {
        void transferTo(OutputStream output) throws IOException {
            try (var input = Files.newInputStream(path)) {
                input.transferTo(output);
            } finally {
                close();
            }
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    static final class ResponseBoundExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        ResponseBoundExceededException(long limit) {
            super("SVC_PEP_PER_001_ATOMIC_RESPONSE_BOUND_EXCEEDED:" + limit);
        }
    }

    private static final class BoundedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        private BoundedOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, values.length);
            requireCapacity(length);
            out.write(values, offset, length);
            written += length;
        }

        private void requireCapacity(int length) throws ResponseBoundExceededException {
            if (length > limit - written) {
                throw new ResponseBoundExceededException(limit);
            }
        }
    }
}
