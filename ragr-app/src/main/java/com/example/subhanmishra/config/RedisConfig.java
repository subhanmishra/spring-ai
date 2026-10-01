package com.example.subhanmishra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.RedisClient;

@Configuration
public class RedisConfig {

    /**
     * The chat memory's Redis client. Spring AI's Redis repository has no auto-configuration, so the
     * Docker Compose service connection never reaches it and the address is read here instead:
     * localhost from IntelliJ, and {@code APP_REDIS_HOST=redis} from the ragr-app container.
     */
    @Bean
    public RedisClient redisClient(@Value("${app.redis.host:localhost}") String host,
                                   @Value("${app.redis.port:6379}") int port) {
        return RedisClient.builder()
                .hostAndPort(host, port)
                .build();
    }
}
