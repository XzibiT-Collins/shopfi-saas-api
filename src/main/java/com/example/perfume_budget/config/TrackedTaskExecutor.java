package com.example.perfume_budget.config;

import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EVT-01 rework: a {@link ThreadPoolTaskExecutor} that counts its tasks from the moment they are SUBMITTED until they
 * have FINISHED (or were rejected). {@link ThreadPoolTaskExecutor#getActiveCount()} plus the queue size misses a task
 * a worker has already taken from the queue but not yet started, so "active == 0 and queue empty" is not "idle";
 * {@link #inFlight()} has no such window. Used to wait for background side effects deterministically (tests, and
 * the shutdown log).
 */
public class TrackedTaskExecutor extends ThreadPoolTaskExecutor {

    private final AtomicLong inFlight = new AtomicLong();
    private volatile RejectedExecutionHandler policy;

    public TrackedTaskExecutor() {
        setTaskDecorator(null);
    }

    /** Wraps {@code decorator} (may be null) so every submitted task is counted until it ends. */
    @Override
    public void setTaskDecorator(TaskDecorator decorator) {
        super.setTaskDecorator(task -> {
            Runnable inner = decorator == null ? task : decorator.decorate(task);
            inFlight.incrementAndGet();
            return new Counted(inner);
        });
    }

    /** A task the pool refuses (the handler throws) will never run: it stops counting. */
    @Override
    protected ExecutorService initializeExecutor(ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        this.policy = handler;
        RejectedExecutionHandler counting = (task, pool) -> {
            try {
                handler.rejectedExecution(task, pool);
            } catch (RuntimeException | Error refused) {
                if (task instanceof Counted c) {
                    c.release();
                }
                throw refused;
            }
        };
        return super.initializeExecutor(threadFactory, counting);
    }

    /**
     * EVT-01b L6: an executor that does not wait for its tasks on shutdown hands every queued task it discards
     * ({@code shutdownNow}) to this method. Such a task will never run, so it stops counting as in flight.
     */
    @Override
    protected void cancelRemainingTask(Runnable task) {
        try {
            super.cancelRemainingTask(task);
        } finally {
            if (task instanceof Counted c) {
                c.release();
            }
        }
    }

    /** The saturation policy configured for this executor (the pool itself holds a counting wrapper around it). */
    public RejectedExecutionHandler getRejectionPolicy() {
        return policy;
    }

    /** Tasks submitted and not yet finished (queued, taken by a worker, or running). */
    public long inFlight() {
        return inFlight.get();
    }

    /**
     * Waits until no task is in flight.
     *
     * @return false if tasks were still in flight after {@code timeout}
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight.get() > 0) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    private final class Counted implements Runnable {
        private final Runnable inner;
        private final AtomicBoolean released = new AtomicBoolean();

        Counted(Runnable inner) {
            this.inner = inner;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public void run() {
            try {
                inner.run();
            } finally {
                release();
            }
        }
    }
}
