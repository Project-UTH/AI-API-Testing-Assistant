package com.aiapitesting.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Bọc bằng DelegatingSecurityContextAsyncTaskExecutor: thread pool của @Async không tự có
     * SecurityContext của thread request gốc (SecurityContextHolder dùng ThreadLocal) - nếu không
     * bọc, mọi service @Async gọi SecurityContextHolder.getContext().getAuthentication() (vd. qua
     * CurrentUserService) sẽ nhận null dù request có token hợp lệ.
     */
    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-task-");
        executor.initialize();
        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }
}
