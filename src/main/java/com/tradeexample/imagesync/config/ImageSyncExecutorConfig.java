package com.tradeexample.imagesync.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 管理图片批量同步线程池，队列满时将拒绝结果交给任务服务处理。 */
@Configuration(proxyBeanMethods = false)
public class ImageSyncExecutorConfig {

    @Bean(defaultCandidate = false)
    @Qualifier("imageSyncExecutor")
    public ThreadPoolTaskExecutor imageSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(6);
        executor.setThreadNamePrefix("image-sync-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}