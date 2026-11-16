package com.example.perfume_budget.config;

import com.example.perfume_budget.handlers.MailDispatcher;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantContextTaskDecorator;
import com.example.perfume_budget.tenancy.TenantIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

@EnableAsync
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    public static final String MAIL_EXECUTOR = "mailTaskExecutor";

    /**
     * EVT-01 capacity of the dedicated e-mail / receipt executor. A paid order costs three short tasks at most
     * (customer receipt, admin e-mail, admin notification, sent from one task) plus one admin "new order" e-mail at
     * checkout; a send through SMTP / Resend takes roughly 0.2-2 s. 4 threads drain ~2-20 mails/s, and 500 queued
     * tasks absorb a burst of a few hundred orders (minutes of drain at worst) without holding much memory (each task
     * holds ids, not rendered mails). Beyond that nothing is dropped: the thread that published the event sends the
     * overflow itself ({@link RunInCallerUnlessShutDown}), which slows that request / webhook worker instead of losing a
     * receipt. Only a shut-down executor rejects, and {@code MailDispatcher} logs the order / payment reference.
     */
    public static final int MAIL_THREADS = 4;
    public static final int MAIL_QUEUE_CAPACITY = 500;

    @Bean(name = MAIL_EXECUTOR)
    public TaskExecutor mailTaskExecutor() {
        return mailExecutor(MAIL_THREADS, MAIL_QUEUE_CAPACITY);
    }

    static ThreadPoolTaskExecutor mailExecutor(int threads, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mail-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        executor.setRejectedExecutionHandler(new RunInCallerUnlessShutDown());
        // on shutdown, queued receipts are still sent (bounded wait) rather than discarded
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Saturation policy of the mail executor: run the task on the submitting thread (like
     * {@link ThreadPoolExecutor.CallerRunsPolicy}) so a burst never drops a receipt, but REJECT once the executor is
     * shut down (CallerRunsPolicy would silently discard it) so the caller logs the reference. The task is wrapped by
     * {@link TenantContextTaskDecorator}, which clears the tenant when it ends; the caller's own tenant context is
     * restored afterwards.
     */
    public static class RunInCallerUnlessShutDown implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            if (pool.isShutdown()) {
                throw new RejectedExecutionException("mail executor is shut down");
            }
            log.warn("Mail executor saturated ({} queued): sending on the calling thread", pool.getQueue().size());
            TenantIdentity callers = TenantContext.get();
            try {
                task.run();
            } finally {
                if (callers != null) {
                    TenantContext.set(callers);
                } else {
                    TenantContext.clear();
                }
            }
        }
    }

    /**
     * EVT-01: an exception escaping a remaining {@code @Async void} method (view counters, shop-floor alerts, webhook
     * processing) is logged with the reference of its arguments ({@code MailDispatcher#reference}: ids only) and the
     * exception class, never the arguments' text or the exception message.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("Async {}#{} failed ({}) for {}",
                method.getDeclaringClass().getSimpleName(), method.getName(), ex.getClass().getName(),
                Arrays.stream(params).map(MailDispatcher::reference).collect(Collectors.joining(", ")));
    }

    @Bean(name = "webhookTaskExecutor")
    public TaskExecutor webhookTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("webhook-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("DefaultExecutor-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Runs tenant provisioning kicked off by onboarding (PLATFORM scope, no tenant). Deliberately has NO
     * {@link TenantContextTaskDecorator}: the submitting request thread carries a PLATFORM identity, and
     * provisioning binds the new tenant's context itself, so the worker must start with a clean context
     * rather than inherit the platform one.
     */
    @Bean(name = "onboardingTaskExecutor")
    public TaskExecutor onboardingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("onboarding-");
        executor.setAwaitTerminationSeconds(30);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
