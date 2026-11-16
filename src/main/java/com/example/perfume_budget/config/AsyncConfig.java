package com.example.perfume_budget.config;

import com.example.perfume_budget.handlers.MailDispatcher;
import com.example.perfume_budget.tenancy.TenantContextTaskDecorator;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@EnableAsync
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    public static final String MAIL_EXECUTOR = "mailTaskExecutor";
    public static final String NOTIFICATION_EXECUTOR = "notificationTaskExecutor";

    /**
     * EVT-01 capacity of the dedicated e-mail / receipt executor. A paid order costs two mail tasks at most (customer
     * receipt + admin payment e-mail, sent from one task, and the admin "new order" e-mail at checkout); a send through
     * SMTP / Resend takes roughly 0.2-2 s, and is bounded by the SMTP / HTTP timeouts (see application.properties).
     * 4 threads drain ~2-20 mails/s, and 500 queued tasks absorb a burst of a few hundred orders without holding much
     * memory (each task holds ids, not rendered mails).
     *
     * <p>Rework 1: the overflow is NEVER run on the submitting thread. The submitter is a request or webhook worker
     * inside the after-commit callback of its transaction: running mail there joined the already committed transaction
     * (notification rows never committed, the STOMP push registered on a synchronization that never fires) and held
     * the caller's JDBC connection through the SMTP conversation, so a mail outage could exhaust the connection pool
     * for every tenant. Instead the submitter waits at most {@link #MAIL_SUBMIT_WAIT_MILLIS} for a queue slot
     * ({@link WaitBrieflyThenReject}); if none frees up the mail is DROPPED and {@code MailDispatcher} logs what was
     * not sent with its order / payment reference (re-send by hand). Trade-off: under a sustained mail outage (504
     * sends stuck) some e-mails are lost, logged, instead of the store going down with the mail server.
     */
    public static final int MAIL_THREADS = 4;
    public static final int MAIL_QUEUE_CAPACITY = 500;
    /** Longest a request / webhook thread waits for a free mail (or notification) queue slot. */
    public static final long MAIL_SUBMIT_WAIT_MILLIS = 1_000;

    /**
     * Rework 1: the admin in-app notification (rows + STOMP push) of a settled payment does not depend on the mail
     * server, so it has its own small executor: a hung SMTP server can neither delay nor drop it.
     */
    public static final int NOTIFICATION_THREADS = 2;
    public static final int NOTIFICATION_QUEUE_CAPACITY = 500;

    @Bean(name = MAIL_EXECUTOR)
    public TaskExecutor mailTaskExecutor() {
        return mailExecutor(MAIL_THREADS, MAIL_QUEUE_CAPACITY, MAIL_SUBMIT_WAIT_MILLIS);
    }

    @Bean(name = NOTIFICATION_EXECUTOR)
    public TaskExecutor notificationTaskExecutor() {
        return boundedExecutor("notify-", NOTIFICATION_THREADS, NOTIFICATION_QUEUE_CAPACITY, MAIL_SUBMIT_WAIT_MILLIS);
    }

    static TrackedTaskExecutor mailExecutor(int threads, int queueCapacity, long submitWaitMillis) {
        return boundedExecutor("mail-", threads, queueCapacity, submitWaitMillis);
    }

    private static TrackedTaskExecutor boundedExecutor(String prefix, int threads, int queueCapacity, long submitWaitMillis) {
        TrackedTaskExecutor executor = new TrackedTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        executor.setRejectedExecutionHandler(new WaitBrieflyThenReject(submitWaitMillis));
        // on shutdown, queued receipts are still sent (bounded wait) rather than discarded
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Saturation policy of the mail / notification executors: wait up to {@code waitMillis} for a queue slot, then
     * REJECT (the caller, {@code MailDispatcher}, logs the reference of what was not sent). Never runs the task on the
     * submitting thread (see {@link #MAIL_SUBMIT_WAIT_MILLIS} for why). A shut-down executor rejects at once.
     */
    public static class WaitBrieflyThenReject implements RejectedExecutionHandler {
        private final long waitMillis;

        public WaitBrieflyThenReject(long waitMillis) {
            this.waitMillis = waitMillis;
        }

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            if (pool.isShutdown()) {
                throw new RejectedExecutionException("executor is shut down");
            }
            boolean queued;
            try {
                queued = pool.getQueue().offer(task, waitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                queued = false;
            }
            if (!queued) {
                throw new RejectedExecutionException("queue full after waiting " + waitMillis + " ms");
            }
            if (pool.isShutdown() && pool.getQueue().remove(task)) {
                throw new RejectedExecutionException("executor is shut down");
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
        TrackedTaskExecutor executor = new TrackedTaskExecutor(); // EVT-01 rework: countable in-flight tasks
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
        TrackedTaskExecutor executor = new TrackedTaskExecutor(); // EVT-01 rework: countable in-flight tasks
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
