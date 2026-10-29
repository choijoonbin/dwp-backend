package com.dwp.services.notification.integration;

import java.util.regex.Pattern;

/** Shared fail-fast boundary for notification Kafka input and dead-letter topics. */
final class NotificationKafkaTopicBoundary {
    private static final int MAX_TOPIC_LENGTH = 249;
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]+");

    private NotificationKafkaTopicBoundary() {
    }

    static void requireDistinctCanonicalTopics(
            String inputTopic,
            String deadLetterTopic,
            String contract) {
        requireCanonical(inputTopic, contract + " input topic");
        requireCanonical(deadLetterTopic, contract + " dead-letter topic");
        if (inputTopic.equals(deadLetterTopic)) {
            throw new IllegalArgumentException(
                    contract + " input and dead-letter topics must differ.");
        }
    }

    private static void requireCanonical(String topic, String label) {
        if (topic == null
                || topic.isBlank()
                || !topic.equals(topic.strip())
                || topic.length() > MAX_TOPIC_LENGTH
                || !TOPIC.matcher(topic).matches()
                || ".".equals(topic)
                || "..".equals(topic)) {
            throw new IllegalArgumentException(label + " must be a canonical Kafka topic.");
        }
    }
}
