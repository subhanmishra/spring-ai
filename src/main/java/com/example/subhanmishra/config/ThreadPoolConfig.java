package com.example.subhanmishra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ForkJoinPool;

@Configuration
public class ThreadPoolConfig {

    /**
     * Creates a dedicated thread pool for document processing to enforce a bulkhead pattern.
     * <p>
     * This prevents CPU-intensive document parsing from starving other application threads (e.g., web server threads).
     * The parallelism level is set to {@code availableProcessors - 2}, ensuring at least two cores are left for other tasks.
     * A minimum of one thread is guaranteed for the pool.
     *
     * @return A custom ForkJoinPool for document processing tasks.
     */
    @Bean
    public ForkJoinPool documentProcessingPool() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // Leave at least 2 cores for other tasks, but ensure at least 1 thread for the pool
        int parallelism = Math.max(1, availableProcessors - 2);
        return new ForkJoinPool(parallelism);
    }
}
