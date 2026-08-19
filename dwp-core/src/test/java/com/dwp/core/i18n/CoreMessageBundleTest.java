package com.dwp.core.i18n;

import com.dwp.core.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CoreMessageBundleTest {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[^{}]+}");

    private static final Set<String> VALIDATION_MESSAGE_KEYS = Set.of(
            "jakarta.validation.constraints.DecimalMax.message",
            "jakarta.validation.constraints.DecimalMin.message",
            "jakarta.validation.constraints.Email.message",
            "jakarta.validation.constraints.Future.message",
            "jakarta.validation.constraints.Max.message",
            "jakarta.validation.constraints.Min.message",
            "jakarta.validation.constraints.NotBlank.message",
            "jakarta.validation.constraints.NotEmpty.message",
            "jakarta.validation.constraints.NotNull.message",
            "jakarta.validation.constraints.Pattern.message",
            "jakarta.validation.constraints.Positive.message",
            "jakarta.validation.constraints.PositiveOrZero.message",
            "jakarta.validation.constraints.Size.message");

    @Test
    void localizedBundlesHaveTheSameKeysAndCoverEveryErrorCode() throws IOException {
        Properties english = load("i18n/dwp-core-messages.properties");
        Properties korean = load("i18n/dwp-core-messages_ko.properties");

        assertThat(korean.keySet()).containsExactlyInAnyOrderElementsOf(english.keySet());
        assertThat(english.values()).allSatisfy(value -> assertThat(value.toString()).isNotBlank());
        assertThat(korean.values()).allSatisfy(value -> assertThat(value.toString()).isNotBlank());

        for (String key : english.stringPropertyNames()) {
            assertThat(placeholders(korean.getProperty(key)))
                    .as("message placeholders for %s", key)
                    .containsExactlyInAnyOrderElementsOf(placeholders(english.getProperty(key)));
        }

        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(english).containsKey("error." + errorCode.getCode());
        }
        assertThat(english.keySet()).containsAll(VALIDATION_MESSAGE_KEYS);
    }

    private Properties load(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("message bundle %s", path).isNotNull();
            properties.load(stream);
        }
        return properties;
    }

    private Set<String> placeholders(String message) {
        Set<String> placeholders = new java.util.LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(message);
        while (matcher.find()) placeholders.add(matcher.group());
        return placeholders;
    }
}
