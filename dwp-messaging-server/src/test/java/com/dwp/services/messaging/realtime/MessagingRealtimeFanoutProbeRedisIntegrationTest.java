package com.dwp.services.messaging.realtime;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_REDIS_HOST", matches = ".+")
class MessagingRealtimeFanoutProbeRedisIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        String host = System.getenv("DWP_MESSAGING_INTEGRATION_REDIS_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        String password = System.getenv("DWP_MESSAGING_INTEGRATION_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            server.setPassword(RedisPassword.of(password));
        }
        SocketOptions sockets = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ZERO)
                .clientOptions(ClientOptions.builder().socketOptions(sockets).build())
                .build();
        connectionFactory = new LettuceConnectionFactory(server, client);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void provesARealDedicatedRedisPublishSubscribeRoundTrip() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MessagingRedisChannels channels = new MessagingRedisChannels(
                "dwp.messaging.probe." + suffix,
                "dwp.messaging.typing." + suffix,
                1);
        MessagingRealtimeFanoutProbe probe = new MessagingRealtimeFanoutProbe(
                redis, channels, true, Duration.ofSeconds(2));
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(probe, channels.readinessTopic());
        container.afterPropertiesSet();
        container.start();

        try {
            MessagingRealtimeFanoutProbe.ProbeResult result =
                    probe.probe(OffsetDateTime.now());
            assertThat(result.effective()).isTrue();
            assertThat(result.state()).isEqualTo("READY");
        } finally {
            container.stop();
            container.destroy();
        }
    }
}
