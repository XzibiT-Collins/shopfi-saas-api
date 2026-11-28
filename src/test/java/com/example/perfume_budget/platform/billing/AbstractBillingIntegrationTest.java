package com.example.perfume_budget.platform.billing;

import com.example.perfume_budget.platform.billing.fake.FakePaystackServer;
import com.example.perfume_budget.platform.plan.AbstractPlansIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shared harness of the BILL-01 HTTP tests: the PLAN-01 harness (real filter chain on PostgreSQL, a signed-in SUPER_ADMIN)
 * plus the shared fake Paystack and a reset of the billing tables to the V52 seed before every test (the audit log is
 * append-only and is never truncated: tests compare ids).
 */
public abstract class AbstractBillingIntegrationTest extends AbstractPlansIntegrationTest {

    protected static final String SETTINGS = "/api/v1/platform/billing/settings";
    protected static final String CREDS = "/api/v1/platform/billing/credentials";
    /** Documentation values, never real keys. */
    protected static final String TEST_PK = "pk_test_DOCSEXAMPLEONLYNOTAKEY0001";
    protected static final String TEST_SK = "sk_test_DOCSEXAMPLEONLYNOTAKEY0002";
    protected static final String LIVE_PK = "pk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0001";
    protected static final String LIVE_SK = "sk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0002";

    @Autowired protected BillingAdminThrottle throttle;
    @Autowired protected BillingClock billingClock;
    @Autowired protected com.example.perfume_budget.platform.billing.paystack.PaystackPlatformClient paystackClient;
    @Autowired protected com.example.perfume_budget.platform.billing.credentials.BillingSecretCipher cipher;

    protected final FakePaystackServer fake = FakePaystackServer.shared();

    @BeforeEach
    void resetBilling() {
        fake.reset();
        throttle.reset();
        billingClock.reset();
        paystackClient.resetCircuitsForTests();
        resetBillingTables();
    }

    /** Test infrastructure only: empties the append-only audit (triggers off in ONE transaction); the app has no such path. */
    protected static void clearAudit() {
        try (java.sql.Connection c = com.example.perfume_budget.support.PostgresTestDatabase.connect(); java.sql.Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("ALTER TABLE master.billing_settings_audit_log DISABLE TRIGGER USER");
            s.execute("DELETE FROM master.billing_settings_audit_log");
            s.execute("ALTER TABLE master.billing_settings_audit_log ENABLE TRIGGER USER");
            c.commit();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static void resetBillingTables() {
        clearAudit();
        exec("DELETE FROM master.billing_settings");
        exec("INSERT INTO master.billing_settings (id, email_enabled) SELECT 1, '{\"TRIAL_REMINDER\": true, \"TRIAL_ENDS_TODAY\": true, "
                + "\"TRIAL_ENDED\": true, \"PAYMENT_SUCCEEDED\": true, \"PAYMENT_FAILED\": true, \"RETRY_SCHEDULED\": true, "
                + "\"GRACE_STARTED\": true, \"GRACE_REMINDER\": true, \"GRACE_ENDING\": true, \"ACCESS_RESTRICTED\": true, "
                + "\"ACCESS_RESTORED\": true, \"RENEWAL_REMINDER\": true, \"UPGRADE_APPLIED\": true, \"DOWNGRADE_SCHEDULED\": true, "
                + "\"DOWNGRADE_APPLIED\": true, \"CANCELLATION_SCHEDULED\": true, \"CANCELLATION_REVERSED\": true, "
                + "\"SUBSCRIPTION_ENDED\": true, \"INVOICE_AVAILABLE\": true, \"REFUND_ISSUED\": true, \"PRICE_CHANGE_NOTICE\": true, "
                + "\"COMPLIMENTARY_STARTED\": true, \"COMPLIMENTARY_ENDING\": true, \"COMPLIMENTARY_ENDED\": true, \"CARD_EXPIRING\": true, "
                + "\"CARD_UPDATED\": true, \"TRIAL_NOT_GRANTED\": true}'::jsonb");
        exec("DELETE FROM master.billing_paystack_credentials");
        exec("INSERT INTO master.billing_paystack_credentials (environment) VALUES ('TEST'), ('LIVE')");
    }

    protected static String credBody(String pk, String sk) {
        return "{\"publicKey\":\"" + pk + "\",\"secretKey\":\"" + sk + "\"}";
    }

    protected MockHttpServletResponse putCredentials(String env, String pk, String sk) throws Exception {
        return asAdmin(put(CREDS + "/" + env).contentType("application/json"), credBody(pk, sk));
    }

    protected MockHttpServletResponse verifyCredentials(String env) throws Exception {
        return asAdmin(post(CREDS + "/" + env + "/verify"), null);
    }

    /** The current settings as a PUT body: every setting plus the version read. */
    protected ObjectNode settingsBody() throws Exception {
        JsonNode view = body(asAdmin(get(SETTINGS), null)).get("data");
        ObjectNode o = ((ObjectNode) view.get("settings")).deepCopy();
        o.put("version", view.get("version").asInt());
        return o;
    }

    protected MockHttpServletResponse putSettings(JsonNode body) throws Exception {
        return asAdmin(put(SETTINGS).contentType("application/json"), body.toString());
    }

    protected static long settingsAuditRows() {
        return scalar("SELECT count(*) FROM master.billing_settings_audit_log");
    }
}
