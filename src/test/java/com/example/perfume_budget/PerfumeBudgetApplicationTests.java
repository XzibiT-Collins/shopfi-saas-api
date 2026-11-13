package com.example.perfume_budget;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/** Full-context smoke test on PostgreSQL (master + tenant_template migrated, ddl-auto=validate). */
class PerfumeBudgetApplicationTests extends AbstractPostgresIntegrationTest {

	@Test
	void contextLoads() {
	}

}
