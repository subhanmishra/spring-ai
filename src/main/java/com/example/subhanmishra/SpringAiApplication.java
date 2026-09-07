package com.example.subhanmishra;

import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.config.SpringAiProperties; // Import the new properties class
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({RagProperties.class, SpringAiProperties.class}) // Enable both properties classes
public class SpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiApplication.class, args);
    }

}