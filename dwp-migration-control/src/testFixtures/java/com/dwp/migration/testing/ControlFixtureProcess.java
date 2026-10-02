package com.dwp.migration.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded child IO; credentials enter only the private child environment. */
final class ControlFixtureProcess {
    private static final int MAX_OUTPUT_BYTES = 1_048_576;

    private ControlFixtureProcess() {
    }

    static String run(List<String> command, Path root, Map<String, String> environment,
            Duration timeout, List<String> secrets) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(root.toFile()).redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().putAll(environment);
            process = builder.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start isolated Control fixture child");
        }
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "isolated-control-fixture-output");
            thread.setDaemon(true);
            return thread;
        });
        var output = executor.submit(() -> readOutput(process));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Isolated Control fixture child exceeded its timeout");
            }
            String text = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Isolated Control fixture child failed (exit="
                        + process.exitValue() + "): " + safeDiagnostic(text, secrets));
            }
            return text;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Isolated Control fixture child was interrupted");
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Cannot capture bounded isolated Control fixture output");
        } finally {
            terminate(process);
            output.cancel(true);
            executor.shutdownNow();
        }
    }

    private static String readOutput(Process process) throws IOException {
        try (var input = process.getInputStream();
                var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_OUTPUT_BYTES) {
                    terminate(process);
                    throw new IOException("Isolated Control fixture output exceeded its bound");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    static String safeDiagnostic(String raw, List<String> secrets) {
        String safe = raw;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                safe = safe.replace(secret, "[redacted]");
            }
        }
        safe = safe.replaceAll(
                "(?i)(password\\s*(?:=|:)?\\s*)(?:'[^']*'|\"[^\"]*\"|\\S+)",
                "$1[redacted]");
        return safe.substring(Math.max(0, safe.length() - 4096));
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        try {
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Only this owned process is cleaned up; no external process is touched.
        }
    }
}
