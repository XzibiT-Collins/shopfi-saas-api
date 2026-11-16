package com.example.perfume_budget.handlers;

import com.example.perfume_budget.config.AsyncConfig;
import com.example.perfume_budget.events.CartTimesEvent;
import com.example.perfume_budget.events.ViewCountEvent;
import com.example.perfume_budget.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * EVT-01 rework 1 (F4 R11): the analytics counters run OFF the publishing thread, on the default async executor (the
 * product page GET must not wait for, or hold its connection through, the counter UPDATE; the add-to-cart publishing
 * thread is in its transaction's after-commit callback).
 */
class ProductViewCountAsyncTest {

    private AnnotationConfigApplicationContext ctx;
    private ProductRepository products;

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        ctx = new AnnotationConfigApplicationContext();
        ctx.register(AsyncConfig.class);
        ctx.registerBean(ProductRepository.class, () -> products);
        ctx.registerBean(ProductViewCountHandler.class);
        ctx.registerBean("txListenerFactory", TransactionalEventListenerFactory.class);
        ctx.refresh();
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void viewAndCartCountersRunOnTheAsyncExecutorNotTheCaller() throws Exception {
        CompletableFuture<String> viewThread = new CompletableFuture<>();
        CompletableFuture<String> cartThread = new CompletableFuture<>();
        doAnswer(inv -> { viewThread.complete(Thread.currentThread().getName()); return null; })
                .when(products).incrementViewCount(anyLong());
        doAnswer(inv -> { cartThread.complete(Thread.currentThread().getName()); return null; })
                .when(products).incrementCartTimesCount(anyLong());

        ctx.publishEvent(new ViewCountEvent(5L));
        ctx.publishEvent(new CartTimesEvent(6L));

        String caller = Thread.currentThread().getName();
        assertThat(viewThread.get(5, TimeUnit.SECONDS)).isNotEqualTo(caller).startsWith("DefaultExecutor-");
        assertThat(cartThread.get(5, TimeUnit.SECONDS)).isNotEqualTo(caller).startsWith("DefaultExecutor-");
    }
}
