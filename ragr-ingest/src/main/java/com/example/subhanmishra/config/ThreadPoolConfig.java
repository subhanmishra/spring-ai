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
     * A separate pool for parsing, which is CPU-heavy, so it cannot starve the web threads. Half the
     * cores, at least one. Parsing only: embedding and writing wait on I/O and use virtual threads.
     */
    @Bean
    public ForkJoinPool documentProcessingPool() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // Half the cores, at least one.
        int parallelism = Math.max(1, availableProcessors / 2);

        final AtomicInteger threadNumber = new AtomicInteger(0);
        final ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("doc-chunk-pool-" + threadNumber.getAndIncrement());
            return worker;
        };

        Thread.UncaughtExceptionHandler exceptionHandler = (t, e) -> log.error("Uncaught exception in thread: {}", t.getName(), e);

        // asyncMode false: LIFO, which suits divide-and-conquer work.
        return new ForkJoinPool(parallelism, factory, exceptionHandler, false);
    }
}
