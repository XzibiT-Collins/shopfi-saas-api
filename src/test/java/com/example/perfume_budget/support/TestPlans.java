package com.example.perfume_budget.support;

import com.example.perfume_budget.platform.plan.model.Plan;
import com.example.perfume_budget.platform.plan.repository.PlanRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** PLAN-01 test fixtures: the seeded plan ids (V51) and a stub for unit tests that build tenant summaries. */
public final class TestPlans {

    public static final long STARTER_ID = 1L;
    public static final long GROWTH_ID = 2L;
    public static final long SCALE_ID = 3L;

    private TestPlans() {
    }

    /**
     * Puts the plan catalog back to EXACTLY the V51 seed (the `public-plans` example state that other test classes
     * assert): extra plans removed (their tenants moved to STARTER), plans 1..3 and their entitlements re-read from the
     * migration's own INSERT statements (no second copy of the seed), versions 0, updated_by null. One transaction.
     * Callers that cache the public list must evict it afterwards ({@code PublicPlanCache#evict}).
     */
    public static void resetToSeed() {
        String v51;
        try {
            v51 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/resources/db/migrations/master/V51__subscription_plans.sql"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        String plansInsert = v51.substring(v51.indexOf("INSERT INTO plans "), v51.indexOf(";", v51.indexOf("INSERT INTO plans ")));
        String entsInsert = v51.substring(v51.indexOf("INSERT INTO plan_entitlements "),
                v51.indexOf(";", v51.indexOf("INSERT INTO plan_entitlements ")));
        try (java.sql.Connection c = PostgresTestDatabase.connect()) {
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SET LOCAL lock_timeout = '10s'");
                st.execute("UPDATE master.tenants SET plan_id = 1 WHERE plan_id > 3");
                st.execute("DELETE FROM master.plan_entitlements WHERE plan_id > 3");
                st.execute("DELETE FROM master.plans WHERE id > 3");
                st.execute("CREATE TEMP TABLE seed_plans (LIKE master.plans INCLUDING DEFAULTS) ON COMMIT DROP");
                st.execute(plansInsert.replace("INSERT INTO plans ", "INSERT INTO seed_plans "));
                st.execute("UPDATE master.plans SET is_default_for_signup = FALSE");
                st.execute("UPDATE master.plans p SET code = s.code, name = s.name, tagline = s.tagline, "
                        + "description = s.description, monthly_price_pesewas = s.monthly_price_pesewas, "
                        + "annual_discount_percent = s.annual_discount_percent, currency = s.currency, trial_days = s.trial_days, "
                        + "transaction_fee_bps = s.transaction_fee_bps, is_public = s.is_public, "
                        + "is_default_for_signup = s.is_default_for_signup, highlighted = s.highlighted, "
                        + "badge_label = s.badge_label, bullets_heading = s.bullets_heading, "
                        + "marketing_bullets = s.marketing_bullets, sort_order = s.sort_order, active = s.active, "
                        + "version = 0, updated_by = NULL, updated_at = now() FROM seed_plans s WHERE p.id = s.id");
                st.execute("DELETE FROM master.plan_entitlements WHERE plan_id <= 3");
                st.execute(entsInsert.replace("INSERT INTO plan_entitlements ", "INSERT INTO master.plan_entitlements "));
                st.execute("SELECT setval(pg_get_serial_sequence('master.plans', 'id'), GREATEST(3, (SELECT max(id) FROM master.plans)), TRUE)");
            }
            c.commit();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A detached plan row as V51 seeds STARTER (only the fields a tenant summary reads). */
    public static Plan plan(long id) {
        String code = id == STARTER_ID ? "STARTER" : id == GROWTH_ID ? "GROWTH" : id == SCALE_ID ? "SCALE" : "PLAN" + id;
        String name = code.charAt(0) + code.substring(1).toLowerCase(java.util.Locale.ROOT);
        return Plan.builder().id(id).code(code).name(name).trialDays(14).isPublic(true).active(true)
                .isDefaultForSignup(id == STARTER_ID).build();
    }

    /** Mock {@code findById} / {@code findAllById} / {@code findDefaultForSignup} to answer for any id. */
    @SuppressWarnings("unchecked")
    public static void stubAnyId(PlanRepository plans) {
        when(plans.findById(anyLong())).thenAnswer(i -> Optional.of(plan(i.getArgument(0))));
        when(plans.findAllById(any(Iterable.class))).thenAnswer(i -> {
            List<Plan> out = new ArrayList<>();
            for (Object id : (Iterable<Object>) i.getArgument(0)) {
                out.add(plan((Long) id));
            }
            return out;
        });
        when(plans.findDefaultForSignup()).thenReturn(Optional.of(plan(STARTER_ID)));
    }
}
