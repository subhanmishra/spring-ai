package com.example.subhanmishra.config;

import com.example.subhanmishra.controller.ConversationIdInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link ConversationIdInterceptor} on the two endpoints that take a chat turn. The
 * conversation read-back and delete endpoints carry the id in their path instead, and must not have one
 * generated for them.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ConversationIdInterceptor())
                .addPathPatterns("/ai/generate", "/ai/generateStream");
    }
}
