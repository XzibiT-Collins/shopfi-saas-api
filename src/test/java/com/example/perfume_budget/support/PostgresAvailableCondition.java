package com.example.perfume_budget.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Skips Postgres-backed tests (with a clear reason) when no database is reachable, unless
 * {@code SHOPFI_TEST_DB_REQUIRED=true}, in which case the test fails loudly. Runs before the
 * Spring context is created, so an absent database never produces a confusing boot error.
 */
public class PostgresAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String reason = PostgresTestDatabase.unreachableReason();
        if (reason == null) {
            return ConditionEvaluationResult.enabled("PostgreSQL reachable");
        }
        if (PostgresTestDatabase.required()) {
            throw new IllegalStateException(reason + " (SHOPFI_TEST_DB_REQUIRED=true)");
        }
        return ConditionEvaluationResult.disabled(reason);
    }
}
