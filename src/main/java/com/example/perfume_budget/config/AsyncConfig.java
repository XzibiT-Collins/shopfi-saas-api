package com.example.perfume_budget.config;

import com.example.perfume_budget.entitlement.NotPlanGated;
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
import java.util.stream.Collectors;

@NotPlanGated(reason = "thread-pool configuration for the @Async handlers, never plan-gated")
@EnableAsync
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    public static final String MAIL_EXECUTOR = "mailTaskExecutor";
    public static final String NOTIFICATION_EXECUTOR = "notificationTaskExecutor";

    /**
     * EVT-01 capacity of the dedicated e-mail / receipt executor. A paid order costs two mail tasks at most (customer
     * receipt + admin payment e-mail, and the admin "new order" e-mail at checkout); a send through SMTP / Resend takes
     * roughly 0.2-2 s, and is bounded by the SMTP / HTTP timeouts (see application.properties). 4 threads drain ~2-20
     * mails/s, and 500 queued tasks absorb a burst of a few hundred orders without holding much memory (each task
     * holds ids, not rendered mails).
     *
     * <p>The overflow is NEVER run on the submitting thread, and (EVT-01b L1) the submitter NEVER waits for a slot:
     * it is a request or webhook worker inside the after-commit callback of its transaction, and Spring releases that
     * transaction's JDBC connection only after the callbacks. A 1 s wait there (EVT-01 rework 1) meant every
     * mail-producing commit held its connection for a second during a mail outage, which starved the shared Hikari
     * pool for every tenant. A full executor now rejects at once ({@link RejectWhenFull}); the work is not lost, its
     * row in the tenant's side-effect outbox ({@code OutboxDrainer}) is picked up again once the executor has room.
     */
    public static final int MAIL_THREADS = 4;
    public static final int MAIL_QUEUE_CAPACITY = 500;

    /**
     * Rework 1: the admin in-app notification (rows + STOMP push) of a settled payment does not depend on the mail
     * server, so it has its own small executor: a hung SMTP server can neither delay nor drop it.
     */
    public static final int NOTIFICATION_THREADS = 2;
    public static final int NOTIFICATION_QUEUE_CAPACITY = 500;

    @Bean(name = MAIL_EXECUTOR)
    public TaskExecutor mailTaskExecutor() {
        return mailExecutor(MAIL_THREADS, MAIL_QUEUE_CAPACITY);
    }

    @Bean(name = NOTIFICATION_EXECUTOR)
    public TaskExecutor notificationTaskExecutor() {
        return boundedExecutor("notify-", NOTIFICATION_THREADS, NOTIFICATION_QUEUE_CAPACITY);
    }

    static TrackedTaskExecutor mailExecutor(int threads, int queueCapacity) {
        return boundedExecutor("mail-", threads, queueCapacity);
    }

    private static TrackedTaskExecutor boundedExecutor(String prefix, int threads, int queueCapacity) {
        TrackedTaskExecutor executor = new TrackedTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        executor.setRejectedExecutionHandler(new RejectWhenFull());
        // on shutdown, queued receipts are still sent (bounded wait) rather than discarded
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Saturation policy of the mail / notification executors (EVT-01b L1): reject AT ONCE, never wait for a queue
     * slot and never run the task on the submitting thread (see {@link #MAIL_THREADS} for why). The message says
     * whether the queue was full or the executor shut down; {@code MailDispatcher} logs it with the reference.
     */
    public static class RejectWhenFull implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            throw new RejectedExecutionException(pool.isShutdown() ? "executor is shut down" : "queue full");
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
