package com.dwp.services.platform.productivity;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class ProductivityCredentialResolver {

    private static final Pattern REFERENCE = Pattern.compile("env:[A-Z][A-Z0-9_]{1,126}");

    private final Environment environment;

    public ProductivityCredentialResolver(Environment environment) {
        this.environment = environment;
    }

    public Optional<String> resolve(String reference) {
        if (reference == null || !REFERENCE.matcher(reference).matches()) return Optional.empty();
        String value = environment.getProperty(reference.substring(4));
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    public boolean validReference(String reference) {
        return reference != null && REFERENCE.matcher(reference).matches();
    }
}
