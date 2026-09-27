package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record SpringAiProperties(int maxChatMessages) {
}