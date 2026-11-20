package com.tradeexample.imagesync.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 分开管理协调线程和分页执行线程，两组线程各自使用有界队列。 */
@Configuration(proxyBeanMethods = false)
public class ImageSyncExecutorConfig {

    /** 协调线程负责取页、等待和汇总任务。 */
    @Bean(defaultCandidate = false)
    @Qualifier("imageSyncExecutor")
    public ThreadPoolTaskExecutor imageSyncExecutor() {
        return createPool("image-sync-");
    }

    /** 分页线程负责处理明细和调用外部图库。 */
    @Bean(defaultCandidate = false)
    @Qualifier("imageSyncPageExecutor")
    public ThreadPoolTaskExecutor imageSyncPageExecutor() {
        return createPool("image-sync-page-");
    }

    /** 创建三个线程、六个排队位置的线程池，队列满时立即拒绝。 */
    private ThreadPoolTaskExecutor createPool(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(6);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
