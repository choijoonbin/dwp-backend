package com.dwp.core.autoconfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;

/** Rejects local bootstrap inputs before Flyway can mutate a non-local database. */
public final class LocalBootstrapProductionEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {
        List<String> violations = LocalBootstrapProductionGuard.violations(environment);
        if (!violations.isEmpty() && !LocalBootstrapProductionGuard.exactLocal(environment)) {
            throw new IllegalStateException(
                    "Non-local bootstrap guard failed: " + String.join(", ", violations));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
