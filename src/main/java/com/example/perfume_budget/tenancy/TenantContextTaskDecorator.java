package com.example.perfume_budget.tenancy;

import org.springframework.core.task.TaskDecorator;
import org.jspecify.annotations.NonNull;

/**
 * Propagates the {@link TenantContext} across async executor boundaries. The tenant bound on
 * the submitting (request) thread is captured at submit time and re-applied on the worker
 * thread, then cleared. Without this, {@code @Async} handlers run with no tenant and query the
 * neutral schema.
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public @NonNull Runnable decorate(@NonNull Runnable runnable) {
        TenantIdentity captured = TenantContext.get();
        return () -> {
            try {
                if (captured != null) {
                    TenantContext.set(captured);
                }
                runnable.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
