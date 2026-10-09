package com.example.subhanmishra.config;

import com.example.subhanmishra.controller.ConversationIdInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link ConversationIdInterceptor} on the two generate endpoints only. The conversation read
 * and delete endpoints take the id in their path, and must never have one generated.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ConversationIdInterceptor())
                .addPathPatterns("/ai/generate", "/ai/generateStream");
    }
}
