package com.dwp.core.i18n;

import com.dwp.core.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class CoreMessageBundleTest {

    @Test
    void localizedBundlesHaveTheSameKeysAndCoverEveryErrorCode() throws IOException {
        Properties english = load("i18n/dwp-core-messages.properties");
        Properties korean = load("i18n/dwp-core-messages_ko.properties");

        assertThat(korean.keySet()).containsExactlyInAnyOrderElementsOf(english.keySet());
        assertThat(english.values()).allSatisfy(value -> assertThat(value.toString()).isNotBlank());
        assertThat(korean.values()).allSatisfy(value -> assertThat(value.toString()).isNotBlank());

        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(english).containsKey("error." + errorCode.getCode());
        }
    }

    private Properties load(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("message bundle %s", path).isNotNull();
            properties.load(stream);
        }
        return properties;
    }
}
