package com.example.perfume_budget.coupon;

import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * API-04 rework 2: a deadlock victim (40P01), serialization failure (40001) or lock timeout (55P03), however it is
 * wrapped, is a {@code 503 {data.code:"TRY_AGAIN"}} with Retry-After, never a 500. API-05: so is Spring's optimistic-lock failure
 * (ObjectOptimisticLockingFailureException), which used to be a 500.
 */
class LockFailureMappingTest {

    @RestController
    static class Thrower {
        @GetMapping("/t/{kind}")
        String t(@PathVariable String kind) {
            throw switch (kind) {
                case "deadlock" -> new CannotAcquireLockException("deadlock", new SQLException("deadlock detected", "40P01"));
                case "loser" -> new DeadlockLoserDataAccessException("deadlock", new SQLException("d", "40P01"));
                case "pessimistic" -> new PessimisticLockingFailureException("p");
                case "jpa-pessimistic" -> new jakarta.persistence.PessimisticLockException("p");
                case "commit" -> new TransactionSystemException("commit failed",
                        new RuntimeException(new SQLException("could not serialize access", "40001")));
                case "jpa-system" -> new JpaSystemException(new RuntimeException(new SQLException("deadlock", "40P01")));
                case "raw" -> new IllegalStateException("wrapped", new SQLException("lock timeout", "55P03"));
                case "optimistic" -> new org.springframework.orm.ObjectOptimisticLockingFailureException("Product", 1L);
                case "jpa-optimistic" -> new jakarta.persistence.OptimisticLockException("stale");
                case "stock-lock-flush" -> stockLockFlushConflict();
                default -> new IllegalStateException("bug");
            };
        }
    }

    /** What ProductStockLocks really throws when flushing the caller's pending changes hits a version conflict. */
    private static RuntimeException stockLockFlushConflict() {
        jakarta.persistence.EntityManager em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        org.mockito.Mockito.doThrow(new jakarta.persistence.OptimisticLockException("stale")).when(em).flush();
        com.example.perfume_budget.service.ProductStockLocks locks = new com.example.perfume_budget.service.ProductStockLocks(
                org.mockito.Mockito.mock(com.example.perfume_budget.repository.ProductRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(locks, "entityManager", em);
        try {
            locks.lock(java.util.List.of(1L));
        } catch (RuntimeException e) {
            return e;
        }
        return new IllegalStateException("no conflict thrown");
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Thrower())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    private MvcResult call(String kind) throws Exception {
        return mvc.perform(get("/t/" + kind)).andReturn();
    }

    @Test
    void deadlockSerializationAndLockTimeoutAre503TryAgain_howeverWrapped() throws Exception {
        for (String kind : new String[]{"deadlock", "loser", "pessimistic", "jpa-pessimistic", "commit", "jpa-system", "raw", "optimistic", "stock-lock-flush"}) {
            MvcResult r = call(kind);
            assertThat(r.getResponse().getStatus()).as(kind).isEqualTo(503);
            assertThat(r.getResponse().getHeader("Retry-After")).as(kind).isEqualTo("1");
            assertThat(r.getResponse().getContentAsString()).as(kind).contains("\"code\":\"TRY_AGAIN\"")
                    .doesNotContain("SQL").doesNotContain("deadlock");
        }
    }

    @Test
    void otherFailuresKeepTheirStatus() throws Exception {
        assertThat(call("bug").getResponse().getStatus()).isEqualTo(500);
    }

    @Test
    void jpasOwnOptimisticLockExceptionKeepsItsDocumented409() throws Exception {
        // API-05 rework (reviewer X10): only Spring's translated OptimisticLockingFailureException became 503; a raw
        // JPA OptimisticLockException (e.g. an untranslated EntityManager call) keeps the 409 it always had.
        MvcResult r = call("jpa-optimistic");
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("updated by another transaction");
    }
}
