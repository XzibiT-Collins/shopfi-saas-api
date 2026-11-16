package com.example.perfume_budget.utils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * EVT-01: runs a side effect that must not happen for work that rolls back (a STOMP push, a message to the outside
 * world) after the current transaction commits, and not at all if it rolls back. Outside a transaction (no
 * synchronization active) it runs at once.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
