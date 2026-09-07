package com.dwp.services.messaging.home;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingSharedLinkExtractorTest {
    @Test
    void supportsPlainAndMarkdownLinksWithoutDuplicateOrClosingPunctuation() {
        assertThat(MessagingSharedLinkExtractor.links(
                "[Report](https://example.com/report) https://example.com/report. "
                        + "https://example.org/wiki/Work_(project)!"))
                .extracting(Object::toString)
                .containsExactly("https://example.com/report", "https://example.org/wiki/Work_(project)");
    }

    @Test
    void refusesCredentialsMalformedHostsAndNonWebSchemes() {
        assertThat(MessagingSharedLinkExtractor.links("javascript:alert(1) data:text/html,hello "
                + "file:///private http://user:secret@example.com/private https:// https://bad%host/path"))
                .isEmpty();
    }

    @Test
    void boundsLinksAndRejectsOverlongUrlsWithoutFetchingAnything() {
        String body = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> "https://example.com/" + i).collect(java.util.stream.Collectors.joining(" "));
        assertThat(MessagingSharedLinkExtractor.links(body)).hasSize(20);
        assertThat(MessagingSharedLinkExtractor.links("https://example.com/" + "a".repeat(2050))).isEmpty();
        assertThat(MessagingSharedLinkExtractor.links(null)).isEmpty();
    }
}
