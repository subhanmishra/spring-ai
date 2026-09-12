package com.example.subhanmishra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    /**
     * Creates a dedicated, named thread pool for document processing to enforce a bulkhead pattern.
     * <p>
     * This prevents CPU-intensive document parsing from starving other application threads (e.g., web server threads).
     * The parallelism level is set to half the available processors, ensuring a significant portion of CPU
     * is left for other tasks. A minimum of one thread is guaranteed for the pool.
     *
     * @return A custom, named ForkJoinPool for document processing tasks.
     */
    @Bean
    public ForkJoinPool documentProcessingPool() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // Leave at least half the cores for other tasks, but ensure at least 1 thread for the pool
        int parallelism = Math.max(1, availableProcessors / 2);

        final AtomicInteger threadNumber = new AtomicInteger(0);
        final ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("doc-chunk-pool-" + threadNumber.getAndIncrement());
            return worker;
        };

        Thread.UncaughtExceptionHandler exceptionHandler = (t, e) -> log.error("Uncaught exception in thread: {}", t.getName(), e);

        // false for asyncMode uses LIFO, which is generally better for performance in divide-and-conquer tasks.
        return new ForkJoinPool(parallelism, factory, exceptionHandler, false);
    }
}
