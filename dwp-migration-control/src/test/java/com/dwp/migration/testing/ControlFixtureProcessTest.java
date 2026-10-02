package com.dwp.migration.testing;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlFixtureProcessTest {
    @Test
    void diagnosticRedactsKnownCredentialsAndPasswordLiterals() {
        String safe = ControlFixtureProcess.safeDiagnostic(
                "known-secret PASSWORD 'literal-one' password=literal-two PASSWORD: \"literal-three\"",
                List.of("known-secret"));
        for (String value : List.of("known-secret", "literal-one", "literal-two", "literal-three")) {
            assertFalse(safe.contains(value));
        }
        assertTrue(safe.contains("[redacted]"));
    }

    @Test
    void diagnosticIsBoundedAfterRedaction() {
        String safe = ControlFixtureProcess.safeDiagnostic("x".repeat(8192), List.of());
        assertEquals(4096, safe.length());
    }

    @Test
    void boundedChildReceivesOnlyExplicitEnvironment() {
        String output = python(
                "import os;print(os.environ['FIXTURE_MARKER']);print(os.getenv('HOME','not-inherited'))",
                Map.of("PATH", path(), "FIXTURE_MARKER", "fixture-only"), Duration.ofSeconds(5));
        assertEquals(List.of("fixture-only", "not-inherited"), output.lines().toList());
    }

    @Test
    void nonzeroChildHasOnlySanitizedDiagnostics() {
        var error = assertThrows(IllegalStateException.class, () -> python(
                "import sys;print(\"password 'synthetic-private-value'\");sys.exit(9)",
                Map.of("PATH", path()), Duration.ofSeconds(5)));
        assertTrue(error.getMessage().contains("exit=9"));
        assertFalse(error.getMessage().contains("synthetic-private-value"));
    }

    @Test
    void timeoutFailsAndCleansUpTheOwnedChild() {
        long started = System.nanoTime();
        var error = assertThrows(IllegalStateException.class, () -> python("import time;time.sleep(60)",
                Map.of("PATH", path()), Duration.ofMillis(150)));
        assertTrue(error.getMessage().contains("exceeded its timeout"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void oversizedChildOutputFailsClosed() {
        var error = assertThrows(IllegalStateException.class, () -> python("print('x'*1048577)",
                Map.of("PATH", path()), Duration.ofSeconds(5)));
        assertTrue(error.getMessage().contains("bounded isolated Control fixture output"));
    }

    private static String python(String script, Map<String, String> environment, Duration timeout) {
        return ControlFixtureProcess.run(List.of("python3", "-I", "-c", script),
                Path.of(System.getProperty("user.dir")), environment, timeout, List.of());
    }

    private static String path() {
        return System.getenv().getOrDefault("PATH", "/usr/bin:/bin");
    }
}
