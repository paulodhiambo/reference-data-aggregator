package com.ncbaloop.rdas.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.ncbaloop.rdas.snapshot.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Application-level Spring beans: snapshot store, Caffeine L2 cache,
 * and async task executor for the startup warm-up.
 */
@Configuration
@EnableCaching
public class AppConfig {

    @Value("${rdas.query-cache.max-size}")
    private int queryCacheMaxSize;

    @Value("${rdas.query-cache.ttl-minutes}")
    private int queryCacheTtlMinutes;

    /** Shared AtomicReference holding the active snapshot */
    @Bean
    public AtomicReference<Snapshot> snapshotStore() {
        return new AtomicReference<>();
    }

    /** Caffeine L2 query-result cache */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager("countryQuery");
        mgr.setCaffeine(Caffeine.newBuilder()
                .maximumSize(queryCacheMaxSize)
                .expireAfterWrite(Duration.ofMinutes(queryCacheTtlMinutes))
                .recordStats());
        return mgr;
    }

    /** Dedicated executor for the asynchronous startup warm-up. */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(10);
        exec.setThreadNamePrefix("rdas-async-");
        exec.initialize();
        return exec;
    }
}
