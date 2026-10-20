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

    /**
     * Executor RIÊNG cho 'AI sinh test gốc + chạy thử': luồng này CHỜ runner (chạy trên taskExecutor) nên nếu dùng chung pool thì 2 yêu cầu
     * cùng lúc có thể chiếm hết thread core, runner nằm chờ trong hàng đợi và cả 2 bế tắc tới hết thời gian chờ.
     */
    /**
     * Executor cho job sinh test chạy nền (GenerationJobService). Tách khỏi taskExecutor (runner chạy test ở đó, job phải chờ runner) và khỏi
     * baselineExecutor để không bế tắc. Hàng đợi 0 vì mỗi project chỉ có 1 job hoạt động và mỗi người tối đa 3.
     */
    @Bean(name = "jobExecutor")
    public Executor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("gen-job-");
        executor.initialize();
        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }

    @Bean(name = "baselineExecutor")
    public Executor baselineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("baseline-");
        executor.initialize();
        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }
}
