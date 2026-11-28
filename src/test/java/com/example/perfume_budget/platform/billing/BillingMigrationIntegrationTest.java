package com.example.perfume_budget.platform.billing;

import com.example.perfume_budget.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** BILL-01 AC1: V52 on a real PostgreSQL: the seed, the single row, every CHECK bound, the credentials rules and the append-only audit. */
class BillingMigrationIntegrationTest extends AbstractBillingIntegrationTest {

    /** Runs one statement and returns the SQLState of its failure, or null when it succeeded (and is rolled back by the caller's reset). */
    private static String failure(String sql) {
        try (Connection c = PostgresTestDatabase.connect(); Statement s = c.createStatement()) {
            s.execute(sql);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static final String CHECK_VIOLATION = "23514";

    @Test
    void flywayAppliedV52AndTheSeedIsTheDocumentedDefaultRow() {
        assertThat(scalar("SELECT count(*) FROM master.flyway_schema_history WHERE version = '52' AND success")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM master.billing_settings")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM master.billing_paystack_credentials")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM master.billing_settings WHERE id = 1 AND version = 0 AND NOT billing_enabled AND active_environment = 'TEST' "
                + "AND card_required_at_signup AND grace_days = 7 AND retry_offsets_days = '[1,3,5]'::jsonb AND retry_time_of_day_utc IS NULL "
                + "AND trial_reminder_days_before = '[3,1]'::jsonb AND renewal_reminder_days_before = '[3]'::jsonb AND price_change_notice_days = 30 "
                + "AND grace_reminder_offsets_days = '[1,3,5]'::jsonb AND grace_ending_reminder_days_before = '[1]'::jsonb "
                + "AND invoice_number_format = 'SUB-{YYYY}-{NNNNNN}' AND receipt_number_format = 'REC-{YYYY}-{NNNNNN}' AND credit_note_number_format = 'CN-{YYYY}-{NNNNNN}' "
                + "AND business_name IS NULL AND business_address IS NULL AND business_tin IS NULL AND business_email IS NULL AND business_phone IS NULL "
                + "AND tax_label IS NULL AND tax_rate_bps IS NULL AND tax_inclusive AND invoice_footer_text IS NULL AND NOT attach_pdf_to_email "
                + "AND card_verification_amount_pesewas = 100 AND allowed_channels = '[\"card\"]'::jsonb AND recurring_channels = '[\"card\"]'::jsonb "
                + "AND reconcile_interval_minutes = 15 AND reconcile_lookback_hours = 48 AND reconcile_full_scan_days = 30 AND billing_run_interval_seconds = 60 "
                + "AND NOT webhook_ip_allowlist_enabled AND webhook_ip_allowlist = '[]'::jsonb AND refund_max_days = 90 AND refund_confirm_threshold_pesewas = 100000 "
                + "AND card_token_retention_days_after_end = 7 AND one_trial_per_card AND card_attempts_per_email_per_day = 5 "
                + "AND card_decline_alert_threshold_per_hour = 20 AND card_setup_stop_threshold_per_hour = 100")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM master.billing_paystack_credentials WHERE status = 'UNSET' AND secret_key_encrypted IS NULL AND version = 0")).isEqualTo(2);
        assertThat(string("SELECT string_agg(k, ',' ORDER BY k) FROM master.billing_settings, jsonb_object_keys(email_enabled) k").split(",")).hasSize(27);
    }

    @Test
    void theSettingsRowIsUniqueByConstraint() {
        assertThat(failure("INSERT INTO master.billing_settings (id, email_enabled) VALUES (2, '{}'::jsonb)")).isEqualTo(CHECK_VIOLATION);
        assertThat(failure("INSERT INTO master.billing_settings (id, email_enabled) VALUES (1, '{}'::jsonb)")).isEqualTo("23505"); // primary key
        assertThat(scalar("SELECT count(*) FROM master.billing_settings")).isEqualTo(1);
    }

    static Stream<Arguments> bounds() {
        return Stream.of(
                Arguments.of("grace_days = 61"), Arguments.of("grace_days = -1"), Arguments.of("retry_offsets_days = '[1,2,3,4,5,6,7]'::jsonb"),
                Arguments.of("retry_offsets_days = '{}'::jsonb"), Arguments.of("retry_time_of_day_utc = '24:00'"), Arguments.of("retry_time_of_day_utc = '9:00'"),
                Arguments.of("trial_reminder_days_before = '[1,2,3,4,5,6]'::jsonb"), Arguments.of("renewal_reminder_days_before = '[1,2,3,4,5,6]'::jsonb"),
                Arguments.of("price_change_notice_days = 366"), Arguments.of("grace_reminder_offsets_days = '[1,2,3,4,5,6,7]'::jsonb"),
                Arguments.of("grace_ending_reminder_days_before = '[1,2,3,4]'::jsonb"), Arguments.of("email_enabled = '[]'::jsonb"),
                Arguments.of("invoice_number_format = 'INV-{YYYY}'"), Arguments.of("credit_note_number_format = 'CN-{YYYY}-{NNNNN}'"),
                Arguments.of("invoice_number_format = repeat('A', 41) || '{NNNNNN}'"), Arguments.of("tax_rate_bps = 10001"), Arguments.of("tax_rate_bps = -1"),
                Arguments.of("card_verification_amount_pesewas = 99"), Arguments.of("card_verification_amount_pesewas = 100001"),
                Arguments.of("allowed_channels = '[]'::jsonb"), Arguments.of("recurring_channels = '[]'::jsonb"), Arguments.of("active_environment = 'STAGING'"),
                Arguments.of("reconcile_interval_minutes = 4"), Arguments.of("reconcile_interval_minutes = 1441"), Arguments.of("reconcile_lookback_hours = 0"),
                Arguments.of("reconcile_lookback_hours = 721"), Arguments.of("reconcile_full_scan_days = 366"), Arguments.of("billing_run_interval_seconds = 9"),
                Arguments.of("billing_run_interval_seconds = 3601"), Arguments.of("webhook_ip_allowlist_enabled = TRUE"),
                Arguments.of("webhook_ip_allowlist = (SELECT jsonb_agg(i) FROM generate_series(1,21) i)"), Arguments.of("refund_max_days = 0"),
                Arguments.of("refund_max_days = 366"), Arguments.of("refund_confirm_threshold_pesewas = -1"), Arguments.of("refund_confirm_threshold_pesewas = 100000001"),
                Arguments.of("card_token_retention_days_after_end = 366"), Arguments.of("card_attempts_per_email_per_day = 0"), Arguments.of("card_attempts_per_email_per_day = 51"),
                Arguments.of("card_decline_alert_threshold_per_hour = 0"), Arguments.of("card_decline_alert_threshold_per_hour = 10001"),
                Arguments.of("card_setup_stop_threshold_per_hour = 100001"), Arguments.of("card_setup_stop_threshold_per_hour = 19"), Arguments.of("version = -1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bounds")
    void everyBoundIsAlsoACheckConstraintOfTheTable(String assignment) {
        // a value too long for its VARCHAR is refused by the type (22001) before the CHECK is evaluated: both are refusals
        assertThat(failure("UPDATE master.billing_settings SET " + assignment)).isIn(CHECK_VIOLATION, "22001");
        assertThat(scalar("SELECT version FROM master.billing_settings")).isZero();
    }

    @Test
    void boundariesAreAcceptedByTheTable() {
        assertThat(failure("UPDATE master.billing_settings SET grace_days = 60, price_change_notice_days = 0, tax_rate_bps = 10000, card_verification_amount_pesewas = 100000, "
                + "reconcile_interval_minutes = 5, billing_run_interval_seconds = 3600, refund_max_days = 365, refund_confirm_threshold_pesewas = 0, "
                + "card_setup_stop_threshold_per_hour = 20, retry_time_of_day_utc = '23:59'")).isNull();
    }

    @Test
    void credentialsRulesOfTheTable() {
        // a secret with status UNSET, and a status without a secret
        assertThat(failure("UPDATE master.billing_paystack_credentials SET secret_key_encrypted = 'v1:x', secret_key_version = 1, public_key = 'pk_test_x', secret_last4 = 'abcd' WHERE environment = 'TEST'"))
                .as("status UNSET with a secret").isEqualTo(CHECK_VIOLATION);
        assertThat(failure("UPDATE master.billing_paystack_credentials SET status = 'UNVERIFIED' WHERE environment = 'TEST'")).as("status without a secret").isEqualTo(CHECK_VIOLATION);
        assertThat(failure("UPDATE master.billing_paystack_credentials SET secret_key_encrypted = 'v1:x', status = 'UNVERIFIED', public_key = 'pk_test_x', secret_last4 = 'abcd' WHERE environment = 'TEST'"))
                .as("a secret needs a key version").isEqualTo(CHECK_VIOLATION);
        // a public key of the other environment
        assertThat(failure("UPDATE master.billing_paystack_credentials SET public_key = 'pk_live_x', public_last4 = 'live', secret_key_encrypted = 'v1:x', secret_key_version = 1, secret_last4 = 'abcd', status = 'UNVERIFIED' WHERE environment = 'TEST'"))
                .as("live public key in the TEST row").isEqualTo(CHECK_VIOLATION);
        assertThat(failure("UPDATE master.billing_paystack_credentials SET failure_code = 'OTHER' WHERE environment = 'TEST'")).isEqualTo(CHECK_VIOLATION);
        assertThat(failure("UPDATE master.billing_paystack_credentials SET status = 'WHATEVER' WHERE environment = 'TEST'")).isEqualTo(CHECK_VIOLATION);
        assertThat(failure("INSERT INTO master.billing_paystack_credentials (environment) VALUES ('STAGING')")).isIn(CHECK_VIOLATION, "22001");
        assertThat(failure("INSERT INTO master.billing_paystack_credentials (environment) VALUES ('DEV')")).isEqualTo(CHECK_VIOLATION);
        assertThat(failure("INSERT INTO master.billing_paystack_credentials (environment) VALUES ('TEST')")).as("one row per environment").isEqualTo("23505");
        assertThat(failure("UPDATE master.billing_paystack_credentials SET public_key = 'pk_test_x', public_last4 = '_x__', secret_key_encrypted = 'v1:x', secret_key_version = 1, secret_last4 = 'abcd', status = 'UNVERIFIED' WHERE environment = 'TEST'")).isNull();
    }

    @Test
    void theSettingsAuditIsAppendOnly_updateDeleteAndTruncateAreRefused() {
        assertThat(failure("INSERT INTO master.billing_settings_audit_log (actor_admin_id, actor_email, settings_version, changes) "
                + "VALUES (1, 'a@b.c', 1, '[{\"field\":\"x\",\"before\":1,\"after\":2}]'::jsonb)")).isNull();
        assertThat(failure("UPDATE master.billing_settings_audit_log SET actor_email = 'x@y.z'")).isEqualTo("P0001");
        assertThat(failure("DELETE FROM master.billing_settings_audit_log")).isEqualTo("P0001");
        assertThat(failure("TRUNCATE master.billing_settings_audit_log")).isEqualTo("P0001");
        assertThat(failure("INSERT INTO master.billing_settings_audit_log (actor_admin_id, actor_email, settings_version, changes) VALUES (1, 'a@b.c', 1, '[]'::jsonb)"))
                .as("an audit row needs at least one change").isEqualTo(CHECK_VIOLATION);
        assertThat(scalar("SELECT count(*) FROM master.billing_settings_audit_log WHERE actor_email = 'a@b.c'")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theApplicationHasNoUpdateOrDeletePathToTheAudit() throws Exception {
        // the repository is a plain Repository: insert and reads only (no delete / update methods exist to call)
        var methods = java.util.Arrays.stream(com.example.perfume_budget.platform.billing.settings.BillingSettingsAuditRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName).toList();
        assertThat(methods).doesNotContain("delete", "deleteAll", "deleteById", "save", "saveAll", "deleteAllInBatch", "flush");
        assertThat(methods).contains("saveAndFlush", "newestFirst", "count");
        assertThat(com.example.perfume_budget.platform.billing.settings.BillingSettingsAuditRepository.class.getInterfaces())
                .containsExactly(org.springframework.data.repository.Repository.class);
        assertThat(com.example.perfume_budget.platform.billing.settings.BillingSettingsAuditLog.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
    }
}
